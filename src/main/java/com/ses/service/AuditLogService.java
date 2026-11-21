package com.ses.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.audit.ActorAttribution;
import com.ses.common.audit.ExecutionActorContext;
import com.ses.common.util.CorrelationContext;
import com.ses.entity.AuditLog;

import java.time.LocalDateTime;

/**
 * API操作監査ログサービス。
 */
public interface AuditLogService {

    /** 監査ログを1件記録する。書き込み失敗は呼び出し元の処理に影響させない。 */
    void record(String username, String method, String uri, int status);
    default void record(String username, String method, String uri, int status, String applicationCode, boolean successFlag) {
        record(username, method, uri, status);
    }

    /** break-glass等の重要操作用。永続化できなければ呼出処理も失敗させる。 */
    void recordRequired(String username, String method, String uri, int status,
                        String applicationCode, boolean successFlag);

    /**
     * backup の書込み静止の解除の後に、静止中の logout の監査を書く（RB-QUIESCE U-8。design.md §6.13）。
     *
     * <p>{@link #record} は今の時刻と今の thread の文脈（主体・correlation ID 等）から値を取るため、解除の後の executor の thread
     * では logout の時点の値にならない。この method は logout の時点に {@link CapturedContext#capture()} で取っておいた値を
     * そのまま書く。呼ぶのは解除後の書込みの一覧（{@code BackupQuiesceDeferredWrites}）だけ（{@code AuditLogMethodLengthGuardTest}
     * が呼出し元を固定する）。取っておいた時刻が静止の最長の保持時間より古い・今より新しい場合は書かずに WARN を残す。
     * 書込みの失敗は例外で返す（解除後の書込みが繰り返す）。
     */
    default void recordCaptured(String username, String method, String uri, int status, String applicationCode,
                                boolean successFlag, CapturedContext context) {
        throw new UnsupportedOperationException("取っておいた値での監査の記録に対応していません");
    }

    /** ドメイン状態変更と同一トランザクションで必須記録する。 */
    void recordDomainEventRequired(AuditLog entry);

    /** 監査ログを条件検索する（username部分一致・method完全一致、created_at降順）。 */
    Page<AuditLog> page(long current, long size, String username, String method);

    /**
     * 監査の行へ {@link #record} が今の thread から取る値（時刻・主体・correlation ID・診断の文脈）を、ある時点で取っておいたもの。
     * 時刻は {@code record} と同じ JVM の時刻帯の {@link LocalDateTime#now()}。
     */
    record CapturedContext(LocalDateTime createdAt, String actorType, String confirmationSource, Long humanUserId,
                           String correlationId, String idempotencyKey, String invoiceId, String digitalInvoiceId,
                           String jobId, String providerOperationId, String errorCode, String errorCategory) {

        /** 今の thread の文脈を取っておく（{@code record} と同じ規則で主体と correlation ID を決める）。 */
        public static CapturedContext capture() {
            ActorAttribution actor = ExecutionActorContext.resolve();
            String contextCorrelation = CorrelationContext.get(CorrelationContext.CORRELATION_ID);
            String providerOperationId = CorrelationContext.get(CorrelationContext.PROVIDER_OPERATION_ID);
            return new CapturedContext(LocalDateTime.now(), actor.actorType().name(), actor.confirmationSource().name(),
                    actor.humanUserId(), contextCorrelation != null ? contextCorrelation : actor.correlationId(),
                    actor.idempotencyKey() != null ? actor.idempotencyKey() : providerOperationId,
                    CorrelationContext.get(CorrelationContext.INVOICE_ID),
                    CorrelationContext.get(CorrelationContext.DIGITAL_INVOICE_ID),
                    CorrelationContext.get(CorrelationContext.JOB_ID), providerOperationId,
                    CorrelationContext.get(CorrelationContext.ERROR_CODE),
                    CorrelationContext.get(CorrelationContext.ERROR_CATEGORY));
        }

        /** 取っておいた値を監査の行へ移す。 */
        public void applyTo(AuditLog entry) {
            entry.setActorType(actorType);
            entry.setConfirmationSource(confirmationSource);
            entry.setHumanUserId(humanUserId);
            entry.setCorrelationId(correlationId);
            entry.setIdempotencyKey(idempotencyKey);
            entry.setInvoiceId(invoiceId);
            entry.setDigitalInvoiceId(digitalInvoiceId);
            entry.setJobId(jobId);
            entry.setProviderOperationId(providerOperationId);
            entry.setErrorCode(errorCode);
            entry.setErrorCategory(errorCategory);
            entry.setCreatedAt(createdAt);
        }
    }
}
