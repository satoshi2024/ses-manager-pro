package com.ses.service.integrationhub.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.entity.integrationhub.ApiDelivery;
import com.ses.entity.integrationhub.ApiDeliveryReplayAudit;
import com.ses.entity.integrationhub.ApiIdempotencyRecord;
import com.ses.entity.integrationhub.ApiPurgeCheckpoint;
import com.ses.entity.integrationhub.ApiRetentionHold;
import com.ses.entity.integrationhub.InboundEvent;
import com.ses.entity.integrationhub.InboundEventReplayRequest;
import com.ses.mapper.ApiDeliveryMapper;
import com.ses.mapper.ApiDeliveryReplayAuditMapper;
import com.ses.mapper.ApiIdempotencyRecordMapper;
import com.ses.mapper.ApiPurgeCheckpointMapper;
import com.ses.mapper.ApiRetentionHoldMapper;
import com.ses.mapper.InboundEventMapper;
import com.ses.mapper.InboundEventReplayRequestMapper;
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
public class ApiRetentionPurgeServiceImpl implements ApiRetentionPurgeService {
    private final ApiRetentionHoldMapper holdMapper;
    private final ApiIdempotencyRecordMapper idempotencyMapper;
    private final ApiDeliveryMapper deliveryMapper;
    private final ApiDeliveryReplayAuditMapper replayAuditMapper;
    private final InboundEventMapper inboundMapper;
    private final InboundEventReplayRequestMapper inboundReplayMapper;
    private final ApiPurgeCheckpointMapper checkpointMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean acquireHold(String recordKind, Long recordId, String reasonCode, LocalDateTime now) {
        validateKind(recordKind);
        if (recordId == null || reasonCode == null || reasonCode.isBlank() || reasonCode.length() > 64 || now == null) {
            throw new IllegalArgumentException("invalid retention hold");
        }
        String retentionClass = targetRetentionClass(recordKind, recordId);
        if (retentionClass != null) {
            ensureCheckpoint(recordKind, retentionClass, now);
        }
        if (!lockTarget(recordKind, recordId)) {
            return false;
        }
        ApiRetentionHold hold = holdMapper.selectForUpdate(recordKind, recordId);
        if (hold == null) {
            try {
                holdMapper.insert(ApiRetentionHold.builder()
                        .recordKind(recordKind)
                        .recordId(recordId)
                        .status("ACTIVE")
                        .holdGeneration(1)
                        .reasonCode(reasonCode)
                        .version(0)
                        .createdAt(now)
                        .updatedAt(now)
                        .build());
                resetCursor(recordKind, retentionClass, now);
                return true;
            } catch (DuplicateKeyException e) {
                // 同時holdは再読込して既存ACTIVEへ収束する。
                hold = holdMapper.selectForUpdate(recordKind, recordId);
            }
        }
        if (hold == null || "ACTIVE".equals(hold.getStatus())) {
            resetCursor(recordKind, retentionClass, now);
            return hold != null;
        }
        boolean reacquired = holdMapper.reacquire(hold.getId(), hold.getVersion(), reasonCode, now) == 1;
        if (reacquired) {
            resetCursor(recordKind, retentionClass, now);
        }
        return reacquired;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean releaseHold(String recordKind, Long recordId, LocalDateTime now) {
        validateKind(recordKind);
        if (recordId == null || now == null) {
            return false;
        }
        String retentionClass = targetRetentionClass(recordKind, recordId);
        if (retentionClass != null) {
            ensureCheckpoint(recordKind, retentionClass, now);
        }
        if (!lockTarget(recordKind, recordId)) {
            return false;
        }
        ApiRetentionHold hold = holdMapper.selectForUpdate(recordKind, recordId);
        boolean released = hold != null && "ACTIVE".equals(hold.getStatus())
                && holdMapper.release(hold.getId(), hold.getVersion(), now) == 1;
        if (released) {
            resetCursor(recordKind, retentionClass, now);
        }
        return released;
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
        boolean fullBatch = false;
        ApiPurgeCheckpoint checkpoint = ensureCheckpoint(recordKind, retentionClass, now);
        LocalDateTime checkpointExpiresAt = checkpoint.getLastExpiresAt();
        Long checkpointRecordId = checkpoint.getLastRecordId();
        int checkpointVersion = checkpoint.getVersion();
        if (checkpointMapper.startBatch(checkpoint.getId(), checkpointVersion, now) != 1) {
            throw new IllegalStateException("purge checkpoint start CAS failed");
        }
        checkpointVersion++;
        LocalDateTime lastExpiresAt = null;
        Long lastRecordId = null;
        if ("IDEMPOTENCY".equals(recordKind)) {
            QueryWrapper<ApiIdempotencyRecord> query = new QueryWrapper<ApiIdempotencyRecord>()
                    .select("id", "retention_expires_at").in("status", "SUCCEEDED", "FAILED", "CONFLICT")
                    .eq("retention_class", retentionClass).le("retention_expires_at", now)
                    .notExists("SELECT 1 FROM t_api_retention_hold h WHERE h.record_kind = 'IDEMPOTENCY' "
                            + "AND h.record_id = t_api_idempotency_record.id AND h.status = 'ACTIVE'")
                    .orderByAsc("retention_expires_at", "id");
            applyCheckpoint(query, checkpointExpiresAt, checkpointRecordId);
            List<ApiIdempotencyRecord> candidates = idempotencyMapper.selectList(query.last("LIMIT " + limit));
            fullBatch = candidates.size() == limit;
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
            QueryWrapper<ApiDelivery> query = new QueryWrapper<ApiDelivery>()
                    .select("id", "retention_expires_at").in("status", "SUCCEEDED", "FAILED", "DLQ")
                    .eq("retention_class", retentionClass).le("retention_expires_at", now)
                    .and(wrapper -> wrapper.and(inner -> inner.isNull("lease_token")
                            .isNull("lease_expires_at"))
                            .or(inner -> inner.isNotNull("lease_token")
                                    .isNotNull("lease_expires_at")
                                    .le("lease_expires_at", now)))
                    .notExists("SELECT 1 FROM t_api_retention_hold h WHERE h.record_kind = 'DELIVERY' "
                            + "AND h.record_id = t_api_delivery.id AND h.status = 'ACTIVE'")
                    .orderByAsc("retention_expires_at", "id");
            applyCheckpoint(query, checkpointExpiresAt, checkpointRecordId);
            List<ApiDelivery> candidates = deliveryMapper.selectList(query.last("LIMIT " + limit));
            fullBatch = candidates.size() == limit;
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
            QueryWrapper<InboundEvent> query = new QueryWrapper<InboundEvent>()
                    .select("id", "retention_expires_at").in("status", "PROCESSED", "DUPLICATE", "CONFLICT", "DLQ")
                    .eq("retention_class", retentionClass).le("retention_expires_at", now)
                    .notExists("SELECT 1 FROM t_api_retention_hold h WHERE h.record_kind = 'INBOUND' "
                            + "AND h.record_id = t_inbound_event.id AND h.status = 'ACTIVE'")
                    .orderByAsc("retention_expires_at", "id");
            applyCheckpoint(query, checkpointExpiresAt, checkpointRecordId);
            List<InboundEvent> candidates = inboundMapper.selectList(query.last("LIMIT " + limit));
            fullBatch = candidates.size() == limit;
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
        } else if ("INBOUND_REPLAY".equals(recordKind)) {
            QueryWrapper<InboundEventReplayRequest> query = new QueryWrapper<InboundEventReplayRequest>()
                    .select("id", "retention_expires_at")
                    .eq("retention_class", retentionClass).le("retention_expires_at", now)
                    .in("status", "PROCESSED", "REJECTED", "DLQ")
                    .notExists("SELECT 1 FROM t_api_retention_hold h WHERE h.record_kind = 'INBOUND_REPLAY' "
                            + "AND h.record_id = t_inbound_event_replay.id AND h.status = 'ACTIVE'")
                    .orderByAsc("retention_expires_at", "id");
            applyCheckpoint(query, checkpointExpiresAt, checkpointRecordId);
            List<InboundEventReplayRequest> candidates = inboundReplayMapper.selectList(query.last("LIMIT " + limit));
            fullBatch = candidates.size() == limit;
            for (InboundEventReplayRequest candidate : candidates) {
                inspected++;
                lastExpiresAt = candidate.getRetentionExpiresAt();
                lastRecordId = candidate.getId();
                if (!lockAndDeleteInboundReplay(candidate.getId(), retentionClass, now)) {
                    held++;
                } else {
                    purged++;
                }
            }
        } else if ("AUDIT".equals(recordKind)) {
            QueryWrapper<ApiDeliveryReplayAudit> query = new QueryWrapper<ApiDeliveryReplayAudit>()
                    .select("id", "retention_expires_at")
                    .eq("retention_class", retentionClass).le("retention_expires_at", now)
                    .notExists("SELECT 1 FROM t_api_retention_hold h WHERE h.record_kind = 'AUDIT' "
                            + "AND h.record_id = t_api_delivery_replay_audit.id AND h.status = 'ACTIVE'")
                    .orderByAsc("retention_expires_at", "id");
            applyCheckpoint(query, checkpointExpiresAt, checkpointRecordId);
            List<ApiDeliveryReplayAudit> candidates = replayAuditMapper.selectList(query.last("LIMIT " + limit));
            fullBatch = candidates.size() == limit;
            for (ApiDeliveryReplayAudit candidate : candidates) {
                inspected++;
                lastExpiresAt = candidate.getRetentionExpiresAt();
                lastRecordId = candidate.getId();
                if (!lockAndDeleteAudit(candidate.getId(), retentionClass, now)) {
                    held++;
                } else {
                    purged++;
                }
            }
        } else {
            throw new IllegalStateException("unsupported purge record kind");
        }
        // lease/holdのようにeligibilityが時間経過で変化するrowをkeysetの先へ取り残さない。
        // 現在のeligible集合を走査し切ったbatchではcursorを先頭へ戻し、次回に再評価する。
        completeCheckpoint(checkpoint, checkpointVersion,
                fullBatch ? lastExpiresAt : null, fullBatch ? lastRecordId : null, now);
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
        return idempotencyMapper.deleteExpired(id, row.getVersion(), now) == 1;
    }

    private boolean lockAndDeleteDelivery(Long id, String retentionClass, LocalDateTime now) {
        ApiDelivery row = deliveryMapper.selectForUpdate(id);
        if (row == null || !retentionClass.equals(row.getRetentionClass()) || hasActiveHold("DELIVERY", id)) {
            return false;
        }
        // audit行はdelivery削除後も独立retentionで残す。H2はON DELETE SET NULLが効かない場合があるため明示的に外す。
        replayAuditMapper.clearDeliveryReference(id);
        return deliveryMapper.deleteExpired(id, row.getVersion(), now) == 1;
    }

    private boolean lockAndDeleteInbound(Long id, String retentionClass, LocalDateTime now) {
        InboundEvent row = inboundMapper.selectForUpdate(id);
        if (row == null || !retentionClass.equals(row.getRetentionClass()) || hasActiveHold("INBOUND", id)) {
            return false;
        }
        return inboundMapper.deleteExpired(id, row.getVersion(), now) == 1;
    }

    private boolean lockAndDeleteInboundReplay(Long id, String retentionClass, LocalDateTime now) {
        InboundEventReplayRequest row = inboundReplayMapper.selectForUpdate(id);
        if (row == null || !retentionClass.equals(row.getRetentionClass())
                || hasActiveHold("INBOUND_REPLAY", id)) {
            return false;
        }
        return inboundReplayMapper.deleteExpired(id, row.getVersion(), now) == 1;
    }

    private boolean lockAndDeleteAudit(Long id, String retentionClass, LocalDateTime now) {
        ApiDeliveryReplayAudit row = replayAuditMapper.selectForUpdate(id);
        if (row == null || !retentionClass.equals(row.getRetentionClass()) || hasActiveHold("AUDIT", id)) {
            return false;
        }
        return replayAuditMapper.deleteExpired(id, now) == 1;
    }

    private boolean hasActiveHold(String recordKind, Long recordId) {
        ApiRetentionHold hold = holdMapper.selectForUpdate(recordKind, recordId);
        return hold != null && "ACTIVE".equals(hold.getStatus());
    }

    private ApiPurgeCheckpoint ensureCheckpoint(String recordKind, String retentionClass, LocalDateTime now) {
        // 欠落rowのSELECT FOR UPDATEはMySQLのgap lockを作り、hold/purgeの同時初期化で
        // INSERT deadlockを招く。unique INSERTを先に試み、既存rowは競合後にFOR UPDATEへ収束する。
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
            ApiPurgeCheckpoint checkpoint = checkpointMapper.selectForUpdate(recordKind, retentionClass);
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
            case "INBOUND_REPLAY" -> inboundReplayMapper.selectForUpdate(recordId) != null;
            case "AUDIT" -> replayAuditMapper.selectForUpdate(recordId) != null;
            default -> false;
        };
    }

