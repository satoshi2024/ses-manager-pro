# backup 監視 runbook（HFP-03-005）

## 監視方法

`check-backup.sh --json` を scheduler から 1 分間隔で実行し、exit code で alert を振り分ける。

| exit | 状態 | 対応 |
|---|---|---|
| 0 | OK | なし |
| 1 | WARN | 目視確認（full 20h / checkpoint 20m / repo check 7d / drill 90d 超過） |
| 2 | CRITICAL | 即時対応（full 26h / checkpoint 30m / binlog lag / gap / heartbeat 停止） |
| 3 | UNKNOWN | source 接続不能。監視自体の障害として対応 |

## 判定基準（watermark ベース）

- 「directory に古い file がある」ことでは FAIL にしない。
- full age: 最新 VALID full の consistency_time からの経過。
- checkpoint age: 最新 VALID checkpoint の consistency_time からの経過。
- binlog lag: 最新 archived closed file と source 現行 file の差（active 1 file は正常）。
- gap: binlog-index の suffix 欠番。
- archiver heartbeat: `archive-state.json.heartbeat` の mtime（30 秒間隔で touch）。
- rpo_available: checkpoint age <= 15 分 かつ gap 0 かつ source 到達可能。

## 対応手順

### CRITICAL: checkpoint 30 分超 / RPO 不達

1. `check-backup.sh --json` で reasons を確認。
2. `docker compose ps` で checkpoint / binlog service の状態確認。
3. checkpoint 停止中なら手動実行: `docker compose run --rm checkpoint`。
4. 復旧後、次の checkpoint が 15 分以内に VALID になることを確認。

### CRITICAL: archiver lag / heartbeat 停止

1. `ls -la $BINLOG_RAW_DIR` で最新 file と source の差を確認。
2. archiver 再起動: `docker compose up -d binlog`（state から再開、不完全 file は取り直し）。
3. 追従確認: `check-backup.sh --json` の binlog_event_lag_seconds が 0 に戻ること。
4. lag が残る場合は snapshot-binlog を実行して index を更新:
   `docker compose run --rm snapshot-binlog`。

### CRITICAL: gap 検出

1. 欠番 file が source 側で purge された可能性が高い（source purge gap）。
2. `restic snapshots --tag kind=binlog` で欠番前後の存在を確認。
3. 欠番区間の復元可能性が無いため RPO 達成を停止扱いとし、
   新しい full を取得して以降の PITR を継続する（`backup-full.sh` 実行）。
4. source の binlog 保持期間（binlog_expire_logs_seconds）を archive 遅延と
   比較し、purge が archive を追い越さない運用にする。

### WARN: repo check / drill 期限

- `restic check` を実行し `last-repo-check` を touch する。
- 四半期 drill（`restore-drill.sh`）を実施し `last-drill` を touch する。
