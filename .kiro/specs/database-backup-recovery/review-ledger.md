# Review Ledger — HFP-03 正式データバックアップ・PITR

> この ledger は追記式で使用する。checkbox や自己申告だけで PASS にしない。secret、raw dump、個人データ、秘密 URL は記録しない。

## 1. Run metadata

| 項目 | 値 |
|---|---|
| Run ID | 20260814-hfp03 |
| Base commit | 841e10aaf67deb295d5b3397321f30e9d08c0fce（origin/main） |
| Reviewed commit/diff | 実装中（HFP-03-001〜逐次追記） |
| Merge status / merge commit | PRE_MERGE / N/A |
| Implementation actor | HFP-03 実装 AI（codex/hfp-03-backup-pitr） |
| Independent reviewer | NOT_SET |
| Started/finished UTC | 2026-08-14T03:00Z / NOT_SET |
| MySQL source/target image digest | `mysql:8.0.36@sha256:a532724022429812ec797c285c1b540a644c15e248579c6bfdf12a8fbaab4964` / NOT_SET |
| Backup tool image digest | `ses-backup-tool@sha256:13b3510035cd1092c70b97e20451c48df25c1d8811cb146be2fe3e199bd63811`（build 毎に更新） |
| Representative profile ID / SHA-256 | NOT_SET / NOT_SET（HFP-03-PROD-007 BLOCKED） |
| Docker/CI URL | NOT_SET |
| Evidence root | `target/backup-recovery-evidence/20260814-hfp03/` |

## 2. Task ledger

| Task ID | Impl status | Review status | Changed files | Test/Demo | Evidence path + SHA-256 | Finding/Blocker |
|---|---|---|---|---|---|---|
| HFP-03-001 | REVIEWABLE | NOT_REVIEWED | `ops/backup/Dockerfile`, `docker-compose.yml`, `preflight.sh`, `lib/common.sh`, `lib/mysql-options.sh`, `tests/{lib/test-framework.sh, fixtures/bin/{mysql,mysqlbinlog,mysqldump}, preflight-test.sh, run-unit-tests.sh, run-all-unit-tests.sh}`, `.gitattributes`, `README.md`, `baseline.md`, `research.md` | preflight-test.sh 59 assert 全 PASS（tool image 内）。shellcheck -S error exit 0。隔離 Demo: synthetic MySQL 8.0.36 + pinned image で preflight exit 0 / MariaDB fixture exit 10 / secret scan 0 | `target/backup-recovery-evidence/20260814-hfp03/HFP-03-001/`（preflight-ok.json=`3ac86a35...` preflight-mariadb.json=`dc84b598...` client-versions.txt=`048387d2...` server-image-digest.txt=`10a3a2e4...` tool-image-digest.txt=`8264f2e0...`） | production 固有値 HFP-03-PROD-001〜008 は BLOCKED（baseline.md §4 に追記）。MySQL 8.0.46 client の `--ssl-ca`+VERIFY_* 不具合は hashed capath で回避（research.md §4 実測） |
| HFP-03-002 | REVIEWABLE | NOT_REVIEWED | `ops/backup/lib/repository-lock.sh`, `lib/quiesce.sh`, `providers/quiesce-local.sh`, `providers/uploads-local.sh`, `tests/quiesce-lock-test.sh`, `tests/fixtures/bin/mysql`（GET_LOCK/RELEASE_LOCK/stdin 対応）, `Dockerfile`（providers COPY）, `docker-compose.yml`, `README.md`（権限分離） | quiesce-lock-test.sh 45 assert 全 PASS。shellcheck exit 0。隔離 Demo: ①stale replica で acquire 失敗 ②全 fresh で acquire/release 成功 ③静止中 GET_LOCK=0・解放後=1（実 MySQL で検証）secret scan 0 | `target/backup-recovery-evidence/20260814-hfp03/HFP-03-002/`（demo-A=`44b6ec54...` demo-B=`78e696b3...` demo-C=`772d874c...`） | DDL 凍結は app 側も同一 GET_LOCK 名を尊重する規約が必要（README 記載）。PROD-004（replica/scheduler 実運用手段）は BLOCKED 継続 |
| HFP-03-003 | REVIEWABLE | NOT_REVIEWED | `ops/backup/backup-full.sh`（legacy 置換）, `lib/manifest.sh`, `lib/metadata.sh`, `tests/full-backup-test.sh`, `tests/fixtures/bin/{mysqldump,restic}`（fixture）, `README.md` | full-backup-test.sh 32 assert 全 PASS（coordinate parse、quiesce/restic 失敗、symlink、1byte 破損、extra file、metadata 後書き、forget 呼出 0）。shellcheck exit 0。隔離 Demo: 実 MySQL+restic で full→restore verify→marker/table count/uploads hash 照合→metadata 改変で verify 失敗 | `target/backup-recovery-evidence/20260814-hfp03/HFP-03-003/`（backup-full-result.json=`7b3119d0...` restore-verify.txt=`c7c28518...` tamper-verify.txt=`e6d26ae5...` manifest-sha.txt=`d63c13d4...` engineer-count-before.txt=`53c234e5...`） | 発見: 背景 DDL session が lock fd/pipe を握る問題を fd 固定化+閉鎖で解決（review 対象）。metadata 内 status は PENDING で upload し、restic tag status=valid 昇格を selector が参照する |
| HFP-03-004 | REVIEWABLE | NOT_REVIEWED | `ops/backup/archive-binlog.sh`（legacy 置換）, `snapshot-binlog.sh`（legacy 置換）, `create-checkpoint.sh`（新規）, `lib/binlog.sh`（新規）, `tests/binlog-checkpoint-test.sh`（新規）, `tests/fixtures/bin/{mysql,mysqlbinlog}`（SHOW/FLUSH/archive/verify fixture 拡張）, `lib/common.sh`（restic::ensure_repository）, `docker-compose.yml` | binlog-checkpoint-test.sh 49 assert 全 PASS（archive resume/UUID/server-id/欠番/checksum/truncated/active 除外/rotation 2 回）。shellcheck exit 0。隔離 Demo: 実 MySQL で --stop-never archive → checkpoint（実 FLUSH/rotation/uploads snapshot）→ 前 marker のみ DB/uploads に存在・解除後 marker は snapshot に不在、closed binlog 3 本 | `target/backup-recovery-evidence/20260814-hfp03/HFP-03-004/`（checkpoint-result.json=`e84f774a...` checkpoint-index-values.txt=`17132852...` marker-files-restored.txt=`d7967249...` immutable-files.txt=`d7f4ac05...` raw-files.txt=`c7f748fc...` binlogs-before.txt=`728a7677...` binlogs-after.txt=`cd08104b...`） | 実装中発見: (1) 背景 DDL session の fd 継承（前 task で対応） (2) checkpoint→snapshot-binlog の lock 自己デッドロックを --no-lock で解決 (3) $(...) 内 init のスコープ問題を main 前置きで解決。いずれも review 対象 |
| HFP-03-005 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-006 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-007 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-008 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-009 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-010 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-011 | NOT_STARTED | NOT_REVIEWED | | | | |
| HFP-03-012 | NOT_STARTED | NOT_REVIEWED | | | | |

