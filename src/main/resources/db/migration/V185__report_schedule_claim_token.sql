-- V185: 定期管理レポートschedulerのclaim所有者をlease rowへ束縛する。
-- rolling deploymentは許可しない。旧scheduler停止・drain後に新versionのみ起動する。
ALTER TABLE m_report_schedule
    ADD COLUMN processing_claim_token VARCHAR(36) NULL COMMENT 'scheduler内部claim所有者token' AFTER processing_claimed_at;
