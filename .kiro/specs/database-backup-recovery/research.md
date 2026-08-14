# Research — MySQL 8 / restic 一次資料

## 1. MySQL 8

| Research ID | 一次資料 | 本仕様への反映 |
|---|---|---|
| HFP-03-RS-001 | [Point-in-Time Recovery Using Binary Log](https://dev.mysql.com/doc/refman/8.0/en/point-in-time-recovery-binlog.html) | full 後の binlog を apply。複数 log は単一 MySQL connection。暗号化 binlog の remote 読取りは TLS、VERIFY_CA/VERIFY_IDENTITY を優先。 |
| HFP-03-RS-002 | [Point-in-Time Recovery Using Event Positions](https://dev.mysql.com/doc/refman/8.0/en/point-in-time-recovery-positions.html) | datetime は対象 event position の発見にだけ使い、実 apply は `--start-position/--stop-position`。 |
| HFP-03-RS-003 | [mysqlbinlog](https://dev.mysql.com/doc/refman/8.0/en/mysqlbinlog.html) | datetime は実行 host の local timezone。`--raw --stop-never`、明示 initial log、一意 connection-server-id、checksum、複数 file/session の制約。 |
| HFP-03-RS-004 | [Using mysqlbinlog to Back Up Binary Log Files](https://dev.mysql.com/doc/refman/8.0/en/mysqlbinlog-backup.html) | live raw archive の公式 pattern。active file を closed/verified 後に snapshot 化する。 |
| HFP-03-RS-005 | [mysqldump](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html) | `--single-transaction` は InnoDB の consistent read。DDL と同時実行不可。`--source-data` が PITR start coordinate を提供。 |
| HFP-03-RS-006 | [The Binary Log](https://dev.mysql.com/doc/refman/8.0/en/binary-log.html) | transaction は commit order で記録、binary logging/暗号/checksum の前提。 |
| HFP-03-RS-007 | [Binary Log Transaction Compression](https://dev.mysql.com/doc/refman/8.0/en/binary-log-transaction-compression.html) | MySQL 8.0.20+ の compressed payload は互換 mysqlbinlog が必要。同じ end position の展開 event を考慮。 |
| HFP-03-RS-008 | [LOCK INSTANCE FOR BACKUP](https://dev.mysql.com/doc/refman/8.0/en/lock-instance-for-backup.html) | backup 中の DDL/file operation 防止の選択肢。必要 privilege/運用影響を HFP-03-001 で決定。 |
| HFP-03-RS-009 | [Replication with GTIDs](https://dev.mysql.com/doc/refman/8.0/en/replication-gtids.html) | GTID は lineage/transaction 補助検証に使う。production GTID mode を推測せず file/position を必須記録。 |

## 2. restic

| Research ID | 一次資料 | 本仕様への反映 |
|---|---|---|
| HFP-03-RS-010 | [Preparing a New Repository](https://restic.readthedocs.io/en/stable/030_preparing_a_new_repo.html) | repository password file、S3 credential、鍵紛失時の復元不能リスク。 |
| HFP-03-RS-011 | [Working with Repositories / Check](https://restic.readthedocs.io/en/stable/045_working_with_repos.html) | metadata check と `--read-data` の定期完全性検査。 |
| HFP-03-RS-012 | [Troubleshooting](https://restic.readthedocs.io/en/stable/077_troubleshooting.html) | repository damage 時は通常 job/prune を停止し、証跡を保存。自動 repair しない。 |
| HFP-03-RS-013 | [Removing Backup Snapshots](https://restic.readthedocs.io/en/stable/060_forget.html) | prune は remote data の download/repack/delete を伴うため backup path と分離し、lock/dependency dry-run を要求。 |
| HFP-03-RS-014 | [Encryption](https://restic.readthedocs.io/en/stable/070_encryption.html) | repository key の複数 key/rotation/escrow と復元確認。 |

## 3. 判断記録

| Decision ID | 決定 | 理由 |
|---|---|---|
| HFP-03-DEC-001 | production source への in-place restore を禁止。 | shell の確認文字列だけでは誤接続を十分に防げず、旧環境を rollback 用に残せない。 |
| HFP-03-DEC-002 | UTC target は checkpoint 選択、apply は file/position。 | MySQL 公式が datetime apply より event position を推奨し、host timezone 差も除去できる。 |
| HFP-03-DEC-003 | DB と uploads は 15 分整合 checkpoint 単位で戻す。 | DB だけ target まで進めると、その間に作成された file 参照を uploads が満たせない。 |
| HFP-03-DEC-004 | active raw binlog は repository snapshot 対象外。 | 書込み中 file の truncation を SHA だけでは正常な末尾として区別できない。rotation/closed end を検証する。 |
| HFP-03-DEC-005 | restic prune は専用 task/role/lock。 | full 成功直後の無条件 prune は binlog snapshot 競合と依存 chain 破壊を招く。 |
| HFP-03-DEC-006 | production client は MySQL 8 exact version/digest pin。 | Alpine の `mysql-client` は MariaDB 系になり得て、MySQL 8 binlog/compression/options の保証にならない。 |
| HFP-03-DEC-007 | write-enable 前 read-only smoke だけ単純 rollback 可。 | write-enable 後に旧 DB へ戻すと新規 transaction を失う。 |
| HFP-03-DEC-008 | client の CA 提供は hashed `ssl-capath` を推奨。 | 2026-08-14 実測: MySQL 8.0.46 client は `--ssl-ca` + `VERIFY_CA/VERIFY_IDENTITY` で `SSL_CTX_set_default_verify_paths failed`（`mysql:8.0.36` 公式イメージの client でも再現）。hashed capath では VERIFY_CA/VERIFY_IDENTITY が動作する。 |

## 4. 実測記録（2026-08-14、隔離 Docker 環境）

| 項目 | 実測結果 |
|---|---|
| `mysql:8.0.36` 公式イメージ | digest `a5327240...b4964`。`mysql`/`mysqldump` は有るが `mysqlbinlog` は無い。 |
| Oracle MySQL apt（bookworm, 8.0.46） | `mysql-community-client` / `-core` に `mysqlbinlog` 無し。`mysql-community-server-core` に含まれる（dpkg -L で確認）。 |
| mysql-apt-config 0.8.33 同梱鍵 | `B7B3B788A8D3785C` が期限切れ（EXPKEYSIG）。`RPM-GPG-KEY-mysql-2025`（同一 fingerprint、2027-10-23 まで有効）の import で解決。 |
| client `--ssl-ca` + VERIFY_* | 8.0.46（Debian apt）・8.0.36（OEL 公式 image）双方で `SSL_CTX_set_default_verify_paths failed`。 |
| client hashed `ssl-capath` + VERIFY_CA | 動作。source の自動生成 CA を `<subject_hash>.0` で配置して成功。 |
| client hashed `ssl-capath` + VERIFY_IDENTITY | CA 検証は動作。server cert の CN/SAN が host 名と一致しない環境では identity 検証が失敗する（想定どおり）。 |
| MySQL 8.0.36 起動 | `--log-bin --server-id --binlog-format=ROW --binlog-checksum=CRC32 --gtid-mode=ON --enforce-gtid-consistency=ON` で binlog ON を確認。socket ping 成功後も TCP 受付まで ~10s の遅延あり（readiness は TCP connect で判定する）。 |
| `caching_sha2_password` | root は TLS 必須。`--ssl-mode=DISABLED` の接続は ERROR 2061 で拒否される（TLS 前提は本仕様と整合）。 |

## 5. 既知の trade-off（元 §4）

- 15 分 checkpoint の書込み静止は可用性コストを持つ。atomic volume/object snapshot provider がない場合は、RPO と無停止性のどちらかを曖昧にせず production owner が選ぶ。静止なし tar を整合 backup と表示しない。
- `mysqldump` は大規模 DB で RTO 4 時間を超える可能性がある。HFP-03-012 の実測で超える場合は physical backup/managed snapshot を別 task として採用し、目標未達のまま本 spec を PASS にしない。
- S3 Object Lock/append-only と restic prune の実装適合性は backend 固有である。HFP-03-001/010 の隔離 repository で実証してから production 設定する。