task status は `NOT_STARTED / IN_PROGRESS / REVIEWABLE / PASS / FAIL / BLOCKED` のみ。必須 task/acceptance を免除して PASS にしない。P2/NOTE の延期は findings へ `DEFERRED` として発注者承認、期限、risk owner、release 影響、代替 control を記録する。

## 3. Requirements trace

| RQ | AC | Owner task | 実装箇所 | 自動 test class/script + case | 隔離 Demo | Review 判定 |
|---|---|---|---|---|---|---|
| HFP-03-RQ-001 | HFP-03-AC-001-01 | HFP-03-001 | `preflight.sh --json`（client/server UUID/version/binlog/engine/TLS/容量/uploads を JSON 化） | preflight-test.sh: normal / mariadb / 5.7 / 8.4 / log_bin off / checksum off / non-innodb / tls off / uploads missing / disk / help | preflight exit 0、JSON に server_uuid/version 出力 | NOT_REVIEWED |
| HFP-03-RQ-001 | HFP-03-AC-001-02 | HFP-03-001 | `preflight.sh` の exit 10〜18 / Dockerfile の Oracle MySQL 8.0.46 pin | preflight-test.sh: mariadb(10), 5.7(10), 8.4(11), log_bin(12), checksum(13), engine(14), tls(15), uploads(16), disk(17), MYSQL_PWD(18) | MariaDB fixture で exit 10 | NOT_REVIEWED |
| HFP-03-RQ-001 | HFP-03-AC-001-03 | HFP-03-001 | `baseline.md` §4: PROD-001〜008 を BLOCKED 化 | 対象: 推測値を production に書かない | — | NOT_REVIEWED |
| HFP-03-RQ-002 | HFP-03-AC-002-01 | HFP-03-002,003,004 | `lib/quiesce.sh` + `providers/quiesce-local.sh`（静止 protocol: replicas/scheduler/DDL lock） | quiesce-lock-test.sh: all_fresh / one_replica_stale / scheduler_no_ack / scheduler_dir_missing / ddl_lock_conflict | Demo B: quiesce.json に started/released/replicas/ddl_lock | NOT_REVIEWED |
| HFP-03-RQ-002 | HFP-03-AC-002-02 | HFP-03-002,003,004 | 静止確認に失敗したら snapshot 発行不可（acquire 非 0） | quiesce-lock-test.sh: stale/ack 欠如で非 0 | Demo A: stale replica で acquire 失敗 | NOT_REVIEWED |
| HFP-03-RQ-002 | HFP-03-AC-002-03 | HFP-03-002,003 | provider 失敗で不完全 snapshot を valid にしない（acquire 非 0 → 呼び出し元で中断） | 同上 | Demo A/C | NOT_REVIEWED |
| HFP-03-RQ-003 | HFP-03-AC-003-01 | HFP-03-003 | `lib/manifest.sh`（manifest.json → manifest.sha256 の順で固定、read-only 化） | full-backup-test.sh: metadata_late_write / corrupt_one_byte / extra_file / absolute_path | Demo: metadata 改変 → size 不一致で verify 失敗 | NOT_REVIEWED |
| HFP-03-RQ-003 | HFP-03-AC-003-02 | HFP-03-003,007 | manifest::verify（size/sha256 照合）+ restic restore --verify | full-backup-test.sh: corrupt_one_byte / restore_verify_content | Demo: restore --verify + MANIFEST_VERIFY_OK | NOT_REVIEWED |
| HFP-03-RQ-003 | HFP-03-AC-003-03 | HFP-03-003,008 | manifest::verify の extra file / 絶対 path / traversal 拒否 | full-backup-test.sh: extra_file / absolute_path | — | NOT_REVIEWED |
| HFP-03-RQ-004 | HFP-03-AC-004-01 | HFP-03-004 | `archive-binlog.sh --stop-never` + `create-checkpoint.sh`（15 分 cadence の checkpoint 生成） | binlog-checkpoint-test.sh: checkpoint_normal / rotation_twice | Demo: checkpoint 生成・closed binlog 3 本 | NOT_REVIEWED |
| HFP-03-RQ-004 | HFP-03-AC-004-02 | HFP-03-004,006,007 | `binlog.sh::files_continuous/size_ok/verify_checksum`、snapshot の active 除外 | binlog-checkpoint-test.sh: continuity_gap / snapshot_closed_only / snapshot_checksum_fail / checkpoint_truncated_reject / checkpoint_checksum_reject | Demo: 実 checksum verify | NOT_REVIEWED |
| HFP-03-RQ-004 | HFP-03-AC-004-03 | HFP-03-004,005 | `archive-binlog.sh` state（last file から再開、不完全 file は取り直し） | binlog-checkpoint-test.sh: archive_restart_resume / archive_restart_incomplete | — | NOT_REVIEWED |
| HFP-03-RQ-005 | HFP-03-AC-005-01 | HFP-03-006 | | | | NOT_REVIEWED |
| HFP-03-RQ-005 | HFP-03-AC-005-02 | HFP-03-006,007 | | | | NOT_REVIEWED |
| HFP-03-RQ-005 | HFP-03-AC-005-03 | HFP-03-006 | | | | NOT_REVIEWED |
| HFP-03-RQ-006 | HFP-03-AC-006-01 | HFP-03-006,007 | | | | NOT_REVIEWED |
| HFP-03-RQ-006 | HFP-03-AC-006-02 | HFP-03-007 | | | | NOT_REVIEWED |
| HFP-03-RQ-006 | HFP-03-AC-006-03 | HFP-03-006,007,009 | | | | NOT_REVIEWED |
| HFP-03-RQ-007 | HFP-03-AC-007-01 | HFP-03-007 | | | | NOT_REVIEWED |
| HFP-03-RQ-007 | HFP-03-AC-007-02 | HFP-03-008 | | | | NOT_REVIEWED |
| HFP-03-RQ-007 | HFP-03-AC-007-03 | HFP-03-009 | | | | NOT_REVIEWED |
| HFP-03-RQ-008 | HFP-03-AC-008-01 | HFP-03-001,002,007 | `lib/mysql-options.sh`（0600 option file、MYSQL_PWD 拒否、argv 先頭 defaults-extra-file） | preflight-test.sh: mysql_pwd_env(18), argv 先頭, option file mode 600, 全 case の secret grep 0 | evidence に secret 0 件（Demo 内 grep） | NOT_REVIEWED |
| HFP-03-RQ-008 | HFP-03-AC-008-02 | HFP-03-002,010 | | | | NOT_REVIEWED |
| HFP-03-RQ-008 | HFP-03-AC-008-03 | HFP-03-001,010,011 | Dockerfile 鍵更新（RPM-GPG-KEY-mysql-2025 import） | shellcheck / image build 成功 | tool image digest 記録 | NOT_REVIEWED |
| HFP-03-RQ-009 | HFP-03-AC-009-01 | HFP-03-006,010 | | | | NOT_REVIEWED |
| HFP-03-RQ-009 | HFP-03-AC-009-02 | HFP-03-002,010 | `lib/repository-lock.sh`（shared/exclusive/maintenance flock + owner metadata） | quiesce-lock-test.sh: shared_and_exclusive / bad_mode（timeout 非 0） | — | NOT_REVIEWED |
| HFP-03-RQ-009 | HFP-03-AC-009-03 | HFP-03-010 | | | | NOT_REVIEWED |
| HFP-03-RQ-010 | HFP-03-AC-010-01 | HFP-03-004,005 | | | | NOT_REVIEWED |
| HFP-03-RQ-010 | HFP-03-AC-010-02 | HFP-03-005 | | | | NOT_REVIEWED |
| HFP-03-RQ-010 | HFP-03-AC-010-03 | HFP-03-005,012 | | | | NOT_REVIEWED |
| HFP-03-RQ-011 | HFP-03-AC-011-01 | HFP-03-007,011,012 | | | | NOT_REVIEWED |
| HFP-03-RQ-011 | HFP-03-AC-011-02 | HFP-03-011 | | | | NOT_REVIEWED |
| HFP-03-RQ-011 | HFP-03-AC-011-03 | HFP-03-007,011 | | | | NOT_REVIEWED |
| HFP-03-RQ-012 | HFP-03-AC-012-01 | HFP-03-005,012 | | | | NOT_REVIEWED |
| HFP-03-RQ-012 | HFP-03-AC-012-02 | HFP-03-012 | | | | NOT_REVIEWED |
| HFP-03-RQ-012 | HFP-03-AC-012-03 | HFP-03-009,012 | | | | NOT_REVIEWED |

