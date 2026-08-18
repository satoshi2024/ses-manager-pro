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
    @Transactional
    public IntegrationJob createJob(Long connectionId, String jobType, String targetType, Long targetId,
                                    String idempotencyKey, String payloadHash) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(400, "idempotencyKey は必須です");
        }
        if (payloadHash == null || payloadHash.isBlank()) {
            throw new BusinessException(400, "payloadHash は必須です");
        }

        // 既存ジョブの冪等性チェック
        IntegrationJob existing = getOne(new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getIdempotencyKey, idempotencyKey));
        if (existing != null) {
            if (!payloadHash.equals(existing.getPayloadHash())) {
                log.warn("Payload hash mismatch for idempotencyKey={}, existing={}, incoming={}",
                        idempotencyKey, existing.getPayloadHash(), payloadHash);
                throw new BusinessException(400, "同一の冪等性キーに対して異なるペイロードでの再送は拒否されます");
            }
            return existing;
        }

        IntegrationJob job = IntegrationJob.builder()
                .connectionId(connectionId)
                .jobType(jobType)
                .targetType(targetType)
                .targetId(targetId)
                .idempotencyKey(idempotencyKey)
                .payloadHash(payloadHash)
                .status("PENDING")
                .attemptCount(0)
                .maxAttempts(5)
                .version(0)
                .build();
        save(job);

        recordEvent(job.getId(), null, "PENDING", "ジョブ登録完了");
        return job;
    }

    @Override
    public IntegrationJob claimJob(Long jobId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = baseMapper.claimJob(jobId, now);
        if (updated > 0) {
            IntegrationJob job = getById(jobId);
            recordEvent(jobId, "PENDING", "RUNNING", "Worker により Claim されました (試行回数: " + job.getAttemptCount() + ")");
            return job;
        }
        return null;
    }

    @Override
    @Transactional
    public void markSucceeded(Long jobId, String externalId, String providerRequestId, String safeDetail) {
        IntegrationJob job = getById(jobId);
        if (job == null) return;

        String fromStatus = job.getStatus();
        job.setStatus("SUCCEEDED");
        job.setExternalId(externalId);
        job.setProviderRequestId(providerRequestId);
        job.setSentAt(LocalDateTime.now());
        job.setErrorCode(null);
        job.setErrorMessageSafe(null);
        updateById(job);

        recordEvent(jobId, fromStatus, "SUCCEEDED", safeDetail != null ? safeDetail : "外部連携成功");
    }

    @Override
    @Transactional
    public void markRetryable(Long jobId, String errorCode, String errorMessageSafe, int backoffSeconds) {
        IntegrationJob job = getById(jobId);
        if (job == null) return;

        if (job.getAttemptCount() >= job.getMaxAttempts()) {
            markFailed(jobId, errorCode, "最大試行回数(" + job.getMaxAttempts() + ")を超過しました: " + errorMessageSafe);
            return;
        }

        String fromStatus = job.getStatus();
        job.setStatus("RETRYABLE");
        job.setErrorCode(errorCode);
        job.setErrorMessageSafe(errorMessageSafe);
        job.setNextRetryAt(LocalDateTime.now().plusSeconds(Math.max(1, backoffSeconds)));
        updateById(job);

        recordEvent(jobId, fromStatus, "RETRYABLE",
                String.format("[%s] %s (次回試行: %d 秒後)", errorCode, errorMessageSafe, backoffSeconds));
    }

    @Override
    @Transactional
    public void markFailed(Long jobId, String errorCode, String errorMessageSafe) {
        IntegrationJob job = getById(jobId);
        if (job == null) return;

        String fromStatus = job.getStatus();
        job.setStatus("FAILED");
        job.setErrorCode(errorCode);
        job.setErrorMessageSafe(errorMessageSafe);
        updateById(job);

        recordEvent(jobId, fromStatus, "FAILED", String.format("[%s] %s", errorCode, errorMessageSafe));
    }

    @Override
    @Transactional
    public void cancelJob(Long jobId, String reason) {
        IntegrationJob job = getById(jobId);
        if (job == null) return;

        String fromStatus = job.getStatus();
        job.setStatus("CANCELLED");
        job.setErrorMessageSafe(reason);
        updateById(job);

        recordEvent(jobId, fromStatus, "CANCELLED", "キャンセル: " + reason);
    }

    @Override
    @Transactional
    public void resetForManualRetry(Long jobId) {
        IntegrationJob job = getById(jobId);
        if (job == null) {
            throw new BusinessException(404, "ジョブが見つかりません (id=" + jobId + ")");
        }
        if ("SUCCEEDED".equals(job.getStatus())) {
            throw new BusinessException(400, "成功済みのジョブは再試行できません");
        }

        String fromStatus = job.getStatus();
        job.setStatus("PENDING");
        job.setNextRetryAt(null);
        job.setErrorCode(null);
        job.setErrorMessageSafe(null);
        updateById(job);

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

    private void recordEvent(Long jobId, String fromStatus, String toStatus, String safeDetail) {
        IntegrationJobEvent event = IntegrationJobEvent.builder()
                .jobId(jobId)
                .fromStatus(fromStatus)
                .toStatus(toStatus)
                .safeDetail(safeDetail)
                .occurredAt(LocalDateTime.now())
                .build();
        jobEventMapper.insert(event);
    }
}
