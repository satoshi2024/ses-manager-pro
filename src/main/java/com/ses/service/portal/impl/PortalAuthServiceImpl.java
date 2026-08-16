package com.ses.service.portal.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityHashUtil;
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
import com.ses.service.portal.PortalSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * portal認証実装。招待tokenの一回性はDB CAS（WHERE used_at IS NULL）で保証する（design §6.3）。
 * パスワードはBCrypt（portalは外部公開のためprofile切替encoderに依存しない）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalAuthServiceImpl implements PortalAuthService {

    private static final BCryptPasswordEncoder PASSWORD_ENCODER = new BCryptPasswordEncoder();

    private final PortalUserMapper userMapper;
    private final PortalOrganizationMapper organizationMapper;
    private final PortalInvitationMapper invitationMapper;
    private final PortalTermsConsentMapper termsConsentMapper;
    private final PortalMfaService mfaService;
    private final PortalSessionService sessionService;
    private final SystemConfigService systemConfigService;
    private final Clock clock;

    @Override
    @Transactional
    public PortalLoginResponse login(PortalLoginRequest request, HttpServletRequest httpRequest,
                                     HttpServletResponse httpResponse) {
        PortalUser user = userMapper.selectByEmail(normalizeEmail(request.getEmail()));
        if (user == null || !StringUtils.hasText(user.getPasswordHash())
                || !PASSWORD_ENCODER.matches(request.getPassword(), user.getPasswordHash())) {
            throw BusinessException.of(401, "error.portal.login.invalid");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw BusinessException.of(403, "error.portal.user.suspended");
        }
        PortalOrganization org = organizationMapper.selectById(user.getPortalOrgId());
        if (org == null || !"ACTIVE".equals(org.getStatus())) {
            throw BusinessException.of(403, "error.portal.org.suspended");
        }
        if (user.getMfaEnabledAt() == null) {
            PortalMfaSetupDto setup = mfaService.setup(user.getId());
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
    public PortalMfaCompleteDto completeMfa(String email, String code, HttpServletRequest httpRequest,
                                            HttpServletResponse httpResponse) {
        PortalUser user = userMapper.selectByEmail(normalizeEmail(email));
        if (user == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        PortalMfaCompleteDto completed = mfaService.enable(user.getId(), code);
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

    private boolean termsPending(Long portalUserId) {
        String current = systemConfigService.getString("portal.terms.current-version", "1");
        String consented = termsConsentMapper.latestConsentedVersion(portalUserId);
        return current != null && !current.equals(consented);
    }

    private PortalUser createOrReactivateUser(PortalInvitation invitation,
                                              PortalAcceptInvitationRequest request) {
        String email = normalizeEmail(request.getEmail());
        PortalUser existing = userMapper.selectByEmail(email);
        if (existing == null) {
            PortalUser user = new PortalUser();
            user.setPortalOrgId(invitation.getPortalOrgId());
            user.setEmail(email);
            user.setDisplayName(request.getDisplayName().trim());
            user.setPasswordHash(PASSWORD_ENCODER.encode(request.getPassword()));
            user.setStatus("ACTIVE");
            user.setMfaPolicy("REQUIRED");
            userMapper.insert(user);
            return user;
        }
        if ("ACTIVE".equals(existing.getStatus())) {
            throw BusinessException.of(409, "error.portal.invite.alreadyRegistered");
        }
        // 論理削除済みemailはreactivate（email UNIQUEはdeleted行も保持するため。
        // @TableLogic付きupdateByIdはdeleted行を除外するため、raw UpdateWrapperで更新する）
        userMapper.update(null, new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalUser>()
                .eq("id", existing.getId())
                .set("display_name", request.getDisplayName().trim())
                .set("password_hash", PASSWORD_ENCODER.encode(request.getPassword()))
                .set("status", "ACTIVE")
                .set("mfa_enabled_at", null)
                .set("totp_secret_encrypted", null)
                .set("totp_secret_key_version", null)
                .set("recovery_code_hash", null)
                .set("recovery_code_used_at", null)
                .set("last_used_step", null)
                .set("deleted_flag", 0));
        sessionService.revokeAllForUser(existing.getId(), "REACTIVATE");
        return userMapper.selectById(existing.getId());
    }

    private String normalizeEmail(String email) {
        return email == null ? null : email.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
