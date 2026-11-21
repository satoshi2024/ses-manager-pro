package com.ses.service.portal.impl;

import com.ses.backup.quiesce.BackupQuiesceMetrics;
import com.ses.backup.quiesce.BackupQuiesceProperties;
import com.ses.backup.quiesce.BackupWriteGate;
import com.ses.common.util.ClientIpResolver;
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
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ClientIpResolver clientIpResolver;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.security.require-https:false}")
    private boolean requireHttps;

    /**
     * 静止中の最終利用の持越しの上限（件）。portal の session は HTTP session を持たないため、静止中に通した時刻を memory に
     * 置く（単一 instance の前提。SRE-5）。上限に達したら持越しを記録しない（最悪でも解除の後に idle 期限で失効するだけで、
     * 緩和を広げない）。
     */
    static final int QUIESCE_CARRY_MAX = 10_000;
    /** 上限（試験だけが小さくする）。 */
    private int quiesceCarryMax = QUIESCE_CARRY_MAX;
    private static final long CARRY_FULL_WARN_INTERVAL_NANOS = java.time.Duration.ofSeconds(60).toNanos();

    /** session の行の ID → 静止中の最終利用（RB-QUIESCE U-15。design.md §6.4）。 */
    private final java.util.concurrent.ConcurrentMap<Long, LocalDateTime> quiesceLastUse =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong lastCarryFullWarnNanos =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime() - CARRY_FULL_WARN_INTERVAL_NANOS);

    /** backup の書込み静止の gate（RB-QUIESCE U-15）。無い context（slice の試験等）では従来どおり毎回 touch する。 */
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
            if (session != null) {
                quiesceLastUse.remove(session.getId());
            }
            return null;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long idleMinutes = Math.max(1, properties.getSessionIdleTimeoutMinutes());
        LocalDateTime carriedIdleDeadline = carriedIdleDeadline(session.getId(), idleMinutes);
        // backup の静止中（permit の無い参照）は session の行へ書かない（RB-QUIESCE U-15。design.md §6.4）
        boolean quiesced = backupWriteGate != null && backupWriteGate.writesBlockedForCurrentThread();
        LocalDateTime idleReference = quiesced ? quiescedIdleReference(now) : now;
        boolean idleValid = session.getIdleExpiresAt() != null && session.getIdleExpiresAt().isAfter(idleReference)
                || carriedIdleDeadline != null && carriedIdleDeadline.isAfter(idleReference);
        if (!idleValid || session.getExpiresAt() == null || !session.getExpiresAt().isAfter(now)) {
            if (!quiesced) {
                revokeById(session.getId(), "EXPIRED");
                quiesceLastUse.remove(session.getId());
            }
            // 静止中は失効を書かず未認証として扱う（行は失効していないが、解除の後の要求でも有効に戻らない）
            return null;
        }
        if (quiesced) {
            // touch を省き、静止中の最終利用を memory に持ち越す（解除の後の最初の要求で touch する）
            rememberQuiescedUse(session.getId(), now, idleMinutes);
            if (backupQuiesceMetrics != null) {
                backupQuiesceMetrics.skippedWrite("session_touch");
            }
        } else {
            // 有効判定はtouchのCASで行う（失効・期限切れは0件で負け）
            LocalDateTime nextIdle = now.plusMinutes(idleMinutes);
            int touched = session.getIdleExpiresAt() != null && session.getIdleExpiresAt().isAfter(now)
                    ? sessionMapper.touchIfValid(session.getId(), now, nextIdle)
                    // 行の idle 期限は過ぎたが、静止中の最終利用から idle の長さの内（解除の後の最初の要求）
                    : sessionMapper.touchCarriedOver(session.getId(), now, nextIdle);
            if (touched == 0) {
                return null;
            }
            if (carriedIdleDeadline != null) {
                quiesceLastUse.remove(session.getId());
            }
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
                .tenantId(org.getTenantId())
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
    @Transactional(rollbackFor = Exception.class)
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
    @Transactional(rollbackFor = Exception.class)
    public void revokeAllForUser(Long portalUserId, String reason) {
        sessionMapper.revokeAllForUser(portalUserId, LocalDateTime.now(clock),
                reason == null ? "ADMIN" : reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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

    /** 静止中の最終利用 + idle の長さ（持越しが無ければ null）。 */
    private LocalDateTime carriedIdleDeadline(Long sessionId, long idleMinutes) {
        LocalDateTime lastUse = sessionId == null ? null : quiesceLastUse.get(sessionId);
        return lastUse == null ? null : lastUse.plusMinutes(idleMinutes);
    }

    /**
     * 静止中に通した時刻を持ち越す。上限に達していれば、idle の長さを過ぎて役に立たなくなった持越しを先に捨て、それでも空かなければ
     * 記録しない（WARN を間引いて出す）。既に持っている session は上書きする（件数は増えない）。
     */
    private void rememberQuiescedUse(Long sessionId, LocalDateTime now, long idleMinutes) {
        if (sessionId == null) {
            return;
        }
        if (!quiesceLastUse.containsKey(sessionId) && quiesceLastUse.size() >= quiesceCarryMax) {
            LocalDateTime staleBefore = now.minusMinutes(idleMinutes);
            quiesceLastUse.values().removeIf(lastUse -> !lastUse.isAfter(staleBefore));
            if (quiesceLastUse.size() >= quiesceCarryMax) {
                long nowNanos = System.nanoTime();
                long last = lastCarryFullWarnNanos.get();
                if (nowNanos - last >= CARRY_FULL_WARN_INTERVAL_NANOS && lastCarryFullWarnNanos.compareAndSet(last, nowNanos)) {
                    log.warn("errorCode=BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED backup の静止中の portal の session の最終利用の"
                            + "持越しが上限（{} 件）に達したため記録しません。解除の後にこれらの session は idle 期限で失効する"
                            + "ことがあります", quiesceCarryMax);
                }
                return;
            }
        }
        quiesceLastUse.put(sessionId, now);
    }

    /**
     * 静止中の idle の判定の基準の時刻: gate が閉じた時刻（閉じた時点で有効だった session を静止の間は失効させない）。
     * 緩和は静止の最長の保持時間までにし、それより前に閉じた（FENCED が続く等）なら「今 − 最長の保持時間」にする
     * （社内の session の U-14 と同じ規則。{@code PersistentSessionServiceImpl}）。
     */
    private LocalDateTime quiescedIdleReference(LocalDateTime now) {
        java.time.Instant closedSince = backupWriteGate.closedSince();
        if (closedSince == null) {
            return now;
        }
        LocalDateTime floor = now.minus(backupQuiesceProperties.longestHold());
        LocalDateTime closedAt = LocalDateTime.ofInstant(closedSince, clock.getZone());
        if (closedAt.isAfter(now)) {
            return now;
        }
        return closedAt.isBefore(floor) ? floor : closedAt;
    }

    /** 持越しの件数（試験用）。 */
    int quiescedUseCount() {
        return quiesceLastUse.size();
    }

    /** 持越しの上限を変える（試験用）。 */
    void setQuiesceCarryMax(int max) {
        this.quiesceCarryMax = Math.max(1, max);
    }

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
        return clientIpResolver.resolve(request);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
