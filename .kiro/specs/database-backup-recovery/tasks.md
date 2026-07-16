# 実装タスク

- [ ] **Objective:** バックアップ運用基盤を追加。**実装:** `ops/backup` の Docker compose、全備・binlog・監視スクリプトを配置。**テスト:** shellcheck と `--help/--dry-run`。**Demo:** 手動実行でS3 snapshotを確認。
- [ ] **Objective:** 安全な復元を提供。**実装:** 二段階 `restore.sh`、整合性検証、uploads原子切替。**テスト:** 隔離MySQLでPITR。**Demo:** 指定時刻へ復元し件数・ハッシュ一致を確認。
- [ ] **Objective:** 定期演習を可能にする。**実装:** `restore-drill.sh` と日本語運用手順。**テスト:** 壊れたsnapshot/binlogで失敗すること。**Demo:** 四半期演習記録を残す。
