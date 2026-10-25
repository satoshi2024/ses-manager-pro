-- V182演練検証専用: 確定障害注入マイグレーションスクリプト
-- 本番マイグレーション(V182)は一切改変せず、隔離テストロケーションから中途適用して確定的マイグレーション障害を再現する。
ALTER TABLE t_digital_invoice
    ADD CONSTRAINT chk_v182_fault_sim
        CHECK (direction IN ('INJECTED_FAULT_NEVER_SATISFIED'));

-- CHECK制約違反によりFlywayを確定的に中途失敗(success=0)させる
INSERT INTO t_digital_invoice (direction, profile, specification_version, message_id, status)
VALUES ('SEND', 'Standard', '1.0', 'msg-fault-trigger-midway', 'QUEUED');