    private String targetRetentionClass(String recordKind, Long recordId) {
        return switch (recordKind) {
            case "IDEMPOTENCY" -> {
                ApiIdempotencyRecord row = idempotencyMapper.selectById(recordId);
                yield row == null ? null : row.getRetentionClass();
            }
            case "DELIVERY" -> {
                ApiDelivery row = deliveryMapper.selectById(recordId);
                yield row == null ? null : row.getRetentionClass();
            }
            case "INBOUND" -> {
                InboundEvent row = inboundMapper.selectById(recordId);
                yield row == null ? null : row.getRetentionClass();
            }
            case "INBOUND_REPLAY" -> {
                InboundEventReplayRequest row = inboundReplayMapper.selectById(recordId);
                yield row == null ? null : row.getRetentionClass();
            }
            case "AUDIT" -> {
                ApiDeliveryReplayAudit row = replayAuditMapper.selectById(recordId);
                yield row == null ? null : row.getRetentionClass();
            }
            default -> null;
        };
    }

    private void resetCursor(String recordKind, String retentionClass, LocalDateTime now) {
        if (retentionClass == null) {
            return;
        }
        ApiPurgeCheckpoint checkpoint = checkpointMapper.selectForUpdate(recordKind, retentionClass);
        if (checkpoint != null && checkpointMapper.resetCursor(checkpoint.getId(), checkpoint.getVersion(), now) != 1) {
            throw new IllegalStateException("purge checkpoint reset CAS failed");
        }
    }

    private <T> void applyCheckpoint(QueryWrapper<T> query, LocalDateTime lastExpiresAt, Long lastRecordId) {
        if (lastExpiresAt != null && lastRecordId != null) {
            query.and(wrapper -> wrapper.gt("retention_expires_at", lastExpiresAt)
                    .or(nested -> nested.eq("retention_expires_at", lastExpiresAt).gt("id", lastRecordId)));
        }
    }

    private void validateKind(String kind) {
        if (!"IDEMPOTENCY".equals(kind) && !"DELIVERY".equals(kind)
                && !"INBOUND".equals(kind) && !"INBOUND_REPLAY".equals(kind) && !"AUDIT".equals(kind)) {
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
