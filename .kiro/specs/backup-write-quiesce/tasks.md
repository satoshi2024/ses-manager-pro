# backup の書込み静止（app 内の静止応答）実装タスク

## 実行規約

- 本書は第 1 段階（spec）で書いた。実装は spec の独立 review が PASS した後、統括の指示で始める。
- task は ID 順に行う。依存する task が終わっていない・BLOCKED のまま後の task を完了にしない。
- `design.md` §16 の未決事項は、利用者の決定を受けてから該当する task を始める（U-1・U-3 は BWQ-002、U-2 は BWQ-010、U-4 は BWQ-012、U-5 は BWQ-008）。決定が推奨と違えば、先に `design.md` とこの task を直す。
- checkbox は、実装・自動試験・Demo が全て終わった時だけ `- [x]` にする（CLAUDE.md の spec の手順）。
- 試験は各 task の対象だけを流す（`mvn test -Dtest=…`、MySQL の試験は `-Pmysql-tests -Dtest=…`、backup tool は `ops/backup/tests/<file>.sh`）。全 gate（`scripts/verify-like-ci.*`）の実行の時期は統括が決める。migration の file を足した・名前を変えた後は `mvn clean test` にする（classpath の古い写しを読まないため）。
- 実 AWS・本番の DB・本番の credential を試験と Demo に使わない。Demo は local の MySQL 8.4（`dev` profile）か Testcontainers・backup の integration の compose で行う。
- repository に書く文字は日本語（UI・log・comment・commit）。message bundle は 4 つとも翻訳する。
- 版番号 V202 は、着手時に `src/main/resources/db/migration` と `db/migration-dev` の merged の状態と全 branch で再確認する（`design.md` §3.5）。

## Task 一覧

- [ ] **BWQ-001 — 信号表の migration・H2 の schema・版番号の連動**

  - **依存:** なし
  - **対応要求:** BWQ-RQ-001
  - **Objective:** 静止の要求・instance・ack の 3 表を、本番の MySQL 8.4 と試験の H2 の両方に同じ形で作り、版番号に連動する文書と試験を合わせる。
  - **対象 file:** `src/main/resources/db/migration/V202__backup_write_quiesce_signal.sql`、`src/test/resources/sql/schema-backup-quiesce-h2.sql`（新規）、`src/test/resources/application-test.yml`（`schema-locations` の V201 の変種の後）、`src/test/resources/sql/engineer-schema-h2.sql`、`src/test/java/com/ses/migration/LatestMigrationVersion.java`、`src/test/java/com/ses/migration/BackupQuiesceSchemaContractTest.java`（新規）、`.kiro/specs/production-launch/deploy-runbook.md`（§3.3・§3.4・§4 の schema guard の log・§1.7 の `FLYWAY_VERSION`・冒頭の実装の一覧）。
  - **Implementation:**
    1. `design.md` §3.1 の列・制約・索引で V202 を書く（日本語の COMMENT。trigger・routine は作らない）。
    2. H2 の変種は `CREATE TABLE IF NOT EXISTS`、COMMENT なしで同じ列・CHECK・一意索引・FK にする。`engineer-schema-h2.sql` は ack → instance → request の順に DROP して作る。
    3. `LATEST` を `202` にし、runbook の版と件数（versioned 201 件・SQL 200 件）を直す。
  - **Automated test:** `MigrationScriptIntegrityTest`、`DeployRunbookMigrationCountTest`、`BackupQuiesceSchemaContractTest`（V202 と H2 の 2 定義の列名・型・NULL 可否・CHECK・一意索引の一致）、`FlywayProdFreshInstallMySqlTest`（`-Pmysql-tests`。空の MySQL 8.4 へ V1〜V202 が通り、routine 0・trigger 67 のまま）。H2 の一意索引の試験: 有効な要求を 2 件入れると失敗する、`status='RELEASED'` で `active_slot=1` は CHECK で拒否。
  - **Demo:** local の MySQL 8.4 の空の DB で `mvn spring-boot:run`（dev）を起動し、`SHOW CREATE TABLE sys_backup_quiesce_request\G` 等で 3 表と制約を確かめ、`flyway_schema_history` の最大が 202 であることを見る。
  - **失敗/rollback:** 版番号が衝突したら上の空き番号へ振り直す（空き番号を埋めない）。適用済みの migration を書き換えない。