## 4. Safety/quality gates

| Gate | Result | 実測値/再現 | Evidence SHA | Reviewer note |
|---|---|---|---|---|
| HFP-03-GATE-01 source destructive operation 0 | NOT_RUN | | | |
| HFP-03-GATE-02 target/full/checkpoint selection | NOT_RUN | | | |
| HFP-03-GATE-03 binlog continuity/single connection | NOT_RUN | | | |
| HFP-03-GATE-04 DB/uploads reference/hash | NOT_RUN | | | |
| HFP-03-GATE-05 negative safety suite | NOT_RUN | | | |
| HFP-03-GATE-06 RPO/RTO | NOT_RUN | RPO= / RTO= | | |
| HFP-03-GATE-07 secret/role separation | NOT_RUN | secret matches= | | |
| HFP-03-GATE-08 Docker/drill/CI skip 0 | NOT_RUN | tests= failures= errors= skipped= | | |

## 5. Restore drill timeline

| Segment | Start UTC | End UTC | Duration | Result | Evidence |
|---|---|---|---:|---|---|
| incident/request | | | | NOT_RUN | |
| plan + approval | | | | NOT_RUN | |
| download + integrity | | | | NOT_RUN | |
| DB full + binlog replay | | | | NOT_RUN | |
| uploads staging | | | | NOT_RUN | |
| validation + read-only smoke | | | | NOT_RUN | |
| cutover/read-write approval | | | | NOT_RUN | |

