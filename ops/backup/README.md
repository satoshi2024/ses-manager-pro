# バックアップ運用手順（HFP-03）

## 概要

MySQL 8 の業務データと uploads を restic repository へ退避し、指定 UTC 時刻以前の
**検証済み整合 checkpoint** へ復元する。RPO 15 分 / RTO 4 時間（HFP-03-012 の隔離演習で実測するまで未達成扱い）。

**現状の注意:** production 固有値（HFP-03-PROD-001〜008）は未確定のため
`baseline.md` で BLOCKED。production 環境への接続・復元は行わないこと。

## 前提

- Docker でビルドしたツールイメージ（`ops/backup/Dockerfile`）:
  Oracle MySQL 8.0.46 client + mysqlbinlog + mysqldump + restic 0.17.3
- `.env.backup` に接続情報（secret 値は含めない）:
  `BACKUP_REPOSITORY`、`MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE`、`MYSQL_USER`
- `secrets/mysql-password`（0600）: MySQL パスワード
- `secrets/restic-password`（0600）: restic repository パスワード
- `secrets/mysql-capath/` : hashed CA ディレクトリ（`openssl rehash` / `c_rehash` 済み）
  ※ MySQL 8.0.46 client は `--ssl-ca` + VERIFY_* が使えない（research.md §4 実測）。
  CA は hashed capath で提供し、`MYSQL_SSL_CAPATH` で指定する。

## 実行

```bash
# 1. 環境契約検査（read-only）: 0 でなければ先へ進まない
docker compose run --rm preflight

# 2. 日次 full（02:00 Asia/Tokyo 想定）
docker compose run --rm backup

# 3. 継続 binlog archive（常駐）・15 分 checkpoint・閉じた binlog snapshot
docker compose up -d binlog
docker compose run --rm checkpoint     # cron 等で 15 分ごと
docker compose run --rm snapshot-binlog

# 4. 監視（watermark 基準。alert は外部へ routing）
docker compose run --rm check --json
```
`MYSQL_PWD` 環境変数は使わない。秘密は 0600 の一時 option file 経由で渡す。

## 権限分離（RQ-008）

| role | DB 権限 | repository 権限 | 用途 |
|---|---|---|---|
| dump role | 対象 DB read、view/trigger/event/routine の最小権限 | writer（backup 可・delete 不可） | full/checkpoint |
| binlog role | `REPLICATION CLIENT`（+ checkpoint 用 rotation 権限は別 account） | writer | archive/snapshot |
| retention role | なし | **唯一** forget/prune 可（専用 maintenance 時のみ） | retention |
| restore target role | recovery target 新規 DB のみ（source 権限なし） | read | restore/validate |

- repository の S3 versioning/immutability を有効化し、通常 backup credential だけで全世代を削除できないようにする。
- 書込み静止（quiesce）は `providers/quiesce-local.sh`（version 管理された executable）のみ。任意 `bash -c` の `APP_STOP_COMMAND` は受け付けない。
- 静止 protocol: 全 replica の heartbeat fresh / scheduler ack / MySQL `GET_LOCK('ses_backup_ddl_freeze')` の三者を bounded deadline 内に確認。app の DDL/deploy は同じ lock 名を尊重する規約。

## 復元

復元は **production source とは別の recovery target** へ行う。手順は `runbooks/restore-cutover.md`。

```bash
# plan（read-only）: 要求時刻は RFC3339 UTC（末尾 Z）
./plan-restore.sh --target 2026-08-14T02:30:00Z

# apply は target guard と二者承認を通過した plan だけ
./restore.sh   --plan <plan-id> --approval <file1> --approval <file2>
./validate-restore.sh --plan <plan-id> --uploads-dir <staging> --smoke <script>
./cutover.sh   --plan <plan-id> --approval <file1> --approval <file2>
# rollback（write-enable 前のみ。旧環境の read-only smoke が PASS した場合だけ）
./rollback-cutover.sh --plan <plan-id>
```

**禁止:** 稼働中 DB への in-place import、`CONFIRM_RESTORE=YES` 固定文字列での実行、
`--target` 表示のみの検証完了扱い。

## 監視

`check-backup.sh --json` は最新 watermark（full/checkpoint/closed binlog の repository 到達点）と
source 現行 coordinate の差で判定する。古い file の存在だけでは FAIL にしない。

## retention（依存グラフに基づく削除）

- `retention.sh --dry-run`（変更なし。PITR 30 日 + 日次/週次/月次代表 + full-only）
- `retention.sh --apply --report <report> --approval <c1> --approval <c2>`
  （report 再計算で一致確認 → 二者承認 → maintenance lock → forget --prune）
- `RETENTION_ROLE=retention|admin` 以外では実行不可（writer は削除できない）
- key rotation は `rotate-key.sh --new-key-file <file>`（旧・新の両キーで restore verify 成功時のみ切替え）。手順は `runbooks/key-rotation.md`

## 演習（restore drill）

四半期ごと、および tool/image/restore 変更時に `restore-drill.sh` を隔離環境で実施する。source/target は別 container/network/credential とし、host port を公開しない。drill は plan → integrity → restore → validate（read-only smoke）→ cutover リハーサル（write-enable せず rollback）まで実 script で実行し、RPO/RTO segment 時間と evidence SHA を記録する。`mysqladmin ping` のみの代替確認は受け付けない。手順は `runbooks/restore-drill.md`、障害モード別対応は `runbooks/restore-failure-modes.md`。

## テスト

- unit: `tests/run-unit-tests.sh`（fake CLI に対する全 script の検証、Docker 必須）
- integration: `tests/run-integration.sh`（実 MySQL 8.0.36 ×2 コンテナで実 PITR、before/after marker 照合。CI の `backup-integration` job と同一）
- ローカルで CI と同じ範囲を確認する場合は `scripts/verify-like-ci.sh`