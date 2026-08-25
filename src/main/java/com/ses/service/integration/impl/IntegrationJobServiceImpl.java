package com.ses.service.integration.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.common.exception.BusinessException;
import com.ses.entity.IntegrationJob;
import com.ses.entity.IntegrationJobEvent;
import com.ses.mapper.IntegrationJobEventMapper;
import com.ses.mapper.IntegrationJobMapper;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class IntegrationJobServiceImpl extends ServiceImpl<IntegrationJobMapper, IntegrationJob>
        implements IntegrationJobService {

    private final IntegrationJobEventMapper jobEventMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationJob createJob(Long connectionId, String jobType, String targetType, Long targetId,
                                    String idempotencyKey, String payloadHash) {
        return createJob(connectionId, jobType, targetType, targetId, idempotencyKey, payloadHash, null, null, null, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationJob createJob(Long connectionId, String jobType, String targetType, Long targetId,
                                    String idempotencyKey, String payloadHash,
                                    String payloadSnapshot, String tenantId, Long legalEntityId, Long organizationId) {
        IntegrationJob existing = getByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            if (payloadHash != null && existing.getPayloadHash() != null && !payloadHash.equals(existing.getPayloadHash())) {
                throw new BusinessException(409, "同一の冪等性キーに対して異なるペイロードでの再送は拒否されます (key=" + idempotencyKey + ")");
            }
            log.info("Job already exists for idempotencyKey={}, returning existing (id={})",
                    idempotencyKey, existing.getId());
            return existing;
        }

        IntegrationJob job = IntegrationJob.builder()
                .connectionId(connectionId)
                .jobType(jobType)
                .targetType(targetType)
                .targetId(targetId)
                .tenantId((tenantId != null && !tenantId.isBlank()) ? tenantId : "default")
                .legalEntityId(legalEntityId)
                .organizationId(organizationId)
                .payloadSnapshot(payloadSnapshot)
                .idempotencyKey(idempotencyKey)
                .payloadHash(payloadHash)
                .status("PENDING")
                .attemptCount(0)
                .maxAttempts(5)
                .version(0)
                .build();

        try {
            save(job);
            recordEvent(job.getId(), null, "PENDING", "ジョブ登録完了 (idempotencyKey=" + idempotencyKey + ")");
            return job;
        } catch (DuplicateKeyException e) {
            log.warn("Concurrent duplicate job creation for idempotencyKey={}, returning existing", idempotencyKey);
            IntegrationJob created = getByIdempotencyKey(idempotencyKey);
            if (created != null) {
                if (payloadHash != null && created.getPayloadHash() != null && !payloadHash.equals(created.getPayloadHash())) {
                    throw new BusinessException(409, "同一の冪等性キーに対して異なるペイロードでの再送は拒否されます (key=" + idempotencyKey + ")");
                }
                return created;
            }
            throw new BusinessException(409, "同一の処理が既に登録されています");
        }
    }

    @Override
    public IntegrationJob getByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return getOne(new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getIdempotencyKey, idempotencyKey));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IntegrationJob claimJob(Long jobId) {
        IntegrationJob job = getById(jobId);
        if (job == null) {
            return null;
        }
        if (!"PENDING".equals(job.getStatus()) && !"RETRYABLE".equals(job.getStatus())) {
            return null;
        }
        if (job.getNextRetryAt() != null && job.getNextRetryAt().isAfter(LocalDateTime.now())) {
            return null;
        }

        String fromStatus = job.getStatus();
        LocalDateTime now = LocalDateTime.now();
        int updated = baseMapper.claimJobCas(jobId, job.getVersion(), now);
        if (updated == 1) {
            recordEvent(jobId, fromStatus, "RUNNING", "ワーカーによるジョブ実行開始 (試行回数: " + (job.getAttemptCount() + 1) + ")");
            return getById(jobId);
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markSucceeded(Long jobId, String externalId, String providerRequestId, String safeDetail) {
        IntegrationJob job = getById(jobId);
        if (job == null) return;
        if (!"RUNNING".equals(job.getStatus())) {
            log.warn("markSucceeded: jobId={} is in state {} (expected RUNNING), skipping", jobId, job.getStatus());
            return;
        }

        String fromStatus = job.getStatus();
        int updated = baseMapper.transitionToSucceeded(
                jobId, job.getVersion(), externalId, providerRequestId, LocalDateTime.now());
        if (updated == 0) {
            log.warn("markSucceeded CAS 失敗: jobId={}, 競合更新が発生しました", jobId);
            return;
        }

        recordEvent(jobId, fromStatus, "SUCCEEDED", safeDetail != null ? safeDetail : "外部連携成功");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markRetryable(Long jobId, String errorCode, String errorMessageSafe, int backoffSeconds) {
        IntegrationJob job = getById(jobId);
        if (job == null) return;
        if (!"RUNNING".equals(job.getStatus())) {
            log.warn("markRetryable: jobId={} is in state {} (expected RUNNING), skipping", jobId, job.getStatus());
            return;
        }

        if (job.getAttemptCount() >= job.getMaxAttempts()) {
            markFailed(jobId, errorCode, "最大試行回数(" + job.getMaxAttempts() + ")を超過しました: " + errorMessageSafe);
            return;
        }

        String fromStatus = job.getStatus();
        LocalDateTime nextRetry = LocalDateTime.now().plusSeconds(Math.max(1, backoffSeconds));
        int updated = baseMapper.updateStatusToRetryable(
                jobId, job.getVersion(), errorCode, errorMessageSafe, nextRetry);
        if (updated == 0) {
            log.warn("markRetryable CAS 失敗: jobId={}", jobId);
            return;
        }

        recordEvent(jobId, fromStatus, "RETRYABLE",
                String.format("[%s] %s (次回試行: %d 秒後)", errorCode, errorMessageSafe, backoffSeconds));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void markFailed(Long jobId, String errorCode, String safeMessage) {
        IntegrationJob job = getById(jobId);
        if (job == null) return;
        if ("SUCCEEDED".equals(job.getStatus()) || "FAILED".equals(job.getStatus()) || "CANCELLED".equals(job.getStatus())) {
            log.warn("markFailed: jobId={} is already terminal (status={}), skipping", jobId, job.getStatus());
            return;
        }

        String fromStatus = job.getStatus();
        int updated = baseMapper.transitionToFailed(jobId, job.getVersion(), fromStatus, errorCode, safeMessage);
        if (updated == 0) {
            log.warn("markFailed CAS 失敗: jobId={}", jobId);
            return;
        }

        recordEvent(jobId, fromStatus, "FAILED", String.format("[%s] %s", errorCode, safeMessage));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelJob(Long jobId, String reason) {
        IntegrationJob job = getById(jobId);
        if (job == null) {
            throw new BusinessException(404, "ジョブが見つかりません (id=" + jobId + ")");
        }
        // 1. SALES_INVOICE_CANCEL は全状態で取消不可 (400)
        if ("SALES_INVOICE_CANCEL".equals(job.getJobType())) {
            throw new BusinessException(400, "CANNOT_CANCEL_CANCELLATION_JOB: 取消連携ジョブ(SALES_INVOICE_CANCEL)はキャンセルできません");
        }
        // 2. 終端状態 (SUCCEEDED, CANCELLED) は取消不可 (400)
        if ("SUCCEEDED".equals(job.getStatus()) || "CANCELLED".equals(job.getStatus())) {
            throw new BusinessException(400, "CANNOT_CANCEL_TERMINAL_JOB: 完了済みのジョブはキャンセルできません (status=" + job.getStatus() + ")");
        }
        // 3. RUNNING 状態の取消マトリクス:
        //    - BP仕入/支払/経費 (PURCHASE_PAYMENT_SYNC, EXPENSE_PAYMENT_SYNC, BP_PURCHASE_SYNC) は RUNNING 取消拒否 (400)
        //    - 売上/入金 (SALES_INVOICE_SYNC, INVOICE_PAYMENT_SYNC) は RUNNING 取消許可 -> CANCELLED 遷移
        if ("RUNNING".equals(job.getStatus())) {
            if ("PURCHASE_PAYMENT_SYNC".equals(job.getJobType())
                    || "EXPENSE_PAYMENT_SYNC".equals(job.getJobType())
                    || "BP_PURCHASE_SYNC".equals(job.getJobType())
                    || "EXPENSE_DEAL_SYNC".equals(job.getJobType())) {
                throw new BusinessException(400, "CANNOT_CANCEL_RUNNING_JOB: 仕入・経費ジョブの実行中キャンセルは許可されていません");
            }
        }

        String fromStatus = job.getStatus();
        int updated = baseMapper.transitionToCancelled(jobId, job.getVersion(), reason);
        if (updated == 0) {
            throw new BusinessException(409, "ジョブの状態が変更されました。再試行してください。");
        }

        recordEvent(jobId, fromStatus, "CANCELLED", "キャンセル: " + reason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetForManualRetry(Long jobId) {
        IntegrationJob job = getById(jobId);
        if (job == null) {
            throw new BusinessException(404, "ジョブが見つかりません (id=" + jobId + ")");
        }
        if (!"RETRYABLE".equals(job.getStatus()) && !"FAILED".equals(job.getStatus())) {
            throw new BusinessException(400, "再試行可能なステータス(RETRYABLE / FAILED)ではありません (現在: " + job.getStatus() + ")");
        }

        String fromStatus = job.getStatus();
        int updated = baseMapper.transitionToManualRetry(jobId, job.getVersion());
        if (updated == 0) {
            throw new BusinessException(409, "ジョブの状態が変更されました。再試行してください。");
        }

        recordEvent(jobId, fromStatus, "PENDING", "手動リトライにより再実行待ちへ変更");
    }

    @Override
    public List<IntegrationJobEvent> listEvents(Long jobId) {
        return jobEventMapper.selectList(new LambdaQueryWrapper<IntegrationJobEvent>()
                .eq(IntegrationJobEvent::getJobId, jobId)
                .orderByAsc(IntegrationJobEvent::getId));
    }

    @Override
    public IntegrationJob getLatestJob(String targetType, Long targetId, String jobType) {
        LambdaQueryWrapper<IntegrationJob> wrapper = new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getTargetType, targetType)
                .eq(IntegrationJob::getTargetId, targetId);
        if (jobType != null && !jobType.isBlank()) {
            wrapper.eq(IntegrationJob::getJobType, jobType);
        }
        return getOne(wrapper.orderByDesc(IntegrationJob::getId).last("LIMIT 1"));
    }

    @Override
    public List<IntegrationJob> listDueJobs(int limit) {
        LocalDateTime now = LocalDateTime.now();
        return baseMapper.selectList(new LambdaQueryWrapper<IntegrationJob>()
                .in(IntegrationJob::getStatus, "PENDING", "RETRYABLE")
                .and(w -> w.isNull(IntegrationJob::getNextRetryAt)
                        .or().le(IntegrationJob::getNextRetryAt, now))
                .eq(IntegrationJob::getDeletedFlag, 0)
                .orderByAsc(IntegrationJob::getId)
                .last("LIMIT " + Math.min(limit, 100)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int recoverStaleRunningJobs(int leaseMinutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(Math.max(1, leaseMinutes));
        return baseMapper.recoverStaleRunning(threshold);
    }

    @Override
    public List<IntegrationJob> listStaleRunningJobs(int leaseMinutes) {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(Math.max(1, leaseMinutes));
        return baseMapper.selectList(new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getStatus, "RUNNING")
                .lt(IntegrationJob::getUpdatedAt, threshold)
                .eq(IntegrationJob::getDeletedFlag, 0)
                .orderByAsc(IntegrationJob::getId));
    }

    @Override
    public void recordJobEvent(Long jobId, String eventType, String detail, String reasonCode) {
        String msg = (reasonCode != null && !reasonCode.isBlank()) ? "[" + reasonCode + "] " + detail : detail;
        recordEvent(jobId, null, eventType, msg);
    }

    private void recordEvent(Long jobId, String fromStatus, String toStatus, String safeDetail) {
        IntegrationJobEvent event = IntegrationJobEvent.builder()
                .jobId(jobId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .occurredAt(LocalDateTime.now())
                .safeDetail(safeDetail)
                .build();
        jobEventMapper.insert(event);
    }
}