- Requested target UTC: NOT_SET
- Effective checkpoint UTC: NOT_SET
- RPO: NOT_SET
- Base full ID / uploads ID / binlog start-stop: NOT_SET
- Plan SHA / manifest SHA: NOT_SET
- Representative profile ID / SHA / tolerance result: NOT_SET / NOT_SET / NOT_RUN
- Marker before DB/file: NOT_RUN / NOT_RUN
- Marker after DB/file absent: NOT_RUN / NOT_RUN
- Source before/after SHA equal: NOT_RUN

## 6. Findings

| Finding ID | Severity | RQ/Task | File:line | 再現 | 影響 | 推奨修正 | Status |
|---|---|---|---|---|---|---|---|
| | | | | | | | |

Severity は P0（production 破壊/復元不能）、P1（RPO/RTO/security/整合性）、P2（限定的な運用性/監視）、NOTE（要件を破らない非必須改善）とする。P0/P1 または未管理 acceptance が残る場合は全体 PASS にしない。P2/NOTEを延期する場合は発注者承認、owner、期限、release影響を記録する。

finding status は `OPEN / FIXED_BY_IMPLEMENTER / VERIFIED_CLOSED / REJECTED / DEFERRED` とする。実装担当は `FIXED_BY_IMPLEMENTER` まで、独立 Reviewer だけが `VERIFIED_CLOSED` にできる。`DEFERRED` は P2/NOTE に限る。

