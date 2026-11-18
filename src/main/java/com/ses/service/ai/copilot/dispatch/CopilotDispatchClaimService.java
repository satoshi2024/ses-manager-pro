package com.ses.service.ai.copilot.dispatch;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AiExternalSendCostBucket;
import com.ses.entity.AiExternalSendCostEntry;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.AiExternalSendApproval;
import com.ses.service.ai.AiExternalSendAuditor;
import com.ses.service.ai.ExternalSendCostLedger;
import com.ses.service.ai.PersistentExternalSendCostLedger;
import com.ses.service.ai.PersistentExternalSendCostLedger.BucketKeys;
import com.ses.service.ai.PersistentExternalSendCostLedger.ReserveResult;
import com.ses.service.ai.copilot.CopilotRunService;
import com.ses.service.ai.copilot.summary.DispatchOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.sql.SQLException;
import java.sql.SQLTransientException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * NF08 B2c-1: summary dispatchのdurable claimと、commit確認後にだけ発行する {@link ClaimPermit}（計画 §1.3）。
 *
 * <p><b>順序</b>（各段は前段が成功した場合だけ進む）:</p>
 * <ol>
 *   <li>{@link #prepareReady}: SUMMARY attempt行（PENDING/v0/NOT_APPLICABLE）を一度だけREADYにし、発行時binding
 *       （claim ID・nonce/idempotency hash・artifact/config/model/region/tier・scope/input・preflight・request digest・
 *       承認digest・card/policy・issued/expires）を固定する。claim IDはここで一度だけ生成し、再試行で変えない。</li>
 *   <li>{@link #claim}（transaction外から呼ぶ）: (a) bucket行の存在保証（別の短いtransaction）→ (b) ATTEMPT監査
 *       （REQUIRES_NEW。失敗したらclaimしない）→ (c) claim transaction（lock順 run → DAY → MONTH → entry）→
 *       (d) commitが確認できた場合だけpermitを返す。拒否・rollback・commit不明では送信前のRESULT監査を
 *       transaction終了後に記録する。</li>
 * </ol>
 * <p><b>再試行</b>: 1213/1205・lock待ちのtimeout・bucket行の欠落・JST日付境界の再準備は合計最大3回。同じclaim IDを保ち、
 * ATTEMPTを重複記録しない。各試行の前に残deadlineを確かめ、1秒未満なら開始しない。transactionのtimeoutは
 * 残deadline以下（最大10秒）に切り下げ、既定50秒のlock待ちに依存しない。</p>
 * <p><b>commit不明</b>: callbackの完了後にcommitが例外になった場合はpermitを返さず、別transactionで行を読み直す
 * （CLAIMEDなら予約を維持、READYなら予約はrollback済み）。どちらでも同じticketは再claimしない。</p>
 * <p><b>B2c-1の範囲</b>: gateway・transportへ接続しない（B2c-2）。{@code CopilotQueryService} /
 * {@code CopilotSummaryService} はlocal-only判定のまま本serviceを呼ばず、prodは external-send=true を起動拒否する。</p>
 */
@Slf4j
@Service
public class CopilotDispatchClaimService {

    public static final String REASON_STALE = "COPILOT_DISPATCH_CLAIM_STALE";
    public static final String REASON_ALREADY_ATTEMPTED = "COPILOT_DISPATCH_CLAIM_ALREADY_ATTEMPTED";
    /** gatewayと同じ理由コード（既存のmessage keyがある）。 */
    public static final String REASON_TRANSACTION_ACTIVE = "AI_EXTERNAL_SEND_TRANSACTION_ACTIVE";
    /** claim時のJST業務日が承認の有効期間外（失効日以降・署名日前）。既存のmessage keyがある（計画 §4 #16）。 */
    public static final String REASON_APPROVAL_NOT_EFFECTIVE = "AI_EXTERNAL_SEND_NOT_APPROVED";
    public static final String REASON_CONTEXT = "EXECUTION_CONTEXT_REQUIRED";
    public static final String REASON_DEADLINE = "COPILOT_DISPATCH_DEADLINE_EXCEEDED";
    public static final String REASON_ATTEMPT_EXPIRED = "COPILOT_DISPATCH_ATTEMPT_EXPIRED";
    public static final String REASON_CARD_NOT_EFFECTIVE = "COPILOT_DISPATCH_CARD_NOT_EFFECTIVE";
    public static final String REASON_CLOCK_INVALID = "CLOCK_INVALID";
    public static final String REASON_LEDGER_UNAVAILABLE = "LEDGER_UNAVAILABLE";
    public static final String REASON_CLAIM_UNCERTAIN = "CLAIM_UNCERTAIN";
    public static final String REASON_CLAIM_FAILED = "COPILOT_DISPATCH_CLAIM_FAILED";
    public static final String REASON_AUDIT_UNAVAILABLE = "AI_EXTERNAL_SEND_AUDIT_UNAVAILABLE";
    /** ATTEMPT監査に記録する理由（claim要求の開始）。 */
    public static final String REASON_CLAIM_REQUESTED = "COPILOT_DISPATCH_CLAIM_REQUESTED";
    /** 日付境界の再準備（内部の再試行理由。上限まで続いた場合はLEDGER_UNAVAILABLEとして記録する）。 */
    static final String REASON_DAY_BOUNDARY = "COPILOT_DISPATCH_DAY_BOUNDARY";

    static final int MAX_TRANSACTION_ATTEMPTS = 3;
    static final Duration MIN_REMAINING = Duration.ofSeconds(1);
    static final Duration CLOCK_TOLERANCE = Duration.ofSeconds(1);
    static final int MAX_TRANSACTION_TIMEOUT_SECONDS = 10;
    /** t_ai_recommendation_run.tenant_id（V162）と同じ幅。 */
    static final int MAX_TENANT_LENGTH = 100;
    private static final int SECRET_BYTES = 32;
    private static final String NOT_STARTED = DispatchOutcome.State.NOT_STARTED.name();

    private final AiRecommendationRunMapper runMapper;
    private final PersistentExternalSendCostLedger ledger;
    private final AiExternalSendAuditor auditor;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final SecureRandom random;

    @Autowired
    public CopilotDispatchClaimService(AiRecommendationRunMapper runMapper, PersistentExternalSendCostLedger ledger,
                                       AiExternalSendAuditor auditor, PlatformTransactionManager transactionManager,
                                       Clock clock) {
        this(runMapper, ledger, auditor, transactionManager, clock, System::nanoTime, new SecureRandom());
    }

    CopilotDispatchClaimService(AiRecommendationRunMapper runMapper, PersistentExternalSendCostLedger ledger,
                                AiExternalSendAuditor auditor, PlatformTransactionManager transactionManager,
                                Clock clock, LongSupplier nanoTime, SecureRandom random) {
        this.runMapper = Objects.requireNonNull(runMapper);
        this.ledger = Objects.requireNonNull(ledger);
        this.auditor = Objects.requireNonNull(auditor);
        this.transactionManager = Objects.requireNonNull(transactionManager);
        this.clock = Objects.requireNonNull(clock);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.random = Objects.requireNonNull(random);
    }

    /**
     * SUMMARY attempt行を一度だけREADYにし、発行時snapshotを返す。B2c-2では {@code beginSummaryAttempt} と同じ
     * transactionで呼び、READY以外の状態のsummary dispatch行を他transactionへ見せない。
     *
     * @throws IllegalArgumentException attempt・入力・artifact snapshotが不正
     * @throws IllegalStateException    行がPENDING/v0/NOT_APPLICABLEでない（既にREADY化済み等）
     */
    @Transactional(rollbackFor = Exception.class)
    public DispatchTicket prepareReady(CopilotRunService.CopilotAttemptRecord attempt, DispatchIssueRequest request) {
        if (attempt == null || request == null || attempt.runId() == null
                || !"SUMMARY".equals(attempt.attemptKind())) {
            throw new IllegalArgumentException("summary attempt and dispatch request are required");
        }
        String tenant = attempt.tenantId();
        if (tenant == null || tenant.isBlank() || tenant.length() > MAX_TENANT_LENGTH
                || !tenant.equals(currentTenantOrNull())) {
            throw BusinessException.of(403, REASON_CONTEXT);
        }
        CopilotRunService.CopilotArtifactSnapshot artifact = attempt.artifact();
        if (artifact == null || !Objects.equals(artifact.artifactVersionId(), request.artifactVersionId())
                || !Objects.equals(artifact.provider(), request.provider())
                || !Objects.equals(artifact.configHash(), request.configHash())) {
            throw new IllegalArgumentException("dispatch request must use the attempt artifact snapshot");
        }
        DispatchTicket ticket = new DispatchTicket(attempt.runId(), tenant, attempt.traceId(),
                UUID.randomUUID().toString(), request, secret(), secret(), clock.instant(), nanoTime.getAsLong());
        if (runMapper.markDispatchReady(ticket.columns()) != 1) {
            throw new IllegalStateException("Copilot summary attemptをREADYにできません");
        }
        return ticket;
    }

    /**
     * durable claimを行い、commitを確認できた場合だけ {@link ClaimPermit} を返す。transaction外から呼ぶ。
     * 同じticketでは一度だけ実行する（二度目は {@link #REASON_ALREADY_ATTEMPTED}）。
     */
    public ClaimOutcome claim(DispatchTicket ticket) {
        Objects.requireNonNull(ticket, "ticket");
        if (transactionBound()) {
            // transaction保持中はREQUIRES_NEW監査もclaim transactionも開始しない（R-2）。何も記録しない。
            return notClaimed(REASON_TRANSACTION_ACTIVE, 0);
        }
        if (!ticket.markClaimStarted()) {
            return notClaimed(REASON_ALREADY_ATTEMPTED, 0);
        }
        if (!ticket.tenantId().equals(currentTenantOrNull())) {
            return notClaimed(REASON_CONTEXT, 0);
        }
        if (remaining(ticket).compareTo(MIN_REMAINING) < 0) {
            return notClaimed(REASON_DEADLINE, 0);
        }
        BucketKeys keys = BucketKeys.of(clock.instant());
        if (!ensureBuckets(ticket, keys)) {
            return notClaimed(REASON_LEDGER_UNAVAILABLE, 0);
        }
        try {
            auditor.recordAttempt(attemptAudit(ticket));
        } catch (RuntimeException ex) {
            // 計画 §4 #2: READYのまま期限で失効させ、照合でATTEMPT_NOT_PERSISTEDのRESULTを補記する。
            log.warn("AI外部送信の送信前監査を永続化できないためclaimしません: claim={} errorType={}",
                    ticket.claimId(), failureType(ex));
            return notClaimed(REASON_AUDIT_UNAVAILABLE, 0);
        }

        int attempts = 0;
        String reason = REASON_LEDGER_UNAVAILABLE;
        while (attempts < MAX_TRANSACTION_ATTEMPTS) {
            Duration remaining = remaining(ticket);
            if (remaining.compareTo(MIN_REMAINING) < 0) {
                reason = REASON_DEADLINE;
                break;
            }
            attempts++;
            ClaimProgress progress = new ClaimProgress();
            BucketKeys attemptKeys = keys;
            try {
                Instant claimedAt = template(remaining, false)
                        .execute(status -> claimInTransaction(ticket, attemptKeys, progress));
                // execute が正常に戻った = commit成功を確認した。ここでだけpermitを作る。
                return new ClaimOutcome(ClaimOutcome.Status.CLAIMED, null, DispatchState.CLAIMED,
                        new ClaimPermit(ticket, claimedAt), attempts);
            } catch (RuntimeException ex) {
                if (progress.callbackCompleted) {
                    return uncertain(ticket, attempts, ex);
                }
                ClaimRefusal refusal = refusalOf(ex);
                if (refusal != null) {
                    reason = refusal.reasonCode;
                    if (!refusal.retryable) {
                        break;
                    }
                    if (refusal.nextKeys != null) {
                        keys = refusal.nextKeys;
                    }
                    if (attempts < MAX_TRANSACTION_ATTEMPTS && !ensureBuckets(ticket, keys)) {
                        reason = REASON_LEDGER_UNAVAILABLE;
                        break;
                    }
                    continue;
                }
                if (isTransient(ex)) {
                    // callbackは完了していないためcommitは発行されていない。statement timeoutの後はHikariが接続を
                    // 壊れたものとして閉じ（rollback自体が失敗し得る）、server側の未commit transactionは切断で
                    // rollbackされる。次の試行の前に残deadlineを確かめ、1秒未満ならDEADLINEとして止める。
                    reason = REASON_LEDGER_UNAVAILABLE;
                    continue;
                }
                log.warn("Copilot dispatch claim transactionが失敗しました: claim={} errorType={}",
                        ticket.claimId(), failureType(ex));
                reason = REASON_CLAIM_FAILED;
                break;
            }
        }
        if (REASON_DAY_BOUNDARY.equals(reason)) {
            reason = REASON_LEDGER_UNAVAILABLE;
        }
        recordResultQuietly(ticket, reason, "NOT_SENT", 0, statusFor(reason));
        return notClaimed(reason, attempts);
    }

    /** claim transactionの本体（lock順 run → DAY → MONTH → entry → run CAS）。拒否は {@link ClaimRefusal} でrollbackする。 */
    private Instant claimInTransaction(DispatchTicket ticket, BucketKeys keys, ClaimProgress progress) {
        DispatchColumns columns = ticket.columns();
        DispatchIssueRequest request = ticket.request();
        DispatchRunRow row = runMapper.lockDispatchRow(columns);
        List<String> drift = ticket.mismatches(row);
        if (!drift.isEmpty()) {
            log.warn("Copilot dispatch claimの対象行が発行時snapshotと一致しないためclaimしません: claim={} columns={}",
                    ticket.claimId(), String.join(",", drift));
            throw ClaimRefusal.of(REASON_STALE);
        }
        // lock取得・全列照合の後、CASの前に新しく時刻を読む（以前の時刻を使い回さない）。
        Instant now = DispatchDigests.micros(clock.instant());
        String timeProblem = timeProblem(ticket, now);
        if (timeProblem != null) {
            throw ClaimRefusal.of(timeProblem);
        }
        Instant highWater = ledger.clockHighWater(request.costScope());
        if (highWater != null && now.plus(CLOCK_TOLERANCE).isBefore(highWater)) {
            throw ClaimRefusal.of(REASON_CLOCK_INVALID);
        }
        BucketKeys current = BucketKeys.of(now);
        if (!current.equals(keys)) {
            // JST日付・月を跨いだ。rollbackしてtransaction外で新しいbucketを事前生成する（ATTEMPTは増やさない）。
            throw ClaimRefusal.retry(REASON_DAY_BOUNDARY, current);
        }
        byte[] approvalDigest = DispatchDigests.hex(request.approvalDigest());
        AiExternalSendCostBucket day = ledger.lockBucket(AiExternalSendCostBucket.TYPE_DAY, request.costScope(),
                keys.dayKey());
        if (day == null) {
            throw ClaimRefusal.retry(PersistentExternalSendCostLedger.REASON_BUCKET_MISSING, null);
        }
        // DAYの待機中に他のclaimが先にcommitして高水位を進め得るため、lock取得後に時刻を読み直し、
        // 同じ検査をやり直す（待機時間を時計の逆行と誤判定しない）。以降の記録はこの時刻を使う。
        now = afterBucketLock(ticket, keys, now);
        reserve(day, request.dailyLimitYen(), ExternalSendCostLedger.REASON_DAILY, ticket, approvalDigest, now);
        AiExternalSendCostBucket month = ledger.lockBucket(AiExternalSendCostBucket.TYPE_MONTH, request.costScope(),
                keys.monthKey());
        if (month == null) {
            throw ClaimRefusal.retry(PersistentExternalSendCostLedger.REASON_BUCKET_MISSING, null);
        }
        reserve(month, request.monthlyLimitYen(), ExternalSendCostLedger.REASON_MONTHLY, ticket, approvalDigest, now);
        ledger.recordReservation(entry(ticket, keys, approvalDigest, now));
        if (runMapper.claimDispatch(columns, DispatchDigests.utc(now)) != 1) {
            throw ClaimRefusal.of(REASON_STALE);
        }
        progress.callbackCompleted = true;
        return now;
    }

    /** DAY bucketのlock取得後の時刻。前の時刻より戻っていれば逆行、期限・日付境界は改めて検査する。 */
    private Instant afterBucketLock(DispatchTicket ticket, BucketKeys keys, Instant beforeLock) {
        Instant locked = DispatchDigests.micros(clock.instant());
        if (locked.isBefore(beforeLock)) {
            throw ClaimRefusal.of(REASON_CLOCK_INVALID);
        }
        String problem = timeProblem(ticket, locked);
        if (problem != null) {
            throw ClaimRefusal.of(problem);
        }
        BucketKeys current = BucketKeys.of(locked);
        if (!current.equals(keys)) {
            throw ClaimRefusal.retry(REASON_DAY_BOUNDARY, current);
        }
        return locked;
    }

    private void reserve(AiExternalSendCostBucket locked, long limitYen, String limitReason,
                         DispatchTicket ticket, byte[] approvalDigest, Instant now) {
        ReserveResult result = ledger.reserve(locked, ticket.request().upperBoundYen(), limitYen, approvalDigest,
                now, CLOCK_TOLERANCE);
        switch (result) {
            case RESERVED -> { }
            case LIMIT_EXCEEDED -> throw ClaimRefusal.of(limitReason);
            case BUCKET_MISSING -> throw ClaimRefusal.retry(PersistentExternalSendCostLedger.REASON_BUCKET_MISSING, null);
            case CLOCK_INVALID -> throw ClaimRefusal.of(REASON_CLOCK_INVALID);
            default -> throw new IllegalStateException("unsupported reserve result");
        }
    }

    /** 時刻の検査。問題があれば理由コード、なければnull。 */
    private String timeProblem(DispatchTicket ticket, Instant now) {
        DispatchIssueRequest request = ticket.request();
        if (now.isBefore(ticket.issuedAt())) {
            return REASON_CLOCK_INVALID;
        }
        // プロセス内のmonotonic経過と比べ、壁時計だけが戻った場合を拒否する（相互変換はしない）。
        long monotonicElapsed = nanoTime.getAsLong() - ticket.issuedNanos();
        long wallElapsed = Duration.between(ticket.issuedAt(), now).toNanos();
        if (wallElapsed + CLOCK_TOLERANCE.toNanos() < monotonicElapsed) {
            return REASON_CLOCK_INVALID;
        }
        if (!now.isBefore(ticket.expiresAt())) {
            return REASON_ATTEMPT_EXPIRED;
        }
        if (now.isBefore(request.cardEffectiveFrom()) || !now.isBefore(request.cardEffectiveUntil())) {
            return REASON_CARD_NOT_EFFECTIVE;
        }
        // 承認の有効期間はJST業務日で判定する（AiExternalSendApprovalと同じ: 署名日以上・失効日未満）。
        if (!request.approvalEffectiveOn(LocalDate.ofInstant(now, AiExternalSendApproval.BUSINESS_ZONE))) {
            return REASON_APPROVAL_NOT_EFFECTIVE;
        }
        if (remaining(ticket).isNegative() || remaining(ticket).isZero()) {
            return REASON_DEADLINE;
        }
        return null;
    }

    /**
     * callback完了後にcommitの成否を確認できなかった場合。permitを返さず、別transactionで読み直す。
     * 読み直しは排他lock付き（{@code FOR UPDATE}）で行い、進行中のcommitが終わるまで待ってから最新の確定値を読む
     * （非lockの読取りだとcommit中の行をREADYと読み、RESULTの費用状態を誤り得る）。待ちはtransactionのtimeout
     * （残deadline以下、最大10秒）で打ち切り、読めなければUNKNOWNとする。行の状態は変えない。
     */
    private ClaimOutcome uncertain(DispatchTicket ticket, int attempts, RuntimeException failure) {
        log.warn("Copilot dispatch claimのcommit結果を確認できないためpermitを発行しません: claim={} errorType={}",
                ticket.claimId(), failureType(failure));
        DispatchState observed = null;
        try {
            Duration remaining = remaining(ticket);
            observed = template(remaining.compareTo(MIN_REMAINING) < 0 ? MIN_REMAINING : remaining, false)
                    .execute(status -> {
                        DispatchRunRow row = runMapper.lockDispatchRow(ticket.columns());
                        if (row == null || !ticket.claimId().equals(row.getDispatchClaimIdText())) {
                            return null;
                        }
                        return DispatchState.of(row.getDispatchState());
                    });
        } catch (RuntimeException ex) {
            log.warn("Copilot dispatch claimの読み直しに失敗しました: claim={} errorType={}",
                    ticket.claimId(), failureType(ex));
        }
        // CLAIMEDなら予約は維持（上限側に残す）。READYならclaim transactionはcommitされていない。
        // 読めなければ送信可能性を否定できないためUNKNOWNとする。いずれもpermitは発行しない。
        String costState = observed == DispatchState.CLAIMED ? "UPPER_BOUND"
                : observed == DispatchState.READY ? "NOT_SENT" : "UNKNOWN";
        Integer costYen = observed == DispatchState.READY ? Integer.valueOf(0)
                : observed == DispatchState.CLAIMED ? Integer.valueOf(ticket.request().upperBoundYen()) : null;
        recordResultQuietly(ticket, REASON_CLAIM_UNCERTAIN, costState, costYen, 503);
        return new ClaimOutcome(ClaimOutcome.Status.UNCERTAIN, REASON_CLAIM_UNCERTAIN, observed, null, attempts);
    }

    /** bucket行の存在保証（claimとは別の短いtransaction）。一時的なlock失敗は最大3回まで繰り返す。 */
    private boolean ensureBuckets(DispatchTicket ticket, BucketKeys keys) {
        for (int i = 0; i < MAX_TRANSACTION_ATTEMPTS; i++) {
            Duration remaining = remaining(ticket);
            if (remaining.compareTo(MIN_REMAINING) < 0) {
                return false;
            }
            try {
                template(remaining, false).executeWithoutResult(
                        status -> ledger.ensureBuckets(ticket.request().costScope(), keys));
                return true;
            } catch (RuntimeException ex) {
                if (!isTransient(ex)) {
                    log.warn("費用bucketを準備できないためclaimしません: claim={} errorType={}",
                            ticket.claimId(), failureType(ex));
                    return false;
                }
            }
        }
        return false;
    }

    private TransactionTemplate template(Duration remaining, boolean readOnly) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        template.setTimeout((int) Math.max(1, Math.min(MAX_TRANSACTION_TIMEOUT_SECONDS, remaining.getSeconds())));
        template.setReadOnly(readOnly);
        return template;
    }

    private AiExternalSendCostEntry entry(DispatchTicket ticket, BucketKeys keys, byte[] approvalDigest, Instant now) {
        DispatchIssueRequest request = ticket.request();
        AiExternalSendCostEntry entry = new AiExternalSendCostEntry();
        entry.setReservationId(ticket.claimId());
        entry.setTenantId(ticket.tenantId());
        entry.setRunId(ticket.runId());
        entry.setTraceId(ticket.traceId());
        entry.setCostScope(request.costScope());
        entry.setDayKey(keys.dayKey());
        entry.setMonthKey(keys.monthKey());
        entry.setDecisionId(request.decisionId());
        entry.setApprovalDigest(approvalDigest);
        entry.setReservedYen(request.upperBoundYen());
        entry.setCreatedAt(DispatchDigests.utc(now));
        return entry;
    }

    private AiExternalSendAuditor.SendAudit attemptAudit(DispatchTicket ticket) {
        DispatchIssueRequest request = ticket.request();
        // ATTEMPTは送信byte数と費用上限見積りだけを持つ。予約後の累計はRESULT側の照合で扱う。
        return new AiExternalSendAuditor.SendAudit(ticket.claimId(), ticket.traceId(), request.decisionId(),
                request.approvalDigest(), request.provider(), request.modelRevision(), DispatchTicket.USE_CASE,
                request.destinationHost(), ticket.tenantId(), request.legalEntityId(), request.queryId(),
                request.artifactVersionId(), request.configHash(), request.promptBytes(), request.upperBoundYen(),
                null, null, NOT_STARTED, REASON_CLAIM_REQUESTED, null, null, "UPPER_BOUND", 202, true, null);
    }

    /** 送信前のRESULT監査（transaction終了後・REQUIRES_NEW）。失敗しても結果は変えず、照合の対象として残す。 */
    private void recordResultQuietly(DispatchTicket ticket, String reason, String costState, Integer costYen,
                                     int status) {
        DispatchIssueRequest request = ticket.request();
        try {
            auditor.recordResult(new AiExternalSendAuditor.SendAudit(ticket.claimId(), ticket.traceId(),
                    request.decisionId(), request.approvalDigest(), request.provider(), request.modelRevision(),
                    DispatchTicket.USE_CASE, request.destinationHost(), ticket.tenantId(), request.legalEntityId(),
                    request.queryId(), request.artifactVersionId(), request.configHash(), request.promptBytes(),
                    request.upperBoundYen(), null, null, NOT_STARTED, reason, null, costYen, costState, status,
                    false, null));
        } catch (RuntimeException ex) {
            log.warn("AI外部送信の結果監査を永続化できません（照合で補記します）: claim={} reason={} errorType={}",
                    ticket.claimId(), reason, ex.getClass().getSimpleName());
        }
    }

    private static int statusFor(String reason) {
        if (ExternalSendCostLedger.REASON_DAILY.equals(reason) || ExternalSendCostLedger.REASON_MONTHLY.equals(reason)) {
            return 429;
        }
        if (REASON_STALE.equals(reason)) {
            return 409;
        }
        return 503;
    }

    private static ClaimOutcome notClaimed(String reason, int attempts) {
        return new ClaimOutcome(ClaimOutcome.Status.NOT_CLAIMED, reason, null, null, attempts);
    }

    private Duration remaining(DispatchTicket ticket) {
        return Duration.ofNanos(ticket.request().deadlineNanos() - nanoTime.getAsLong());
    }

    private byte[] secret() {
        byte[] value = new byte[SECRET_BYTES];
        random.nextBytes(value);
        return value;
    }

    private static String currentTenantOrNull() {
        try {
            return AccountingTenantContextHolder.requireTenantContext();
        } catch (BusinessException ex) {
            return null;
        }
    }

    /** gateway（G1r）と同じ判定: 実transaction・同期scope・thread-bound resourceのいずれかがあれば拒否する。 */
    private static boolean transactionBound() {
        return TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty();
    }

    private static ClaimRefusal refusalOf(Throwable failure) {
        if (failure instanceof ClaimRefusal refusal) {
            return refusal;
        }
        // rollback自体が失敗した場合、TransactionTemplateはrollbackの例外を投げ、元の例外を保持する。
        if (failure instanceof TransactionSystemException system
                && system.getApplicationException() instanceof ClaimRefusal refusal) {
            return refusal;
        }
        return null;
    }

    /**
     * 1213（deadlock）・1205（lock wait timeout）・statement/transaction timeoutなど、再試行してよい失敗。
     * <p>cause鎖に加えて、{@link TransactionSystemException} が保持する元の例外（application exception）も辿る。
     * 実MySQLではstatement timeout（Connector/Jの {@code MySQLTimeoutException}、{@link java.sql.SQLTimeoutException}
     * の派生）の後にHikariが接続を壊れたものとして閉じるため、続くrollbackが失敗し、TransactionTemplateは
     * rollbackの例外（cause鎖に元のtimeoutを含まない）を投げる。元の例外を見ないとtimeoutを一般の失敗と誤分類する。</p>
     */
    static boolean isTransient(Throwable failure) {
        java.util.Deque<Throwable> pending = new java.util.ArrayDeque<>();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        if (failure != null) {
            pending.add(failure);
        }
        while (!pending.isEmpty() && seen.size() < 32) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof PessimisticLockingFailureException || current instanceof QueryTimeoutException
                    || current instanceof TransactionTimedOutException || current instanceof SQLTransientException) {
                return true;
            }
            if (current instanceof SQLException sql
                    && (sql.getErrorCode() == 1213 || sql.getErrorCode() == 1205 || "40001".equals(sql.getSQLState()))) {
                return true;
            }
            if (current instanceof TransactionSystemException system && system.getApplicationException() != null) {
                pending.add(system.getApplicationException());
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
        }
        return false;
    }

    /** logに出す例外の型名。rollbackの失敗が元の例外を覆った場合は元の型も示す（値・messageは出さない）。 */
    static String failureType(Throwable failure) {
        if (failure instanceof TransactionSystemException system && system.getApplicationException() != null) {
            return failure.getClass().getSimpleName() + "<-" + system.getApplicationException().getClass().getSimpleName();
        }
        return failure.getClass().getSimpleName();
    }

    /** callbackがCASまで完了したか（commit失敗とcallback内の失敗を区別する）。 */
    private static final class ClaimProgress {
        private boolean callbackCompleted;
    }

    /** claim transaction内の拒否。投げるとtransactionはrollbackされる。stack traceを持たない。 */
    private static final class ClaimRefusal extends RuntimeException {
        private final String reasonCode;
        private final boolean retryable;
        private final BucketKeys nextKeys;

        private ClaimRefusal(String reasonCode, boolean retryable, BucketKeys nextKeys) {
            super(reasonCode, null, false, false);
            this.reasonCode = reasonCode;
            this.retryable = retryable;
            this.nextKeys = nextKeys;
        }

        static ClaimRefusal of(String reasonCode) {
            return new ClaimRefusal(reasonCode, false, null);
        }

        static ClaimRefusal retry(String reasonCode, BucketKeys nextKeys) {
            return new ClaimRefusal(reasonCode, true, nextKeys);
        }
    }
}
