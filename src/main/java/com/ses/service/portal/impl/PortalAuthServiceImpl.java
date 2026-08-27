package com.ses.service.portal.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.ClientIpResolver;
import com.ses.common.util.SecurityHashUtil;
import com.ses.config.PortalSecurityProperties;
import com.ses.dto.portal.PortalAcceptInvitationRequest;
import com.ses.dto.portal.PortalLoginRequest;
import com.ses.dto.portal.PortalLoginResponse;
import com.ses.dto.portal.PortalMfaCompleteDto;
import com.ses.dto.portal.PortalMfaSetupDto;
import com.ses.entity.PortalInvitation;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalTermsConsent;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalInvitationMapper;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalTermsConsentMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.portal.PortalAuthService;
import com.ses.service.portal.PortalMfaService;
import com.ses.service.portal.PortalRateLimiter;
import com.ses.service.portal.PortalSessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * portal認証実装。招待tokenの一回性はDB CAS（WHERE used_at IS NULL）で保証する（design §6.3）。
 * パスワードはBCrypt（portalは外部公開のためprofile切替encoderに依存しない）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalAuthServiceImpl implements PortalAuthService {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();
    /** MFA設定完了までの短命ticket cookie（login MFA_SETUP時に発行。S13-P1-02） */
    static final String MFA_SETUP_COOKIE = "PORTAL_MFA_SETUP";
    private static final long MFA_SETUP_TICKET_TTL_MILLIS = 10 * 60_000L;

    private final PortalUserMapper userMapper;
    private final PortalOrganizationMapper organizationMapper;
    private final PortalInvitationMapper invitationMapper;
    private final PortalTermsConsentMapper termsConsentMapper;
    private final com.ses.mapper.PortalUserPermissionMapper permissionMapper;
    private final PortalMfaService mfaService;
    private final PortalSessionService sessionService;
    private final SystemConfigService systemConfigService;
    private final PortalRateLimiter rateLimiter;
    private final PortalSecurityProperties portalSecurityProperties;
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    /** setup ticket hash → (userId, expiresAtEpochMs)。単一インスタンス運用向けインメモリ。 */
    private final Map<String, MfaSetupTicket> setupTickets = new ConcurrentHashMap<>();
    /** email → login失敗ガード（単一インスタンス。S13-XFF-01） */
    private final Map<String, LoginFailureGuard> loginFailureGuards = new ConcurrentHashMap<>();

    @Value("${app.security.require-https:false}")
    private boolean requireHttps;

    private record MfaSetupTicket(long userId, long expiresAtEpochMs) {
    }

    private record LoginFailureGuard(int failedCount, long lockedUntilEpochMs) {
    }

    @Override
    @Transactional
    public PortalLoginResponse login(PortalLoginRequest request, HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        String email = normalizeEmail(request.getEmail());
        assertLoginNotLocked(email);
        PortalUser user = userMapper.selectByEmail(email);
        if (user == null || !StringUtils.hasText(user.getPasswordHash())
                || !PASSWORD_ENCODER.matches(request.getPassword(), user.getPasswordHash())) {
            recordLoginFailure(email);
            throw BusinessException.of(401, "error.portal.login.invalid");
        }
        clearLoginFailures(email);
        if (!"ACTIVE".equals(user.getStatus())) {
            throw BusinessException.of(403, "error.portal.user.suspended");
        }
        PortalOrganization org = organizationMapper.selectById(user.getPortalOrgId());
        if (org == null || !"ACTIVE".equals(org.getStatus())) {
            throw BusinessException.of(403, "error.portal.org.suspended");
        }
        if (user.getMfaEnabledAt() == null) {
            PortalMfaSetupDto setup = mfaService.setup(user.getId());
            issueMfaSetupTicket(user.getId(), httpResponse);
            return PortalLoginResponse.builder().status("MFA_SETUP").mfaSetup(setup).build();
        }
        if (!StringUtils.hasText(request.getMfaCode())) {
            return PortalLoginResponse.builder().status("MFA_REQUIRED").build();
        }
        if (!mfaService.verify(user.getId(), request.getMfaCode())) {
            throw BusinessException.of(401, "error.portal.mfa.invalidCode");
        }
        userMapper.updateLastLogin(user.getId(), LocalDateTime.now(clock));
        sessionService.issue(httpRequest, httpResponse, user.getId());
        return PortalLoginResponse.builder().status("OK")
                .termsPending(termsPending(user.getId()))
                .build();
    }

    @Override
    @Transactional
    public PortalMfaCompleteDto completeMfa(String email, String code, String password,
                                            HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        String normalizedEmail = normalizeEmail(email);
        // IP+email rate limit（S13-P1-02）。email未指定でもIP単位で消費する
        String rateKey = "mfa-complete:" + clientIp(httpRequest) + ":"
                + (normalizedEmail == null ? "" : normalizedEmail);
        int perMinute = portalSecurityProperties.getRateLimit().getMfaCompletePerMinute();
        if (perMinute > 0 && !rateLimiter.tryAcquire(rateKey, perMinute)) {
            throw BusinessException.of(429, "error.portal.mfa.rateLimited");
        }
        if (!StringUtils.hasText(normalizedEmail)) {
            throw BusinessException.of(401, "error.portal.login.invalid");
        }
        PortalUser user = userMapper.selectByEmail(normalizedEmail);
        if (user == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        boolean passwordOk = StringUtils.hasText(password)
                && StringUtils.hasText(user.getPasswordHash())
                && PASSWORD_ENCODER.matches(password, user.getPasswordHash());
        boolean ticketOk = validateMfaSetupTicket(httpRequest, user.getId());
        if (!passwordOk && !ticketOk) {
            throw BusinessException.of(401, "error.portal.mfa.setupAuthRequired");
        }
        PortalMfaCompleteDto completed = mfaService.enable(user.getId(), code);
        consumeMfaSetupTicket(httpRequest, httpResponse, user.getId());
        userMapper.updateLastLogin(user.getId(), LocalDateTime.now(clock));
        sessionService.issue(httpRequest, httpResponse, user.getId());
        return completed;
    }

    @Override
    @Transactional
    public void acceptInvitation(PortalAcceptInvitationRequest request, HttpServletRequest httpRequest) {
        String tokenHash = SecurityHashUtil.sha256(request.getToken());
        PortalInvitation invitation = invitationMapper.selectByTokenHash(tokenHash);
        if (invitation == null) {
            throw BusinessException.of(404, "error.portal.invite.invalid");
        }
        // 4条件すべてを検証（design §6.1: used_at IS NULLだけでは有効と判定しない）
        if (invitation.getUsedAt() != null) {
            throw BusinessException.of(409, "error.portal.invite.used");
        }
        if (invitation.getExpiresAt() == null || !invitation.getExpiresAt().isAfter(LocalDateTime.now(clock))) {
            throw BusinessException.of(400, "error.portal.invite.expired");
        }
        if (!invitation.getEmail().equalsIgnoreCase(normalizeEmail(request.getEmail()))) {
            throw BusinessException.of(400, "error.portal.invite.emailMismatch");
        }
        PortalOrganization org = organizationMapper.selectById(invitation.getPortalOrgId());
        if (org == null || !"ACTIVE".equals(org.getStatus())) {
            throw BusinessException.of(403, "error.portal.org.suspended");
        }
        // 一回性はDB CAS。同時使用の敗者は0件で拒否される（design §6.3）
        int consumed = invitationMapper.consumeIfUnused(invitation.getId(),
                LocalDateTime.now(clock), null);
        if (consumed == 0) {
            throw BusinessException.of(409, "error.portal.invite.used");
        }
        PortalUser user = createOrReactivateUser(invitation, request);
        invitationMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalInvitation>()
                .eq("id", invitation.getId())
                .set("accepted_by", user.getId()));
        log.info("portal招待受諾: orgId={} email={} (tokenはログへ出さない)",
                invitation.getPortalOrgId(), user.getEmail());
    }

    @Override
    @Transactional
    public void consentTerms(Long portalUserId, String termsVersion, HttpServletRequest httpRequest) {
        PortalUser user = userMapper.selectById(portalUserId);
        if (user == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        String current = systemConfigService.getString("portal.terms.current-version", "1");
        if (!StringUtils.hasText(termsVersion) || !termsVersion.equals(current)) {
            throw BusinessException.of(409, "error.portal.terms.versionMismatch", current);
        }
        PortalTermsConsent consent = new PortalTermsConsent();
        consent.setUserId(portalUserId);
        consent.setTermsVersion(current);
        consent.setConsentedAt(LocalDateTime.now(clock));
        consent.setIpHash(SecurityHashUtil.sha256(clientIp(httpRequest)));
        try {
            termsConsentMapper.insert(consent);
        } catch (DuplicateKeyException duplicate) {
            // 既に同意済み（二重POST）は冪等に成功とみなす
        }
    }

    @Override
    @Transactional
    public void logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        sessionService.revokeCurrent(httpRequest, httpResponse);
    }

    @Override
    @Transactional
    public void updatePreferences(Long portalUserId, boolean notifyEmail) {
        PortalUser user = userMapper.selectById(portalUserId);
        if (user == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", portalUserId)
                .set("notify_email", notifyEmail ? 1 : 0));
    }

    private boolean termsPending(Long portalUserId) {
        String current = systemConfigService.getString("portal.terms.current-version", "1");
        String consented = termsConsentMapper.latestConsentedVersion(portalUserId);
        return current != null && !current.equals(consented);
    }

    private PortalUser createOrReactivateUser(PortalInvitation invitation,
                                              PortalAcceptInvitationRequest request) {
        String email = normalizeEmail(request.getEmail());
        // 論理削除済みを含めて既存userを確認する（email UNIQUEはdeleted行も保持するため）
        PortalUser existing = userMapper.selectByEmailIncludingDeleted(email);
        if (existing == null) {
            PortalUser user = new PortalUser();
            user.setPortalOrgId(invitation.getPortalOrgId());
            user.setEmail(email);
            user.setDisplayName(request.getDisplayName().trim());
            user.setPasswordHash(PASSWORD_ENCODER.encode(request.getPassword()));
            user.setStatus("ACTIVE");
            user.setMfaPolicy("REQUIRED");
            userMapper.insert(user);

            // 顧客組織の場合、初期権限として service-desk.view / service-desk.create を付与 (CS-IMPL-P1-01)
            grantDefaultCustomerPermissions(user.getId(), invitation.getPortalOrgId());
            return user;
        }
        // 論理削除済み（過去に削除されたアカウント）はreactivate可能。statusは無関係（deleted行優先）
        if (Integer.valueOf(1).equals(existing.getDeletedFlag())) {
            return reactivate(existing, invitation, request);
        }
        if ("ACTIVE".equals(existing.getStatus())) {
            throw BusinessException.of(409, "error.portal.invite.alreadyRegistered");
        }
        if ("SUSPENDED".equals(existing.getStatus())) {
            // 停止されたuserは招待受諾で自己復活できない（S13-R1-P0-01）。
            // 復活は管理者の明示操作（B1: 停止/再開）のみ。停止前に発行された未使用invitationも
            // 停止時に失効済みのため、この経路は成立しない。
            throw BusinessException.of(409, "error.portal.invite.suspended");
        }
        // その他の状態（例: データ不整合）はreactivate（SUSPENDED以外の非ACTIVE）
        return reactivate(existing, invitation, request);
    }

    /**
     * reactivate: 論理削除済み・非ACTIVE userを招待の組織で再活性化する。
     * S13-R1-P0-01: portal_org_id は必ず invitation の組織へ付け替える（組織跨ぎの残留を防ぐ）。
     */
    private PortalUser reactivate(PortalUser existing, PortalInvitation invitation,
                                  PortalAcceptInvitationRequest request) {
        int updated = userMapper.reactivate(existing.getId(), invitation.getPortalOrgId(),
                request.getDisplayName().trim(), PASSWORD_ENCODER.encode(request.getPassword()));
        if (updated == 0) {
            throw BusinessException.of(409, "error.portal.invite.alreadyRegistered");
        }
        sessionService.revokeAllForUser(existing.getId(), "REACTIVATE");
        return userMapper.selectById(existing.getId());
    }

    private void issueMfaSetupTicket(Long userId, HttpServletResponse response) {
        purgeExpiredSetupTickets();
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String raw = HexFormat.of().formatHex(bytes);
        String hash = SecurityHashUtil.sha256(raw);
        long expiresAt = System.currentTimeMillis() + MFA_SETUP_TICKET_TTL_MILLIS;
        setupTickets.put(hash, new MfaSetupTicket(userId, expiresAt));
        Cookie cookie = new Cookie(MFA_SETUP_COOKIE, raw);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setSecure(requireHttps);
        cookie.setMaxAge((int) (MFA_SETUP_TICKET_TTL_MILLIS / 1000L));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
    }

    /** ticketの妥当性のみ確認（失敗リトライ用に消費しない）。 */
    private boolean validateMfaSetupTicket(HttpServletRequest request, Long userId) {
        String raw = readCookie(request, MFA_SETUP_COOKIE);
        if (!StringUtils.hasText(raw) || userId == null) {
            return false;
        }
        String hash = SecurityHashUtil.sha256(raw);
        MfaSetupTicket ticket = setupTickets.get(hash);
        if (ticket == null) {
            return false;
        }
        if (ticket.expiresAtEpochMs() < System.currentTimeMillis()) {
            setupTickets.remove(hash);
            return false;
        }
        return ticket.userId() == userId;
    }

    private void consumeMfaSetupTicket(HttpServletRequest request, HttpServletResponse response, Long userId) {
        String raw = readCookie(request, MFA_SETUP_COOKIE);
        if (StringUtils.hasText(raw)) {
            setupTickets.remove(SecurityHashUtil.sha256(raw));
        }
        Cookie cleared = new Cookie(MFA_SETUP_COOKIE, "");
        cleared.setHttpOnly(true);
        cleared.setPath("/");
        cleared.setSecure(requireHttps);
        cleared.setMaxAge(0);
        cleared.setAttribute("SameSite", "Lax");
        response.addCookie(cleared);
    }

    private void purgeExpiredSetupTickets() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, MfaSetupTicket>> it = setupTickets.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAtEpochMs() < now) {
                it.remove();
            }
        }
    }

    private String readCookie(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void assertLoginNotLocked(String email) {
        if (email == null) {
            return;
        }
        int threshold = portalSecurityProperties.getRateLimit().getLoginFailureLockThreshold();
        if (threshold <= 0) {
            return;
        }
        LoginFailureGuard guard = loginFailureGuards.get(email);
        if (guard != null && guard.lockedUntilEpochMs() > System.currentTimeMillis()) {
            throw BusinessException.of(429, "error.portal.login.locked");
        }
    }

    private void recordLoginFailure(String email) {
        if (email == null) {
            return;
        }
        int threshold = portalSecurityProperties.getRateLimit().getLoginFailureLockThreshold();
        if (threshold <= 0) {
            return;
        }
        int lockMinutes = Math.max(1, portalSecurityProperties.getRateLimit().getLoginFailureLockMinutes());
        loginFailureGuards.compute(email, (key, prev) -> {
            long now = System.currentTimeMillis();
            if (prev != null && prev.lockedUntilEpochMs() > now) {
                return prev;
            }
            int next = (prev == null || prev.lockedUntilEpochMs() > 0 && prev.lockedUntilEpochMs() <= now)
                    ? 1 : prev.failedCount() + 1;
            if (next >= threshold) {
                return new LoginFailureGuard(next, now + lockMinutes * 60_000L);
            }
            return new LoginFailureGuard(next, 0L);
        });
    }

    private void clearLoginFailures(String email) {
        if (email != null) {
            loginFailureGuards.remove(email);
        }
    }

    private void grantDefaultCustomerPermissions(Long userId, Long portalOrgId) {
        PortalOrganization org = organizationMapper.selectById(portalOrgId);
        if (org != null && "CUSTOMER".equals(org.getType())) {
            List<String> defaultKeys = List.of("service-desk.view", "service-desk.create");
            for (String key : defaultKeys) {
                com.ses.entity.PortalUserPermission perm = new com.ses.entity.PortalUserPermission();
                perm.setUserId(userId);
                perm.setPermissionKey(key);
                try {
                    permissionMapper.insert(perm);
                } catch (Exception e) {
                    // ignore duplicate
                }
            }
        }
    }

    private String clientIp(HttpServletRequest request) {
        return clientIpResolver.resolve(request);
    }
}
