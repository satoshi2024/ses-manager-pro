-- V185: 定期管理レポートschedulerのclaim所有者をlease行へ束縛する（NF-10 scheduler claim fence）。
-- lease占有の有無は従来どおりprocessing_claimed_atで判定し、本列はその間のowner token（UUID文字列）を保持する。
-- tokenは大文字小文字を区別して完全一致比較するため、表既定のutf8mb4_unicode_ciではなくascii_binとする。
-- 列位置指定は行わない（末尾追加とし、MySQL 8.0.29未満でもINSTANT追加の対象外にならないようにする）。
-- 既存lease行（V185以前の旧版claim）はtoken NULLのまま残し、ownerを捏造しない。期限切れ後に新版がCASで接管する。
-- rolling deploymentは許可しない。旧scheduler停止・in-flight処理のdrain完了後に新versionのみ起動する
-- （scheduler-claim-fence-plan.md §10、SRE/DBA承認待ち）。
ALTER TABLE m_report_schedule
    ADD COLUMN processing_claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL
        COMMENT 'scheduler内部claim所有者token';
