package com.ses.service.security.impl;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityHashUtil;
import com.ses.common.util.SecurityUtils;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.PersistentSessionProperties;
import com.ses.dto.security.SessionSummary;
import com.ses.entity.UserSession;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserSessionMapper;
import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/** DBにsession失効状態を保存する。生session ID/IPはDBへ保存しない。 */
@Service
@RequiredArgsConstructor
public class PersistentSessionServiceImpl implements PersistentSessionService {

    private final UserSessionMapper userSessionMapper;
    private final SysUserMapper sysUserMapper;
    private final PersistentSessionProperties properties;
    private final OidcSecurityProperties oidcProperties;
    private final Clock clock;

    @Override
    @Transactional
    public void register(HttpServletRequest request, Authentication authentication) {
        if (!properties.isEnabled() || authentication == null || !authentication.isAuthenticated()) {
            return;
        }
        Long userId = SecurityUtils.currentUserId();
        if (userId == null) {
            throw new BusinessException("認証ユーザーを解決できないためsessionを発行できません");
        }
        // session行が0件の初回同時loginでも確実に直列化できるよう、
        // 存在が保証されるsys_user行をmutexとして先にlockする。
        SysUser lockedUser = sysUserMapper.selectByIdForUpdate(userId);
        if (lockedUser == null || !Integer.valueOf(1).equals(lockedUser.getStatus())) {
            throw new BusinessException("有効な認証ユーザーを確認できないためsessionを発行できません");
        }
        HttpSession session = request.getSession(true);
        String sessionHash = sessionHash(session.getId());
        LocalDateTime now = LocalDateTime.now(clock);
        UserSession existing = userSessionMapper.selectBySessionHash(tenantId(), sessionHash);
        if (existing != null && existing.getRevokedAt() == null) {
            session.setAttribute(SESSION_HASH_ATTRIBUTE, sessionHash);
            session.setAttribute(TRACKED_ATTRIBUTE, Boolean.TRUE);
            return;
        }

        // 同一userのquery→revoke→insertは上のsys_user行lockで直列化済み。
        // sessionが0件のuserにFOR UPDATEを使うとInnoDBのgap lockが異なるuser間でも
        // 競合するため、ここでは通常の参照queryを使用する。
        List<UserSession> active = userSessionMapper.selectActiveByUser(tenantId(), userId, now);
        if (active == null) {
            throw new BusinessException("session上限を確認できないためsessionを発行できません");
        }
        int max = Math.max(1, properties.getMaxConcurrentSessions());
        int toRevoke = Math.max(0, active.size() - max + 1);
        for (int i = active.size() - 1; i >= 0 && toRevoke > 0; i--, toRevoke--) {
            userSessionMapper.revoke(active.get(i).getId(), now, "MAX_CONCURRENT");
        }

        UserSession entity = new UserSession();
        entity.setTenantId(tenantId());
        entity.setSessionIdHash(sessionHash);
        entity.setUserId(userId);
        entity.setIssuedAt(now);
        entity.setLastSeenAt(now);
        entity.setIdleExpiresAt(now.plusMinutes(Math.max(1, properties.getIdleTimeoutMinutes())));
        entity.setExpiresAt(now.plusHours(Math.max(1, properties.getMaxLifetimeHours())));
        entity.setIpHash(metadataHash(request.getRemoteAddr()));
        entity.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
        userSessionMapper.insert(entity);
        session.setAttribute(SESSION_HASH_ATTRIBUTE, sessionHash);
        session.setAttribute(TRACKED_ATTRIBUTE, Boolean.TRUE);
    }

