package com.ses.backup.quiesce;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 信号表（V202。{@code sys_backup_quiesce_request}・{@code _instance}・{@code _ack}）の SQL を集めた入口（design.md §3・§6.5.4）。
 *
 * <p>実装（{@link JdbcBackupQuiesceSignalRepository}）は制御用・heartbeat 用の接続 pool の {@code JdbcTemplate} だけを使い、
 * 業務の DataSource・MyBatis-Plus の mapper・HTTP の request thread を通らない。時刻は全て DB の {@code UTC_TIMESTAMP(6)} で
 * 書き・比べる。ack の状態の遷移は条件付きの文と影響した行数で確定する（§6.9）。
 * 例外は Spring の {@code DataAccessException}（一意の違反は {@code DuplicateKeyException}）で返す。
 */
public interface BackupQuiesceSignalRepository {

    /** protocol の版（初版 1）。 */
    int PROTOCOL_VERSION = 1;

    /** instance の登録の値（§3.1）。 */
    record InstanceRegistration(String instanceId, String hostName, String appCommit, long pollIntervalMs,
                                long heartbeatIntervalMs, long fenceAfterMs, long maxHoldCheckpointMs,
                                long maxHoldFullMs, boolean schedulingEnabled, String gateState) {
    }

    /** 登録の結果（{@code USER()} の host 部と {@code CONNECTION_ID()}）。 */
    record Registered(String dbClientHost, long connectionId) {
    }

    /**
     * 静止要求の1行と、同じ文の中の {@code UTC_TIMESTAMP(6)} で判定した値。{@code holdUntil} は
     * {@code LEAST(lease_expires_at, requested_at + 最大保持時間(job_kind))}、{@code holdRemainingMicros} はその時刻までの残り、
     * {@code holdCapped} は hold 期限が最大保持時間で決まった（lease の方が後にある）こと。
     */
    record RequestView(long id, String mode, String jobKind, String status, String releaseReason, int protocolVersion,
                       boolean leaseValid, boolean maxHoldReached, boolean holdCapped, LocalDateTime holdUntil,
                       long holdRemainingMicros,
                       LocalDateTime verifyRequestedAt) {

        /** 有効（REQUESTED で lease が期限内）か。 */
        public boolean active() {
            return "REQUESTED".equals(status) && leaseValid;
        }

        public boolean full() {
            return "FULL".equals(jobKind);
        }

        public boolean appStopped() {
            return "APP_STOPPED".equals(mode);
        }
    }

    /** 同じ app account の接続元の観測（{@code host=件数} の一覧と、観測の文の {@code UTC_TIMESTAMP(6)}）。 */
    record Observation(String hosts, LocalDateTime observedAt) {
    }

    /** ack の状態。 */
    enum AckState { QUIESCED, REFUSED, RESUMED }

    /** instance を STARTING で登録する（heartbeat 用の接続。最初の heartbeat）。 */
    Registered registerInstance(InstanceRegistration registration);

    /** heartbeat を書く（heartbeat 用の接続）。行が無い・STOPPED なら false。 */
    boolean heartbeat(String instanceId, String status, String gateState);

    /** instance を STOPPED にする（heartbeat 用の接続。業務の接続 pool を閉じた後にだけ呼ぶ）。 */
    boolean markStopped(String instanceId);

    /** 有効枠を持つ（REQUESTED の）要求。lease の期限は {@link RequestView#leaseValid()} で見る。 */
    Optional<RequestView> findActiveRequest();

    Optional<RequestView> findRequest(long requestId);

    /** {@code information_schema.PROCESSLIST} の同じ app account の接続元を観測する。 */
    Observation observeAppHosts();

    void insertQuiescedAck(long requestId, String instanceId, String schedulerState, int drainMillis,
                           LocalDateTime holdUntil, Observation observation);

    /** REFUSED を書く。{@code observation} が null なら接続元は空で観測の時刻はこの文の時刻。 */
    void insertRefusedAck(long requestId, String instanceId, String schedulerState, int drainMillis,
                          String refuseReason, Observation observation);

    Optional<AckState> findAckState(long requestId, String instanceId);

    /** QUIESCED の ack の {@code hold_until} を延ばす（今の値より後の時だけ）。 */
    int updateHoldUntil(long requestId, String instanceId, LocalDateTime holdUntil);

    int updateObservation(long requestId, String instanceId, Observation observation);

    /** QUIESCED → RESUMED（1行に効いた時だけ確定）。 */
    int markResumed(long requestId, String instanceId, String resumeReason);

    /** 保存期間を過ぎた RELEASED / ABORTED / EXPIRED の要求を消す（ack は CASCADE）。 */
    int purgeRequests(Duration retention);

    /** {@code last_beat_at} が保存期間より古い instance を消す（自分の行は消さない）。 */
    int purgeInstances(Duration retention, String ownInstanceId);
}
