# 正式データバックアップ・PITR 実装タスク

## 実行規約

- task は ID 順に実行する。依存 task が未完了/BLOCKED のまま先を PASS にしない。
- checkbox は Objective、implementation、automated test、隔離 Demo、evidence、失敗/rollback 判定がすべて完了した時だけ更新する。
- production credential/repository/DB/uploads を test/Demo に使用しない。
- 各 task の開始/終了を `review-ledger.md` に追記し、evidence file の SHA-256 を記録する。
- 実装前に failure を再現する test を追加し、最小の変更で通す。無関係な refactor をしない。
- Docker、topology、承認 verifier 等が不足する場合は PASS ではなく `BLOCKED(理由)` とする。

## Task 一覧

- [x] **HFP-03-001 — production baseline と toolchain contract を固定する**

  - **依存:** なし
  - **対応要求:** HFP-03-RQ-001、HFP-03-RQ-008
  - **Objective:** 実装が暗黙に仮定している MySQL/storage/deployment 条件を機械検証可能にし、MariaDB client や未確定 topology で後続処理が動かないようにする。
  - **対象 file:** `ops/backup/Dockerfile`、`ops/backup/docker-compose.yml`、`ops/backup/preflight.sh`、`ops/backup/lib/mysql-options.sh`、`ops/backup/tests/preflight-test.sh`、本 spec の `baseline.md`。
  - **Implementation:**
    1. production DB/storage/app replica/quiesce/TLS/binlog/tool version を実環境 owner と確定し baseline の「未確定」を埋める。HFP-03-PROD-007は実データを含めず、DB/上位table、uploads、binlog、CPU/RAM/storage/networkの匿名profile ID/SHA、synthetic生成条件、容量許容差、restore resource上限まで固定する。
    2. production と互換の Oracle MySQL 8 client を exact version + image digest で pin する。`mysql/mysqlbinlog/mysqldump --version` を起動時に検証する。
    3. read-only preflight で server UUID、version、GTID、log_bin/format/checksum/compression、retention、table engine、TLS、空き容量、uploads topology を JSON 化する。
    4. `MYSQL_PWD` を廃止し mode 0600 の一時 option file を用いる。secret を argv/log/env dump に出さない。
  - **Automated test:** MySQL 8 正常、MariaDB client、version mismatch、log_bin off、非 InnoDB、TLS/secret file permission 不正を fixture で検証。shellcheck。`/proc`/argv/env/log secret grep 0。
  - **隔離 Demo:** pinned image と synthetic MySQL 8 を起動し preflight JSON、image digest、server UUID を出力する。意図的に MariaDB client fixture を指定し非 0 を示す。
  - **Evidence:** `target/backup-recovery-evidence/<run-id>/HFP-03-001/` に version、digest、preflight JSON、test log、secret scan を保存。
  - **失敗/rollback:** production topology 未確定、互換 client 未固定、secret 漏洩が 1 件でもあれば BLOCKED/FAIL。既存 image へ自動 fallback しない。

- [x] **HFP-03-002 — 排他 lock、権限分離、書込み静止 provider を実装する**

  - **依存:** HFP-03-001
  - **対応要求:** HFP-03-RQ-002、HFP-03-RQ-006、HFP-03-RQ-008、HFP-03-RQ-009
  - **Objective:** backup/checkpoint/prune の競合と DB/uploads の跨ぎ不整合を、共通 lock と検証可能な書込み静止 protocol で防止する。
  - **対象 file:** `ops/backup/lib/repository-lock.sh`、`ops/backup/providers/*`、`ops/backup/lib/quiesce.sh`、`ops/backup/docker-compose.yml`、`ops/backup/tests/quiesce-lock-test.sh`、運用側 schedule/deployment 定義。
  - **Implementation:**
    1. 全 restic 操作の lock mode/timeout/owner metadata を統一する。
    2. 全 app replica、scheduler、DDL/deployment lock を確認できる version 管理 provider を実装する。任意 `bash -c` env command は削除する。
    3. uploads provider は snapshot ID と開始/終了 UTC、inventory を返す。ローカル volume は静止中に同一 filesystem staging を作る。
    4. dump/binlog/retention/restore target の credential と repository writer/retention role を分離する。
  - **Automated test:** lock race、timeout、provider partial failure、replica 1 台未静止、scheduler active、DDL lock conflict、解除 failure、role privilege negative test。
  - **隔離 Demo:** 2 app-writer fixture のうち 1 台を故意に残して checkpoint が発行されないこと、その後両方を静止して marker 境界が一致することを示す。
  - **Evidence:** lock timeline、quiesce acknowledgement、negative test exit code、role grant redacted report。
  - **失敗/rollback:** 静止解除失敗は重大 incident として traffic を勝手に再開しない。snapshot は INVALID 隔離。既存 production data は変更しない。

