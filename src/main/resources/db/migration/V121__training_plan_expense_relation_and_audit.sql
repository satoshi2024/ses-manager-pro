-- ===================================================================
-- V121: NF-03 F2-2 learning planの経費正本関連と状態監査
-- ===================================================================

ALTER TABLE t_learning_plan
    ADD COLUMN expense_request_id BIGINT NULL COMMENT '実費の正本t_expense_request.id';

CREATE TABLE IF NOT EXISTS t_learning_plan_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    plan_id BIGINT NOT NULL,
    source_type VARCHAR(40) NOT NULL COMMENT 'PLAN/ENROLLMENT',
    source_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    amount_snapshot DECIMAL(12,0) NULL COMMENT '税込JPY。0円確認eventにも保存',
    actor_user_id BIGINT NULL,
    reason VARCHAR(2000) NULL,
    occurred_at DATETIME NOT NULL,
    idempotency_key VARCHAR(255) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_learning_plan_event_plan (tenant_id, plan_id, occurred_at, id),
    UNIQUE KEY uk_learning_plan_event_idempotency (tenant_id, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='learning plan/enrollment append-only event';
