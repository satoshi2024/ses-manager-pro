package com.ses.service.integration;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.IntegrationJob;
import com.ses.entity.IntegrationJobEvent;

import java.util.List;

public interface IntegrationJobService extends IService<IntegrationJob> {

    /**
     * 新規ジョブを登録する（Outboxパターン）。
     * 同一 idempotency_key で同一 payload_hash の再送は既存ジョブを返却。
     * 同一 idempotency_key で異なる payload_hash は拒否 (BusinessException)。
     */
    IntegrationJob createJob(Long connectionId, String jobType, String targetType, Long targetId,
                             String idempotencyKey, String payloadHash);

    /**
     * 新規ジョブをテナント・法人・組織スコープ付きで登録する。
     */
    IntegrationJob createJob(Long connectionId, String jobType, String targetType, Long targetId,
                             String idempotencyKey, String payloadHash,
                             String payloadSnapshot, String tenantId, Long legalEntityId, Long organizationId);


    /**
     * ジョブを RUNNING 状態に claim する (CAS更新)。
     * 成功した worker のみ job を取得、二重処理を防止。
     *
     * @return claim 成功時は更新後 Job、競合等で失敗時は null
     */
    IntegrationJob claimJob(Long jobId);

    /**
     * 実行成功を記録する。
     */
    void markSucceeded(Long jobId, String externalId, String providerRequestId, String safeDetail);

    /**
     * 一時障害（429/5xx/timeout）による再試行待ちを記録する。
     */
    void markRetryable(Long jobId, String errorCode, String errorMessageSafe, int backoffSeconds);

    /**
     * 恒久障害（400/422 validation, 403 plan制限, 最大試行超過）を記録する。
     */
    void markFailed(Long jobId, String errorCode, String errorMessageSafe);

    /**
     * ジョブを取り消す。
     */
    void cancelJob(Long jobId, String reason);

    /**
     * 失敗/再試行可能ジョブを手動で再実行待ち（PENDING）に戻す。
     */
    void resetForManualRetry(Long jobId);

    /**
     * ジョブの状態遷移イベント一覧を取得する。
     */
    List<IntegrationJobEvent> listEvents(Long jobId);

    /**
     * 対象エンティティに紐づく最新ジョブを取得する。
     */
    IntegrationJob getLatestJob(String targetType, Long targetId, String jobType);

    /**
     * idempotencyKey でジョブを取得する。
     */
    IntegrationJob getByIdempotencyKey(String idempotencyKey);

    /**
     * due な PENDING/RETRYABLE job を最大 limit 件取得する (ワーカー用)。
     * next_retry_at が null または <= now のものを対象とする。
     */
    List<IntegrationJob> listDueJobs(int limit);

    /**
     * lease timeout を超えた RUNNING ジョブを RETRYABLE に戻す。
     *
     * @param leaseMinutes この分数以上 RUNNING のままのジョブを対象とする
     * @return 回収件数
     */
    int recoverStaleRunningJobs(int leaseMinutes);
}
