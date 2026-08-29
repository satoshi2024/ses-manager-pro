-- ===================================================================
-- V131: NF-01退社3大blocker例外免除の永続台帳
-- 承認済みLIFECYCLE_EXCEPTIONとの対応を追記し、プロセスメモリ状態に依存しない。
-- ===================================================================
CREATE TABLE IF NOT EXISTS t_asset_offboarding_waiver (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    engineer_id BIGINT NOT NULL COMMENT '対象要員ID',
    approval_request_id BIGINT NOT NULL COMMENT '承認済みLIFECYCLE_EXCEPTION申請ID',
    reason VARCHAR(1000) NOT NULL COMMENT '免除理由',
    approved_by BIGINT COMMENT '承認適用操作者ID',
    approved_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '免除適用日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_asset_offboarding_waiver_request (approval_request_id),
    INDEX idx_asset_offboarding_waiver_engineer (engineer_id, approved_at),
    CONSTRAINT fk_asset_offboarding_waiver_approval
        FOREIGN KEY (approval_request_id) REFERENCES t_approval_request(id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='退社blocker例外免除追記台帳';