- [x] **HFP-03-003 — 一貫 full backup と完全 manifest を実装する**

  - **依存:** HFP-03-001、HFP-03-002
  - **対応要求:** HFP-03-RQ-002、HFP-03-RQ-003
  - **Objective:** dump、uploads、metadata が同一静止区間に属し、全 payload が manifest で検証される daily full を作る。
  - **対象 file:** `ops/backup/backup-full.sh`、`ops/backup/lib/manifest.sh`、`ops/backup/lib/metadata.sh`、`ops/backup/tests/full-backup-test.sh`、`ops/backup/README.md`。
  - **Implementation:** metadata→payload close→manifest→manifest SHA→restic→restore verify→VALID index の順を固定する。dump の `--source-data=2` coordinate を parse し server status/GTID と照合する。uploads の特殊 file/path を拒否する。`forget --prune` は成功 path から除く。
  - **Automated test:** metadata 後書き、dump/uploads/manifest 1 byte corruption、途中 producer、DDL、symlink/device/path traversal、restic upload failure、coordinate parse failure。
  - **隔離 Demo:** marker を作り full を取得、restic から別 temp へ戻し manifest/coordinate/table counts/uploads hash を照合。metadata を改変して verify failure を示す。
  - **Evidence:** snapshot ID、manifest SHA、coordinate、quiesce window、restore verification、test log。raw dump/個人データは evidence に含めない。
  - **失敗/rollback:** verify 前の snapshot を VALID 登録しない。app write 静止を bounded cleanup で解除し、不完全 staging を隔離する。

- [x] **HFP-03-004 — 継続 binlog archive と 15 分整合 checkpoint を実装する**

  - **依存:** HFP-03-002、HFP-03-003
  - **対応要求:** HFP-03-RQ-004、HFP-03-RQ-005
  - **Objective:** active file を snapshot せず、closed/checksummed binlog と同時点 uploads を 15 分以内に永続化する。
  - **対象 file:** `ops/backup/archive-binlog.sh`、`ops/backup/snapshot-binlog.sh`、`ops/backup/create-checkpoint.sh`、`ops/backup/lib/binlog.sh`、`ops/backup/tests/binlog-checkpoint-test.sh`、schedule 定義。
  - **Implementation:** 明示 initial log、一意 connection-server-id、TLS VERIFY_IDENTITY、state file、source UUID guard、rotation、source size/end position、checksum、immutable rename、restart resume、checkpoint manifest を実装する。active raw file を restic 対象外にする。
  - **Automated test:** 2 回 rotation、archiver restart、欠番、truncation、checksum error、別 UUID、重複 server ID、source purge gap、active file exclusion、15 分 watermark。
  - **隔離 Demo:** DB/uploads に checkpoint 前 marker、解除後 marker を作成。2 本以上の binlog と checkpoint metadata が同一境界を示すことを確認する。
  - **Evidence:** source/current/closed coordinate、file list/size/SHA、checkpoint ID、lag 秒、negative logs。
  - **失敗/rollback:** gap を検出したら `RPO_UNAVAILABLE` と重大 alert。黙って次 file から再開せず、新しい full 取得の運用判断まで停止する。

