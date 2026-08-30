package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.ApiIdempotencyRecord;
import com.ses.entity.integrationhub.ApiPurgeCheckpoint;
import com.ses.entity.integrationhub.ApiRetentionHold;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.ApiIdempotencyRecordMapper;
import com.ses.mapper.ApiPurgeCheckpointMapper;
import com.ses.mapper.ApiRetentionHoldMapper;
import com.ses.mapper.InboundEventMapper;
import com.ses.service.integrationhub.ApiRetentionPurgeService;
import com.ses.service.integrationhub.IntegrationHubStates;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** NF-05 legal hold and retention purge implementation。 */
@Service
@RequiredArgsConstructor
public class ApiRetentionPurgeServiceImpl extends ServiceImpl<ApiRetentionHoldMapper, ApiRetentionHold>
        implements ApiRetentionPurgeService {
    private final ApiIdempotencyRecordMapper idempotencyMapper;
    private final ApiDeliveryMapper deliveryMapper;
    private final InboundEventMapper inboundMapper;
    private final ApiPurgeCheckpointMapper checkpointMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean acquireHold(String recordKind, Long recordId, String reasonCode, LocalDateTime now) {
        validateKind(recordKind);
        if (recordId == null || reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 64 || now == null) {
            throw new IllegalArgumentException("invalid retention hold");
        }
        if (!lockTarget(recordKind, recordId)) {
            return false;
        }
        ApiRetentionHold hold = baseMapper.selectForUpdate(recordKind, recordId);
        if (hold == null) {
            try {
                baseMapper.insert(ApiRetentionHold.builder()
                        .recordKind(recordKind)
                        .recordId(recordId)
                        .status("ACTIVE")
                        .holdGeneration(1)
                        .reasonCode(reasonCode)
                        .version(0)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                return true;
            } catch (DuplicateKeyException e) {
                // 同時holdは再読込して既存ACTIVEへ収束する。
                hold = baseMapper.selectForUpdate(recordKind, recordId);
            }
        }
        if (hold == null || "ACTIVE".equals(hold.getStatus())) {
            return hold != null;
        }
        return baseMapper.reacquire(hold.getId(), hold.getVersion(), reasonCode, now) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseHold(String recordKind, Long recordId, LocalDateTime now) {
        validateKind(recordKind);
        if (recordId == null || now == null || !lockTarget(recordKind, recordId)) {
            return false;
        }
        ApiRetentionHold hold = baseMapper.selectForUpdate(recordKind, recordId);
        return hold != null && "ACTIVE".equals(hold.getStatus())
                && baseMapper.release(hold.getId(), hold.getVersion(), now) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PurgeReport purgeExpired(String recordKind, String retentionClass, LocalDateTime now, int maxRows) {
        validateKind(recordKind);
        validateRetentionClass(retentionClass);
        if (now == null || maxRows <= 0) {
            throw new IllegalArgumentException("invalid purge request");
        }
        int limit = Math.min(maxRows, 1000);
        int inspected = 0;
        int purged = 0;
        int held = 0;
        ApiPurgeCheckpoint checkpoint = ensureCheckpoint(recordKind, retentionClass, now);
        int checkpointVersion = checkpoint.getVersion();
        if (checkpointMapper.startBatch(checkpoint.getId(), checkpointVersion, now) != 1) {
            throw new IllegalStateException("purge checkpoint start CAS failed");
        }
        checkpointVersion++;
        LocalDateTime lastExpiresAt = null;
        Long lastRecordId = null;
        if ("IDEMPOTENCY".equals(recordKind)) {
            List<ApiIdempotencyRecord> candidates = idempotencyMapper.selectList(new QueryWrapper<ApiIdempotencyRecord>()
                    .select("id", "retention_expires_at").in("status", "SUCCEEDED", "FAILED", "CONFLICT")
                    .eq("retention_class", retentionClass).le("retention_expires_at", now)
                    .orderByAsc("retention_expires_at", "id").last("LIMIT " + limit));
            for (ApiIdempotencyRecord candidate : candidates) {
                inspected++;
                lastExpiresAt = candidate.getRetentionExpiresAt();
                lastRecordId = candidate.getId();
                if (!lockAndDeleteIdempotency(candidate.getId(), retentionClass, now)) {
                    held++;
                } else {
                    purged++;
                }
            }
        } else if ("DELIVERY".equals(recordKind)) {
            List<ApiDelivery> candidates = deliveryMapper.selectList(new QueryWrapper<ApiDelivery>()
                    .select("id", "retention_expires_at").in("status", "SUCCEEDED", "FAILED", "DLQ")
                    .eq("retention_class", retentionClass).le("retention_expires_at", now)
                    .orderByAsc("retention_expires_at", "id").last("LIMIT " + limit));
            for (ApiDelivery candidate : candidates) {
                inspected++;
                lastExpiresAt = candidate.getRetentionExpiresAt();
                lastRecordId = candidate.getId();
                if (!lockAndDeleteDelivery(candidate.getId(), retentionClass, now)) {
                    held++;
                } else {
                    purged++;
                }
            }
        } else if ("INBOUND".equals(recordKind)) {
            List<InboundEvent> candidates = inboundMapper.selectList(new QueryWrapper<InboundEvent>()
                    .select("id", "retention_expires_at").in("status", "PROCESSED", "DUPLICATE", "CONFLICT", "DLQ")
                    .eq("retention_class", retentionClass).le("retention_expires_at", now)
                    .orderByAsc("retention_expires_at", "id").last("LIMIT " + limit));
            for (InboundEvent candidate : candidates) {
                inspected++;
                lastExpiresAt = candidate.getRetentionExpiresAt();
                lastRecordId = candidate.getId();
                if (!lockAndDeleteInbound(candidate.getId(), retentionClass, now)) {
                    held++;
                } else {
                    purged++;
                }
            }
        } else {
            // AUDIT metadata purgeは既存監査契約の専用jobへ接続するまで削除しない。
            completeCheckpoint(checkpoint, checkpointVersion, null, null, now);
            return new PurgeReport(0, 0, 0, checkpoint.getRestoreEpoch());
        }
        completeCheckpoint(checkpoint, checkpointVersion, lastExpiresAt, lastRecordId, now);
        return new PurgeReport(inspected, purged, held, checkpoint.getRestoreEpoch());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public long advanceRestoreEpoch(String recordKind, String retentionClass, LocalDateTime now) {
        validateKind(recordKind);
        validateRetentionClass(retentionClass);
        if (now == null) {
            throw new IllegalArgumentException("now is required");
        }
        ApiPurgeCheckpoint checkpoint = checkpointMapper.selectForUpdate(recordKind, retentionClass);
        if (checkpoint == null) {
            checkpoint = ApiPurgeCheckpoint.builder()
                    .recordKind(recordKind)
                    .retentionClass(retentionClass)
                    .restoreEpoch(1L)
                    .runStatus("READY")
                    .version(0)
                    .updatedAt(now)
                    .build();
            checkpointMapper.insert(checkpoint);
            return 1L;
        }
        long next = (checkpoint.getRestoreEpoch() == null ? 0L : checkpoint.getRestoreEpoch()) + 1L;
        if (checkpointMapper.resetAfterRestore(checkpoint.getId(), checkpoint.getVersion(), next, now) != 1) {
            throw new IllegalStateException("purge checkpoint CAS failed");
        }
        return next;
    }

    private boolean lockAndDeleteIdempotency(Long id, String retentionClass, LocalDateTime now) {
        ApiIdempotencyRecord row = idempotencyMapper.selectByIdForUpdate(id);
        if (row == null || !retentionClass.equals(row.getRetentionClass()) || hasActiveHold("IDEMPOTENCY", id)) {
            return false;
        }
        return idempotencyMapper.deleteExpired(id, now) == 1;
    }

    private boolean lockAndDeleteDelivery(Long id, String retentionClass, LocalDateTime now) {
        ApiDelivery row = deliveryMapper.selectForUpdate(id);
        if (row == null || !retentionClass.equals(row.getRetentionClass()) || hasActiveHold("DELIVERY", id)) {
            return false;
        }
        return deliveryMapper.deleteExpired(id, now) == 1;
    }

    private boolean lockAndDeleteInbound(Long id, String retentionClass, LocalDateTime now) {
        InboundEvent row = inboundMapper.selectForUpdate(id);
        if (row == null || !retentionClass.equals(row.getRetentionClass()) || hasActiveHold("INBOUND", id)) {
            return false;
        }
        return inboundMapper.deleteExpired(id, now) == 1;
    }

    private boolean hasActiveHold(String recordKind, Long recordId) {
        ApiRetentionHold hold = baseMapper.selectForUpdate(recordKind, recordId);
        return hold != null && "ACTIVE".equals(hold.getStatus());
    }

    private ApiPurgeCheckpoint ensureCheckpoint(String recordKind, String retentionClass, LocalDateTime now) {
        ApiPurgeCheckpoint checkpoint = checkpointMapper.selectForUpdate(recordKind, retentionClass);
        if (checkpoint != null) {
            return checkpoint;
        }
        try {
            ApiPurgeCheckpoint newCheckpoint = ApiPurgeCheckpoint.builder()
                    .recordKind(recordKind)
                    .retentionClass(retentionClass)
                    .restoreEpoch(0L)
                    .runStatus("READY")
                    .version(0)
                    .updatedAt(now)
                    .build();
            checkpointMapper.insert(newCheckpoint);
            return newCheckpoint;
        } catch (DuplicateKeyException e) {
            checkpoint = checkpointMapper.selectForUpdate(recordKind, retentionClass);
            if (checkpoint == null) {
                throw e;
            }
            return checkpoint;
        }
    }

    private void completeCheckpoint(ApiPurgeCheckpoint checkpoint, int version,
                                    LocalDateTime lastExpiresAt, Long lastRecordId, LocalDateTime now) {
        if (checkpointMapper.completeBatch(checkpoint.getId(), version, lastExpiresAt, lastRecordId, now) != 1) {
            throw new IllegalStateException("purge checkpoint complete CAS failed");
        }
    }

    private boolean lockTarget(String recordKind, Long recordId) {
        return switch (recordKind) {
            case "IDEMPOTENCY" -> idempotencyMapper.selectByIdForUpdate(recordId) != null;
            case "DELIVERY" -> deliveryMapper.selectForUpdate(recordId) != null;
            case "INBOUND" -> inboundMapper.selectForUpdate(recordId) != null;
            case "AUDIT" -> true;
            default -> false;
        };
    }

    private void validateKind(String kind) {
        if (!"IDEMPOTENCY".equals(kind) && !"DELIVERY".equals(kind)
                && !"INBOUND".equals(kind) && !"AUDIT".equals(kind)) {
            throw new IllegalArgumentException("invalid retention record kind");
        }
    }

    private void validateRetentionClass(String retentionClass) {
        if (!IntegrationHubStates.RETENTION_SUCCEEDED_30D.equals(retentionClass)
                && !IntegrationHubStates.RETENTION_FAILED_90D.equals(retentionClass)
                && !IntegrationHubStates.RETENTION_AUDIT_1Y.equals(retentionClass)) {
            throw new IllegalArgumentException("invalid retention class");
        }
    }
}
