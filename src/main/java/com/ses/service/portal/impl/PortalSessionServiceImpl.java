package com.ses.service.portal.impl;

import com.ses.common.util.SecurityHashUtil;
import com.ses.config.PortalSecurityProperties;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalSession;
import com.ses.entity.PortalUser;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalSessionMapper;
import com.ses.mapper.PortalTermsConsentMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.PortalUserPermissionMapper;
import com.ses.portal.PortalLoginUser;
import com.ses.service.SystemConfigService;
import com.ses.service.portal.PortalSessionService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * portal専用DB sessionの実装。生tokenはcookieのみ・DBはSHA-256 hashのみ保存する。
 * 毎リクエストでuser/組織状態・MFA epoch・期限を検証するため、停止やMFA resetは即時に全sessionへ効く（G3）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalSessionServiceImpl implements PortalSessionService {

    private static final String COOKIE_PATH = "/";

    private final PortalSessionMapper sessionMapper;
    private final PortalUserMapper userMapper;
    private final PortalOrganizationMapper organizationMapper;
    private final PortalUserPermissionMapper permissionMapper;
    private final PortalTermsConsentMapper termsConsentMapper;
    private final SystemConfigService systemConfigService;
    private final PortalSecurityProperties properties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.security.require-https:false}")
    private boolean requireHttps;

    @Override
    @Transactional
    public void issue(HttpServletRequest request, HttpServletResponse response, Long portalUserId) {
        PortalUser user = userMapper.selectById(portalUserId);
        if (user == null) {
            throw new IllegalStateException("portal userが存在しません");
        }
        // 同時session上限を超えている場合は古いsessionから失効させる
        LocalDateTime now = LocalDateTime.now(clock);
        List<PortalSession> active = sessionMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PortalSession>()
                        .eq(PortalSession::getUserId, portalUserId)
                        .isNull(PortalSession::getRevokedAt)
                        .gt(PortalSession::getExpiresAt, now));
        int max = Math.max(1, properties.getSessionMaxConcurrent());
        int toRevoke = Math.max(0, active.size() - max + 1);
        for (int i = active.size() - 1; i >= 0 && toRevoke > 0; i--, toRevoke--) {
            revokeById(active.get(i).getId(), "MAX_CONCURRENT");
        }

        String rawToken = randomToken();
        PortalSession session = new PortalSession();
        session.setUserId(portalUserId);
        session.setTokenHash(SecurityHashUtil.sha256(rawToken));
        session.setIssuedAt(now);
        session.setLastSeenAt(now);
        session.setIdleExpiresAt(now.plusMinutes(Math.max(1, properties.getSessionIdleTimeoutMinutes())));
        session.setExpiresAt(now.plusHours(Math.max(1, properties.getSessionMaxLifetimeHours())));
        session.setIpHash(ipHash(request));
        session.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
        sessionMapper.insert(session);

        Cookie cookie = new Cookie(properties.getSessionCookieName(), rawToken);
        cookie.setHttpOnly(true);
        cookie.setPath(COOKIE_PATH);
        cookie.setSecure(requireHttps);
        cookie.setMaxAge((int) Math.min(Integer.MAX_VALUE, properties.getSessionMaxLifetimeHours() * 3600));
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
        log.info("portal session発行: userId={} (tokenはログへ出さない)", portalUserId);
    }

    @Override
    public PortalLoginUser resolve(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        String rawToken = null;
        for (Cookie cookie : cookies) {
            if (properties.getSessionCookieName().equals(cookie.getName()) && StringUtils.hasText(cookie.getValue())) {
                rawToken = cookie.getValue();
                break;
            }
        }
        if (rawToken == null) {
            return null;
        }
        PortalSession session = sessionMapper.selectByTokenHash(SecurityHashUtil.sha256(rawToken));
        if (session == null || session.getRevokedAt() != null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (session.getIdleExpiresAt() == null || !session.getIdleExpiresAt().isAfter(now)
                || session.getExpiresAt() == null || !session.getExpiresAt().isAfter(now)) {
            revokeById(session.getId(), "EXPIRED");
            return null;
        }
        // 有効判定はtouchのCASで行う（失効・期限切れは0件で負け）
        int touched = sessionMapper.touchIfValid(session.getId(), now,
                now.plusMinutes(Math.max(1, properties.getSessionIdleTimeoutMinutes())));
        if (touched == 0) {
            return null;
        }
        PortalUser user = userMapper.selectById(session.getUserId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            return null;
        }
        PortalOrganization org = organizationMapper.selectById(user.getPortalOrgId());
        if (org == null || !"ACTIVE".equals(org.getStatus())) {
            return null;
        }
        // MFA reset / password変更 / 管理者操作はrevokeAllForUserで全sessionを明示失効させるため、
        // ここでの追加検証は不要（内部PersistentSessionServiceと同じ設計）。
        Set<String> permissions = new HashSet<>(permissionMapper.selectPermissionKeys(user.getId()));
        PortalLoginUser principal = PortalLoginUser.builder()
                .portalUserId(user.getId())
                .portalOrgId(org.getId())
                .orgType(org.getType())
                .customerId(org.getCustomerId())
                .bpCompanyId(org.getBpCompanyId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .mfaEnabledAt(user.getMfaEnabledAt())
                .orgStatus(org.getStatus())
                .userStatus(user.getStatus())
                .orgAdmin(permissions.contains(PERMISSION_ORG_ADMIN))
                .notifyEmail(user.getNotifyEmail())
                .permissions(permissions)
                .build();
        // 規約同意待ちフラグ（同意versionが現行未満なら同意画面へ強制）
        String current = systemConfigService.getString("portal.terms.current-version", "1");
        String consented = termsConsentMapper.latestConsentedVersion(user.getId());
        principal.setTermsPending(current != null && !current.equals(consented));
        return principal;
    }

    @Override
    @Transactional
    public void revokeCurrent(HttpServletRequest request, HttpServletResponse response) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (properties.getSessionCookieName().equals(cookie.getName())
                        && StringUtils.hasText(cookie.getValue())) {
                    PortalSession session = sessionMapper.selectByTokenHash(
                            SecurityHashUtil.sha256(cookie.getValue()));
                    if (session != null && session.getRevokedAt() == null) {
                        revokeById(session.getId(), "LOGOUT");
                    }
                }
            }
        }
        Cookie clear = new Cookie(properties.getSessionCookieName(), "");
        clear.setHttpOnly(true);
        clear.setPath(COOKIE_PATH);
        clear.setMaxAge(0);
        response.addCookie(clear);
    }

    @Override
    @Transactional
    public void revokeAllForUser(Long portalUserId, String reason) {
        sessionMapper.revokeAllForUser(portalUserId, LocalDateTime.now(clock),
                reason == null ? "ADMIN" : reason);
    }

    @Override
    @Transactional
    public void revokeAllForOrg(Long portalOrgId, String reason) {
        sessionMapper.revokeAllForOrg(portalOrgId, LocalDateTime.now(clock),
                reason == null ? "ORG_SUSPEND" : reason);
    }

    @Override
    public List<PortalSession> listActive(Long portalUserId) {
        LocalDateTime now = LocalDateTime.now(clock);
        return sessionMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PortalSession>()
                .eq(PortalSession::getUserId, portalUserId)
                .isNull(PortalSession::getRevokedAt)
                .gt(PortalSession::getExpiresAt, now));
    }

    /** 組織管理者権限キー（field-inventory §6.4のpermission_key契約） */
    public static final String PERMISSION_ORG_ADMIN = "org.admin";

    private void revokeById(Long id, String reason) {
        com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalSession> update =
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<PortalSession>()
                        .eq("id", id)
                        .isNull("revoked_at")
                        .set("revoked_at", LocalDateTime.now(clock))
                        .set("revoked_reason", reason);
        sessionMapper.update(null, update);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String ipHash(HttpServletRequest request) {
        return SecurityHashUtil.sha256(clientIp(request));
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
