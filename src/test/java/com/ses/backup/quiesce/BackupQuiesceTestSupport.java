package com.ses.backup.quiesce;

import java.time.Clock;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * 他の package の試験が backup の書込み静止の gate を決定的に開閉するための部品（試験の source だけに置く）。
 *
 * <p>状態の遷移は本来は静止応答の状態機械だけが行う（package-private）。ここでは試験が信号表を通さずに gate を閉じ・開ける。
 * heartbeat の thread は無いため、FENCED にならないよう時計を試験が決める（{@code nanoClock} を止めておけば FENCED にならず、
 * 手元の期限にも達しない）。
 */
public final class BackupQuiesceTestSupport {

    public static final Duration FENCE_AFTER = Duration.ofSeconds(20);

    private BackupQuiesceTestSupport() {
    }

    /** 登録の後の OPEN の gate（ARMED。prod の profile かどうかを選ぶ）。 */
    public static BackupWriteGate openGate(boolean prodProfile, LongSupplier nanoClock, Clock wallClock) {
        BackupWriteGate gate = new BackupWriteGate(BackupWriteGate.Mode.ARMED, prodProfile, FENCE_AFTER, 10_000,
                nanoClock, wallClock);
        gate.armOpen();
        return gate;
    }

    /** 登録の前（UNARMED）の gate。 */
    public static BackupWriteGate unarmedGate(boolean prodProfile, LongSupplier nanoClock, Clock wallClock) {
        return new BackupWriteGate(BackupWriteGate.Mode.ARMED, prodProfile, FENCE_AFTER, 10_000, nanoClock, wallClock);
    }

    /** 登録の前（UNARMED）に静止応答が main の thread で取る startup permit（§6.8）を、呼んだ thread に取る。 */
    public static BackupWritePermit startupPermit(BackupWriteGate gate) {
        return gate.acquireStartupPermitWhileUnarmed("startup");
    }

    /** 排水の途中（CLOSING）にする。 */
    public static void beginClosing(BackupWriteGate gate, long requestId) {
        if (!gate.beginClosing(requestId)) {
            throw new IllegalStateException("gate を閉じられません: " + gate);
        }
    }

    /** QUIESCED を試みた後（CLOSED）にする。手元の期限は今から {@code hold} の後。 */
    public static void close(BackupWriteGate gate, long requestId, Duration hold) {
        beginClosing(gate, requestId);
        gate.extendLocalDeadline(gate.nanoTime() + hold.toNanos());
        if (!gate.markQuiescedAttempt(requestId)) {
            throw new IllegalStateException("gate を CLOSED にできません: " + gate);
        }
    }

    /** 非 QUIESCED を確定して開ける（I-3 (a)）。開いた時の callback（保留した task の投入等）が動く。 */
    public static void reopen(BackupWriteGate gate, long requestId) {
        gate.confirmNonQuiesced(requestId);
        if (!gate.reopen(requestId)) {
            throw new IllegalStateException("gate を開けられません: " + gate);
        }
    }

    /** heartbeat を書けたことにする（FENCED にしない）。 */
    public static void heartbeat(BackupWriteGate gate) {
        gate.recordHeartbeatSuccess();
    }
}
