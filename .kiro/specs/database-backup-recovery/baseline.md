# Baseline — HFP-03 正式データバックアップ・PITR

## 1. 調査時点

- 調査日: 2026-08-12
- branch 基点: `99fbed82`
- 対象: `.kiro/specs/database-backup-recovery/`、`ops/backup/`、`.github/workflows/ci.yml`、`application*.yml`、uploads の保存経路。
- 制約: production DB、S3/restic repository、credential、deployment topology への接続は行っていない。

## 2. 結論

`ops/backup` には full/binlog/health/restore/drill の骨格があるが、指定時刻 PITR、uploads 復元、誤接続防止、実 restore drill は未実装である。現状を production recovery として運用してはいけない。

| Finding ID | 現状 | 影響 | 対応 task |
|---|---|---|---|
| HFP-03-BL-001 | `restore.sh --target` は target を表示するだけで選択/replay に使用しない。 | 指定時刻へ戻らない。 | HFP-03-006,007 |
| HFP-03-BL-002 | `restic snapshots --tag full --latest 1` で単純に最新 full を選ぶ。 | target より後の full を選び得る。 | HFP-03-006 |
| HFP-03-BL-003 | dump import のみで binlog を一件も適用しない。 | PITR ではなく full restore。RPO 15 分を満たさない。 | HFP-03-004,007 |
| HFP-03-BL-004 | `uploads.tar` は backup するが restore/検証/atomic cutover しない。 | DB の file 参照が壊れる。 | HFP-03-007〜009 |
| HFP-03-BL-005 | `manifest.sha256` 作成後に `metadata.txt` を追加する。 | metadata は hash 対象外。 | HFP-03-003 |
| HFP-03-BL-006 | DB dump と uploads tar の間に write barrier がない。uploads 不在も成功する。 | 同じ時点の DB/files にならない。 | HFP-03-002,003 |
| HFP-03-BL-007 | `archive-binlog.sh` は initial log を渡さず、一意 connection-server-id/TLS identity/state/restart 方針がない。 | live archive が開始不能または競合・漏れの可能性。 | HFP-03-004 |
| HFP-03-BL-008 | active raw binlog directory 全体を restic backup する。 | 書込み途中/truncated file を保存し得る。 | HFP-03-004 |
| HFP-03-BL-009 | binlog continuity、source UUID、file size/end position の検査がない。 | gap/別 source 混在を成功扱い。 | HFP-03-004,006 |
| HFP-03-BL-010 | health は directory の「任意の古い file」を stale とする。最新 event upload/source lag を見ない。 | false positive と実 gap 見逃し。 | HFP-03-005 |
| HFP-03-BL-011 | full 成功 path で毎回 `forget --prune`。binlog snapshot 等との共有 lock/依存 graph がない。 | 競合、保持 checkpoint の chain 破壊。 | HFP-03-002,010 |
| HFP-03-BL-012 | DB password を `MYSQL_PWD` として export。設計文書の「使用しない」と矛盾。 | process environment から secret 露出。 | HFP-03-001,002 |
| HFP-03-BL-013 | Alpine `mysql-client` の実体/version を production MySQL 8 と固定検証しない。 | MariaDB client/option/format 非互換の恐れ。 | HFP-03-001 |
| HFP-03-BL-014 | restore target の UUID/marker/allowlist/空 DB 判定なし。`CONFIRM_RESTORE=YES` だけで既存 DB へ import。 | 誤った DB・production source を破壊し得る。 | HFP-03-006,007 |
| HFP-03-BL-015 | `APP_STOP_COMMAND` を任意 `bash -c` 実行し、空でも許可。停止確認/二者承認なし。 | command injection、稼働中 import、承認形骸化。 | HFP-03-002,009 |
| HFP-03-BL-016 | `restore-drill.sh` は `mysqladmin ping` と案内文だけ。 | restore を一度も実行せず green。 | HFP-03-011,012 |
| HFP-03-BL-017 | backup scripts の CI/static/integration test がない。 | 破損・誤接続回帰を検出不能。 | HFP-03-011 |
| HFP-03-BL-018 | repository writer/retention role、versioning/immutability、key escrow が未定義。 | credential 侵害/鍵紛失で全 backup 喪失。 | HFP-03-002,010 |

## 3. uploads 対象の確認

`app.upload.base-path` の既定値は `./uploads`。現時点で DB から file を参照する代表列/実装は次を含む。実装時は固定リストだけにせず schema/entity scan と provider inventory を組み合わせる。

- `t_document_version.storage_key`
- `t_file_security_metadata.stored_name`
- `t_contract_document.pdf_path/signed_pdf_path/certificate_path`
- `t_proposal.skill_sheet_path`
- resume/project/BP ingestion の `stored_file_name`
- local storage の `quarantine`、`published`、`documents`、`contracts`

## 4. production で未確定のため BLOCKED の項目

