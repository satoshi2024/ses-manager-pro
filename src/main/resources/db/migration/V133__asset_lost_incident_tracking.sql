-- ===================================================================
-- V133: AS-R3.3 紛失インシデント追跡台帳
-- ===================================================================

CREATE TABLE IF NOT EXISTS t_asset_lost_incident (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL COMMENT '紛失資産ID',
    reported_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'インシデント起票日時',
    reported_by BIGINT COMMENT '紛失報告者ユーザーID',
    incident_details VARCHAR(2000) COMMENT 'インシデント概要（秘密情報非含有）',
    remote_wipe_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED' COMMENT 'NOT_REQUESTED, REQUESTED, EXECUTED, CONFIRMED, FAILED, UNKNOWN',
    remote_wipe_requested_at DATETIME COMMENT 'リモートワイプ要求日時',
    remote_wipe_executed_at DATETIME COMMENT 'リモートワイプ実施日時',
    remote_wipe_confirmed_at DATETIME COMMENT 'リモートワイプ確認日時',
    police_report_number VARCHAR(128) COMMENT '警察届出番号',
    insurance_claim_status VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLIED' COMMENT 'NOT_APPLIED, APPLIED, SETTLED, REJECTED',
    insurance_claimed_at DATETIME COMMENT '保険申請日時',
    version INT NOT NULL DEFAULT 0 COMMENT '対応情報更新の楽観ロック用バージョン',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uq_asset_lost_incident_asset (asset_id),
    INDEX idx_asset_lost_incident_wipe (remote_wipe_status),
    INDEX idx_asset_lost_incident_insurance (insurance_claim_status),
    CONSTRAINT fk_asset_lost_incident_asset
        FOREIGN KEY (asset_id) REFERENCES m_asset(id)
        ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='紛失資産インシデント対応台帳（秘密非保存）';