- [ ] **BWQ-002 — 書込み gate と設定（core）**

  - **依存:** BWQ-001、U-1・U-3 の決定
  - **対応要求:** BWQ-RQ-002、BWQ-RQ-003、BWQ-RQ-004、BWQ-RQ-008、BWQ-RQ-010
  - **Objective:** 状態遷移・permit・排水・hold 期限・self-fence を、Spring にも DB にも依らない 1 つの部品で正しく実装する。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupWriteGate.java`・`BackupWritePermit.java`・`BackupQuiesceProperties.java`・`BackupQuiesceMetrics.java`（新規）、`src/main/resources/application.yml`（`app.backup-quiesce.*`）、`src/test/resources/application-test.yml`（`enabled: false`）、試験 `BackupWriteGateTest.java`・`BackupQuiescePropertiesTest.java`（新規）。
  - **Implementation:**
    1. 状態 `OPEN`・`CLOSING`・`CLOSED`・`FENCED`（`design.md` §6.2）。permit の種類 HTTP・SCHEDULER・ASYNC・STARTUP を thread に結び付け、入れ子を許す。取得は「増やしてから状態を読み直し、OPEN でなければ戻す」。
    2. 書込み可能な業務の接続の数を持つ（BWQ-004 が増減する）。排水の完了条件は「permit の合計 0 かつ書込み可能な接続 0」。上限は job_kind ごと。
    3. hold 期限（単調時計）と fence の判定は、gate を参照する側で毎回評価する（静止応答の thread が止まっても働く）。hold 期限の延長は「`hold_until` を書けた後」に呼ぶ API にする（`design.md` §3.3）。
    4. 設定の範囲と相互の関係（`design.md` §12.1）を `@Validated` で検査する。`enabled=false` では常に OPEN で fence も無い。
    5. metric（`design.md` §13.2）を登録する。
  - **Automated test:** `BackupWriteGateTest`: 状態遷移の全経路、100 thread で permit を取りながら閉じても閉じた後に新しい permit が出ないこと、排水の上限で REFUSED になり OPEN へ戻ること、hold 期限で再開すること、fence の閾値を超えると FENCED になり heartbeat の回復で OPEN に戻ること（有効な要求があれば戻らない）、入れ子の permit。`BackupQuiescePropertiesTest`: 範囲外・順序の誤りで bind が失敗する。
  - **Demo:** 試験の結果（状態遷移の表を `@ParameterizedTest` で出した一覧）を確かめる。
  - **失敗/rollback:** 競合の試験が 1 回でも失敗したら設計（増やしてから読み直す順）を見直す。`enabled=false` で既存の試験の挙動が変わらないことを、BWQ-005 までに確かめる。

- [ ] **BWQ-003 — 制御用 pool・信号表の repository・静止応答（responder）**

  - **依存:** BWQ-001、BWQ-002
  - **対応要求:** BWQ-RQ-001、BWQ-RQ-002、BWQ-RQ-003、BWQ-RQ-004、BWQ-RQ-005
  - **Objective:** app が信号表を通じて要求を受け、排水・ack・再開・自動再開・self-fence・起動の待機と拒否・停止の記録・保存期間の削除を行う。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupQuiesceControlConnectionPool.java`・`BackupQuiesceSignalRepository.java`・`BackupQuiesceResponder.java`・`BackupQuiesceConfiguration.java`（新規）、試験 `BackupQuiesceResponderTest.java`・`BackupQuiesceControlPoolUsageTest.java`（新規）・`BackupQuiesceResponderMySqlTest.java`（新規、`@Tag("mysql")`）、`scripts/test-suites/mysql-shard-3.txt`。
  - **Implementation:**
    1. 制御用 pool は `DataSourceProperties` から作る `HikariDataSource`（最大 2）を内部に持つ holder にし、`DataSource` の bean にしない（`design.md` §6.5.4）。
    2. repository の SQL は全て `NOW(6)` と SQL の比較で時刻を扱う。PROCESSLIST の観測（`design.md` §5.4）、hold 期限の計算（`LEAST(lease_expires_at, requested_at + 最大保持時間)`）、ack の 1 transaction の書込み。
    3. responder: `afterSingletonsInstantiated` で登録・最初の heartbeat・thread の開始・startup permit、有効な要求があれば ONLINE は待機（`startup-wait-max`）、APP_STOPPED は起動を中止（`design.md` §6.8）。`ApplicationReadyEvent` で RUNNING と permit の返却。1 秒ごとの要求の読取り、5 秒ごとの heartbeat、要求の処理（CLOSING → 排水 → ack / REFUSED）、lease の延長の反映（ack の `hold_until` を先に書く）、解除と自動再開（RESUMED と理由）、静止中の観測の更新、1 日 1 回の保存期間の削除。graceful shutdown の最後に STOPPED（CLOSED のままなら RESUMED を書かない）。
    4. log は `design.md` §13.1 の固定の errorCode で日本語にする。
  - **Automated test:** `BackupQuiesceResponderTest`（repository を fake にして、要求→ack、REFUSED、解除、lease の期限切れ、最大保持時間、要求の消失、同じ要求の二重処理の無さ、起動の待機と中止、STOPPED）。`BackupQuiesceControlPoolUsageTest`（`src/main/java` を走査し、制御用 pool と repository を参照するのが `com.ses.backup.quiesce` の中だけで、mapper・controller が 3 表の名前を含まないこと）。`BackupQuiesceResponderMySqlTest`（実 MySQL 8.4: 登録の `db_client_host` が PROCESSLIST の host と一致、ack の観測に自分の接続元が出る、lease を延ばさないと `LEASE_EXPIRED` で再開、APP_STOPPED の保持中は起動処理が例外で止まる）。`MySqlTestShardInventoryTest`・`MySqlTestImagesTest`。
  - **Demo:** dev profile の app を起動し、MySQL の client で `INSERT INTO sys_backup_quiesce_request (…) VALUES (…, NOW(6) + INTERVAL 60 SECOND)` を手で入れ、ack の行（`QUIESCED`・`hold_until`・`observed_app_hosts`）と app の log（`BACKUP_QUIESCE_REQUESTED`・`ACKED`）を確かめる。延長せずに待ち、`AUTO_RESUMED` の ERROR と ack の `RESUMED`（`LEASE_EXPIRED`）を見る。
  - **失敗/rollback:** `DataSource` の bean が 2 つになって自動構成が崩れたら、holder の形に戻す。背景の thread が test profile で動いたら設定を見直す。