    @Override
    @Transactional
    public boolean validateAndTouch(HttpServletRequest request, Authentication authentication) {
        if (!properties.isEnabled() || authentication == null || !authentication.isAuthenticated()) {
            return true;
        }
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute(TRACKED_ATTRIBUTE))) {
            return properties.isAllowUntrackedSessions();
        }
        String sessionHash = (String) session.getAttribute(SESSION_HASH_ATTRIBUTE);
        Long userId = SecurityUtils.currentUserId();
        if (!StringUtils.hasText(sessionHash) || userId == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        UserSession entity = userSessionMapper.selectBySessionHash(tenantId(), sessionHash);
        if (entity == null || !userId.equals(entity.getUserId()) || entity.getRevokedAt() != null
                || entity.getIdleExpiresAt() == null || !entity.getIdleExpiresAt().isAfter(now)
                || entity.getExpiresAt() == null || !entity.getExpiresAt().isAfter(now)) {
            return false;
        }
        return userSessionMapper.touch(entity.getId(), now,
                now.plusMinutes(Math.max(1, properties.getIdleTimeoutMinutes()))) == 1;
    }

    @Override
    public List<SessionSummary> list(Authentication authentication, HttpServletRequest request) {
        Long userId = requireUser(authentication);
        LocalDateTime now = LocalDateTime.now(clock);
        String currentHash = currentHash(request);
        return userSessionMapper.selectActiveByUser(tenantId(), userId, now).stream()
                .map(session -> new SessionSummary(session.getId(), session.getIssuedAt(), session.getLastSeenAt(),
                        session.getIdleExpiresAt(), session.getExpiresAt(), session.getUserAgent(),
                        session.getSessionIdHash().equals(currentHash)))
                .toList();
    }

    @Override
    @Transactional
    public void revoke(Long sessionId, Long userId, String currentHash, String reason) {
        UserSession target = userSessionMapper.selectById(sessionId);
        if (target == null || !tenantId().equals(target.getTenantId()) || !userId.equals(target.getUserId())) {
            throw BusinessException.of(404, "error.session.notFound");
        }
        if (target.getSessionIdHash().equals(currentHash)) {
            throw BusinessException.of("error.session.currentCannotRevoke");
        }
        userSessionMapper.revoke(sessionId, LocalDateTime.now(clock), reason);
    }

    @Override
    @Transactional
    public void revokeOthers(Long userId, String currentHash, String reason) {
        userSessionMapper.revokeOthers(tenantId(), userId, currentHash, LocalDateTime.now(clock), reason);
    }

    @Override
    @Transactional
    public void revokeAllForUser(Long userId, String reason) {
        userSessionMapper.revokeAllForUser(tenantId(), userId, LocalDateTime.now(clock), reason);
    }

    @Override
    @Transactional
    public void revokeCurrent(HttpServletRequest request, Authentication authentication, String reason) {
        if (!properties.isEnabled() || request == null || authentication == null) {
            return;
        }
        HttpSession session = request.getSession(false);
        String hash = session == null ? null : (String) session.getAttribute(SESSION_HASH_ATTRIBUTE);
        if (!StringUtils.hasText(hash)) {
            return;
        }
        UserSession current = userSessionMapper.selectBySessionHash(tenantId(), hash);
        Long userId = authentication.getPrincipal() instanceof com.ses.config.LoginUser loginUser
                ? loginUser.getSysUser().getId() : SecurityUtils.currentUserId();
        if (current == null || userId == null || !userId.equals(current.getUserId())) {
            return;
        }
        userSessionMapper.revoke(current.getId(), LocalDateTime.now(clock),
                StringUtils.hasText(reason) ? reason : "LOGOUT");
    }

    private Long requireUser(Authentication authentication) {
        Long userId = SecurityUtils.currentUserId();
        if (authentication == null || userId == null) {
            throw new BusinessException("認証ユーザーを解決できません");
        }
        return userId;
    }

    private String currentHash(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        return session == null ? null : (String) session.getAttribute(SESSION_HASH_ATTRIBUTE);
    }

    private String sessionHash(String sessionId) {
        return metadataHash(sessionId);
    }

    private String metadataHash(String value) {
        return SecurityHashUtil.sha256(properties.getHashKey() + ":" + (value == null ? "" : value));
    }

    private String tenantId() {
        return StringUtils.hasText(oidcProperties.getTenantId()) ? oidcProperties.getTenantId() : "default";
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
