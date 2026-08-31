-- ===================================================================
-- V131: NF-05 A1短期read snapshot
--
-- cursorのページ間でmembershipとallow-list公開値を固定するためのmaterialized snapshot。
-- payload_jsonはexternal DTOのallow-list JSONだけで、internal entity/request/raw bodyは保存しない。
-- cursor TTL（現行300秒）を超えて保持しない。purgeはread開始時の期限付きcleanupと運用purgeで行う。
-- ===================================================================

CREATE TABLE IF NOT EXISTS t_api_read_snapshot (
    snapshot_id    CHAR(36) NOT NULL COMMENT '暗号化cursorからのみ参照するsnapshot識別子',
    client_id      VARCHAR(100) NOT NULL,
    tenant_id      VARCHAR(64) NOT NULL,
    legal_entity_id BIGINT NOT NULL,
    route_template VARCHAR(255) NOT NULL,
    scope_digest   CHAR(64) NOT NULL,
    as_of          DATETIME(6) NOT NULL,
    expires_at     DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (snapshot_id),
    INDEX idx_api_read_snapshot_expiry (expires_at, snapshot_id),
    CONSTRAINT chk_api_read_snapshot_expiry CHECK (expires_at > as_of)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 A1 short-lived read snapshot';

CREATE TABLE IF NOT EXISTS t_api_read_snapshot_item (
    snapshot_id  CHAR(36) NOT NULL,
    resource_id  BIGINT NOT NULL,
    payload_json TEXT NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (snapshot_id, resource_id),
    CONSTRAINT fk_api_read_snapshot_item_snapshot
        FOREIGN KEY (snapshot_id) REFERENCES t_api_read_snapshot(snapshot_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 A1 safe DTO snapshot item';

-- ROLLBACK EVIDENCE (手動運用のみ。適用済みmigrationの編集・再実行は禁止):
-- 1) cursor利用を停止し、期限切れでないsnapshotの保全要否を確認する。
-- 2) t_api_read_snapshot_itemを先に確認し、backup/restoreとpurge証跡を取得する。
-- 3) t_api_read_snapshot_item、t_api_read_snapshotの順に削除し、Flyway schema historyを更新する。
