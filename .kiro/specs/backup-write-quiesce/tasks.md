# backup の書込み静止（app 内の静止応答）実装タスク

## 実行規約

- 本書は第 1 段階（spec）で書いた（第 4 版: `32733d28` の review、`c2e18127` と `9b8cc0a5` の差分 review、2026-11-21 の利用者の決定 U-1〜U-14 を反映。指摘と task の対応は `design.md` §20）。実装は spec の独立 review が PASS した後、統括の指示で始める。
- task は次の順に行う: BWQ-001 → 002 → 003 → 004 → 005 → 006 → **015** → 007 → 008 → 009 → 010 → 011 → 012 → 013 → 014（BWQ-015 は review の後に足したため番号が後ろにある）。依存する task が終わっていない・BLOCKED のまま後の task を完了にしない。
- 利用者の決定（`design.md` §16。全て決定済み）に従う。決定を変える必要が出たら、先に統括へ戻し、`design.md` とこの task を直してから進める。
- checkbox は、実装・自動試験・Demo が全て終わった時だけ `- [x]` にする（CLAUDE.md の spec の手順）。
- 試験は各 task の対象だけを流す（`mvn test -Dtest=…`、MySQL の試験は `-Pmysql-tests -Dtest=…`、backup tool は `ops/backup/tests/<file>.sh`）。全 gate（`scripts/verify-like-ci.*`）の実行の時期は統括が決める。migration の file を足した・名前を変えた後は `mvn clean test` にする。
- 実 AWS・本番の DB・本番の credential を試験と Demo に使わない。Demo は local の MySQL 8.4（`dev` profile）か Testcontainers・backup の integration の compose で行う。
- repository に書く文字は日本語（UI・log・comment・commit）。message bundle は 4 つとも翻訳する。
- 版番号 V202 は、着手時に `src/main/resources/db/migration` と `db/migration-dev` の merged の状態と全 branch で再確認する（`design.md` §3.5）。
- `design.md` §19 の未実測の前提は、該当する task の試験で確かめ、違えば spec を直してから進める。

## Task 一覧

- [ ] **BWQ-001 — 信号表の migration・H2 の schema・版番号の連動**
  - **状態:** 実装・fast の試験は済み（commit `73713475`）。MySQL の試験と Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2a）:** V202・H2 の変種・`engineer-schema-h2.sql`・`LATEST=202`・`ProductionSchemaVersionGuardTest`・runbook（冒頭・§1.7・§3.3・§3.4・§4）。ack の `resume_reason` にも CHECK `ck_bq_ack_resume` を足した（「理由の値が拒否される」の対象に含めるため）。fast は `MigrationScriptIntegrityTest`・`DeployRunbookMigrationCountTest`・`ProductionSchemaVersionGuardTest`・`BackupQuiesceSchemaContractTest` が通過。**Runner 待ち**: `FlywayProdFreshInstallMySqlTest`（mysql shard 3。V202 の3表・生成列・負例を足した）と Demo（local の MySQL 8.4 の dev 起動）。

  - **依存:** なし
  - **対応要求:** BWQ-RQ-001
  - **Objective:** 静止の要求・instance・ack の 3 表を、本番の MySQL 8.4 と試験の H2 の両方に同じ形で作り、版番号に連動する文書と試験を合わせる。
  - **対象 file:** `src/main/resources/db/migration/V202__backup_write_quiesce_signal.sql`、`src/test/resources/sql/schema-backup-quiesce-h2.sql`（新規）、`src/test/resources/application-test.yml`（`schema-locations` の V201 の変種の後）、`src/test/resources/sql/engineer-schema-h2.sql`、`src/test/java/com/ses/migration/LatestMigrationVersion.java`、`src/test/java/com/ses/migration/BackupQuiesceSchemaContractTest.java`（新規）、`.kiro/specs/production-launch/deploy-runbook.md`（§3.3・§3.4・§4 の schema guard の log・§1.7 の `FLYWAY_VERSION`・冒頭の実装の一覧）。
  - **Implementation:**
    1. `design.md` §3.1 の列・制約・索引で V202 を書く。`active_slot` は生成列と一意索引。instance に `max_hold_*`、`gate_state` に `DISABLED`、要求の `release_reason` に `NEW_INSTANCE`、ack の `refuse_reason` に `DISABLED` を含める。時刻の列は UTC の DATETIME(6) で、DEFAULT に `CURRENT_TIMESTAMP` を使わない。trigger・routine は作らない。
    2. H2 の変種は `CREATE TABLE IF NOT EXISTS`、COMMENT なし、同じ生成式・CHECK・一意索引・FK。`engineer-schema-h2.sql` は ack → instance → request の順に DROP して作る。
    3. `LATEST` を `202` にし、runbook の版と件数（versioned 201 件・SQL 200 件）を直す。
  - **Automated test:** `MigrationScriptIntegrityTest`、`DeployRunbookMigrationCountTest`、`BackupQuiesceSchemaContractTest`（V202 と H2 の一致）、`FlywayProdFreshInstallMySqlTest`（`-Pmysql-tests`。routine 0・trigger 67 のまま）。負例（H2 と MySQL）: 有効な要求を 2 件入れると一意索引で失敗、`active_slot` へ値を直接書く INSERT・UPDATE が失敗、`(status='REQUESTED', active_slot=NULL)` の行を作れない、`RELEASED` にすると次の要求を登録できる、CHECK の外の状態・理由の値が拒否される。
  - **Demo:** local の MySQL 8.4 の空の DB で `mvn spring-boot:run`（dev）を起動し、`SHOW CREATE TABLE sys_backup_quiesce_request\G` 等で 3 表と生成列を確かめ、`flyway_schema_history` の最大が 202 であることを見る。
  - **失敗/rollback:** 版番号が衝突したら上の空き番号へ振り直す（空き番号を埋めない）。適用済みの migration を書き換えない。