## 7. Evidence manifest

| Evidence file | SHA-256 | Producer | Redaction/secret scan | Retention/CI artifact |
|---|---|---|---|---|
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-001/preflight-ok.json` | `3ac86a350e2a9ade62e8530c2277c6c09b0c6dc6d020db3b75e258dcb7e798f7` | HFP-03-001 Demo | host/user/DB 名なし、password 値 grep 0 | gitignore 対象（target/） |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-001/preflight-mariadb.json` | `dc84b5981911d5869beab050637dce797f11667a2791fa8ff2bcc8ab1cafdec7` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-001/client-versions.txt` | `048387d2639ada52b80c813a52312f954a4dac11ac935df1abde71015090f0e7` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-001/server-image-digest.txt` | `10a3a2e45ceca34a5436e36c76067fbbe1e817d4e1da98521416d4db982aa79b` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-001/tool-image-digest.txt` | `8264f2e0e30140f4b134a8b751f7f39cd0dc95bde73d8034d092f859bc88fa4f` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-002/demo-A-stale-replica.txt` | `44b6ec543a1a7c1fc8603701541f2e4306e378a07a43b9882e5ce21350fcb644` | HFP-03-002 Demo | password 値 grep 0 | gitignore 対象 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-002/demo-B-quiesce-ok.txt` | `78e696b3612536cbfce73dee33b5ccff4e608751158d0c9b9bad812258c84e28` | HFP-03-002 Demo | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-002/demo-C-ddl-lock.txt` | `772d874cd993032ce59a4fabb0fde0088b5b92962c1710c3554166a1e13c90f7` | HFP-03-002 Demo | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-003/backup-full-result.json` | `7b3119d03aefc5aab898a4636b3a3c9250ec69844cdfe826f965569c83c6f4f5` | HFP-03-003 Demo | password 値 grep 0 | gitignore 対象 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-003/restore-verify.txt` | `c7c2851848cb122c932b1db6f50a3a7633419d71a12940e39ce37f986d119693` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-003/tamper-verify.txt` | `e6d26ae58015e073ff9f3cad8917da2ef7d65483575c63cf31432eac1fd34c06` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-003/manifest-sha.txt` | `d63c13d46ac92b6c27b965f48172a1fcbc1e5f70e25dacb516ab0c7dbc194a42` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-003/engineer-count-before.txt` | `53c234e5e8472b6ac51c1ae1cab3fe06fad053beb8ebfd8977b010655bfdd3c3` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-004/checkpoint-result.json` | `e84f774a26218ca847d05b89be1516c7238aad52c99322023091336ffbb49cee` | HFP-03-004 Demo | password 値 grep 0 | gitignore 対象 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-004/checkpoint-index-values.txt` | `171328524e26f5e038ce8026bae5aa0156e4de96a72edff4429b0b479555577f` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-004/marker-files-restored.txt` | `d796724922740f2a98a44760d096708e803307e06d7f1c1cc60b292b875a7a41` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-004/immutable-files.txt` | `d7f4ac05ef1c58989636e85e5687e9dc3c33963ee585f381ba043cd8d5995455` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-004/raw-files.txt` | `c7f748fcd27123c6b88acb6223c0a054d28fc671fd8da312f14597d3a868adc2` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-004/binlogs-before.txt` | `728a7677c33fea37cd0758a25ccd704f2d8e16435b3193427989bb42c6316338` | 同上 | 同上 | 同上 |
| `target/backup-recovery-evidence/20260814-hfp03/HFP-03-004/binlogs-after.txt` | `cd08104b5c41b19209d2356a58cf021cc36fd0caf71defed35dd1c809a44b475` | 同上 | 同上 | 同上 |

## 8. Final decision history（追記）

| UTC | Reviewer | Decision | Open finding/blocker | 根拠 |
|---|---|---|---|---|
| | | NOT_REVIEWED | | |

Decisionは`REVIEWABLE / PASS / FAIL / BLOCKED`のいずれかとする。`REVIEWABLE`はmerge前、`PASS`はmerge済みcommitとmerge deltaを独立Reviewした場合だけ使用する。
