package com.ses.service.security.impl;

import com.ses.backup.quiesce.BackupQuiesceMetrics;
import com.ses.backup.quiesce.BackupQuiesceProperties;
import com.ses.backup.quiesce.BackupWriteGate;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.ClientIpResolver;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
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

    /**
     * ip_hashの元になるclient IP（FWD-SANITIZE PRE-P2-1）。framework strategyの {@code getRemoteAddr()} は
     * X-Forwarded-Forの最左（clientが書ける値）なので使わない。既存constructorを保つためsetter注入にする。
     */
    private ClientIpResolver clientIpResolver = new ClientIpResolver("");

    @Autowired(required = false)
    public void setClientIpResolver(ClientIpResolver clientIpResolver) {
        if (clientIpResolver != null) {
            this.clientIpResolver = clientIpResolver;
        }
    }

    /** backup の書込み静止の gate（RB-QUIESCE U-14）。無い context（slice の試験等）では従来どおり毎回 touch する。 */
    private BackupWriteGate backupWriteGate;
    private BackupQuiesceProperties backupQuiesceProperties = new BackupQuiesceProperties();
    private BackupQuiesceMetrics backupQuiesceMetrics;

    @Autowired(required = false)
    public void setBackupWriteGate(BackupWriteGate backupWriteGate) {
        this.backupWriteGate = backupWriteGate;
    }

    @Autowired(required = false)
    public void setBackupQuiesceProperties(BackupQuiesceProperties backupQuiesceProperties) {
        if (backupQuiesceProperties != null) {
            this.backupQuiesceProperties = backupQuiesceProperties;
        }
    }

    @Autowired(required = false)
    public void setBackupQuiesceMetrics(BackupQuiesceMetrics backupQuiesceMetrics) {
        this.backupQuiesceMetrics = backupQuiesceMetrics;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        entity.setIpHash(metadataHash(clientIpResolver.resolve(request)));
        entity.setUserAgent(truncate(request.getHeader("User-Agent"), 512));
        userSessionMapper.insert(entity);
        session.setAttribute(SESSION_HASH_ATTRIBUTE, sessionHash);
        session.setAttribute(TRACKED_ATTRIBUTE, Boolean.TRUE);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
                || entity.getIdleExpiresAt() == null
                || entity.getExpiresAt() == null || !entity.getExpiresAt().isAfter(now)) {
            return false;
        }
        int idleMinutes = Math.max(1, properties.getIdleTimeoutMinutes());
        LocalDateTime carriedIdleDeadline = carriedIdleDeadline(session, idleMinutes);
        boolean quiesced = backupWriteGate != null && backupWriteGate.writesBlockedForCurrentThread();
        LocalDateTime idleReference = quiesced ? quiescedIdleReference(now) : now;
        boolean idleValid = entity.getIdleExpiresAt().isAfter(idleReference)
                || carriedIdleDeadline != null && carriedIdleDeadline.isAfter(idleReference);
        if (!idleValid) {
            return false;
        }
        if (quiesced) {
            // backup の静止中は touch を省き、通した時刻を HTTP session に持ち越す（U-14。design.md §6.4）
            session.setAttribute(QUIESCE_LAST_USE_ATTRIBUTE, now);
            if (backupQuiesceMetrics != null) {
                backupQuiesceMetrics.skippedWrite("session_touch");
            }
            return true;
        }
        LocalDateTime nextIdle = now.plusMinutes(idleMinutes);
        boolean touched;
        if (entity.getIdleExpiresAt().isAfter(now)) {
            touched = userSessionMapper.touch(entity.getId(), now, nextIdle) == 1;
        } else {
            // 行の idle 期限は過ぎたが、静止中の最終利用から idle の長さの内（解除の後の最初の要求）
            touched = userSessionMapper.touchCarriedOver(entity.getId(), now, nextIdle) == 1;
        }
        if (touched && carriedIdleDeadline != null) {
            session.removeAttribute(QUIESCE_LAST_USE_ATTRIBUTE);
        }
        return touched;
    }

    /** 静止中の最終利用 + idle の長さ（属性が無ければ null）。 */
    private static LocalDateTime carriedIdleDeadline(HttpSession session, int idleMinutes) {
        Object lastUse = session.getAttribute(QUIESCE_LAST_USE_ATTRIBUTE);
        return lastUse instanceof LocalDateTime time ? time.plusMinutes(idleMinutes) : null;
    }

    /**
     * 静止中の idle の判定の基準の時刻: gate が閉じた時刻（閉じた時点で有効だった session を静止の間は失効させない）。
     * 緩和は静止の最長の保持時間までにし、それより前に閉じた（FENCED が続く等）なら「今 − 最長の保持時間」にする。
     */
    private LocalDateTime quiescedIdleReference(LocalDateTime now) {
        Instant closedSince = backupWriteGate.closedSince();
        LocalDateTime floor = now.minus(backupQuiesceProperties.longestHold());
        if (closedSince == null) {
            return now;
        }
        LocalDateTime closedAt = LocalDateTime.ofInstant(closedSince, clock.getZone());
        if (closedAt.isAfter(now)) {
            return now;
        }
        return closedAt.isBefore(floor) ? floor : closedAt;
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
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
    public void revokeOthers(Long userId, String currentHash, String reason) {
        userSessionMapper.revokeOthers(tenantId(), userId, currentHash, LocalDateTime.now(clock), reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeAllForUser(Long userId, String reason) {
        userSessionMapper.revokeAllForUser(tenantId(), userId, LocalDateTime.now(clock), reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

    @Override
    public CapturedRevocation captureCurrent(HttpServletRequest request, Authentication authentication, String reason) {
        if (!properties.isEnabled() || request == null || authentication == null) {
            return null;
        }
        HttpSession session = request.getSession(false);
        String hash = session == null ? null : (String) session.getAttribute(SESSION_HASH_ATTRIBUTE);
        Long userId = authentication.getPrincipal() instanceof com.ses.config.LoginUser loginUser
                ? loginUser.getSysUser().getId() : SecurityUtils.currentUserId();
        if (!StringUtils.hasText(hash) || userId == null) {
            return null;
        }
        return new CapturedRevocation(tenantId(), hash, userId, LocalDateTime.now(clock),
                StringUtils.hasText(reason) ? reason : "LOGOUT");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void revokeCaptured(CapturedRevocation captured) {
        if (captured == null) {
            return;
        }
        UserSession current = userSessionMapper.selectBySessionHash(captured.tenantId(), captured.sessionHash());
        if (current == null || !captured.userId().equals(current.getUserId()) || current.getRevokedAt() != null) {
            return;
        }
        userSessionMapper.revoke(current.getId(), captured.revokedAt(), captured.reason());
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