- [x] **HFP-03-005 — watermark 監視と alert contract を実装する**

  - **依存:** HFP-03-003、HFP-03-004
  - **対応要求:** HFP-03-RQ-010、HFP-03-RQ-012
  - **Objective:** 古い file の存在ではなく最新成功 event/checkpoint と source の差から、RPO 可否を正しく監視する。
  - **対象 file:** `ops/backup/check-backup.sh`、`ops/backup/lib/health.sh`、monitor/schedule 定義、`ops/backup/tests/health-test.sh`、runbook。
  - **Implementation:** JSON/metric、full/checkpoint/event age、archiver heartbeat、gap、repository check、capacity、drill age、個別 exit code、warning/critical routing を実装する。
  - **Automated test:** full 26h、checkpoint/event 20m/30m、古い file + 最新正常、archiver stop + source advance、gap、repository check failure、clock skew、drill overdue。
  - **隔離 Demo:** 正常→archiver 停止→critical→再開/追従の状態遷移を示す。古い closed file を残して false alert 0 を確認。
  - **Evidence:** 各状態の JSON/exit code、alert receipt/redacted routing、recovery timestamp。
  - **失敗/rollback:** monitor 自体が repository/source を読めない場合も OK を返さず UNKNOWN/CRITICAL。alert routing 未接続は BLOCKED。

- [x] **HFP-03-006 — UTC target から不変 restore plan を生成する**

  - **依存:** HFP-03-003、HFP-03-004
  - **対応要求:** HFP-03-RQ-005、HFP-03-RQ-006
  - **Objective:** 要求時刻より後の snapshot を選ばず、正しい base full/start position/checkpoint stop position/依存 snapshot/target を固定する。
  - **対象 file:** `ops/backup/plan-restore.sh`、`ops/backup/lib/selector.sh`、`ops/backup/lib/plan.sh`、`ops/backup/lib/approval.sh`、`ops/backup/tests/restore-plan-test.sh`。
  - **Implementation:** strict RFC3339 Z parser、lineage、最新 `<= target` checkpoint/full 選択、dependency/checksum/continuity、RPO 計算、target fingerprint、canonical plan SHA、expiry、二者 approval claim verification を実装する。datetime option は `TZ=UTC` の read-only 調査だけに限定する。
  - **Automated test:** target 前後の full/checkpoint、restic latest が target 後、同時刻/境界、UTC/JST/DST、15 分超、lineage mismatch、gap、plan tamper、approval 0/1/同一 actor/期限切れ/別 target。
  - **隔離 Demo:** 3 full/checkpoint fixture の中央を target とし、期待 snapshot/coordinate を選ぶ。同じ入力を 3 timezone で実行し plan SHA が一致することを示す。
  - **Evidence:** requested/effective time、RPO 秒、selected IDs/start-stop coordinate、plan SHA、timezone matrix、approval verifier result。署名秘密は保存しない。
  - **失敗/rollback:** ambiguity/dependency/RPO/approval 不正では plan を APPLYABLE にしない。既存 plan を書換えず新 plan ID を作る。

- [x] **HFP-03-007 — recovery target guard と staging restore を実装する**

  - **依存:** HFP-03-006
  - **対応要求:** HFP-03-RQ-003、HFP-03-RQ-006、HFP-03-RQ-007
  - **Objective:** source/非空/allowlist 外 DB を import 前に拒否し、新規 target DB と uploads staging だけへ完全復元する。
  - **対象 file:** `ops/backup/restore.sh`、`ops/backup/lib/target-guard.sh`、`ops/backup/lib/safe-extract.sh`、`ops/backup/tests/restore-integration-test.sh`、隔離 compose。
  - **Implementation:** target UUID/marker/allowlist/TLS/空 DB/credential scope/plan/approval を pre-write 検証。restic+manifest verify 後に dump import。全 binlog を file order 固定し単一 mysqlbinlog→単一 `mysql --binary-mode` connection で start/stop position まで replay。uploads は versioned staging へ安全展開する。
  - **Automated test:** same UUID、allowlist/marker mismatch、nonempty DB/existing schema、default host、bad TLS、plan/payload tamper、dump failure、middle binlog failure、mysql connection count=1、malicious archive、uploads hash failure。全 negative case で source SHA 不変。
  - **隔離 Demo:** source/target 別 container、internal network、synthetic credential で実 restore。target 前 marker のみ存在し、source count/SHA が不変であることを示す。
  - **Evidence:** target fingerprint、guard report、restic/manifest result、replay start-stop、mysql connection count、source before/after SHA。接続秘密は除外。
  - **失敗/rollback:** target を `FAILED_RESTORE` として隔離し公開しない。再試行は別の空 target/DB で行い、途中 DB を再利用しない。