- [ ] **BWQ-004 — DataSource の最後の防線（読取り専用と接続の数え方）**

  - **依存:** BWQ-002
  - **対応要求:** BWQ-RQ-002（AC-002-02）
  - **Objective:** 静止中に permit を持たない thread が業務の pool で書けないことを MySQL の層で保証し、排水が「書込み可能な接続」まで待つようにする。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupQuiesceAwareDataSource.java`・`BackupQuiesceDataSourcePostProcessor.java`・`BackupQuiesceReadOnlyViolation.java`（新規）、試験 `BackupQuiesceAwareDataSourceTest.java`・`BackupQuiesceReadOnlyViolationTest.java`（新規）・`BackupWriteQuiesceMySqlTest.java`（新規、`@Tag("mysql")`）、`scripts/test-suites/mysql-shard-3.txt`。
  - **Implementation:**
    1. `BeanPostProcessor` で業務の `DataSource` を包む。`unwrap`・`isWrapperFor` を委譲し、Hikari の metric と health（`db`）が今までどおり出ることを保つ。
    2. gate が OPEN でなく permit の無い thread が借りた接続に `setReadOnly(true)`。静止で読取り専用にした接続への `setReadOnly(false)` は無視する（DEBUG）。
    3. 借りられていて書込み可能な接続の数を gate へ伝える（借りた時・`setReadOnly`・`close`）。
    4. `BackupQuiesceReadOnlyViolation.matches(Throwable)`: 原因の連鎖の `SQLException` の vendor code 1792 または SQLSTATE `25006`。
  - **Automated test:** `BackupQuiesceAwareDataSourceTest`（H2 と mock: 閉じている時だけ `setReadOnly(true)`、permit の thread は呼ばれない、数の増減、`unwrap(HikariDataSource.class)`）。`BackupWriteQuiesceMySqlTest`（実 MySQL 8.4: 閉じている間の permit 無しの INSERT が 1792 で失敗、permit の thread は書ける、閉じる前に借りた書込み可能な接続が返されるまで排水が終わらない、読取り専用の transaction の後に Spring が `setReadOnly(false)` を呼んでも書けない、制御用 pool は書ける、20 thread が書き続ける中で静止すると ack の後に commit された業務の行が 0 件で、解除の後は書ける（marker の前後））。
  - **Demo:** dev profile の app で、静止中に一覧の画面が表示でき、MySQL の general log（local だけ）に業務の接続の `SET SESSION TRANSACTION READ ONLY` が出ることを見る。
  - **失敗/rollback:** Hikari の metric・health が消えたら包み方（委譲の範囲）を直す。1792 以外の code で失敗する版の差があれば matcher に足す前に原因を調べる。

- [ ] **BWQ-005 — HTTP の分類と 503（filter・例外・エラーページ・文言・session・監査）**

  - **依存:** BWQ-002、BWQ-004
  - **対応要求:** BWQ-RQ-002（AC-002-01・05・06）
  - **Objective:** 静止中の更新の要求を認証より前に 503 にし、参照は続け、想定外の書込みも 503 で返す。利用者には翻訳された文言を出す。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupWriteGateFilter.java`・`BackupQuiesceRequestClassifier.java`・`BackupWriteQuiescedException.java`（新規）、`src/main/java/com/ses/common/util/AuditedDownloadPaths.java`（新規。`ApiAuditFilter.isDownloadUri` を移す）、`ApiAuditFilter.java`、`GlobalExceptionHandler.java`、`CustomErrorController.java`、`PersistentSessionServiceImpl.java`、`AuditLogServiceImpl.java`、`messages.properties`・`messages_en.properties`・`messages_ko.properties`・`messages_zh_CN.properties`、試験 `BackupQuiesceRequestClassifierTest.java`・`AuditedDownloadPathsTest.java`・`BackupWriteGateMockMvcTest.java`（新規）、`PersistentSessionServiceImplTest`・`CustomErrorControllerTest`・`GlobalExceptionHandlerTest`・`ApiAuditFilter` の既存試験（追加）。
  - **Implementation:**
    1. 分類（`design.md` §6.3・§6.4）: ALWAYS / READ / WRITE。download の判定は共有の util を使う。
    2. filter は `@Order(Ordered.HIGHEST_PRECEDENCE + 30)`。WRITE は permit を取り、取れなければ 503。chain 全体を包み、静止中の 1792 を 503 に変える（応答が未 commit の場合）。
    3. 503 の形（`design.md` §6.10）: API は `ApiResult`・`Retry-After`・`X-SES-Error-Code: BACKUP_QUIESCED`、画面は `sendError(503)` と request 属性、`CustomErrorController` は属性があれば bundle の文言と icon。
    4. `GlobalExceptionHandler`: `BackupWriteQuiescedException` の handler、`handleException` の前で 1792 の判定。
    5. `validateAndTouch`: 閉じていて permit が無ければ検証だけをして touch を省く。`AuditLogServiceImpl.record`: 同じ条件で DB へ書かず WARN `BACKUP_QUIESCE_AUDIT_DEFERRED`。
    6. 文言 3 key を 4 bundle に足し、それぞれ翻訳する。
  - **Automated test:** `BackupQuiesceRequestClassifierTest`（method×path×session の表）。`BackupWriteGateMockMvcTest`（閉じた gate で: `/api/**` の POST が 503 の JSON と header、画面の POST が 503 のエラーページで文言が locale に従う、`/login` の POST が 503 で `sys_user.failed_count` が変わらない、download の GET が 503、bootstrap session の GET が 503、一覧の GET が 200、静的資源・`/actuator/health/readiness`・`/actuator/prometheus` が通る。開いた gate では従来どおり）。session の touch が省かれ 401 にならないこと。`MessageBundleConsistencyTest`。
  - **Demo:** dev profile の app で静止を手で入れ（BWQ-003 の Demo の SQL）、ブラウザで一覧が見え、保存の操作で 503 の toast（日本語・英語の切替えで文言が変わる）が出て、login の画面で送信すると統一エラーページ（「一時的に更新を停止しています」）が出ることを見る。PWA の送信は queue に残り、解除後に送られることを見る。
  - **失敗/rollback:** 既存の filter の順（`CorrelationIdFilter` 等）を変えない。503 の body が HTML になる経路があれば `CustomErrorController` の JSON 判定を見直す。

