package com.ses.service.repair;

import java.util.Objects;

/**
 * 修復キュー行の不変スナップショット (t_nf09_scope_repair_queue)
 */
public record ScopeRepairQueueSnapshot(
    Long id,
    String entityType,
    Long entityId,
    String status,
    String resolutionNote,
    Long version
) {
    public ScopeRepairQueueSnapshot {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(status, "status must not be null");
    }
}