- [x] **HFP-03-008 — DB/uploads/application validation を実装する**

  - **依存:** HFP-03-007
  - **対応要求:** HFP-03-RQ-007、HFP-03-RQ-011
  - **Objective:** SQL import 成功を復旧成功と誤認せず、復旧点・DB/files・アプリ互換を独立確認する。
  - **対象 file:** `ops/backup/validate-restore.sh`、`ops/backup/lib/validate-db.sh`、`ops/backup/lib/validate-uploads.sh`、recovery read-only profile/config、`ops/backup/tests/restore-validation-test.sh`。
  - **Implementation:** Flyway、CHECK TABLE、critical counts/invariants、before/after markers、全 storage reference inventory、保存 hash、matching app build の read-only smoke を report 化する。scheduler/Flyway migrate/mail/external API/cleanup を無効にする。
  - **Automated test:** Flyway failed/history mismatch、missing file、hash mismatch、extra orphan、marker after present、count mismatch、wrong app build、smoke failure。missing/hash は fatal、extra は隔離 report。
  - **隔離 Demo:** 正常 restore を READY にし、その後 staging file 1 件を削除して FAILED_VALIDATION/cutover 不可を示す。
  - **Evidence:** validation JSON、Flyway/count/reference/hash summary、read-only smoke result、app build SHA。
  - **失敗/rollback:** production pointer は変更しない。FAILED staging は調査用 read-only、期限後に承認付き cleanup。

- [x] **HFP-03-009 — production cutover/rollback と二者承認を実装する**

  - **依存:** HFP-03-006、HFP-03-008
  - **対応要求:** HFP-03-RQ-006、HFP-03-RQ-007、HFP-03-RQ-012
  - **Objective:** READY target を停止中に read-only 起動し、書込み解放前だけ安全に旧環境へ戻せる change 手順を固定する。
  - **対象 file:** `ops/backup/cutover.sh`、`ops/backup/rollback-cutover.sh`、`ops/backup/lib/approval.sh`、deployment provider、`ops/backup/runbooks/restore-cutover.md`、`ops/backup/tests/cutover-test.sh`。
  - **Implementation:** signed two-person approval、change ticket、全 replica stop、旧 fingerprint、DB config + atomic uploads pointer、read-only smoke、commit/rollback state machine を実装。restore script から app start を削除する。
  - **Automated test:** approval tamper/same actor/expired、replica active、pointer partial failure、read-only smoke failure rollback、write-enable 後 rollback refusal、old/new config mismatch。
  - **隔離 Demo:** smoke を意図的に失敗させ旧 DB/uploads に戻り、write 0 を確認。その後正常 smoke→write enable を行い、単純 rollback が拒否されることを示す。
  - **Evidence:** redacted approval claims/signature result、ticket、old/new fingerprint、stop/read-only/write timestamps、rollback/commit result。
  - **失敗/rollback:** read-only 中だけ旧環境へ rollback。write 解放後は自動 rollback せず incident commander へ移管する。

- [x] **HFP-03-010 — dependency-aware retention、暗号鍵、削除耐性を実装する**

  - **依存:** HFP-03-003、HFP-03-004、HFP-03-006
  - **対応要求:** HFP-03-RQ-008、HFP-03-RQ-009
  - **Objective:** prune が保持 checkpoint の full/binlog/uploads chain を壊さず、通常 backup credential の侵害だけで全世代を削除できないようにする。
  - **対象 file:** `ops/backup/retention.sh`、`ops/backup/lib/dependency-graph.sh`、repository IAM/config/runbook、`ops/backup/tests/retention-test.sh`。
  - **Implementation:** PITR 30 日 dependency graph、weekly8/monthly12 full-only、dry-run/approval/apply report、maintenance lock、別 retention role、S3 versioning/immutability、key escrow/rotation を実装する。
  - **Automated test:** oldest/middle/latest chain、orphan snapshot、missing dependency、prune race、writer delete denial、retention role absence、key rotation old/new restore。
  - **隔離 Demo:** synthetic 31 日分 metadata に dry-run→prune→保持点 3 箇所の plan/restore verify。writer credential で delete が拒否されることを示す。
  - **Evidence:** dependency graph before/after、deleted IDs、retained restore proof、IAM negative result、key IDs/rotation result（key value は不可）。
  - **失敗/rollback:** dependency 不明なら削除 0。prune failure は repository repair を自動実行せず、通常 job を停止して runbook へ移行。