- [ ] **BWQ-002 — 書込み gate と設定（core）**
  - **状態:** 実装・fast の試験は済み（commit `61a846dd`）。MySQL の試験と Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2a）:** `BackupWriteGate`（状態・I-4・I-3・手元の期限・遅延評価の FENCED・保留一覧・開いた時の callback）、`BackupWritePermit`（thread への結び付け・入れ子・投入時の数えと引継ぎ）、`BackupQuiesceDeferredWrites`（上限 10,000 件・捨てる時の WARN・3 回の繰返し・閉じ直しで戻す）、`BackupQuiesceProperties`（`@Validated`）、`BackupQuiesceMetrics`（`MeterBinder`）、`application.yml`・`application-test.yml`（`enabled: false`）。手元の期限に達したと一度でも判定した後は延ばせない（参照する側が開いたと見なした後に閉じ直さないため）。gate の働き方は INERT（prod 以外の `enabled=false`）・ARMED・DISABLED（prod の停止口）の3つ。上限で捨てる WARN の errorCode は `BACKUP_QUIESCE_DEFERRED_WRITE_DROPPED`（§13.1 に無いため足した）。fast は `BackupWriteGateTest`・`BackupQuiesceDeferredWritesTest`・`BackupQuiescePropertiesTest` が通過（Demo の状態遷移の表は `BackupWriteGateTest` の `@ParameterizedTest`）。

  - **依存:** BWQ-001
  - **対応要求:** BWQ-RQ-002、BWQ-RQ-003、BWQ-RQ-004、BWQ-RQ-008、BWQ-RQ-010
  - **Objective:** 状態遷移・permit・排水・開け直しの規則・手元の期限・self-fence・保留一覧・解除後の書込みの一覧を、Spring にも DB にも依らない部品で正しく実装する。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupWriteGate.java`・`BackupWritePermit.java`・`BackupQuiesceDeferredWrites.java`・`BackupQuiesceProperties.java`・`BackupQuiesceMetrics.java`（新規）、`src/main/resources/application.yml`（`app.backup-quiesce.*`）、`src/test/resources/application-test.yml`（`enabled: false`）、試験 `BackupWriteGateTest.java`・`BackupQuiesceDeferredWritesTest.java`・`BackupQuiescePropertiesTest.java`（新規）。
  - **Implementation:**
    1. 状態 `UNARMED`・`OPEN`・`CLOSING`・`CLOSED`・`FENCED`・`SHUTDOWN`・`DISABLED`（`design.md` §6.2）。UNARMED の扱いは prod の profile かどうかで決める（D-P3-3）。
    2. I-4 の順（`design.md` §6.1）: permit と書込み可能な接続の数は「先に増やしてから状態と permit を読み、閉じていれば戻す」。gate は「先に CLOSING、数を読み、CLOSED の前に読み直す」。permit は thread に結び付け、入れ子を許し、取得の時刻と持ち主を記録する。
    3. I-3 の開け直しの規則: 要求ごとに「QUIESCED を試したか」「非 QUIESCED を確定したか」を持ち、試した要求は確定か手元の期限でだけ開く。手元の期限は延ばすだけで縮めない API にする。
    4. 手元の期限と fence の判定は、gate を参照する側で毎回評価する。FENCED は他の判定より優先する。
    5. 閉じた後の async の保留一覧（`design.md` §6.7）と、解除後の書込みの一覧（`design.md` §6.13。上限 10,000 件、超えたら最も古いものを WARN で捨てる）と、開いた時の callback。
    6. 設定の範囲と相互の関係（`design.md` §12.1。U-1: 最大保持時間 checkpoint 300 秒・full 60 分、U-3: 排水の上限 checkpoint 30 秒・full 600 秒、U-12: 保存期間 30 日、制御用の接続の timeout < `fence-after − heartbeat-interval`）を `@Validated` で検査する。
    7. metric（`design.md` §13.2）を `MeterBinder` で登録する。
  - **Automated test:** `BackupWriteGateTest`: 状態遷移の全経路（UNARMED（prod と prod 以外）・SHUTDOWN・DISABLED を含む）、100 thread で permit を取りながら閉じても閉じた後に新しい permit が出ないこと（I-4。10,000 回）、排水の上限で REFUSED の道に入り OPEN へ戻ること、QUIESCED を試した要求は確定か手元の期限でしか開かないこと、手元の期限が縮まないこと、fence、入れ子の permit、保留一覧と解除後の書込みが開いた時に投入されること、`oldest_permit_seconds`。`BackupQuiesceDeferredWritesTest`: 上限で捨てる時の WARN、投入の順。`BackupQuiescePropertiesTest`: 既定値が U-1・U-3・U-12 の値であること、範囲外・順序の誤りで bind が失敗すること。
  - **Demo:** 試験の結果（状態遷移の表を `@ParameterizedTest` で出した一覧）を確かめる。
  - **失敗/rollback:** 競合の試験が 1 回でも失敗したら I-4 の順を見直す。`enabled=false` で既存の試験の挙動が変わらないことを BWQ-005 までに確かめる。

- [ ] **BWQ-003 — 制御用・heartbeat 用の接続・信号表の repository・静止応答（responder）・停止の順**
  - **状態:** 実装・fast の試験は済み（commit `47c41934`）。MySQL の試験と Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2a）:** `BackupQuiesceControlConnectionPool`（holder。DataSource の bean にしない）、`BackupQuiesceSignalRepository`（interface）と `JdbcBackupQuiesceSignalRepository`（SQL は `UTC_TIMESTAMP(6)` だけ）、`BackupQuiesceHosts`（`PROCESSLIST.HOST` だけ port を外す）、`BackupQuiesceTicker`（専用 thread）、`BackupQuiesceLifecycleAnchor`（pool・gate・登録・heartbeat・STOPPED。heartbeat の thread も anchor が持ち STOPPED の直前まで続ける。D4-P3-2 の補足）、`BackupQuiesceResponder`（塞がない状態機械・起動の3分岐・停止口・保存期間の削除・解除後の投入は executor の queue が満杯なら次の tick）、`BackupQuiesceDestructionOrderPostProcessor`（bean が無ければ何もしない。D4-P3-2）、`BackupQuiesceConfiguration`。H2 には `UTC_TIMESTAMP`・`CONNECTION_ID` の関数 alias を置いた（`com.ses.test.H2MySqlTimeFunctions`）。`SchedulingDisabledDrainTest` の既知一覧に静止応答の thread と起動・停止の hook を理由つきで足した（BWQ-006 の 3 の先取り。走査で見つかる key だけ）。STOPPED の書込みの入口は BWQ-004 の `postProcessBeforeDestruction`。fast は `BackupQuiesceResponderTest`・`BackupQuiesceControlPoolUsageTest`・`BackupQuiesceContextStartTest`（test profile の全体の context と、prod の profile の scheduling 有効・無効と停止口）・`SchedulingDisabledDrainTest`・`MySqlTestShardInventoryTest`・`MySqlTestImagesTest`・`TestIsolationAuditTest` が通過。**Runner 待ち**: `BackupQuiesceResponderMySqlTest`（mysql shard 3。STOPPED の順の3件は BWQ-004 で足す）と Demo（dev の app と MySQL の client）。

  - **実装の記録（第 2 段階 2c、2b の review の fix C）:** P3-4: 停止口（DISABLED の mode）の instance は heartbeat でも停止の途中でも `gate_state` に `DISABLED` を書き、STOPPED にする時も `DISABLED` のまま残す（`design.md` §3.9）。試験は `BackupQuiesceResponderTest`・`JdbcBackupQuiesceSignalRepositoryPurgeTest`。

  - **依存:** BWQ-001、BWQ-002
  - **対応要求:** BWQ-RQ-001、BWQ-RQ-002、BWQ-RQ-003、BWQ-RQ-004、BWQ-RQ-005、BWQ-RQ-008
  - **Objective:** app が信号表を通じて要求を受け、塞がない状態機械で排水・ack（結果不明の解決を含む）・hold 期限の延長・再開・自動再開を行い、heartbeat を別の thread で書き続け、起動と停止（起動の失敗を含む）の順を守り、停止口の動きと保存期間の削除を行う。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupQuiesceControlConnectionPool.java`・`BackupQuiesceSignalRepository.java`・`BackupQuiesceResponder.java`・`BackupQuiesceConfiguration.java`・`BackupQuiesceDestructionOrderPostProcessor.java`（新規）、試験 `BackupQuiesceResponderTest.java`・`BackupQuiesceControlPoolUsageTest.java`（新規）・`BackupQuiesceResponderMySqlTest.java`（新規、`@Tag("mysql")`）、`scripts/test-suites/mysql-shard-3.txt`。
  - **Implementation:**
    1. 接続（`design.md` §5.2・§6.5.4）: `DataSourceProperties` から作る 2 つの `HikariDataSource`（状態機械用 最大 2、heartbeat 用 最大 1）を holder に持ち、`DataSource` の bean にしない。`connectionInitSql` で `innodb_lock_wait_timeout = 5`、Connector/J の `connectTimeout=5000`・`socketTimeout=10000`、`JdbcTemplate` の query timeout 5 秒、Hikari の `connectionTimeout=3000`・`validationTimeout=2000`（D3-P3-3）。holder・gate・STOPPED を書く部品は最小の anchor の bean `BackupQuiesceLifecycleAnchor` に持たせ、anchor は `DataSourceProperties` と `BackupQuiesceProperties` にだけ依存する（D3-P2-1）。
    2. repository の SQL は時刻を全て `UTC_TIMESTAMP(6)` で扱う。PROCESSLIST の観測は `PROCESSLIST.HOST` だけ末尾の port を外し、`USER()` の host 部はそのまま使う（D-P3-1）。ack の状態の遷移は条件付きの文と影響した行数で確定する。
    3. thread（`design.md` §5.2）: heartbeat の thread は heartbeat の UPDATE だけを行う。状態機械の thread は 200 ms の tick で、1 tick に短い文を高々 1 つ実行し、排水の完了は memory の数で毎 tick 確かめる（sleep・繰返しの loop を持たない）。
    4. 起動の順（`design.md` §6.8）と、ONLINE の full の最中の起動（U-13）では要求の消失で待ちを終えること。APP_STOPPED の保持を読んだ起動は中止し続ける（D3-P2-2）。
    5. 要求の処理（`design.md` §6.9）。`verify_requested_at` を読んだら観測し直す。解除の後は保留一覧と解除後の書込み（logout の失効と監査）を `taskExecutor` へ投入する。
    6. 停止口（U-7。`design.md` §12.2）: prod で ticket つきの `enabled=false` なら DISABLED で登録し heartbeat を続け、要求には `REFUSED`（`DISABLED`）を返し、起動時の APP_STOPPED の保持の拒否も続ける。prod 以外の `enabled=false` は何もしない。
    7. 停止の順（`design.md` §6.11。D-P2-1・D3-P2-1・D3-P3-1）: `ContextClosedEvent` で SHUTDOWN にし、呼んだ thread の permit を返す。`BackupQuiesceDestructionOrderPostProcessor`（`BeanFactoryPostProcessor`）が `taskExecutor` と `taskScheduler` の依存に `dataSource`、`dataSource` の依存に anchor **だけ**を足す。静止応答は `taskExecutor`・業務の service・`MeterRegistry` を `ObjectProvider` で引き、`dataSource` の依存に入れない。STOPPED の書込みは BWQ-004 の `postProcessBeforeDestruction` から anchor が行い（排水が 10 秒で終わった場合だけ）、制御用・heartbeat 用の pool は anchor の破棄で最後に閉じる。
    8. 1 日 1 回の保存期間（30 日）の削除。log は `design.md` §13.1 の固定の errorCode で日本語にする。
  - **Automated test:** `BackupQuiesceResponderTest`（fake の repository）: 要求→ack、REFUSED、解除、lease の期限切れ、最大保持時間、要求の消失、二重処理の無さ、QUIESCED の INSERT が fake の store に入った後で例外を投げる場合に手元の期限まで開かないこと、結果不明の後の REFUSED の成功・重複・例外、hold 期限の UPDATE が失敗しても手元の期限が先に延びていること、RESUMED の UPDATE が 0 行・例外の場合、起動の 3 分岐、**排水を 60 秒続けても heartbeat が 5 秒ごとに書かれ FENCED にならないこと**、**REFUSED の INSERT が timeout しても次の tick へ回り状態機械が止まらないこと**、停止口の DISABLED の登録と REFUSED（`DISABLED`）、解除後の書込みの投入、保存期間の削除。`BackupQuiesceControlPoolUsageTest`（制御用・heartbeat 用の pool と repository を参照するのが `com.ses.backup.quiesce` の中だけ、mapper・controller が 3 表の名前を含まない、SQL に `NOW(` 等が無い）。host の解釈の単体試験（`PROCESSLIST.HOST` の `fe80::1:54321` → `fe80::1`、`USER()` の `fe80::1` はそのまま）。`BackupQuiesceResponderMySqlTest`（実 MySQL 8.4）: 登録の `db_client_host` が PROCESSLIST の host と一致、lease を延ばさないと `LEASE_EXPIRED` で再開、APP_STOPPED の保持中は起動処理が例外で止まる（DISABLED でも）、ack の INSERT の commit の後に接続を `KILL` で切った場合に閉じたままで REFUSED の INSERT が重複になり QUIESCED として続く、**別の session が ack の行の lock を持つ間、REFUSED の INSERT が 5 秒で timeout し heartbeat は続く**、session の time_zone の違い、**通常の context の close と refresh の失敗（DataSource の作成の後に初期化で例外を投げる bean）の両方で、STOPPED を書いた時点で業務の Hikari が閉じていて `taskExecutor` の task が終わっている**、**permit を持つ処理が 10 秒より長く続く停止では STOPPED を書かず WARN `BACKUP_QUIESCE_STOP_UNCONFIRMED` が出る**、**`ApplicationRunner` の失敗で main の thread の startup permit が返され、停止が 10 秒待たない**。`BackupQuiesceContextStartTest`（D3-P2-1）: prod の profile（H2・外部依存は mock）と prod 以外の profile（`enabled=true`）の両方で context が起動し、depends-on の循環が無く、`dataSource` の依存が anchor だけである。`MySqlTestShardInventoryTest`・`MySqlTestImagesTest`。
  - **Demo:** dev profile の app を起動し、MySQL の client で要求を手で入れ、ack の行と log（`BACKUP_QUIESCE_REQUESTED`・`ACKED`）を確かめる。延長せずに待ち、`AUTO_RESUMED` の ERROR と ack の `RESUMED`（`LEASE_EXPIRED`）を見る。app を止め、instance の行が `STOPPED` になることを見る。
  - **失敗/rollback:** `DataSource` の bean が 2 つになって自動構成が崩れたら holder の形に戻す。破棄の順の依存が循環したら、静止応答が DataSource に依存していないかを確かめる。

