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

| Baseline ID | 未確定値 |
|---|---|
| HFP-03-PROD-001 | MySQL server exact version/image、`@@server_uuid`、GTID/binlog/checksum/compression/TLS/retention。 |
| HFP-03-PROD-002 | 全 table engine と非 app writer の有無。 |
| HFP-03-PROD-003 | uploads が Docker volume/LVM/EBS/S3 等のどれか、atomic snapshot と versioning の可否。 |
| HFP-03-PROD-004 | app replica/scheduler/traffic drain/DDL change-lock の実運用手段。 |
| HFP-03-PROD-005 | S3 互換 backend、IAM、versioning/immutability、repository size/throughput。 |
| HFP-03-PROD-006 | 二者承認 identity/signature verifier と change-ticket system。 |
| HFP-03-PROD-007 | 匿名化した代表profile ID/SHA。DB総bytes/rowsと上位table分布、uploads file数/総bytes/size分布、15分/日次binlog量、CPU/RAM/storage IOPS・throughput/network、利用可能maintenance window、RTO区間予算。synthetic fixtureは各容量をbaseline未満にせず上限+10%以内、上位分布は±10%、restore環境はproductionより高性能にしない。 |
| HFP-03-PROD-008 | alert routing、運用 owner、incident commander、四半期 drill owner。 |

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