以下は HFP-03-001 で owner と実測しない限り production gate を PASS にしない。
**2026-08-14 時点: production 環境への接続・計測は行っていないため、全項目 BLOCKED のまま。**
production 固有値を推測せず、unit/Docker 隔離での実装と検証のみ進める。

| Baseline ID | 未確定値 | 状態 | 確定に必要な証跡 |
|---|---|---|---|
| HFP-03-PROD-001 | MySQL server exact version/image、`@@server_uuid`、GTID/binlog/checksum/compression/TLS/retention。 | BLOCKED | production owner による `preflight.sh --json` 出力（redacted） |
| HFP-03-PROD-002 | 全 table engine と非 app writer の有無。 | BLOCKED | 同上（engine 検査含む） |
| HFP-03-PROD-003 | uploads が Docker volume/LVM/EBS/S3 等のどれか、atomic snapshot と versioning の可否。 | BLOCKED | storage owner の inventory |
| HFP-03-PROD-004 | app replica/scheduler/traffic drain/DDL change-lock の実運用手段。 | BLOCKED | deployment owner の inventory |
| HFP-03-PROD-005 | S3 互換 backend、IAM、versioning/immutability、repository size/throughput。 | BLOCKED | infra owner の inventory |
| HFP-03-PROD-006 | 二者承認 identity/signature verifier と change-ticket system。 | BLOCKED | security owner の決定 |
| HFP-03-PROD-007 | 匿名化した代表profile ID/SHA。DB総bytes/rowsと上位table分布、uploads file数/総bytes/size分布、15分/日次binlog量、CPU/RAM/storage IOPS・throughput/network、利用可能maintenance window、RTO区間予算。synthetic fixtureは各容量をbaseline未満にせず上限+10%以内、上位分布は±10%、restore環境はproductionより高性能にしない。 | BLOCKED | production owner の匿名 profile + ID/SHA |
| HFP-03-PROD-008 | alert routing、運用 owner、incident commander、四半期 drill owner。 | BLOCKED | 運用組織の決定 |

## 4.1 HFP-03-001 で確定した事実（隔離環境・一次資料ベース）

| 項目 | 確定値 | 根拠 |
|---|---|---|
| 隔離 source イメージ | `mysql:8.0.36@sha256:a532724022429812ec797c285c1b540a644c15e248579c6bfdf12a8fbaab4964` | docker inspect（2026-08-14 実測） |
| ツールイメージ | `ses-backup-tool:8.0.46`（Oracle MySQL apt client 8.0.46 + restic 0.17.3） | evidence `tool-image-digest.txt` |
| MySQL apt 署名鍵 | mysql-apt-config 0.8.33 同梱鍵は 2025-10-22 で期限切れ（EXPKEYSIG）。`RPM-GPG-KEY-mysql-2025` の更新鍵（有効期限 2027-10-23）を import して解決。 | Dockerfile コメント + research.md |
| mysqlbinlog の所在 | MySQL 8.0.46 Debian package では `mysql-community-client-core` に無く、`mysql-community-server-core` に含まれる。 | dpkg -L 実測（research.md） |
| client TLS | MySQL 8.0.46 client は `--ssl-ca` + `VERIFY_CA/VERIFY_IDENTITY` で `SSL_CTX_set_default_verify_paths failed` になる（実測）。hashed `ssl-capath` では VERIFY_CA/VERIFY_IDENTITY が機能する。 | dbg-ssl 実測（research.md） |
| 隔離環境 TLS | source は自動生成自己署名 CA。client は hashed capath + VERIFY_CA で接続（VERIFY_IDENTITY は server cert の CN/SAN が host 名と一致しないため不可 → 隔離環境は期限付き VERIFY_CA を許容、production は host naming を満たす CA を用意する） | HFP-03-001 Demo |
| BL-012 廃止 | `MYSQL_PWD` は preflight/mysql-options で使用検出すると拒否（exit 18）。mode 0600 option file + `--defaults-extra-file` 先頭配置へ移行。 | preflight-test.sh |
| BL-013 対応 | Alpine の MariaDB 系 `mysql-client` を廃止。Oracle MySQL apt の exact version を base digest 固定で導入。`mysql/mysqlbinlog/mysqldump --version` を preflight で検証。 | preflight.sh / Dockerfile |

## 5. task別の変更前 safety baseline test

以下は全体のfailure inventoryであり、一括実装の指示ではない。実装AIは各taskの着手時に、§2の「対応task」列がそのtaskを指す項目だけを現行scriptに対するfailing testとして先に固定する。他taskのowner fileへ先回りせず、各taskのred→最小修正→greenを維持する。

1. target より後の latest full が選ばれてしまう。
2. `--target` を変えても実行 command が同一。
3. binlog replay、uploads restore が 0 回。
4. nonempty/same-source target が拒否されない。
5. metadata 改変が manifest 検査を通る。
6. active/truncated binlog が snapshot 対象になる。
7. 古い file が 1 件あるだけで health failure になる一方、最新 source lag を測らない。
8. `MYSQL_PWD` が child process environment に存在する。
9. restore drill が restore command 0 回でも exit 0。
