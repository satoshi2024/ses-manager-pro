package com.ses.service.servicedesk;

import java.util.Objects;

/**
 * 顧客ヘルススナップショットの実行主体を明示するコンテキスト。
 * SYSTEM は許可された scheduler source に限定する。
 */
public record SnapshotExecutionContext(String actorType, Long actorId, String actorName, String source) {

    public static final String ADMIN_HTTP = "CUSTOMER_HEALTH_ADMIN_HTTP";
    public static final String SYSTEM_SCHEDULER = "CUSTOMER_HEALTH_SCHEDULER";

    public SnapshotExecutionContext {
        if (actorType == null || actorType.isBlank()) {
            throw new IllegalArgumentException("actorType is required");
        }
        if (actorName == null || actorName.isBlank()) {
            throw new IllegalArgumentException("actorName is required");
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source is required");
        }
    }

    public static SnapshotExecutionContext systemScheduler() {
        return new SnapshotExecutionContext("SYSTEM", null, "SYSTEM", SYSTEM_SCHEDULER);
    }

    public static SnapshotExecutionContext admin(String actorName, Long actorId) {
        return new SnapshotExecutionContext("INTERNAL_USER", actorId,
                Objects.requireNonNullElse(actorName, "管理者"), ADMIN_HTTP);
    }
}