- [ ] **BWQ-004 — DataSource の最後の防線（読取り専用・接続の数え方・停止の時の STOPPED）**
  - **状態:** 実装・fast の試験は済み（commit `d3d3c589`）。MySQL の試験と Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2a）:** `BackupQuiesceAwareDataSource`（`DelegatingDataSource`。接続は proxy で `setReadOnly`・`close` を数える）、`BackupQuiesceDataSourcePostProcessor`（static な `@Bean`。gate が INERT なら包まない。停止の時は SHUTDOWN・呼んだ thread の permit の返却・最長 10 秒の排水の待ち・Hikari を閉じてから STOPPED、閉じられなければ STOPPED を書かない）、`BackupQuiesceReadOnlyViolation`。BWQ-003 の MySQL 試験に停止の順の4件（通常の close・refresh の失敗・10 秒を超える permit・`ApplicationRunner` の失敗）を足した。fast は `BackupQuiesceAwareDataSourceTest`（H2 の Hikari の metric と `db` の health を含む）・`BackupQuiesceReadOnlyViolationTest`・`BusinessConnectionBypassGuardTest` と影響する既存の試験が通過。**Runner 待ち**: `BackupWriteQuiesceMySqlTest`・`BackupQuiesceResponderMySqlTest`（mysql shard 3。§19 の locking read と Hikari の読取り専用の戻しの記録を含む）と Demo（dev の app の静止中の一覧の表示と MySQL の general log）。

  - **実装の記録（第 2 段階 2d、fix E。`fea83915` の Runner の実測）:** Connector/J 8.3.0 は読取り専用の接続の DML を server へ送る前に SQLSTATE `S1009` で拒否するため、`BackupQuiesceReadOnlyViolation.matches` が client 側の形も読取り専用の拒否とする（`design.md` §6.5.1・§19・§20.7）。H2 の試験の driver は既定で client 側の形を模す。`BackupWriteQuiesceMySqlTest` は判定で両方を受け入れ、server 側の 1792 の形を別の case で固定する。**Runner 待ち**: `BackupWriteQuiesceMySqlTest`（8 件）。

  - **依存:** BWQ-002、BWQ-003
  - **対応要求:** BWQ-RQ-002（AC-002-02・AC-002-07）、BWQ-RQ-004（AC-004-01）
  - **Objective:** 静止中に permit を持たない thread が業務の pool で書けないことを MySQL の層で保証し、排水が「書込み可能な接続」まで待つようにし、停止の時に業務の pool を閉じてから STOPPED を書く。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupQuiesceAwareDataSource.java`・`BackupQuiesceDataSourcePostProcessor.java`・`BackupQuiesceReadOnlyViolation.java`（新規）、試験 `BackupQuiesceAwareDataSourceTest.java`・`BackupQuiesceReadOnlyViolationTest.java`・`BusinessConnectionBypassGuardTest.java`（新規）・`BackupWriteQuiesceMySqlTest.java`（新規、`@Tag("mysql")`）、`scripts/test-suites/mysql-shard-3.txt`。
  - **Implementation:**
    1. `BackupQuiesceDataSourcePostProcessor` は static な `@Bean`（依存は `ObjectProvider` で遅れて引く）で、`BeanPostProcessor` と `DestructionAwareBeanPostProcessor` を兼ねる。`unwrap`・`isWrapperFor` を委譲し、Hikari の metric と health（`db`）を保つ。
    2. 借りる時・`setReadOnly(false)` の時は I-4 の順。`setReadOnly(true)` は autocommit の間に成功した場合だけ数から外す。返す時に書込み可能なら減らす（`design.md` §6.5.2）。
    3. `postProcessBeforeDestruction`（`dataSource` の包む前の Hikari。`requiresDestruction` は `dataSource` に true を返す）: SHUTDOWN にし、呼んだ thread の permit を返し、permit と書込み可能な接続が 0 になるのを最長 10 秒待つ。0 になった場合だけ Hikari を明示に `close()` し、その後で anchor に STOPPED を書かせる。0 にならなければ STOPPED を書かず WARN を出す（`design.md` §6.11。D-P2-1・D3-P3-1）。
    4. `BackupQuiesceReadOnlyViolation.matches(Throwable)`: 原因の連鎖の vendor code 1792 または SQLSTATE `25006`。
  - **Automated test:** `BackupQuiesceAwareDataSourceTest`（H2 と mock: 閉じている時だけ `setReadOnly(true)`、permit の thread は呼ばれない、数の増減、`unwrap(HikariDataSource.class)`、借りる thread と閉じる thread の交互の順を全て並べた決定的な試験、`postProcessBeforeDestruction` が Hikari を閉じてから STOPPED を求める順、10 秒で 0 にならなければ Hikari を閉じる前に STOPPED を求めないこと）。`BusinessConnectionBypassGuardTest`（main の `DriverManager.getConnection`・`HikariDataSource` 等の生成・`unwrap(HikariDataSource.class)` を走査し、既知の例外（制御用・heartbeat 用の pool、`PrivacyJournalConnections`、`LifecycleAuthorizationPreflight`）以外を拒否）。`BackupWriteQuiesceMySqlTest`（実 MySQL 8.4）: permit 無しの INSERT が 1792、locking read の挙動の記録（`design.md` §19）、permit の thread は書ける、閉じる前に借りた書込み可能な接続が返されるまで排水が終わらない、静止の前に始まった読取り専用の transaction の終わりの `setReadOnly(false)` で書込み可能に戻らない、制御用 pool は書ける、32 thread × 200 回の開閉の負荷で各回の ack の後・開く前に commit された permit の無い行が 0 件、marker の前後、Hikari が返す時に read-only を戻すこと。
  - **Demo:** dev profile の app で、静止中に一覧の画面が表示でき、MySQL の general log（local だけ）に業務の接続の `SET SESSION TRANSACTION READ ONLY` が出ることを見る。
  - **失敗/rollback:** Hikari の metric・health が消えたら包み方を直す。負荷の試験で 1 件でも漏れたら I-4 の実装を見直す（試験を緩めない）。

- [ ] **BWQ-005 — HTTP の分類と 503（filter・例外・エラーページ・文言・session・logout・監査・外部 API）**
  - **状態:** 実装・fast の試験は済み（commit `b47ee2d2`）。Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2b）:** `BackupWriteGateFilter`（+35。WRITE・EXTERNAL は permit、LOGOUT は取れれば permit、chain 全体の 1792・`BackupWriteQuiescedException` を 503 へ）・`BackupQuiesceRequestClassifier`・`BackupWriteQuiescedException`・`BackupQuiesceRejections`（503 の header・文言・`Retry-After`）・`BackupQuiesceLogoutDeferral`（logout の handler と success handler）・`AuditedDownloadPaths`、gate の bean（`BackupQuiesceConfiguration`。破棄の callback を推定させない）。`AuditLogService.recordCaptured`（D4-P3-3: 呼出し元は `BackupQuiesceDeferredWrites` だけで `AuditLogMethodLengthGuardTest` が bytecode で固定、時刻は `[今 − 最長の保持時間 − 余裕, 今]`、余裕は 5 分＋checkpoint の最大保持時間）と `PersistentSessionService.captureCurrent`・`revokeCaptured`、U-14 の解除後の最初の touch は `UserSessionMapper.touchCarriedOver`（既存の `touch` は行の idle 期限で絞るため）。`t_audit_log` に IP の列が無いため、解除後の監査に IP は書けない（取っておく値から外した）。

  - **実装の記録（第 2 段階 2c、U-15。2026-11-22 の利用者の決定）:** 静止中も portal の参照を続ける。`PortalSessionServiceImpl.resolve` は gate が書込みを許さず permit が無いとき `t_portal_session` へ書かず（`touchIfValid` も `revokeById(EXPIRED)` も）、idle は U-14 と同じ規則（gate が閉じた時刻、下限は今 − 最長の保持時間）、失効と `expires_at` は今の時刻で判定する。静止中の最終利用は行の ID を鍵とする memory の表（上限 10,000 件。溢れたら記録せず WARN、緩和は広げない）に持ち越し、解除の後の最初の要求は `PortalSessionMapper.touchCarriedOver` で touch する。portal の静的資源も同じ経路で通る。portal の GET の他の書込みは download の best-effort の監査だけ（U-10 で 503）。試験: `PortalSessionServiceImplTest`（新規）、`BackupWriteGateMockMvcTest`（静止中の portal の参照・静的資源が 200 で行が変わらず `readonly_sql` が増えない、本物の portal の session で logout が 503。2b の review P3-5）。BWQ-AC-002-10。

  - **実装の記録（第 2 段階 2c、2b の review の fix C）:** P2-1: `/api/**` の controller の中の拒否も `BackupQuiesceRejectionRecorder`（filter と共有）で `rejected_requests{kind}` を数え 1792 の WARN を間引く。P3-1（統括の決定）: bootstrap・break-glass の session の `POST /logout` も U-8（`design.md` L-21）。P3-2: NF05 の公開 API が OFF なら外部 API は静止中も平時の 404。P3-6: `deferred{logout}` は logout 1 回に 1。P3-7: 解除後の監査の時刻は未来 5 秒まで許す。試験は `GlobalExceptionHandlerTest`・`BackupWriteGateMockMvcTest`・`BackupQuiesceRequestClassifierTest`・`BackupQuiesceLogoutDeferralTest`・`AuditLogServiceImplTest`。

  - **依存:** BWQ-002、BWQ-004
  - **対応要求:** BWQ-RQ-002（AC-002-01・05・06・08・09）
  - **Objective:** 静止中の更新の要求を認証より前に 503 にし、参照は続け、logout は静止中も終わらせ、想定外の書込みも 503 で返す。利用者には翻訳された文言を出す。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupWriteGateFilter.java`・`BackupQuiesceRequestClassifier.java`・`BackupWriteQuiescedException.java`（新規）、`src/main/java/com/ses/common/util/AuditedDownloadPaths.java`（新規）、`ApiAuditFilter.java`、`SecurityConfig.java`（logout の handler と success handler）、`GlobalExceptionHandler.java`、`CustomErrorController.java`、`PersistentSessionServiceImpl.java`、`AuditLogServiceImpl.java`、`ExternalApiErrorWriter.java`・`ExternalApiCorrelationFilter.java`（correlation ID の作り方の共有）・外部 API の抑止の metric、`messages.properties`・`messages_en.properties`・`messages_ko.properties`・`messages_zh_CN.properties`、試験 `BackupQuiesceRequestClassifierTest.java`・`AuditedDownloadPathsTest.java`・`BackupWriteGateMockMvcTest.java`（新規）、`PersistentSessionServiceImplTest`・`AuditLogServiceImplTest`・`CustomErrorControllerTest`・`GlobalExceptionHandlerTest`・`ApiAuditFilter` の既存試験（追加）。
  - **Implementation:**
    1. 分類（`design.md` §6.3・§6.4）: ALWAYS / READ / WRITE / LOGOUT / EXTERNAL。download は WRITE と同じく 503（U-10）。
    2. filter は `@Order(Ordered.HIGHEST_PRECEDENCE + 35)`。WRITE は I-4 の順で permit を取り、取れなければ 503。chain 全体を包み、静止中の 1792 を 503 に変える。
    3. 503 の形（`design.md` §6.10）。外部 API（U-6）は `ExternalApiErrorWriter` の 503（retryable・Retry-After・error code `BACKUP_QUIESCED`）で、correlation ID は `ExternalApiCorrelationFilter` と共有の static method で作り、監査行は書かず `audit_suppressed_total`（理由 `BACKUP_QUIESCED`）を数える。
    4. logout（U-8。`design.md` §6.13）: permit を取れれば従来どおり。取れなければ HTTP session を直ちに無効にし、`revokeCurrent` の DB の書込みと `LOGOUT` の監査を、logout の時点の user・tenant・session の hash・時刻・correlation ID とともに（接続元の IP は `t_audit_log` に列が無いため対象外。`design.md` §6.13）解除後の書込みの一覧へ入れて WARN を出し、logout の完了の画面へ進める。解除後の監査は取っておいた値をそのまま書く `AuditLogService.recordCaptured` で書く。解除後に permit を取れなければ一覧へ戻す（D3-P3-5）。portal の logout（`/api/portal/auth/logout`）は対象外で WRITE と同じく 503。
    5. `GlobalExceptionHandler`: `BackupWriteQuiescedException` の handler、`handleBusinessException`・`handleException`・`handleLockConflict` の前で 1792 の判定。
    6. `validateAndTouch`（U-14。`design.md` §6.4）: 閉じていて permit が無ければ touch を省き、idle を gate が閉じた時刻で判定し、HTTP session の属性に静止中の最終利用を書く。解除の後の最初の要求は持ち越した時刻でも判定し、touch して属性を消す。
    7. `AuditLogServiceImpl.record`（U-9）: 閉じていて permit が無ければ DB へ書かず WARN `BACKUP_QUIESCE_AUDIT_DEFERRED` と metric。
    8. 文言 3 key を 4 bundle に足し、それぞれ翻訳する。
  - **Automated test:** `BackupQuiesceRequestClassifierTest`（method×path×session の表）。`BackupWriteGateMockMvcTest`（閉じた gate で: `/api/**` の POST が 503 の JSON と header、画面の POST が 503 のエラーページで文言が locale に従う、`/login` の POST が 503 で `sys_user.failed_count` が変わらない、download が 503、bootstrap session の GET が 503、一覧の GET が 200、静的資源・`/actuator/health/readiness`・`/actuator/prometheus` が通る、portal の chain と外部 API の chain でも同じ、**外部 API の 503 の本文が `ExternalApiErrorWriter` の形で `X-Correlation-ID` を持ち、監査行が増えず抑止の metric が増える**、OFF の機能は静止中も 404、**静止中の logout が 302 で終わり同じ JSESSIONID で 401 になり、解除の後に `t_user_session` の `revoked_at` と `LOGOUT` の監査が書かれ、監査の `created_at`・correlation ID が logout の時点の値である（IP は対象外）**、**解除後の書込みの途中で gate がまた閉じると、書込みは捨てられず次の解除で書かれる**、**portal の logout（`/api/portal/auth/logout`）は静止中 503 で `t_portal_session` は変わらない**。開いた gate では従来どおり）。`PersistentSessionServiceImplTest`: 閉じる前に有効だった session は、静止の間に idle 期限を過ぎても有効、**解除の後の最初の要求でも失効せず touch で `idle_expires_at` が延びる**、最大保持時間を超えた緩和は無い、`expires_at` は今の時刻で判定される。`AuditLogServiceImplTest`: 静止中の best-effort の監査が DB に入らず WARN に出る。`GlobalExceptionHandlerTest`: `BusinessException` に包まれた 1792 が 503。`MessageBundleConsistencyTest`。
  - **Demo:** dev profile の app で静止を手で入れ、ブラウザで一覧が見え、保存の操作で 503 の toast（日本語・英語の切替えで文言が変わる）が出て、login の画面で送信すると統一エラーページが出ること、logout が終わり解除の後に `t_user_session` が失効することを見る。PWA の送信は queue に残り、解除後に送られることを見る。
  - **失敗/rollback:** 既存の filter の順を変えない。503 の body が HTML になる経路があれば `CustomErrorController` の JSON 判定を見直す。

- [ ] **BWQ-006 — scheduler・async・起動時処理の接続**
  - **状態:** 実装・fast の試験は済み（commit `3ee708cd`、走査の規則に当たった時計の呼出しの修正 `8fc0b1a3`）。Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2b）:** `BackupQuiesceLockProvider`（`SchedulerLockConfig` の provider を包む。待った時は lock の設定の開始時刻を取り直す。待ちの上限は gate の単調時計で最長の保持時間）・`BackupQuiesceTaskDecorator`（`AsyncConfig`。`CallerRunsPolicy` と同じ拒否の handler で、executor の停止の後に捨てた task の permit を返す）。2a review P3-4: 状態機械の tick の中で `CallerRunsPolicy` が実行しかけた投入は実行せず「受け付けなかった」として次の tick へ回す。解除後の書込みは flush の thread が async の permit を持つため、gate が書込みを許さなければ入れ子でも書かずに戻す。`SchedulingDisabledDrainTest` の走査に `SmartInitializingSingleton`・`DisposableBean`・`DestructionAwareBeanPostProcessor`（com.ses の class が callback を実装したものだけ）を足し、既存の5件（`ProductionSchemaVersionGuard`・NF07 の4件）と静止応答の4件を理由つきで登録した。

  - **依存:** BWQ-002、BWQ-003
  - **対応要求:** BWQ-RQ-002（AC-002-03・04）
  - **Objective:** 静止中に job と async が書かず、閉じる前に始まったものは排水され、cron が飛ばず、閉じた後の async が thread を待たせず、停止の時に scheduler を待たせない。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupQuiesceLockProvider.java`・`BackupQuiesceTaskDecorator.java`（新規）、`SchedulerLockConfig.java`、`AsyncConfig.java`、試験 `BackupQuiesceLockProviderTest.java`・`BackupQuiesceTaskDecoratorTest.java`（新規）、`SchedulerLockProxyInvocationTest.java`・`SchedulerLockH2IntegrationTest.java`・`SchedulingDisabledDrainTest.java`（更新）。
  - **Implementation:**
    1. LockProvider の包み（`design.md` §6.6）。SHUTDOWN・割込みでは待たずに空を返す（割込みの印は戻す。D-P3-4）。
    2. TaskDecorator（`design.md` §6.7）。
    3. `SchedulingDisabledDrainTest` の `THREAD_API_INVENTORY`（静止応答の 2 つの `ScheduledExecutorService`）と `STARTUP_HOOK_INVENTORY`（静止応答の `SmartInitializingSingleton`・`ContextClosedEvent` の listener・破棄の callback）に理由つきで足す。`ScheduledExecutorService` は bean にしない。
  - **Automated test:** `BackupQuiesceLockProviderTest`（閉じている間は委譲されない、開くと委譲される、委譲が空なら permit を返す、unlock の後に permit を返す、permit を持つ thread は待たない、**SHUTDOWN と割込みで直ちに空を返す**）。`SchedulerLockProxyInvocationTest` に「閉じている間は `shedlock` の行が書かれず、開くと method が実行される」を足す。`BackupQuiesceTaskDecoratorTest`（閉じる前の投入は排水の対象、閉じた後の投入は保留され開いた時に実行される、`CallerRunsPolicy` でも待たずに戻る）。`ScheduledMethodsHaveSchedulerLockTest`（既存）。
  - **Demo:** dev profile の app を scheduler 有効で起動し、静止を手で入れて 1 分以上待つ。毎分の job が `shedlock` を更新しないことと、解除の後に実行されることを見る。静止中に app を止め、停止が scheduler の待ちで遅れないことを見る。
  - **失敗/rollback:** 待機が最大保持時間を超えないことを確かめる。

- [ ] **BWQ-015 — uploads の最後の防線（storage 層の確認と一覧の試験）**
  - **状態:** 実装・fast の試験は済み（commit `5efe3683`）。Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2b）:** `BackupWriteGate.assertUploadsMutationAllowed`（prod の UNARMED を含め、書込みを許さず permit が無ければ `BackupWriteQuiescedException`。filter の metric は `uploads`）。assert を置いた入口: `FileStorageServiceImpl`（保存・削除・再 scan）、`LocalDocumentStorage`（put・promote・delete・deleteStrict）、`ContractDocumentServiceImpl.create`（PDF）、`ImportStageReconciliationLedger`（write・remove）、`LegacyUploadMigrationService.migrate`、`FileCleanupServiceImpl.cleanupOrphanFiles`、一覧の試験で見つけた `ImportRawTempFileStore`（create は assert、deleteOrReport は「例外を投げない」契約のため静止中は消さずに孤児清掃へ委ねる）。`UploadsMutationInventoryTest` は 40 件（MUTATES 23・DIRECTORY_ONLY 9・OUTSIDE 8）を照合し、MUTATES は guard の method が gate の判定を呼ぶことも確かめる。`DocumentServiceImpl`・CloudSign の一時 file と NF01 の preflight の CLI は upload root の外。

  - **実装の記録（第 2 段階 2c、2b の review の fix C）:** P3-3: `BackupQuiesceProductionContextStartTest` を、読取り専用の接続の DML を 1792 で拒否する試験の driver（`com.ses.test.ReadOnlyEnforcingH2Driver`）で起動し、登録の前に業務の pool で DML を実行した bean が無いことを確かめる（`spring.sql.init` だけ外す。`design.md` L-13）。P3-8: `ImportRawTempFileStore.deleteOrReport` の静止の分岐を削除の失敗と同じ metric と file 名の log に揃えた（内容は切り詰めない）。

  - **実行順:** BWQ-006 の後・BWQ-007 の前
  - **依存:** BWQ-002、BWQ-006
  - **対応要求:** BWQ-RQ-013
  - **Objective:** DB に書かない file の変更を含め、permit の無い thread が静止中に uploads の file を変えられないことを保証し、prod の起動を妨げない。
  - **対象 file:** `BackupWriteGate.java`（`assertUploadsMutationAllowed`）、`FileStorageServiceImpl.java`、`LocalDocumentStorage.java`、`ContractDocumentServiceImpl.java`、`ImportStageReconciliationLedger.java`、`LegacyUploadMigrationService.java`、`FileCleanupServiceImpl.java`、upload root を変える残りの class（一覧の試験で確定する）、試験 `UploadsMutationInventoryTest.java`・`BackupQuiesceProductionContextStartTest.java`（新規）・各 storage の既存試験（追加）。
  - **Implementation:** `design.md` §6.12。一覧の分類は 3 つ（file を変える: assert あり／upload root の下で directory を作るだけ: assert なし（`LocalDocumentStorage` の constructor 等）／upload root の外: 理由を書いて assert なし）。D-P2-7。
  - **Automated test:** `UploadsMutationInventoryTest`（bytecode の走査で変更系の呼出しを列挙し既知の一覧と 3 分類で照合。一覧に無い呼出しの fixture で失敗することも確かめる）。storage の試験: 閉じた gate で permit の無い thread からの保存・移動・削除が `BackupWriteQuiescedException` になり file が変わらない、permit を持つ thread は変えられる、directory の作成は拒否されない、`ImportStageReconciler` の台帳の削除が scheduler の permit の下で動く。**`BackupQuiesceProductionContextStartTest`: prod の profile（`enabled=true`、H2 の schema、外部依存は mock）で context が起動し、UNARMED の間の `LocalDocumentStorage` の directory の作成が assert に当たらない**。
  - **Demo:** dev profile の app で静止を手で入れ、upload の POST が 503 になり、upload root の file の数が変わらないことを見る。
  - **失敗/rollback:** 一覧に理由の無い呼出しが残れば完了にしない。

- [ ] **BWQ-007 — prod の起動時検査・停止口・雛形・照合 script・§1.0**
  - **状態:** 実装・fast の試験・照合 script の自己試験（pwsh と Windows PowerShell 5.1 で `0 mismatched`）は済み（第 2 段階 2c）。Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2c）:** `BackupQuiesceProductionValidator`（`SmartInitializingSingleton`。静止応答が instance の登録の前にも呼ぶ。1 回だけ検査する）、`com.ses.common.jdbc.ConnectorJUrl`（`ImportJdbcReconnectGuard` の入れ子の enum を public にして移した。規則は同じ）。`readOnlyPropagatesToServer` は URL の query・data-source-properties の key（大文字小文字を問わない）・Connector/J の展開後の属性で見て、`true`・`yes` 以外は無効とみなし、複数 host・解析できない URL は拒否する。`@MockBean BackupQuiesceProductionValidator` は数え直しで `ProductionSecurityConfigurationTest` の 1 件（`enabled=false` を明示）。`BackupQuiesceProductionContextStartTest`（`enabled=true`）は置き換えずに実の検査が通ったことを確かめる。`BackupQuiesceTestContextInventoryTest` に「全ての `@SpringBootTest` が test profile（基底 class を含む）か明示を持ち、prod の `enabled=false` は `@MockBean` を持つ」を足した。`SchedulingDisabledDrainTest` の起動の hook の一覧に validator を足した。雛形に `BACKUP_QUIESCE_ENABLED=true` と `stopTimeout: 120`、照合 script に停止口の組・名前の綴り・secrets・正規名（`APP_BACKUP_QUIESCE_*`・`SPRING_LIFECYCLE_*`）・`SPRING_APPLICATION_JSON`・範囲・`startPeriod`・`stopTimeout` の検査、§1.0 に 31〜41 の行・設定してはいけない値・停止口の使い方を足した。**Runner 待ち**: Demo（dev の DB に prod 相当の設定の一部を与えた起動）。

  - **依存:** BWQ-002、BWQ-003、BWQ-004
  - **対応要求:** BWQ-RQ-008
  - **Objective:** 本番で最後の防線を消す JDBC 設定と範囲外の設定の起動を止め、停止口を ticket と組でだけ許し（U-7）、配備の雛形と照合で同じ条件と `startPeriod`・`stopTimeout` を確かめる。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupQuiesceProductionValidator.java`（新規）、`src/main/java/com/ses/common/jdbc/ConnectorJUrl.java`（新規。`ImportJdbcReconnectGuard` の入れ子の enum を移す）、`src/main/java/com/ses/importcenter/metadata/ImportJdbcReconnectGuard.java`、prod の profile で全 context を起動する試験（`application-test.yml` を import するもの。実装の時に grep で数え直し、`@MockBean BackupQuiesceProductionValidator` を足す）、`infra/ecs/task-definition.prod.json`（`BACKUP_QUIESCE_ENABLED=true`、`stopTimeout: 120`）、`infra/ecs/offline/validate-task-definition.ps1`・`test-validate-task-definition.ps1`、`src/test/java/com/ses/ops/ProductionTaskDefinitionTemplateTest.java`、`deploy-runbook.md` §1.0、試験 `BackupQuiesceProductionValidatorTest.java`・`ConnectorJUrlTest.java`・`BackupQuiesceTestContextInventoryTest.java`（新規）。
  - **Implementation:** `design.md` §12.2・§12.3。停止口は prod で ticket（`^[A-Z][A-Z0-9]+-[0-9]+$`）が無ければ中止、あれば DISABLED で起動し ERROR と gauge を出す（BWQ-003 の DISABLED の動き）。`readOnlyPropagatesToServer` は `ConnectorJUrl.effectiveProperties` で展開後の属性も見る。照合 script は範囲、`startPeriod >= startup-wait-max + 120`、`stopTimeout >= 90`、実値の mode の false と ticket の組を確かめる。§1.0 に「設定してはいけない値」・範囲・停止口の使い方を足す。
  - **Automated test:** `BackupQuiesceProductionValidatorTest`（prod で ticket の無い `enabled=false` は中止、形式の違う ticket も中止、正しい ticket なら起動して DISABLED と ERROR、`readOnlyPropagatesToServer=false`（URL・data-source-properties・展開後の属性）は中止、prod 以外の `enabled=false` は通る）。`ConnectorJUrlTest` と `ImportJdbcReconnectGuard` の既存試験。`BackupQuiesceTestContextInventoryTest`（全ての `@SpringBootTest` が test profile か `app.backup-quiesce.enabled` の明示を持つ）。`ProductionTaskDefinitionTemplateTest`（`BACKUP_QUIESCE_ENABLED=true`、`stopTimeout`）。`test-validate-task-definition.ps1` を pwsh と Windows PowerShell 5.1 で流し `0 mismatched`（値の違い・ticket の無い false・形式の違う ticket・secrets に置いた・正規名・`SPRING_APPLICATION_JSON` の上書き・範囲外・`startPeriod` の不足・`stopTimeout` の不足の負例、ticket つきの false の正例）。
  - **Demo:** dev の DB で prod 相当の設定の一部を与え、`readOnlyPropagatesToServer=false` の URL と ticket の無い `BACKUP_QUIESCE_ENABLED=false` で起動が port を開く前に日本語の message で止まり、ticket をつけると DISABLED で起動して instance の行の `gate_state` が `DISABLED` になることを見る。`validate-task-definition.ps1`（雛形の mode）が 0 で終わる。
  - **失敗/rollback:** prod の試験が止まる場合は `@MockBean` の付け漏れを直す（validator を弱めない）。

- [ ] **BWQ-008 — Flyway job の DDL 凍結 callback**
  - **状態:** 実装・fast の試験は済み（第 2 段階 2c）。MySQL の試験と Demo は Runner 待ち。

  - **実装の記録（第 2 段階 2c）:** `ProductionBackupDdlFreezeFlywayCallback`（`BEFORE_MIGRATE`・`BEFORE_REPAIR` で専用の接続の `GET_LOCK('ses_backup_ddl_freeze', 120)`、1 以外は日本語の message で失敗、`AFTER_MIGRATE`・`AFTER_MIGRATE_ERROR`・repair の対で `RELEASE_LOCK` と接続の close）。Flyway は callback を名前の昇順で動かすため、名前 `BackupDdlFreezeLock` で初期管理者 hash の検査の後・`LegacyDatabaseFlywayCallback` の前にした（`design.md` §8 の実装の記録）。`ProductionFlywayJobConfig`（試験の helper）を `com.ses.config` の MySQL 試験から使うため public にした。runbook §3.3・§3.5 に lock と失敗時の対応を書いた。**Runner 待ち**: `ProductionBackupDdlFreezeFlywayCallbackMySqlTest`（mysql shard 3）、`FlywayProdFreshInstallMySqlTest`（job 設定に callback が増えた）と Demo。

  - **依存:** BWQ-001
  - **対応要求:** BWQ-RQ-006
  - **Objective:** 本番の Flyway job と backup の静止区間が重ならないことを、runbook の timer の停止に加えて機械で保証する（U-5）。
  - **対象 file:** `src/main/java/com/ses/config/ProductionBackupDdlFreezeFlywayCallback.java`（新規）、`ops/flyway/flyway-prod.conf`（`flyway.callbacks` の先頭）、`src/test/java/com/ses/migration/ProductionFlywayJobConfigurationTest.java`、`src/test/java/com/ses/config/ProductionBackupDdlFreezeFlywayCallbackTest.java`・`ProductionBackupDdlFreezeFlywayCallbackMySqlTest.java`（新規、`@Tag("mysql")`）、`scripts/test-suites/mysql-shard-3.txt`、`deploy-runbook.md` §3・§3.5。
  - **Implementation:** `design.md` §8。
  - **Automated test:** `ProductionFlywayJobConfigurationTest`（callback の順）。`ProductionBackupDdlFreezeFlywayCallbackTest`（event の対応、失敗の message）。`ProductionBackupDdlFreezeFlywayCallbackMySqlTest`（別の session が lock を持つと migration が 1 件も適用されず日本語の message で失敗、空いていれば適用し、終了後に `IS_FREE_LOCK('ses_backup_ddl_freeze')` が 1）。`FlywayProdFreshInstallMySqlTest`（既存）。
  - **Demo:** 検証用の空の MySQL で、別の client が `SELECT GET_LOCK('ses_backup_ddl_freeze', 600);` を持つ間に `mvn -B -o -Pprod-migrate clean compile flyway:migrate`（検証用の `FLYWAY_*`）を流し、適用 0 件で日本語の message で止まることを見る。lock を返してから流し直すと適用される（本番ではない DB だけで行う）。
  - **失敗/rollback:** lock を返さずに job が終わる経路があれば、接続の close で必ず返ることを確かめる。

- [ ] **BWQ-009 — backup tool: `quiesce-db` provider・保持 process・verify**
  - **状態:** 実装・shell の試験（tool image の中）は済み（第 2 段階 2d）。Demo（`run-unit-tests.sh`。image の build を含む）と、SQL を実 MySQL 8.4 で動かす BWQ-011・BWQ-012 は Runner 待ち。

  - **実装の記録（第 2 段階 2d）:** `providers/quiesce-db.sh`（`acquire|verify|release|hold`）・`lib/quiesce-db-common.sh`・`providers/quiesce-db.d/*.sql`（11 file）。判定（必要な instance の分類・ack・hold 期限・整合時刻・停止口）は SQL の `VERDICT` の行が出し、shell は観測した接続元の照合（文字列の分解）・時間の上限・state dir の印だけを見る（同じ SQL を BWQ-011 が実 MySQL で実行して固定するため）。SQL の引数は `int`・`hex`・`enum` の型で検査して `SET @p_…` で渡し（`requested_by` は hex にする）、string の user 変数を列と比べない（collation の衝突を避ける。要求は番号で指す）。整合時刻は `quiesce.json` の `started_at_utc`（秒、従来の書式）と `started_at_epoch_us`（DB の epoch の μ秒。verify の `acked_at <= 整合時刻` に使う）。保持 process は `quiesce::_start_hold` が `exec {QUIESCE_HOLD_FD}> >(…)` で起動し、DDL lock の session（mysql）を loop の右の pipe で持つ（`DO 0;` で idle を保ち、失えば `ddl-lock.lost`）。release は要求を解いた後に `release.requested` で保持 process を終わらせ（lock の返却）、その後で再開を確かめる。D4-P3-1 は「source の時に `exec {QUIESCE_HOLD_FD}>/dev/null` で初期化する」方式（環境変数の値は使わない）。D4-P3-4 は `backup_full::dump`（background・`if ! wait`・kill の後に wait・`{QUIESCE_HOLD_FD}>&-`）。DDL lock を待つ秒数は固定の 30 秒（`QUIESCE_DB_LOCK_WAIT_SECONDS`。§12.4 に変数を足さない）。静止区間の秒数と内訳は provider によらず `quiesce::step` で記録し、`QUIESCE_WINDOW_WARN_SECONDS` を超えたら WARN。preflight の `checks.quiesce`（exit 19）は `QUIESCE_PROVIDER` が DB の provider の時だけ。`full-backup-test.sh`・`binlog-checkpoint-test.sh` は image に写した `preflight.sh`・`snapshot-binlog.sh` ではなく repository の版を使うようにした。

  - **依存:** BWQ-001
  - **対応要求:** BWQ-RQ-001、BWQ-RQ-003、BWQ-RQ-004、BWQ-RQ-006、BWQ-RQ-007、BWQ-RQ-010、BWQ-RQ-014
  - **Objective:** backup の job が DB の信号表で app の静止を取得・確認・解除し、延長と DDL lock を job 本体に結び付け、途切れた静止・登録の割込み・必要な instance の欠け・後からの ack・停止口・full の最中の新しい instance を VALID にしない。
  - **対象 file:** `ops/backup/providers/quiesce-db.sh`（`acquire|verify|release|hold`）・`ops/backup/lib/quiesce-db-common.sh`・`ops/backup/providers/quiesce-db.d/*.sql`（新規）、`ops/backup/lib/quiesce.sh`（振り分け、`quiesce::verify`、保持 process を `exec {fd}> >(…)` で起動し release で閉じる）、`ops/backup/providers/quiesce-local.sh`（変えない。`verify` は常に成功）、`ops/backup/backup-full.sh`・`ops/backup/create-checkpoint.sh`（verify の呼出し、heartbeat の directory の必須を `quiesce-local` だけに、静止区間の秒数と内訳の記録と WARN、full の `NEW_INSTANCE` の中止の message）、`ops/backup/lib/content-hash.sh`（信号表の拒否）、`ops/backup/preflight.sh`（`checks.quiesce`、exit 19）、`ops/backup/tests/fixtures/bin/mysql`（`ses-backup:quiesce-db:*` の応答）、`ops/backup/tests/quiesce-db-test.sh`（新規）、`ops/backup/tests/full-backup-test.sh`・`binlog-checkpoint-test.sh`・`script-contract-test.sh`・`preflight-test.sh`・`run-unit-tests.sh`。
  - **Implementation:** `design.md` §7.1〜§7.3・§7.6・§12.4。手順は「保持 process の起動と DDL lock → 事前の確認 → 要求の INSERT と commit → 待ちの毎回に新しい transaction で分類し直す（必要な instance 1 つ以上）」。verify は `verify_requested_at` を書き、観測し直しを待ってから、要求の前からの instance の `acked_at <= started_at_utc`、STOPPED でない DISABLED の行が無いこと（heartbeat の古さによらない。D3-P2-3）、hold 期限、観測を評価する。release は、この要求に QUIESCED の ack を持ち heartbeat が新しい instance にだけ RESUMED を求める（D3-P3-4）。保持 process は起動の直後から 1 秒ごとに pipe を読み（DDL lock の待ちの間も）、EOF で DDL lock の session を閉じて終わる。保持 process の stdout・stderr は state dir の `hold.log` へ向ける。job 本体の長い子 process（mysqldump・`cp -a`・restic 等）は `{QUIESCE_HOLD_FD}>&-` で起動する（D3-P3-2）。延長の上限は `QUIESCE_LEASE_MAX_SECONDS` と instance の `max_hold_*` の小さい方。**`mode=ONLINE` かつ `job_kind=FULL` のときだけ** 5 秒ごとに新しい instance を調べ、見つけたら要求を `ABORTED`（`NEW_INSTANCE`）にして中止の印を書く（U-13・D3-P2-2）。`backup-full.sh` は mysqldump を background で動かして 5 秒ごとに中止の印を確かめ、印があれば mysqldump を止めて直ちに失敗し、他の step の間でも `quiesce::check_aborted` で確かめる（D3-P3-4）。時刻は全て `UTC_TIMESTAMP(6)`。SQL の値は検査した整数・hex・列挙だけを `SET @p_…` で渡す。
  - **Automated test:** `quiesce-db-test.sh`: 全 ack で取得成功と `quiesce.json`、状態不明の instance で失敗、停止済みは無視、新しい instance 0 件で失敗（`--app-stopped` の案内）、REFUSED・RESUMED・DISABLED で即失敗、事前の確認と INSERT の間に instance が増えた場合に ack を求めること、**登録の後に唯一の instance が STOPPED になり必要な instance が 0 になると失敗すること**、**要求の前からの instance が整合時刻の後に ack すると verify が失敗すること**、要求の後に登録した instance が checkpoint の最中に後から ack しても通ること、`hold_until` の超過・延長の停止・観測し直しの欠け・host の不一致で verify 失敗、閾値の式・protocol の版の不整合で失敗、DDL lock の競合で要求を登録しない、**release が heartbeat の新しい instance にだけ RESUMED を求め、落ちた instance は WARN にすること**、**release が ack の行の無い新しい instance（U-13 の中止の時）を待たず exit 3 を出さないこと**、**job 本体を kill すると保持 process が終わり lock が返ること（mysqldump の最中・DDL lock の取得の待ちの間を含む）**、**acquire の process が終わっても保持 process が延長を続けること**、**保持 process の出力が結果の JSON に混ざらず `hold.log` に出ること**、**ONLINE の full の最中に新しい instance を足すと要求が `ABORTED`（`NEW_INSTANCE`）になり、job が mysqldump を止めて 10 秒以内に非 0 で終わること**、**APP_STOPPED の full の最中に新しい instance が登録されても要求は `ABORTED` にならないこと（負例。D3-P2-2）**、**heartbeat が 300 秒より古い STOPPED でない DISABLED の行があると ONLINE の acquire・verify が失敗し、片付けの SQL の後は通ること（D3-P2-3）**、延長の上限で延長が止まること。`tool-image-test.sh`: tool image の bash が 4.4 以上であること。`script-contract-test.sh`: `lib/quiesce.sh` を読む script の長い子 process の起動が `{QUIESCE_HOLD_FD}>&-` を持つこと。`full-backup-test.sh`・`binlog-checkpoint-test.sh`: verify が解除の直前に呼ばれる、verify の失敗で index に VALID が無い。`script-contract-test.sh`: provider の列挙、`bash -c` の不使用、パイプの後段の規則、信号表の SQL に `NOW(` が無い。content hash: `sys_backup_quiesce_ack` の指定で失敗。preflight: 表単位の権限が無い・DISABLED の instance があると exit 19。
  - **Demo:** `ops/backup/tests/run-unit-tests.sh`（tool image の中）の結果を見る。
  - **失敗/rollback:** 既存の `quiesce-local` の試験（`quiesce-lock-test.sh`）を壊さない。tool image の content ID が変わるため、配布の pin のし直しを BWQ-013 の runbook に書く。

- [ ] **BWQ-010 — backup tool: `quiesce-app-stopped` と `--app-stopped`**

  - **依存:** BWQ-009、BWQ-003（起動の拒否・停止の順・DISABLED）
  - **対応要求:** BWQ-RQ-005
  - **Objective:** app が動いていない時の backup（初回・配備の時・保守）を、誤用できない形で行えるようにする（U-2: PROCESS は誰にも与えず DBA の手の確認を残す）。
  - **対象 file:** `ops/backup/providers/quiesce-app-stopped.sh`（新規）、`ops/backup/providers/quiesce-db.d/stopped-*.sql`（新規）、`ops/backup/lib/quiesce.sh`（環境変数からの選択の拒否）、`ops/backup/backup-full.sh`・`create-checkpoint.sh`（`--app-stopped`）、`ops/backup/tests/quiesce-db-test.sh`（追加）、`full-backup-test.sh`。
  - **Implementation:** `design.md` §9。保持を先に登録して commit し、その後で分類し直す。`tool-image.sh compose run --rm backup --app-stopped` で渡せることを確かめる（`tool-image.sh` は変えない）。
  - **Automated test:** 新しい heartbeat があれば取得しない（DISABLED の instance を含む）、**heartbeat が 300 秒より古くても STOPPED でない DISABLED の行があれば取得しない、片付けの SQL の後は取得する（D3-P2-3）**、表が空なら取得する、STOPPED と停止済みは通る、有効な要求があれば失敗、保持の登録と instance の登録が重なる場合に失敗する、保持中の instance の登録・heartbeat で verify 失敗、**APP_STOPPED の full の最中に起動しようとした instance（登録して保持を読み、起動を中止して STOPPED を書く）では保持は `ABORTED` にならず、full は VALID になる（D3-P2-2 の負例）**、`QUIESCE_PROVIDER=quiesce-app-stopped` を環境変数で与えると full・checkpoint が始まらない。app 側の起動の拒否と STOPPED の順は BWQ-003 の MySQL 試験。
  - **Demo:** dev の DB で app を止め、`--app-stopped` の full を unit の fixture で流して `quiesce.json` の `mode: APP_STOPPED` を見る。保持中に app を起動して `BACKUP_QUIESCE_STARTUP_ABORT` で止まることを見る。
  - **失敗/rollback:** 定期の job から停止確認が選べる経路が 1 つでも残れば完了にしない。

- [ ] **BWQ-011 — provider と app の protocol の MySQL 契約試験**

  - **依存:** BWQ-003、BWQ-009、BWQ-010
  - **対応要求:** BWQ-RQ-001、BWQ-RQ-003、BWQ-RQ-004、BWQ-RQ-007、BWQ-RQ-012、BWQ-RQ-014
  - **Objective:** shell の provider と Java の responder が同じ表・状態・時刻の意味で動くことを、実 MySQL 8.4 で固定する（U-4）。
  - **対象 file:** `src/test/java/com/ses/backup/quiesce/BackupQuiesceProviderContractMySqlTest.java`（新規、`@Tag("mysql")`）、`scripts/test-suites/mysql-shard-3.txt`。
  - **Implementation:** container に V202 と業務の表 1 つを作り、README「source 側の account の権限」と同じ権限だけの user（schema の `SELECT`、表を作った後に要求の表の表単位の `INSERT`・`UPDATE`）を作る（表を作る前の表単位の GRANT が `CREATE` 無しで失敗するかも記録する）。`ops/backup/providers/quiesce-db.d/*.sql` を JDBC（その user。session の time_zone `+00:00`）で順に実行し、実の responder（session の time_zone `+09:00`）と往復させる。
  - **Automated test:** acquire → ack → verify 成功 → release → RESUMED の正常系、延長を止めた・responder を止めた・instance を追加した場合の verify の失敗、事前の確認と INSERT の間に別の instance を登録する場合と要求の後に登録する場合、**必要な instance が 0 になる場合の失敗**、**要求の前からの instance が整合時刻の後に ack する場合の失敗**、**ONLINE の full の最中に新しい instance を登録すると要求が `ABORTED`（`NEW_INSTANCE`）になり responder の gate が開く**、**APP_STOPPED の full の最中に responder を起動すると、保持は `ABORTED` にならず responder は起動を中止する**、**DISABLED の responder で ONLINE も停止確認も失敗し、その行の heartbeat を 300 秒より古くしても失敗し続け、片付けの SQL の後は通る**、QUIESCED の commit の後に responder の接続を切る場合に responder が `hold_until` より前に開かないこと、その user で業務の表への INSERT が拒否されること、APP_STOPPED の保持中に responder の起動が止まること。
  - **Demo:** 試験の log に、各 SQL file の実行と ack の状態の推移が日本語で出ることを見る。
  - **失敗/rollback:** SQL file の列名・状態の文字列が Java とずれたら、両側の試験を同じ変更で直す。

- [ ] **BWQ-012 — CI の backup integration を DB の protocol へ切り替える**

  - **依存:** BWQ-009、BWQ-010
  - **対応要求:** BWQ-RQ-002（AC-002-02 の backup 側）、BWQ-RQ-012
  - **Objective:** 実 MySQL 8.4 の PITR の試験で、新しい provider が DB の protocol を通って full と checkpoint を取ることを CI で確かめる（U-4）。
  - **対象 file:** `ops/backup/tests/docker-compose.integration.yml`（`scheduler`・`replica` を `responder` に置き換える）、`ops/backup/tests/run-integration.sh`（service の一覧・識別子・掃除）、`ops/backup/tests/integration-pitr.sh`（source に V202 を適用、表単位の GRANT、最初の full を `--app-stopped`、responder の起動、checkpoint を `quiesce-db`、responder を止めた負例）、`ops/backup/tests/harness-selftest-test.sh`、`.github/workflows/ci.yml`（変更が要る場合だけ）。
  - **Implementation:** responder は tool image の中の shell の loop で、app と同じ SQL（instance の登録・heartbeat・要求の読取り・ack・RESUMED。時刻は `UTC_TIMESTAMP(6)`）を mysql client で実行する（専用の合成 user）。V202 の file は repository から WORK へ写して source に適用する。
  - **Automated test:** `run-integration.sh` が既存の marker の照合を全て通し、証跡に `quiesce.json`（`mode`・`request_id`・`verified_at_utc`）が残る。負例: responder を止めた checkpoint が非 0 で終わり、index に VALID が増えない。生成列 `active_slot` を含む V202 の表が dump・復元・schema fingerprint の照合を通ることを確かめる（D-P3-7）。mysqldump 8.4 の起点の採取の lock の待ちを記録する（`design.md` §19）。
  - **Demo:** local の Docker で `ops/backup/tests/run-integration.sh` を流し、evidence の `quiesce.json` と marker の結果を見る（Runner の実行は統括が決める）。
  - **失敗/rollback:** 所要時間が大きく増えたら responder の間隔を見直す。`MySqlTestImagesTest` が integration の image の参照を拒否しないこと。

- [ ] **BWQ-013 — 文書（runbook・README・監視・restore・baseline・pii-inventory）**

  - **依存:** BWQ-001〜BWQ-012・BWQ-015（文書は各 task の内容に合わせて最後にまとめる。各 task で直した節はその task で直してよい）
  - **対応要求:** BWQ-RQ-007、BWQ-RQ-011、BWQ-RQ-014
  - **Objective:** 配備・運用・復元の手順が実装と一致し、権限を付ける時期・署名・PROD-004 の解除に要る証跡・欠けた full の取り直しが明確になる。
  - **対象 file:** `.kiro/specs/production-launch/deploy-runbook.md`（冒頭の一覧、§1.0、§1.2、§1.7 の「Backup 開始の前提」・DBA-1・署名一覧（U-11 の Security・DBA）・NF05 の行（U-6 の NF05 owner の受理）、§1.8 の RR-10 の例外の一覧（`design.md` §9.4 の保持の手での終了と停止口の行の片付けの 2 つの SQL。D3-P2-3）、§2 の0・4・5、§3・§3.4・§3.5、§3.3、§4（instance の確認と障害の切分け）、§5）、`ops/backup/README.md`（設定・実行・権限分離・静止の説明（origin/main `42719b10` の 298〜299 行）・欠けた full の取り直し・テスト）、`ops/backup/runbooks/backup-health-monitoring.md`（critical・warning の対応、full の中止の取り直し）、`ops/backup/runbooks/restore-cutover.md`（信号表の残りと、backup account と表単位の権限の付け直し）、`.kiro/specs/database-backup-recovery/baseline.md`（PROD-002 の writer の記録、PROD-004 の 004a・004b）、`.kiro/specs/database-backup-recovery/design.md`（§5.1 の参照 1 行）、`.kiro/specs/privacy-retention-dsar/pii-inventory.md`（DB-148〜DB-150）。
  - **Implementation:** `design.md` §15 の表のとおり。表単位の GRANT は §3.4 の後・初回 backup の前（`design.md` §4）。§1.7 の解除の条件は 004a を初回 backup の前、004b を §4 の後とする。004a に staging の EFS の snapshot の実測（U-1）と `stopTimeout` を含める。§2 の4 に STOPPED が無い場合の 300 秒の待ちを書く。欠けた full の取り直しは「中止を知ったら 2 時間以内（前日の full から 26 時間で full の CRITICAL になるため、当日のおよそ 04:00 まで）」と書く（D3-P3-4）。停止口の行の片付けの SQL は、ECS の task の停止と processlist の 0 件を確かめてから行う手順として書く。runbook の文言は既存の書き方（「未取得」「決定済み（日付）」）に合わせる。
  - **Automated test:** `DeployRunbookMigrationCountTest`、`MessageBundleConsistencyTest`（既存）、`ops/backup/tests/script-contract-test.sh`。文書の中の file・行番号の参照が実在することを目で確かめる（行番号は origin/main の値で書く）。
  - **Demo:** §2〜§4 の手順を上から読み、BWQ-014 の通しの Demo と同じ順（timer の停止 → drain → 停止 → GRANT の確認 → `--app-stopped` の full → Flyway → 起動 → timer の再開 → 最初の ONLINE checkpoint）になっていることを確かめる。
  - **失敗/rollback:** 新しく書く文は日本語の用語（統合・リリース・本番）を使う。既存の節の記述と矛盾したら、正本（§1.0・README の表）を先に直す。

- [ ] **BWQ-014 — 隔離環境での通しの Demo と証跡**

  - **依存:** BWQ-001〜BWQ-013・BWQ-015。本番の前に SRE が staging の EFS で uploads の snapshot の時間を測る（U-1。`design.md` §10.1）
  - **対応要求:** 全体（特に BWQ-RQ-002・003・005・010・014）
  - **Objective:** 初回 backup（停止確認）→ app 起動 → 15 分 checkpoint（ONLINE）→ 解除の失敗と自動再開 → full の最中の置換え → restore の後の起動、の一連が隔離環境で runbook どおりに動くことを示す。
  - **対象 file:** なし（証跡は repository の外。`target/backup-recovery-evidence/<run-id>/RB-QUIESCE/` を推奨）。
  - **Implementation / Demo:**
    1. 空の MySQL 8.4 に Flyway（dev）で V1〜V202 を適用し、backup account に表単位の権限を付け、app を起動しない状態で `--app-stopped` の full を取る。
    2. app（dev、scheduler 有効）を起動し、instance の行を確かめる。
    3. app に書込みの負荷（marker の INSERT を続ける script と upload）をかけながら checkpoint を取り、ack の後に marker と uploads が増えないこと、解除の後に増えることを確かめる。静止中に logout し、解除の後に `t_user_session` が失効することを確かめる。静止区間の秒数と内訳、503 の件数（metric）を記録する。
    4. backup の job を静止の最中に kill し、保持 process が終わり、app が lease で再開し `AUTO_RESUMED` が出ることを確かめる。
    5. checkpoint の最中に app を再起動し、新しい instance が ack して checkpoint が VALID になることを確かめる。ONLINE の full の最中に app を再起動し、full が `NEW_INSTANCE` で中止され、job が mysqldump を止めて早く失敗し、app が 1〜2 分で起動し、手で取り直した full が VALID になることを確かめる。`--app-stopped` の full の最中に app を起動し、保持が解かれず app が起動を中止することを確かめる。
    6. 停止口（ticket つきの `enabled=false`）で起動し、checkpoint と `--app-stopped` の full の両方が失敗することを確かめる。その app を SIGKILL で止め、300 秒を過ぎても `--app-stopped` の full が失敗し、片付けの SQL の後は通ることを確かめる。
    7. checkpoint から隔離の recovery target へ復元し、backup account と権限を付け直し、app を起動して残った要求が期限切れとして無視されることを確かめる。
  - **Evidence:** 各 step の log の要約（errorCode・request_id・秒数）、`quiesce.json`、metric の抜粋、marker の照合結果。個人データ・秘密を含めない。
  - **失敗/rollback:** 1 つでも期待と違えば該当する task へ戻る。本番の環境では行わない。

## 実装規模の見込み

- 約 122 file（Java の main 約 42、試験 約 36、resources 約 8、ops/backup 約 24、infra 3、文書 約 11）、15 task（`design.md` §18）。
- 主な危険: I-3・I-4 の順の実装の誤り（負荷の試験で固定する）、業務の DataSource の包み・破棄の順と Spring Boot の自動構成・metric の相互作用、状態機械を塞がない実装、保持 process の終わり方（job の SIGKILL で EOF）、想定外の GET の書込みと locking read（静止中に 503）、uploads の増加による静止時間の伸び（約 3,000〜5,000 件で上限）、長い処理と重なった checkpoint の欠落、full の中止による full の欠け、logout の解除後の書込みの喪失、full の起点の採取の lock の待ち、tool image の pin のし直し、表単位の GRANT の時期と restore の後の付け忘れ。