- [ ] **HFP-03-011 — Docker integration/CI と偽 green 防止を追加する**

  - **依存:** HFP-03-005〜HFP-03-010
  - **対応要求:** HFP-03-RQ-011
  - **Objective:** 核心 path を mock だけで済ませず、MySQL 8 source→別 target の実 PITR を CI で毎回検証する。
  - **対象 file:** `ops/backup/tests/docker-compose.integration.yml`、`ops/backup/tests/run-integration.sh`、`.github/workflows/*`、`scripts/verify-like-ci.*` または専用 verify、CI artifact 定義。
  - **Implementation:** pinned MySQL 8/MinIO、隔離 network/no host port、synthetic secrets、before/after markers、複数 log、timezone matrix、negative safety suite、skip=0 contract、artifact redaction を実装する。
  - **Automated test:** HFP-03-AC-001-01〜HFP-03-AC-011-03 の自動化可能項目を trace table へ接続。Docker 不可を CI failure、local BLOCKED とする。secret scan と source invariance を全 case 後に実施。
  - **隔離 Demo:** clean machine/CI で integration suite を実行し tests/failures/errors/skipped、RPO、image digest、plan SHA を提示。
  - **Evidence:** CI URL/artifact SHA、test report、skip count、secret scan、source invariance report。
  - **失敗/rollback:** CI job を optional/allow-failure にしない。既存 CI を壊す場合は原因を直し、test を削除/skip して green にしない。

- [ ] **HFP-03-012 — 実 restore drill、RPO/RTO、runbook を完了する**

  - **依存:** HFP-03-001〜HFP-03-011
  - **対応要求:** HFP-03-RQ-010、HFP-03-RQ-011、HFP-03-RQ-012
  - **Objective:** `mysqladmin ping` だけではない production 相当の隔離復元を行い、運用者が incident 時に再現できる証跡付き runbook を完成する。
  - **対象 file:** `ops/backup/restore-drill.sh`、`ops/backup/runbooks/*`、`ops/backup/README.md`、schedule/monitor、`.kiro/specs/database-backup-recovery/review-ledger.md`。
  - **Implementation:** actual plan→restore→validate→read-only cutover→rollback/commit を呼び、RTO segment、RPO、markers、DB/uploads、Flyway/app smoke を記録。gap、key loss、validation failure、write-enable 後障害を runbook 化。四半期 schedule/owner/escalation を設定。
  - **Automated test:** drill wrapper が全 subcommand を実行し、いずれか失敗・skip・evidence 欠落・目標超過で非 0。`mysqladmin ping` のみの fake success を拒否。
  - **隔離 Demo:** HFP-03-PROD-007のprofile ID/SHAに対し、各容量がbaseline以上かつ上限+10%以内、上位分布±10%、restore resourceがproduction同等以下であることをpreflight検証してから四半期相当drillを実施する。RPO≤15m、RTO≤4h、before marker present/after absent、missing refs/hash mismatch 0、skip 0を示す。
  - **Evidence:** representative profile ID/SHA/tolerance report、immutable drill report、segment timings、plan/manifest SHA、image/app digest、test counts、review ledger、ticket（機密は redaction）。
  - **失敗/rollback:** RPO/RTO 未達、credential/topology/Docker 不足、未実施項目は FAIL/BLOCKED。期限付き改善 task と owner を ledger に残し、全体 PASS にしない。

## merge前の実装引渡し gate

HFP-03-001〜012 が完了し、HFP-03-GATE-01〜08 を独立 Review AI が再実行してすべてPASSした場合、実装branchを`REVIEWABLE`とする。task checkbox、実装AIの自己申告、単一の正常系restoreは代替証跡にならない。

merge後は、merge済みcommitとmerge delta、main上の運用script/CI回帰を独立Reviewし、同じgateが維持される場合だけ本specを最終`PASS`とする。merge前の`REVIEWABLE`を最終PASSとして転記しない。