- [ ] **BWQ-006 — scheduler・async・起動時処理の接続**

  - **依存:** BWQ-002、BWQ-003
  - **対応要求:** BWQ-RQ-002（AC-002-03・04）
  - **Objective:** 静止中に job と async が書かず、閉じる前に始まったものは排水され、cron が飛ばず、起動時処理が静止と重ならない。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupQuiesceLockProvider.java`・`BackupQuiesceTaskDecorator.java`（新規）、`SchedulerLockConfig.java`、`AsyncConfig.java`、試験 `BackupQuiesceLockProviderTest.java`・`BackupQuiesceTaskDecoratorTest.java`（新規）、`SchedulerLockProxyInvocationTest.java`・`SchedulerLockH2IntegrationTest.java`・`SchedulingDisabledDrainTest.java`（更新）。
  - **Implementation:**
    1. LockProvider の包み（`design.md` §6.6）: permit を持てば委譲、持たなければ OPEN を待って scheduler の permit を取り委譲、`unlock()` の後で permit を返す。
    2. TaskDecorator（`design.md` §6.7）: 投入時に OPEN か permit を持つ thread なら async の permit を取り、閉じた後の投入は実行の開始で待つ。
    3. 起動時処理は BWQ-003 の startup permit が覆う。`SchedulingDisabledDrainTest` の `THREAD_API_INVENTORY`（静止応答の `ScheduledExecutorService`）と `STARTUP_HOOK_INVENTORY`（静止応答の `SmartInitializingSingleton`・`Lifecycle`）に理由つきで足す。`ScheduledExecutorService` は bean にしない（同試験の bean の検査）。
  - **Automated test:** `BackupQuiesceLockProviderTest`（閉じている間は委譲されない、開くと委譲される、委譲が空なら permit を返す、unlock の後に permit を返す、permit を持つ thread は待たない）。`SchedulerLockProxyInvocationTest` に「閉じている間は `shedlock` の行が書かれず、開くと method が実行される」を足す。`BackupQuiesceTaskDecoratorTest`（閉じる前の投入は排水の対象、閉じた後の投入は開くまで始まらない、`CallerRunsPolicy` でも同じ）。`ScheduledMethodsHaveSchedulerLockTest`（既存）。
  - **Demo:** dev profile の app を scheduler 有効で起動し、静止を手で入れて 1 分以上待つ。毎分の job（`NotificationOutboxScheduler` 等）が `shedlock` を更新しないこと（`SELECT name, locked_at FROM shedlock`）と、解除の後に実行されることを見る。
  - **失敗/rollback:** ShedLock の挙動（`lockAtLeastFor`）が変わったら包みの位置を見直す。待機が最大保持時間を超えないことを確かめる。

- [ ] **BWQ-007 — prod の起動時検査・雛形・照合 script・§1.0**

  - **依存:** BWQ-002、BWQ-004
  - **対応要求:** BWQ-RQ-008
  - **Objective:** 本番で静止応答を無効にした起動と、最後の防線を消す JDBC 設定の起動を止め、配備の雛形と照合で同じ条件を確かめる。
  - **対象 file:** `src/main/java/com/ses/backup/quiesce/BackupQuiesceProductionValidator.java`（新規）、prod の profile で全 context を起動する試験（`application-test.yml` を import するもの。`@MockBean BackupQuiesceProductionValidator` を足す）、`infra/ecs/task-definition.prod.json`、`infra/ecs/offline/validate-task-definition.ps1`・`test-validate-task-definition.ps1`、`src/test/java/com/ses/ops/ProductionTaskDefinitionTemplateTest.java`、`deploy-runbook.md` §1.0、試験 `BackupQuiesceProductionValidatorTest.java`（新規）。
  - **Implementation:** `design.md` §12.2・§12.3。prod の判定は `Environment.acceptsProfiles`。`readOnlyPropagatesToServer` は `ConnectorJUrl.effectiveProperties` で展開後の属性も見る。
  - **Automated test:** `BackupQuiesceProductionValidatorTest`（prod で `enabled=false` は中止、URL・data-source-properties・展開後の属性の `readOnlyPropagatesToServer=false` は中止、`true`・未指定は通る、prod 以外の `enabled=false` は通る）。`ProductionTaskDefinitionTemplateTest`。`test-validate-task-definition.ps1` を pwsh と Windows PowerShell 5.1 で流し `0 mismatched`（値の違い・secrets に置いた・正規名・`SPRING_APPLICATION_JSON` の上書きの負例）。
  - **Demo:** dev の DB で `SPRING_PROFILES_ACTIVE=prod` 相当の設定の一部を与え、`BACKUP_QUIESCE_ENABLED=false` で起動が port を開く前に日本語の message で止まることを見る。`validate-task-definition.ps1`（雛形の mode）が 0 で終わる。
  - **失敗/rollback:** prod の試験が `enabled=false` で止まる場合は `@MockBean` の付け漏れを直す（validator を弱めない）。

- [ ] **BWQ-008 — Flyway job の DDL 凍結 callback（U-5 が A の場合）**

  - **依存:** BWQ-001、U-5 の決定
  - **対応要求:** BWQ-RQ-006
  - **Objective:** 本番の Flyway job と backup の静止区間が重ならないことを、人の手順に依らず保証する。
  - **対象 file:** `src/main/java/com/ses/config/ProductionBackupDdlFreezeFlywayCallback.java`（新規）、`ops/flyway/flyway-prod.conf`（`flyway.callbacks` の先頭）、`src/test/java/com/ses/migration/ProductionFlywayJobConfigurationTest.java`、`src/test/java/com/ses/config/ProductionBackupDdlFreezeFlywayCallbackTest.java`・`ProductionBackupDdlFreezeFlywayCallbackMySqlTest.java`（新規、`@Tag("mysql")`）、`scripts/test-suites/mysql-shard-3.txt`、`deploy-runbook.md` §3・§3.5。
  - **Implementation:** `design.md` §8。待ち時間 120 秒は定数。専用の接続で `GET_LOCK` を取り、migrate（と repair）の終わり・失敗の後で `RELEASE_LOCK` と接続の close。U-5 が B なら本 task は文書だけ（BWQ-013 に吸収）にする。
  - **Automated test:** `ProductionFlywayJobConfigurationTest`（callback の順）。`ProductionBackupDdlFreezeFlywayCallbackTest`（event の対応、失敗の message）。`ProductionBackupDdlFreezeFlywayCallbackMySqlTest`（別の session が lock を持つと migration が 1 件も適用されず日本語の message で失敗、空いていれば適用し、終了後に `IS_FREE_LOCK('ses_backup_ddl_freeze')` が 1）。`FlywayProdFreshInstallMySqlTest`（既存。job 設定そのものを読む）。
  - **Demo:** 検証用の空の MySQL で、別の client が `SELECT GET_LOCK('ses_backup_ddl_freeze', 600);` を持つ間に `mvn -B -o -Pprod-migrate clean compile flyway:migrate`（検証用の `FLYWAY_*`）を流し、適用 0 件で日本語の message で止まることを見る。lock を返してから流し直すと適用される（本番ではない DB だけで行う）。
  - **失敗/rollback:** lock を返さずに job が終わる経路があれば、接続の close で必ず返ることを確かめる。

- [ ] **BWQ-009 — backup tool: `quiesce-db` provider と verify**

  - **依存:** BWQ-001
  - **対応要求:** BWQ-RQ-001、BWQ-RQ-003、BWQ-RQ-004、BWQ-RQ-006、BWQ-RQ-007、BWQ-RQ-010
  - **Objective:** backup の job が DB の信号表で app の静止を取得・確認・解除し、途切れた静止を VALID にしない。
  - **対象 file:** `ops/backup/providers/quiesce-db.sh`・`ops/backup/lib/quiesce-db-common.sh`・`ops/backup/providers/quiesce-db.d/*.sql`（新規）、`ops/backup/lib/quiesce.sh`（振り分けと `quiesce::verify`）、`ops/backup/providers/quiesce-local.sh`（DDL lock の関数を共通の lib へ移す。`verify` は常に成功）、`ops/backup/backup-full.sh`・`ops/backup/create-checkpoint.sh`（verify の呼出し、heartbeat の directory の必須を `quiesce-local` だけに、静止区間の秒数の記録と WARN）、`ops/backup/lib/content-hash.sh`（信号表の拒否）、`ops/backup/preflight.sh`（`checks.quiesce`、exit 19）、`ops/backup/tests/fixtures/bin/mysql`（`ses-backup:quiesce-db:*` の応答）、`ops/backup/tests/quiesce-db-test.sh`（新規）、`ops/backup/tests/full-backup-test.sh`・`binlog-checkpoint-test.sh`・`script-contract-test.sh`・`preflight-test.sh`・`run-unit-tests.sh`。
  - **Implementation:** `design.md` §7.1〜§7.3・§12.4。lease の延長は固定の関数の background process（pid を state dir、止まったら印の file）。SQL の値は検査した整数・hex・列挙だけを `SET @p_…` で渡す。`quiesce.json` は既存の `started_at_utc`・`released_at_utc` を保ち、`request_id` 等を足す。
  - **Automated test:** `quiesce-db-test.sh`: 全 ack で取得成功と `quiesce.json`、状態不明の instance で失敗（ID と経過秒）、停止済みは無視、新しい instance 0 件で失敗（`--app-stopped` の案内）、REFUSED で即失敗、静止中の新しい instance で verify 失敗、`hold_until` の超過で verify 失敗、延長の停止で verify 失敗、観測した host の不一致で verify 失敗、protocol の版・閾値の不整合で失敗、DDL lock の競合で要求を登録しない、解除の後に RESUMED が来ないと exit 3。`full-backup-test.sh`・`binlog-checkpoint-test.sh`: verify が解除の直前に呼ばれる、verify の失敗で index に VALID が無い。`script-contract-test.sh`: provider の列挙、`bash -c` の不使用、パイプの後段の規則。content hash: `sys_backup_quiesce_ack` の指定で失敗。preflight: 権限が足りないと exit 19。
  - **Demo:** `ops/backup/tests/run-unit-tests.sh`（tool image の中）の結果を見る。
  - **失敗/rollback:** 既存の `quiesce-local` の試験（`quiesce-lock-test.sh`）を壊さない。tool image の content ID が変わるため、配布の pin のし直しを BWQ-013 の runbook に書く。

- [ ] **BWQ-010 — backup tool: `quiesce-app-stopped` と `--app-stopped`**

  - **依存:** BWQ-009、BWQ-003（起動の拒否）、U-2 の決定
  - **対応要求:** BWQ-RQ-005
  - **Objective:** app が動いていない時の backup（初回・配備の時・保守）を、誤用できない形で行えるようにする。
  - **対象 file:** `ops/backup/providers/quiesce-app-stopped.sh`（新規）、`ops/backup/providers/quiesce-db.d/stopped-*.sql`（新規）、`ops/backup/lib/quiesce.sh`（環境変数からの選択の拒否）、`ops/backup/backup-full.sh`・`create-checkpoint.sh`（`--app-stopped`）、`ops/backup/tests/quiesce-db-test.sh`（追加）、`full-backup-test.sh`。U-2 が B・C の場合は processlist の照合の SQL と権限の試験も足す。
  - **Implementation:** `design.md` §9。`tool-image.sh compose run --rm backup --app-stopped` で渡せることを確かめる（`tool-image.sh` は変えない）。
  - **Automated test:** 新しい heartbeat があれば取得しない、表が空なら取得する、STOPPED と停止済みは通る、有効な要求があれば失敗、保持中の instance の登録・heartbeat で verify 失敗、`QUIESCE_PROVIDER=quiesce-app-stopped` を環境変数で与えると full・checkpoint が始まらない。app 側の起動の拒否は BWQ-003 の MySQL 試験。
  - **Demo:** dev の DB で app を止め、`--app-stopped` の full を unit の fixture で流して `quiesce.json` の `mode: APP_STOPPED` を見る。保持中に app を起動して `BACKUP_QUIESCE_STARTUP_ABORT` で止まることを見る。
  - **失敗/rollback:** 定期の job から停止確認が選べる経路が 1 つでも残れば完了にしない。

- [ ] **BWQ-011 — provider と app の protocol の MySQL 契約試験**

  - **依存:** BWQ-003、BWQ-009、BWQ-010
  - **対応要求:** BWQ-RQ-001、BWQ-RQ-003、BWQ-RQ-007、BWQ-RQ-012
  - **Objective:** shell の provider と Java の responder が同じ表・状態・時刻の意味で動くことを、実 MySQL 8.4 で 1 つの試験として固定する。
  - **対象 file:** `src/test/java/com/ses/backup/quiesce/BackupQuiesceProviderContractMySqlTest.java`（新規、`@Tag("mysql")`）、`scripts/test-suites/mysql-shard-3.txt`。
  - **Implementation:** container に V202 と業務の表 1 つを作り、README「source 側の account の権限」と同じ権限だけの user（schema の `SELECT`、要求の表の `INSERT`・`UPDATE`）を作る。`ops/backup/providers/quiesce-db.d/*.sql` を JDBC（その user）で順に実行し、実の responder（制御用 pool・gate・業務の pool の包み）と往復させる。
  - **Automated test:** acquire → ack → verify 成功 → release → RESUMED の正常系、延長を止めた・responder を止めた・instance を追加した場合の verify の失敗、その user で業務の表への INSERT が拒否されること、APP_STOPPED の保持中に responder の起動が止まること。
  - **Demo:** 試験の log に、各 SQL file の実行と ack の状態の推移が日本語で出ることを見る。
  - **失敗/rollback:** SQL file の列名・状態の文字列が Java とずれたら、片側だけを直さず両側の試験を同じ変更で直す。

- [ ] **BWQ-012 — CI の backup integration を DB の protocol へ切り替える（U-4）**

  - **依存:** BWQ-009、BWQ-010、U-4 の決定
  - **対応要求:** BWQ-RQ-002（AC-002-02 の backup 側）、BWQ-RQ-012
  - **Objective:** 実 MySQL 8.4 の PITR の試験で、新しい provider が DB の protocol を通って full と checkpoint を取ることを CI で確かめる。
  - **対象 file:** `ops/backup/tests/docker-compose.integration.yml`（`scheduler`・`replica` を `responder` に置き換える）、`ops/backup/tests/run-integration.sh`（service の一覧・識別子・掃除）、`ops/backup/tests/integration-pitr.sh`（source に V202 を適用、最初の full を `--app-stopped`、responder の起動、checkpoint を `quiesce-db`、responder を止めた負例）、`ops/backup/tests/harness-selftest-test.sh`（service 名の変更に追従）、`.github/workflows/ci.yml`（変更が要る場合だけ）。
  - **Implementation:** U-4 が A の場合: responder は tool image の中の shell の loop で、app と同じ SQL（instance の登録・heartbeat・要求の読取り・ack・RESUMED）を mysql client で実行する（専用の合成 user）。V202 の file は repository から WORK へ写して source に適用する。B の場合は app の jar の build と起動を足す（所要時間と secret の扱いを先に決める）。
  - **Automated test:** `run-integration.sh` が既存の marker の照合（before は DB/uploads にある、after は無い、mid は replay される）を全て通し、証跡に `quiesce.json`（`mode`・`request_id`・`verified_at_utc`）が残る。負例: responder を止めた checkpoint が非 0 で終わり、index に VALID が増えない。
  - **Demo:** local の Docker で `ops/backup/tests/run-integration.sh` を流し、evidence の `quiesce.json` と marker の結果を見る（Runner の実行は統括が決める）。
  - **失敗/rollback:** 所要時間が大きく増えたら responder の間隔を見直す。`MySqlTestImagesTest` が integration の image の参照を拒否しないこと。

- [ ] **BWQ-013 — 文書（runbook・README・監視・restore・baseline・pii-inventory）**

  - **依存:** BWQ-001〜BWQ-012（文書は各 task の内容に合わせて最後にまとめる。各 task で直した節はその task で直してよい）
  - **対応要求:** BWQ-RQ-007、BWQ-RQ-011
  - **Objective:** 配備・運用・復元の手順が実装と一致し、PROD-004 の解除に要る証跡が明確になる。
  - **対象 file:** `.kiro/specs/production-launch/deploy-runbook.md`（冒頭の一覧、§1.0、§1.2、§1.7 の「Backup 開始の前提」・DBA-1、§2 の0 と5、§3・§3.5、§3.3・§3.4・§4、§5）、`ops/backup/README.md`（設定・実行・権限分離・静止の説明・テスト）、`ops/backup/runbooks/backup-health-monitoring.md`、`ops/backup/runbooks/restore-cutover.md`、`.kiro/specs/database-backup-recovery/baseline.md`（PROD-004）、`.kiro/specs/database-backup-recovery/design.md`（§5.1 の参照 1 行）、`.kiro/specs/privacy-retention-dsar/pii-inventory.md`（DB-148〜DB-150）。
  - **Implementation:** `design.md` §15 の表のとおり。runbook の文言は既存の書き方（「未取得」「決定済み（日付）」）に合わせる。PROD-004 は実装済みでも BLOCKED のまま（deployment owner の inventory 待ち）。
  - **Automated test:** `DeployRunbookMigrationCountTest`、`MessageBundleConsistencyTest`（既存）、`ops/backup/tests/script-contract-test.sh`（README の例の command が許可した使い方か、既存の検査があれば）。文書の中の file・行番号の参照が実在することを目で確かめる。
  - **Demo:** §2〜§4 の手順を上から読み、BWQ-014 の通しの Demo と同じ順になっていることを確かめる。
  - **失敗/rollback:** 新しく書く文は日本語の用語（統合・リリース・本番）を使う。既存の節の記述と矛盾したら、正本（§1.0・README の表）を先に直す。

- [ ] **BWQ-014 — 隔離環境での通しの Demo と証跡**

  - **依存:** BWQ-001〜BWQ-013
  - **対応要求:** 全体（特に BWQ-RQ-002・003・005・010）
  - **Objective:** 初回 backup（停止確認）→ app 起動 → 15 分 checkpoint（ONLINE）→ 解除の失敗と自動再開 → restore の後の起動、の一連が隔離環境で runbook どおりに動くことを示す。
  - **対象 file:** なし（証跡は repository の外。`target/backup-recovery-evidence/<run-id>/RB-QUIESCE/` を推奨）。
  - **Implementation / Demo:**
    1. 空の MySQL 8.4 に Flyway（dev）で V1〜V202 を適用し、app を起動しない状態で `--app-stopped` の full を取る。
    2. app（dev、scheduler 有効）を起動し、instance の行を確かめる。
    3. app に書込みの負荷（marker の INSERT を続ける script）をかけながら checkpoint を取り、ack の後に marker が増えないこと、解除の後に増えることを確かめる。静止区間の秒数と 503 の件数（metric）を記録する。
    4. backup の job を静止の最中に止め、app が lease で再開し `AUTO_RESUMED` が出ることを確かめる。
    5. checkpoint から隔離の recovery target へ復元し、app を起動して残った要求が期限切れとして無視されることを確かめる。
  - **Evidence:** 各 step の log の要約（errorCode・request_id・秒数）、`quiesce.json`、metric の抜粋、marker の照合結果。個人データ・秘密を含めない。
  - **失敗/rollback:** 1 つでも期待と違えば該当する task へ戻る。本番の環境では行わない。

## 実装規模の見込み

- 約 90 file（Java の main 約 25、試験 約 25、resources 約 8、ops/backup 約 22、infra 3、文書 約 9）、14 task（`design.md` §18）。
- 主な危険: 業務の DataSource の包みと Spring Boot の自動構成・metric の相互作用、想定外の GET の書込み（静止中に 503 になる）、uploads の増加による静止時間の伸び（RPO の維持）、長い処理と重なった checkpoint の欠落、lease と hold 期限の時刻の扱い、tool image の pin のし直し。
