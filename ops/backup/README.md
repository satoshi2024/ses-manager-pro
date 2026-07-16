# バックアップ運用手順

`.env.backup` に `BACKUP_REPOSITORY`、`MYSQL_HOST`、`MYSQL_DATABASE`、`MYSQL_USER`、`UPLOADS_DIR` を設定し、`secrets/` に restic パスワードとDBパスワードを配置する（リポジトリへコミットしない）。cron等で毎日02:00に `backup-full.sh`、`archive-binlog.sh` を常駐させ、15分ごとに `snapshot-binlog.sh` を実行する。`check-backup.sh` は監視から実行する。

復元は必ずアプリを停止し、`restore.sh --target ...` の検証結果を確認後、保守承認を得て `CONFIRM_RESTORE=YES ... --apply` を実行する。復元後は Flyway履歴、主要テーブル件数、uploadsハッシュ、ログインと主要画面を確認してから再開する。四半期ごとに `restore-drill.sh` を隔離環境で実施し、RPO15分・RTO4時間を記録する。
