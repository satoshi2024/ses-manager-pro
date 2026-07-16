# 設計

## 構成
`ops/backup/docker-compose.yml` の backup コンテナ（restic、MySQL client）と binlog コンテナを運用環境の Docker network に接続する。S3互換ストレージは `BACKUP_REPOSITORY`、暗号鍵は `RESTIC_PASSWORD_FILE` で指定する。

## データフロー
1. `backup-full.sh` が一時ディレクトリへ dump と uploads manifest を作成し、restic backup 後に `forget --keep-daily 30 --prune` を実行する。
2. `archive-binlog.sh` がリモート MySQL の binlog を raw 形式で保存し、15分ごとに restic snapshot を作成する。
3. `restore.sh --target ISO8601 --apply` が最新全備を復元し、必要なbinlogを順番に適用する。適用前に `--dry-run` 検証を必須とする。

## 安全策
- すべてのスクリプトは `set -Eeuo pipefail`、一時ファイルは `trap` で削除する。
- DBパスワードは `MYSQL_PWD` ではなく `MYSQL_PWD_FILE` からプロセス直前に読み込む。
- restore は `APP_STOP_COMMAND` が成功し、`--apply` が指定されない限り書き込みを行わない。復元先DB名を空文字・本番以外に限定しないが、明示入力を要求する。
- manifest と SHA-256 を復元後に照合し、不一致なら非0終了する。

