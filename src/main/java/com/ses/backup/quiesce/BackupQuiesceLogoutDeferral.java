package com.ses.backup.quiesce;

import com.ses.config.LoginUser;
import com.ses.service.AuditLogService;
import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 社内の logout の失効と監査（RB-QUIESCE U-8。design.md §6.13）。{@code SecurityConfig} の logout の handler と success handler
 * から使う。
 *
 * <p>gate が書込みを許さず、logout の要求の thread が permit を持たない（{@code BackupWriteGateFilter} が permit を取れなかった）
 * ときは、{@code t_user_session} の失効と {@code LOGOUT} の監査を DB へ書かずに、logout の時点の値を取っておいて解除後の書込みの
 * 一覧へ入れる。HTTP session の無効化と cookie の削除・完了の画面への遷移は従来どおり直ちに行う（Spring Security の logout）。
 * それ以外（OPEN・permit あり・gate の無い context）は従来どおり直ちに書く。
 *
 * <p>監査の値は従来の success handler と同じ時点（HTTP session の無効化の後）に取る。主体・correlation ID の決め方を、
 * 静止していない時の {@code LOGOUT} の行と揃えるため。
 */
public final class BackupQuiesceLogoutDeferral {

    /** logout の監査の固定値（従来の success handler の値）。 */
    static final String LOGOUT_AUDIT_METHOD = "AUTH";
    static final String LOGOUT_AUDIT_URI = "/logout";
    static final int LOGOUT_AUDIT_STATUS = 302;
    static final String LOGOUT_AUDIT_CODE = "LOGOUT";
    static final String REVOKE_REASON = "LOGOUT";

    private final PersistentSessionService sessions;
    private final AuditLogService audit;
    private final Supplier<BackupWriteGate> gate;
    private final Supplier<BackupQuiesceMetrics> metrics;

    public BackupQuiesceLogoutDeferral(PersistentSessionService sessions, AuditLogService audit,
                                       Supplier<BackupWriteGate> gate, Supplier<BackupQuiesceMetrics> metrics) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** logout の handler（HTTP session を無効にする前）: 今の session の行を失効する。静止中は後へ回す。 */
    public void revokeOnLogout(HttpServletRequest request, Authentication authentication) {
        BackupWriteGate current = blockingGate();
        if (current == null) {
            sessions.revokeCurrent(request, authentication, REVOKE_REASON);
            return;
        }
        PersistentSessionService.CapturedRevocation captured =
                sessions.captureCurrent(request, authentication, REVOKE_REASON);
        if (captured != null) {
            current.deferredWrites().add(BackupQuiesceDeferredWrites.logoutRevocation(sessions, captured));
            countDeferred();
        }
    }

    /** logout の success handler（HTTP session を無効にした後）: {@code LOGOUT} の監査を書く。静止中は後へ回す。 */
    public void recordLogoutAudit(Authentication authentication) {
        String username = authentication == null ? null : authentication.getName();
        BackupWriteGate current = blockingGate();
        if (current == null) {
            audit.record(username, LOGOUT_AUDIT_METHOD, LOGOUT_AUDIT_URI, LOGOUT_AUDIT_STATUS, LOGOUT_AUDIT_CODE, true);
            return;
        }
        Long userId = authentication != null && authentication.getPrincipal() instanceof LoginUser loginUser
                && loginUser.getSysUser() != null ? loginUser.getSysUser().getId() : null;
        current.deferredWrites().add(BackupQuiesceDeferredWrites.logoutAudit(audit, userId, username,
                LOGOUT_AUDIT_METHOD, LOGOUT_AUDIT_URI, LOGOUT_AUDIT_STATUS, LOGOUT_AUDIT_CODE, true,
                AuditLogService.CapturedContext.capture()));
        countDeferred();
    }

    /** 今の thread が書いてはならない gate（書いてよければ null）。 */
    private BackupWriteGate blockingGate() {
        BackupWriteGate current = gate.get();
        return current != null && current.writesBlockedForCurrentThread() ? current : null;
    }

    private void countDeferred() {
        BackupQuiesceMetrics current = metrics.get();
        if (current != null) {
            current.deferred("logout");
        }
    }
}
