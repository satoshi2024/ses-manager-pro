# 本番配備runbook（初回: 空DBへの新規導入・全停止・専用Flyway job）

- 状態: **草案（SRE/DBA承認待ち）**。本書は承認・実施済みの手順ではない。本番は引き続きNO-GO。
- 対象: SES Manager Pro 初回本番リリース（S-NF集成候補）。以後のreleaseも同じ全停止方式を前提とする。
- 根拠となるユーザー決定（2026-11-13）:
  - P-2: 本番DBは未構築。初回は空DBへの新規導入（V1→LATEST）。
  - P-3: 全停止＋専用Flyway job。アプリinstanceは起動時にmigrationを実行しない。
  - 部署-1: V2の業務sampleはprod専用scriptで削除する（V2本体は変更しない）。修復queueの対応行も消す。
  - 部署-2: 修正したflyway-maven-pluginで `mvn flyway:migrate` を実行する。
  - 部署-3: 初期管理者パスワードは配備時にsecretからhashを注入し、初回ログインで変更させる。公開済みパスワードを残さない。
  - DB時区: 本番MySQLのserver時区はDBAが `+09:00` に設定する（コードは変更しない）。
  - bootstrap方式A（2026-11-13）: 本番初回起動は期限付きbootstrap modeで行う（§5）。
  - bootstrapの完了と再開（2026-11-13、D1 第3回修正）: 初回bootstrapの完了後に再びbootstrapを有効にするには承認済みの変更ticketが必要（コードで強制。§5.7）。管理者は5名（break-glass 2名＋OIDCでログインする起票者1名・承認者2名）で準備する（§5.0）。
- 実装（配備バッチD1）:
  - job設定: `ops/flyway/flyway-prod.conf`（唯一の定義）。`pom.xml` の `prod-migrate` profileが読む。
  - prod専用script: `db/migration-prod/R__update_admin_password_bcrypt.sql`（初期管理者）、`db/migration-prod/R__remove_v2_sample_data.sql`（V2 sample削除）。
  - 初期管理者hashの検査: `com.ses.config.ProductionAdminInitialPasswordFlywayCallback`（job設定からclass名で登録）。
  - prodの起動時migrate禁止と起動時schema guard: `application-prod.yml`（`spring.flyway.enabled: false`）、`com.ses.config.ProductionSchemaGuardConfiguration` / `ProductionSchemaVersionGuard`。
  - 初回パスワード変更: `V186__sys_user_password_change_required.sql`、`PasswordChangeEnforcementFilter`、`/password/change`。
  - 期限付きbootstrap mode: `ProductionBootstrapProperties`（`app.security.bootstrap.*`）、`ProductionBootstrapMode`、`BootstrapSessionFilter`。IdPの登録API: `POST /api/identity-providers`（`IdentityProviderApiController`）。
  - 初回bootstrapの完了marker: `ProductionBootstrapCompletionLatch`（`m_system_config` の `security.bootstrap.completed-at`。新しいmigrationは無い）。
  - 検証: `FlywayProdFreshInstallMySqlTest`（実MySQL、job設定そのものを読む）、`ProductionFlywayJobConfigurationTest`、`ProductionSchemaVersionGuardTest`、`PasswordChangeEnforcementIntegrationTest`、`ProductionBootstrapIntegrationTest`、`ProductionBootstrapModeTest`、`ProductionBootstrapPersistenceTest`、`ProductionSecurityConfigurationValidatorBootstrapTest` ほか。

## 0. 全体の流れ

| 順 | 作業 | 実施者 | 完了条件 |
|---|---|---|---|
| 1 | 前提条件の確認（§1） | DBA / SRE / Security | §1の全項目が確認済み |
| 2 | 全停止とdrain（§2） | SRE | アプリ接続0件・scheduler停止 |
| 3 | 専用Flyway jobの実行と確認（§3） | SRE（DBA立会い） | §3.4の確認SQLが全て期待値 |
| 4 | アプリ起動（§4。初回はbootstrap mode） | SRE | schema guardのINFO log、bootstrapのWARN log、readiness OK |
| 5 | 初回bootstrap（§5） | 初期管理者担当 / IdP管理者 / Security | §5.5の完全な起動検査が通り、完了markerが書かれる |
| 6 | 失敗時・ロールバック（§6） | SRE / DBA | — |

## 1. 前提条件（配備前に確認する）

### 1.1 DBサーバー（DBA）

- MySQL 8.0系（版はDBAが確定する。testは `mysql:8.0.36`。確定後は `-Dses.flyway.fresh-install.mysql-image` で同じ版を検証する）。
- **server時区 `+09:00`**（ユーザー決定）。my.cnf / parameter groupで `default_time_zone = '+09:00'` を設定し、次で確認する。
  ```sql
  SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();
  -- 期待: @@global.time_zone = '+09:00'、NOW() が UTC_TIMESTAMP() の9時間後
  ```
  アプリのJDBC URLは `serverTimezone=Asia/Tokyo` を付ける（`application.yml` の既定 `DB_URL` と同じparameter）。jobの `FLYWAY_URL` にも同じparameterを付ける。
- database作成（DBAが空で作る）。文字コードは全表がV1等で `utf8mb4` / `utf8mb4_unicode_ci` を明示しているため、DBの既定も揃える（DBA確認）。
  ```sql
  CREATE DATABASE ses_manager_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  ```
- **対象schemaが空であること**。jobは `baselineOnMigrate=true`（従来設定と同じ）のため、履歴表の無い非空schemaはV9としてbaselineされ、V1〜V9を飛ばしてV10で失敗する（`FlywayProdFreshInstallMySqlTest` の対照caseで確認済み）。
  ```sql
  SELECT COUNT(*) FROM information_schema.tables   WHERE table_schema = 'ses_manager_db';  -- 0
  SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = 'ses_manager_db'; -- 0
  SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = 'ses_manager_db'; -- 0
  ```

### 1.2 DB account（DBA確認・承認が必要）

migrator（job専用）とアプリ用を分ける。

- **migrator account**（jobだけが使う。アプリには渡さない）
  - 対象schemaへの `CREATE, ALTER, DROP, INDEX, REFERENCES, SELECT, INSERT, UPDATE, DELETE, CREATE TEMPORARY TABLES, CREATE ROUTINE, ALTER ROUTINE, EXECUTE, TRIGGER`。
    - `CREATE TEMPORARY TABLES`: V169等3本が一時表を使う。
    - `CREATE ROUTINE / ALTER ROUTINE / EXECUTE`: helper procedureを作成・実行・削除する（V102_1のhelperはV186で削除する。全migration後にroutineは0件）。function・event・viewは作らない。
  - **trigger（55件）とbinlog**: binlogが有効で `log_bin_trust_function_creators = 0` のとき、`CREATE TRIGGER` はSUPER相当の権限を要求する。RDS等でSUPERを渡せない場合は parameter group で `log_bin_trust_function_creators = 1` にするか、DBAが代替を決める。**testは `--skip-log-bin` かつrootで実行しており、この条件は検証していない**（DBA確定後に同条件のDB変種で検証する。W1-B3 review §9.4）。
  - **DEFINER**: triggerは `DEFINER = migrator` で作られる。migrator accountを削除・lockするとtriggerの付いた表への書き込みが `ERROR 1449`（definer不在）で失敗する。migrator accountは残す（loginだけ無効化する等はDBAが判断する）。
  - binlogが有効で `enforce_gtid_consistency = ON` の場合、transaction内の一時表はbinlog_format=ROW等の条件が要る。DBAが設定を確認する。
- **アプリ account**: 対象schemaへの `SELECT, INSERT, UPDATE, DELETE` だけ。DDL権限は渡さない。起動時schema guardが `flyway_schema_history` を読むため、そのSELECTも必要（schema単位のSELECTで足りる）。

### 1.3 secretの注入（Security / SRE）

job実行環境へ渡す値は環境変数の次の4つだけ。POM・Maven property・`-D`・コマンド引数には書かない（process一覧へ出さない）。

| 環境変数 | 内容 |
|---|---|
| `FLYWAY_URL` | `jdbc:mysql://<host>:3306/ses_manager_db?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Tokyo` |
| `FLYWAY_USER` / `FLYWAY_PASSWORD` | migrator account |
| `FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH` | 初期管理者パスワードのBCrypt hash |

- **jobでは `mvn -X` / `--debug` を使わない。`org.flywaydb` のlogもDEBUGにしない**（D1 review P3-1）。FlywayはDEBUGで実行SQLをそのままlogへ出すため、`R__update_admin_password_bcrypt.sql` の `SET @… = '<注入したhash>'` がjob logに残る。調査でdebugが必要な場合は、本番のsecretを使わない検証用DBと使い捨てのhashで再現する。
- 初期管理者パスワードは、Securityが十分な長さの乱数で生成し、hashだけをsecret storeへ登録する。平文は初回ログイン担当者へ安全な経路で1回だけ渡す。
- hashはBCryptの `$2a$` / `$2b$` / `$2y$`、cost 10以上に限る。未設定・空・BCrypt以外（平文を誤って入れた場合を含む）・旧scriptが書いていた admin123 の公開hashは、jobがmigrationを1件も実行する前に失敗する（`ProductionAdminInitialPasswordFlywayCallback`）。値はlogへ出さない。
- **hashは毎回のjob実行に必要**。placeholderがRepeatableのchecksumに入るため、以後のreleaseでも同じsecretを渡す。値を変えると初期管理者scriptが再実行されるが、adminが初期状態でなければ何もしない（初回適用後は上書きしない）。
- 上の4つ以外の `FLYWAY_*` 環境変数は設定しない（`ops/flyway/flyway-prod.conf` の値より優先されるため）。`~/flyway.conf` もjob実行環境に置かない。
  ```bash
  env | grep '^FLYWAY_' | cut -d= -f1 | sort
  # 期待: FLYWAY_PASSWORD / FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH / FLYWAY_URL / FLYWAY_USER の4行だけ
  ```
- Flyway Maven pluginは上の環境変数とconf以外にも設定を読む（D1 review P3-6）。job実行環境で次も確認する。
  - 作業directory（checkoutのroot）に `flyway.conf` が無い（`ls flyway.conf` が失敗する）。
  - `~/.m2/settings.xml`（および `MAVEN_OPTS`・`.mvn/maven.config`）に `flyway.*` のpropertyと `-Dflyway.*` が無い。
  - `~/.m2/settings.xml` に `<server><id>flyway-db</id>` が無い（plugin既定のserver idで接続情報を差し込めるため）。
- 秘匿上の注意: hashは `R__update_admin_password_bcrypt.sql` の `SET` 文1行だけに展開される。その文が失敗するのは値に引用符等が含まれる（＝BCryptではない）場合だけだが、jobのlogは秘匿扱いで保管する。job実行中はDBの general log を無効にする。`flyway_schema_history.checksum` にはhashを含む内容のCRC32が残る（hash自体は復元できない）。

### 1.4 artifactとjobの一致（SRE）

- jobはsource checkoutで `compile` したclass/resourceを使う。**jobのcommit SHAと、配備するimage（docker save/load、digest記録。決定XB-10）のSHAを一致させる**。
  ```bash
  git rev-parse HEAD   # imageの記録SHAと一致すること
  git status --porcelain   # 空であること
  ```
- `clean` を必ず付ける（`target/classes` に古いmigrationが残ると、jobとartifactのmigrationがずれる）。

### 1.5 job実行環境のMaven依存（SRE。offline実行を推奨）

jobはflyway-maven-pluginとそのplugin依存（`flyway-mysql`・`mysql-connector-j`）、およびprojectのcompile/runtime依存をMaven local repositoryから解決する。配備環境はofflineや社内registry無しのことがあるため、**依存を事前にlocal repositoryへ揃え、jobは `-o`（offline）で実行することを推奨する**（§3のコマンドは `-o` 付きで書く）。

- **推奨: buildと同じ機械・同じ `.m2` でjobを実行する**。artifact（image）をbuildした機械の `~/.m2/repository`（または `-Dmaven.repo.local=<path>` で指定したdirectory）を、jobでもそのまま使う。buildとjobで別の `.m2` を使うと、片方にだけ無いartifactでjobが止まる。
- 別の機械でjobを実行する場合は、接続できる準備環境で次を行い、得たlocal repositoryをjob環境へ複製する（job環境では `-Dmaven.repo.local` で同じpathを指す）。
  1. 配備するSHAをcheckoutし、`mvn -B -Pprod-migrate -Dmaven.repo.local=<path> dependency:go-offline` を実行する。
  2. plugin依存も確実に取得するため、同じ `-Dmaven.repo.local` で**検証用の空MySQL**（本番DBではない）に対して §3.2 の `flyway:info` を1回通す（`FLYWAY_*` は検証用の値。§1.3の注意を守る）。
  3. job環境で、検証用DBに対して `mvn -B -o -Pprod-migrate -Dmaven.repo.local=<path> clean compile flyway:info` が依存解決エラー無く通ることを確認してから、本番の§3へ進む。
- `mysql-connector-j` のplugin依存からは `com.google.protobuf:protobuf-java` を除外している（X DevAPI専用でJDBCでは使わない。アプリ側のdriverはSpring BootのdependencyManagementが除外するが、その除外はplugin依存には効かない）。依存解決で `protobuf-java` を要求された場合は、古いpom.xmlでjobを動かしている疑いがある（§1.4のSHA一致を確認する）。
- 依存の取得経路（Maven Central・社内mirror）とMaven・JDKの版はSREが決める（§1.6の4）。

### 1.6 未決事項（配備前に決定が必要）

1. **初回起動のbootstrap**: **決定済み（方式A: 期限付きbootstrap mode。完了後の再開は承認ticket必須。管理者5名）**。空DBにはadmin 1件だけでMFA・IdP・外部identityも無く、通常のprod設定では最初の1人がログインできない（`ProductionSecurityConfigurationValidator` もbreak-glass管理者2件・MFA登録済みを起動条件にしている）。期限付きで初期adminのlocal loginだけを開け、bootstrapに必要な入口だけを使わせる（§4・§5）。管理者の人数と役割は§5.0、完了後の再開は§5.7。
2. §1.2のmigrator権限・trigger/binlog・DEFINERの扱い（DBA）。
3. MySQLの版・collation・parameter group（DBA）。
4. job実行環境（Maven・JDK・依存取得経路。§1.5のlocal repositoryの準備方法）（SRE）。

## 2. 全停止とdrain（SRE）

初回導入では稼働中のinstanceは無い。§2.1〜§2.3は2回目以降のreleaseの手順で、初回は「アプリinstanceが1台も起動していないこと」の確認だけを行う。

1. LB/ingressを保守表示にし、新しいrequestを止める。
2. **scheduler drain**: 全instanceを `APP_SCHEDULING_ENABLED=false`（`app.scheduling.enabled=false`）で再起動し、`@Scheduled` を止める。定期管理レポートの実行中leaseが無いことを `scheduler-claim-fence-plan.md` §10.3 のQ1で確認する（0件、または全件がlease期限30分を超過）。手動でclearしない。
3. 全instanceを停止する（SIGTERM。`server.shutdown: graceful` で処理中requestを排水してから終了する）。
4. DBへのアプリ接続が0件であることを確認する。
   ```sql
   SELECT COUNT(*) FROM information_schema.processlist WHERE user = '<アプリaccount>';  -- 0
   ```
5. 2回目以降のreleaseではbackupを取得する（full dumpまたはPITR基準点。`ops/backup/runbooks/`）。初回は§3完了直後に取得する。

## 3. 専用Flyway jobの実行と確認

### 3.1 準備

```bash
git checkout <配備するSHA>
git rev-parse HEAD
env | grep '^FLYWAY_' | cut -d= -f1 | sort
```

- 以下のコマンドは §1.5 の推奨どおり `-o`（offline）で書く。local repositoryを別pathにした場合は `-Dmaven.repo.local=<path>` を全コマンドに同じく付ける。依存を揃えていない環境で `-o` を外す場合は、取得経路をSREが確認する。

### 3.2 事前確認（任意・推奨）

```bash
mvn -B -o -Pprod-migrate clean compile flyway:info
```
- 全migrationが `Pending`、エラー無しであること（Java migration `74.3` とprodのRepeatable 2本を含む）。
- placeholderが未設定だとここで失敗する（`No value provided for placeholder`）。

### 3.3 migrate

```bash
mvn -B -o -Pprod-migrate clean compile flyway:migrate
```
- `compile` を先に行うのは、classpath location（`target/classes`）からJava migration（`db.migration.V74_3`）とcallback classを解決するため。
- 実行順: V1〜V186（versioned）→ Repeatable（説明の昇順: `crm contact reconciliation` → `remove v2 sample data` → `update admin password bcrypt`）。
- callback: `ProductionAdminInitialPasswordFlywayCallback`（hash検査）→ `LegacyDatabaseFlywayCallback`（空DBでは何もしない）。

### 3.4 適用結果の確認（DBA立会い）

```bash
mvn -B -o -Pprod-migrate flyway:validate   # 3.3と同じcheckout・同じ環境変数・同じlocal repositoryで実行
```

```sql
-- 失敗行が無い
SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0;                                  -- 0
-- baselineされていない（空DBからV1で開始）
SELECT COUNT(*) FROM flyway_schema_history WHERE type = 'BASELINE';                            -- 0
SELECT version FROM flyway_schema_history ORDER BY installed_rank LIMIT 1;                     -- '1'
-- 最新version（本releaseのLATEST）
SELECT version FROM flyway_schema_history WHERE version IS NOT NULL
 ORDER BY installed_rank DESC LIMIT 1;                                                         -- '186'
-- Repeatable 3本が成功
SELECT script FROM flyway_schema_history WHERE version IS NULL AND success = 1 ORDER BY script;
-- R__crm_contact_reconciliation.sql / R__remove_v2_sample_data.sql / R__update_admin_password_bcrypt.sql

-- 初期管理者: 注入hash・初回変更要求
SELECT username, LEFT(password, 7) AS hash_prefix, password_change_required, failed_count, locked_until
  FROM sys_user;
-- admin 1行だけ。hash_prefix は $2a$1x$ 等、password_change_required = 1、failed_count = 0、locked_until = NULL
SELECT COUNT(*) FROM sys_user WHERE password IN ('admin123',
  '$2a$10$b4R.5YvbxFPJ5C39IEFE/ua.G9F82I11lOAyGGNBvWJuLSVFUl41y');                           -- 0

-- V2 sampleと派生行が無い（配備決定-1）
SELECT (SELECT COUNT(*) FROM m_customer) AS customers, (SELECT COUNT(*) FROM t_project) AS projects,
       (SELECT COUNT(*) FROM t_engineer) AS engineers, (SELECT COUNT(*) FROM t_proposal) AS proposals,
       (SELECT COUNT(*) FROM t_contract) AS contracts,
       (SELECT COUNT(*) FROM t_customer_contact) AS contacts,
       (SELECT COUNT(*) FROM t_engineer_accounting_history) AS engineer_history,
       (SELECT COUNT(*) FROM nf02_nf03_ownership_repair_queue) AS ownership_queue,
       (SELECT COUNT(*) FROM t_legal_entity_backfill_audit) AS legal_entity_audit,
       (SELECT COUNT(*) FROM t_nf09_scope_repair_queue) AS nf09_queue;                         -- 全て0
SELECT contract_id FROM t_contract_acceptance_backfill;                                        -- 0 の1行（V80の完了marker）
SELECT code FROM m_organization_unit WHERE deleted_flag = 0;                                   -- LEGACY（V60の移行組織。V2 sampleとは無関係に作られる）

-- helper procedureが残っていない・trigger件数
SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE();           -- 0
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE();           -- 55（2026-11-14 R-3予行演習（MySQL 8.0.36・offline job）の実測値。静的計数の56は誤り）
```

- `R__remove_v2_sample_data.sql` は「本scriptの成功履歴が無い」かつ「sample 5表がV2の行だけ」のときだけ削除する。条件を満たさない場合は何も削除せず成功扱いになるため、上の件数が0でなければ実データ混入の可能性として停止し、DBAと調査する（手作業で消さない）。

### 3.5 jobが失敗した場合

- **hash検査・placeholder未設定で失敗した場合**: migrationは1件も実行されていない（履歴表は空、またはそもそも無い）。環境変数を直して§3.3を再実行する。`flyway repair` は不要。
- **migrationの途中で失敗した場合（初回導入）**: MySQLのDDLはtransactionで戻らないため、失敗行（`success = 0`）と途中までのschemaが残る。初回は業務dataが無いので、`flyway repair` で続行せず、**DBAがschemaを削除して空で作り直し**、原因を直してから§3.3を最初からやり直す。`flyway clean` は使わない（設定で拒否している）。
- **2回目以降のreleaseで失敗した場合**: §2.5で取得したbackupから戻すことを基本とする。`CLAUDE.md` のrepair手順はlocal開発DB向けであり、本番では許可済みscriptの確認・backup・DBA承認を経てだけ使う。

## 4. アプリ起動（SRE）

- 環境変数: `SPRING_PROFILES_ACTIVE=prod`（prod単独）、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`（アプリaccount）、その他prodのsecret（§4.1）。
- **`SPRING_FLYWAY_ENABLED` は設定しない**。`true` にしても `ProductionSchemaGuardConfiguration` がSpring BootのFlyway初期化でmigrateを拒否し、1件も適用せずに起動を止める。
- **JVMの時区を `Asia/Tokyo` に固定する（必須）**。`JAVA_TOOL_OPTIONS` に `-Duser.timezone=Asia/Tokyo` を加える（imageの既定 `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` を消さないよう、両方を書く）か、container環境変数 `TZ=Asia/Tokyo` を設定する。起動logの `Picked up JAVA_TOOL_OPTIONS: ...` で確認する。
  - 理由: account lockは `AccountLockServiceImpl` が `Asia/Tokyo` の時計で `sys_user.locked_until` を書き、login時の判定（`LoginUser.isAccountNonLocked`）はJVMの既定時区の `LocalDateTime.now()` と比べる。JVMがUTCのままだと30分のlockが約9時間30分続く（bootstrap中に `admin` がlockされると作業時間枠が実質閉じる。§5.6）。監査の `created_at` 等、JVMの既定時区で日時を作る箇所は他にもある。testは `argLine` で時区を固定しているが、本番は配備側で固定しない限り固定されない。DB側の時区（§1.1、`+09:00`）とは別に必要。
- 初回は `APP_SCHEDULING_ENABLED=false` で1台だけ起動する。
- 起動時schema guard: DBの `flyway_schema_history` とartifactのmigrationを照合し、一致しなければTomcatのport・schedulerを開始する前に起動を中止する。
  - 成功: INFO log `[schema guard] DBのschemaはartifactの最新migration V186 と一致しています`
  - 失敗: ERROR log `[schema guard] DBのschemaがartifactと一致しないため起動を中止します。...`（未適用・失敗行・artifactに無いmigration・最新versionの不一致・一度も適用されていないRepeatableを列挙する）。jobの結果（§3.4）とartifactのSHA（§1.4）を確認する。
  - guardはplaceholderを置換しないため、アプリへ初期管理者hashを渡す必要は無い。placeholderを含むRepeatable（初期管理者）は「同じscriptの成功適用が履歴にある」ことだけを確認する。jobが `db/migration-prod` を落として実行された（`-Dflyway.locations` 等の上書き）場合は、prodのRepeatableが一度も適用されていないとして起動を止める。
- `/actuator/health/readiness` がUPであることを確認する。
- schedulerは、Report Owner等の署名後に `APP_SCHEDULING_ENABLED=true` で再起動して有効化する。機能スイッチは `feature-switches.md` に従い1機能ずつONにする。

### 4.1 常に必要なsecurity設定（初回・以後とも。外さない）

OIDCの固定metadata・client credential、MFA鍵、session hash key等のprodのsecurity設定は、bootstrap中も以後も全て設定する（毎回の起動時に `ProductionSecurityConfigurationValidator` が検査する）。特に次は**初回だけでなく永久に必要**で、bootstrapを終える再起動（§5.5）でも外さない（D1 review P2-B）。

| 環境変数（property） | 値 | 外した場合 |
|---|---|---|
| `OIDC_TENANT_ID`（`app.security.oidc.tenant-id`） | `default`（V2のadminと権限groupのtenant） | OIDC login・外部identityの登録と承認・MFAとsessionのtenantは全てこの値で照合する。外すとIdPのproviderを解決できず**全員がOIDCでログインできなくなり**、break-glass incidentも起票できない（system全体のlockout）。そのため未設定は起動時検査で拒否する（`OIDCのtenant-id（OIDC_TENANT_ID）を設定してください`） |

### 4.2 初回起動（bootstrap mode）だけの環境変数とアクセス元制限

初回だけ、§5のbootstrapを行うために§4.1に加えて次を設定して起動する。

| 環境変数（property） | 値 |
|---|---|
| `SECURITY_BOOTSTRAP_ENABLED`（`app.security.bootstrap.enabled`） | `true` |
| `SECURITY_BOOTSTRAP_EXPIRES_AT`（`app.security.bootstrap.expires-at`） | 期限。**offset付きISO-8601**（例 `2026-11-14T18:00:00+09:00`）。起動時点から**24時間以内**の未来 |
| `SECURITY_BOOTSTRAP_REOPEN_TICKET`（`app.security.bootstrap.reopen-ticket`） | **初回は設定しない**。完了後に承認を得てbootstrapを再開する時だけ設定する（§5.7） |
| `BREAK_GLASS_USERNAMES` | **設定しない（空）**。bootstrap中に設定すると起動しない（§5.5で設定する） |
| `LOCAL_LOGIN_ENABLED` | 設定しない（既定false）。bootstrap中も `true` は起動しない |

- **LB/ingressでアクセス元を作業端末に限定する（必須）**。bootstrap中は `admin` のlocal password loginが開いているため、次の危険がある（D1 review P3-k）。起動前にSREが制限を入れ、作業端末以外から `/login` に到達できないことを確認してから§5.1へ進む。bootstrapの再開（§5.7）でも同じ。
  - 初期パスワードの総当たり。
  - **lockによる妨害（DoS）**: login失敗が5回で `admin` が30分lockされる（`AccountLockService`）。`sys_user` の照合順序は大小文字を区別しないため、`ADMIN` 等の綴りでの失敗も `admin` の失敗として数えられる。`/login` に到達できる者は誰でも `admin` をlockでき、作業を止められる。JVMの時区が固定されていないとlockが約9時間30分に延びる（§4）。
  - 先回りの改密: 初期パスワードが漏れていると、正規の担当者より先にloginしてパスワードを変え、bootstrapを乗っ取れる（兆候と対応は§5.8）。
- 起動の拒否（fail-fast、Tomcatのportを開く前）: `SECURITY_BOOTSTRAP_ENABLED=true` なのに期限が未設定・offset無し・既に過ぎている・現在から24時間超、または初回bootstrapの完了marker（§5.5）が有るのに承認ticketが無い・形式が不正・再開の監査を残せない。ERROR/例外messageは `[bootstrap] ...` で始まる。
- 起動時のWARN log: `[bootstrap] 期限付きbootstrap modeが有効です（期限: ...）` と `[bootstrap] 期限付きbootstrap mode中のため、break-glass管理者の登録検査とOIDC管理者の人数検査を省略します（期限: ...、再開ticket: なし（初回））`。この2行が出ていることを確認する。
- 期限を過ぎると、**再起動しなくても** `admin` のlocal loginは拒否され、既存のbootstrap sessionも次の要求で失効する（API 401 / 画面はlogin画面へ）。作業が終わらなかった場合は、新しい期限（24時間以内）を設定して再起動する（完了markerが書かれる前なので承認ticketは不要）。
- prod以外のprofileではbootstrap modeの設定は無視される（dev/testの挙動は変わらない）。

## 5. 初回bootstrap（期限付きbootstrap mode）

§4.1・§4.2の設定で起動した後、次の順に行う。bootstrap中にlocal password loginできるのは初期管理者 `admin` だけで、他のユーザーのlocal loginは拒否される。OIDC loginは通常どおり使える。

### 5.0 準備する管理者（5名）と役割

break-glass incidentは、起票者1名と、起票者以外の互いに異なる承認者2名が必要で、break-glass usernameのOIDC sessionは失効させられる（既存仕様）。したがって、break-glass口座2つとは別に、OIDCでログインできる管理者口座が3つ要る（合計5名）。bootstrapを終える起動（§5.5）で `ProductionSecurityConfigurationValidator` が「break-glass username以外でOIDCでログインできる有効な管理者が3名以上」を検査し、満たさなければ起動しない。

| 記号 | 口座（例） | 役割 | bootstrap後のlogin | SESのMFA（TOTP） | 作成 | local password |
|---|---|---|---|---|---|---|
| A | `admin` | break-glass管理者1 | break-glassだけ（IdP障害時のlocal login） | §5.1で本人がbootstrap sessionから登録 | V2のseed | §5.1で本人が初期パスワードから変更する |
| B | `bg-second` | break-glass管理者2 | break-glassだけ | §5.4で本人がOIDC sessionから登録 | §5.3でAが作成 | Securityが生成した初期値をBだけに渡し、§5.4で本人が変更する |
| C | `ops-issuer` | incidentの起票者 | OIDC | 登録しない | §5.3でAが作成 | 使わない（作成に必須のため、Securityが十分な長さの乱数を生成して入力し、誰にも渡さず破棄する） |
| D | `ops-approver1` | incidentの承認者1 | OIDC | 登録しない | §5.3でAが作成 | 同上 |
| E | `ops-approver2` | incidentの承認者2 | OIDC | 登録しない | §5.3でAが作成 | 同上 |

- 作成順: A（既存）→ C → D → E → B。OIDC管理者を先に揃えておくと、Bの準備中にA・Bに問題が起きてもC〜Eから復旧できる（§5.8）。
- 「OIDCでログインできる」の起動時判定はDB側の条件だけ（設定tenant・issuerの有効なOIDC provider、APPROVEDの外部identity link、有効な `管理者`）。IdP側のaccount状態・MFA assuranceは起動時に確かめられないため、§5.3の5で実際にloginして確認する。
- SESのTOTPを登録するのはbreak-glass口座（A・B）だけ。C〜EはIdP側のMFA（MFA assurance claim）でloginする。bootstrap終了後は、break-glass username以外はSESのTOTPを登録できない。
- 人の割当: 推奨は5名とも別人（break-glass口座の保持者と、起票者・承認者を分ける）。兼務を認めるかはOwner/Securityが決める。口座名は例であり、実際の名前はOwner/Securityが決める。

### 5.1 adminのログイン・パスワード変更・MFA登録（A）

0. LBのアクセス元制限（§4.2）が効いていることを、作業端末以外から `/login` に到達できないことで確認する。
1. 初回ログイン担当者（A）が、§1.3で受け取った初期パスワードで `admin` としてログインする（bootstrap中だけlogin画面にusername/password formが出る）。
2. **パスワード変更が先**。全画面が `/password/change` へ、全APIが403（`パスワードの変更が必要です`）になる（`PasswordChangeEnforcementFilter`。通るのは変更画面・`PUT /api/profile/password`・logout・静的資源だけ）。現在のパスワード（初期パスワード）と新しいパスワード（8文字以上で英字と数字を含む）を入力して保存する。
   - 変更に成功すると、同じ `admin` の他のsession（例: 別の端末で初期パスワードでloginしたsession）は失効する（D1 review P3-e。他sessionは次の要求で401になる）。
   - 「現在のパスワードが正しくありません」と表示された場合は、初期パスワードを他者が先に変えた疑いがある。作業を止めて§5.8へ進む。
3. **次にMFA登録**。変更後は全画面が `/mfa/setup`（未登録）または `/mfa/challenge`（登録済み）へ、全APIが403（`MFA認証が必要です`）になる（`BootstrapSessionFilter`）。`/mfa/setup` でsecretを発行し、認証アプリへ登録して6桁コードで有効化する。表示されるrecovery codeを安全に保管する。
4. 確認:
   ```sql
   SELECT password_change_required FROM sys_user WHERE username = 'admin';                            -- 0
   SELECT COUNT(*) FROM t_user_mfa m JOIN sys_user u ON u.id = m.user_id
    WHERE u.username = 'admin' AND m.encrypted_totp_secret IS NOT NULL AND m.enabled_at IS NOT NULL;  -- 1
   ```
5. 初期パスワードの控えを破棄する。secret storeのhashは以後のjob実行で必要なため残す（adminの変更後は使われない）。

- 2回目以降のbootstrap login（パスワード変更済み）は、MFA登録済みならMFA認証、未登録ならMFA登録へ直接進む。
- 初回ログイン前に初期パスワードを紛失した場合: jobを再実行しても上書きしない（一度だけ）。Securityが新しいhashを作り、DBAが立会いの下で次を実行する（general logを無効にした接続で行い、値を履歴に残さない）。
  ```sql
  UPDATE sys_user SET password = '<新しいBCrypt hash>', password_change_required = 1,
         failed_count = 0, locked_until = NULL
   WHERE username = 'admin' AND password_change_required = 1;
  ```

### 5.2 bootstrap sessionで使える入口

パスワード変更とMFA登録が済んだadminのsession（bootstrap session）でも、使えるのは次だけで、それ以外は403になる（APIはApiResult JSON・`X-SES-Error-Code: BOOTSTRAP_SCOPE_VIOLATION`、画面は統一エラー画面）。`/` はユーザー管理画面（`/user/list`）へ誘導される。通知ベルはbootstrap sessionでは表示しない。

| 用途 | 入口（action key / URI） |
|---|---|
| 管理者の作成・状態変更（ユーザー管理画面） | `user.*`（`/user/list`、`/api/users/**`）、`GET /api/autocomplete/users` |
| 権限group・role別menuの**参照だけ** | `permission.manage` のGET（`GET /api/permission-groups/**`、`GET /api/role-menus/**`） |
| IdPと外部identity link | `identity-provider.*`（`/api/identity-providers`、`/api/identity-providers/{id}/external-identities`） |
| MFA・本人session・logout | `/mfa/setup`、`/mfa/challenge`、`/api/security/mfa/{status,setup,enable,verify}`、`/api/security/sessions/**`、`POST /logout` |
| パスワード変更 | `GET /password/change`、`PUT /api/profile/password` |
| 画面の静的資源・i18n | `/css/**`、`/js/**`（`/js/i18n.js` を含む）、`/lib/**`、`/img/**`、`/favicon.ico` |

- `user.*` には、管理者の作成のほか、更新（他ユーザーのパスワード設定・role変更を含む）・状態変更・論理削除も含まれる。空DBではadmin以外のユーザーが居ないため許容しているが、bootstrap中は§5.0の口座の作成以外を行わない（監査で確認する。§5.6）。自分自身のrole変更・削除・無効化は既存の制御で拒否される。
- role別menuの全置換（`PUT /api/role-menus`）と権限groupの割当替え（`PUT /api/permission-groups/users/*`）は、bootstrap sessionでは403になる（D1 review P3-b）。管理者を作成するとroleの既定権限group（`role-admin`）がserver側で割り当たるため、bootstrapに権限の書込は要らない。
- bootstrap sessionの要求は、書込（`BOOTSTRAP_WRITE_ATTEMPT`・`BOOTSTRAP_WRITE`）に加えて参照（`BOOTSTRAP_READ`）も監査に残る。
- bootstrap sessionの印はbootstrap loginの本人（`admin` の `sys_user.id`）に結び付いており、同じブラウザで別の口座がloginすると、印・MFA検証済みの状態・初回変更要求は引き継がれない（D1 review P3-a）。それでも混乱を避けるため、OIDCでのlogin確認（§5.3の5・§5.4）はbootstrap sessionと別のブラウザ（またはprivate window）で行う。
- この制限はbootstrap sessionだけに掛かる一時制限で、業務権限（baseline + deny）には影響しない。OIDCでログインした管理者のsessionには掛からない。

### 5.3 IdP・管理者C〜E・B・外部identityの登録（Aのbootstrap session）

1. **IdPを登録する**。画面は無いため、`/user/list` を開いたままブラウザの開発者consoleで次を実行する（`SES.api` がCSRF tokenを付ける）。登録内容はrequestから受け取らず、起動設定（`OIDC_TENANT_ID` / `OIDC_ISSUER_URI` / `OIDC_CLIENT_ID`）から作られる。client secretはDBへ保存しない。
   ```javascript
   const provider = await SES.api.post('/api/identity-providers', {});   // 同じissuerが登録済みならその行を返す
   provider.id   // 以降の {providerId}
   ```
   - 同じissuerの行が論理削除されている場合は、UNIQUE制約により登録が409になる。DBAが `m_identity_provider` の該当行（`deleted_flag`・`enabled`）を確認して判断する（D1 review P3-l）。
2. **管理者を作成する（C → D → E → Bの順）**。ユーザー管理画面で、role `管理者` として作成する（作成時にroleの既定権限groupが割り当たる）。パスワードは§5.0の表のとおり、C〜EはSecurityが生成した乱数（誰にも渡さず破棄）、BはSecurityが生成してBだけに渡す初期値にする。
3. **各管理者の `sys_user.id` を調べる**。consoleで次を実行する（username検索は部分一致のため、完全一致で絞る）。
   ```javascript
   for (const name of ['ops-issuer', 'ops-approver1', 'ops-approver2', 'bg-second']) {
     const page = await SES.api.get('/api/users', { username: name, size: 50 });
     console.log(page.records.filter(u => u.username === name).map(u => ({ id: u.id, username: u.username, role: u.role, status: u.status })));
   }
   ```
   DBAが確認する場合:
   ```sql
   SELECT id, username, role, status FROM sys_user
    WHERE username IN ('admin', 'bg-second', 'ops-issuer', 'ops-approver1', 'ops-approver2') AND deleted_flag = 0;
   ```
4. **外部identity linkを登録・承認する（B〜Eの4名）**。IdP管理者から各管理者のOIDC `sub`（subject。emailではない）を受け取り、consoleで次を実行する（登録と同時にAPPROVEDになり、承認監査 `OIDC_BINDING_APPROVED` が残る）。Aにはlinkを登録しない（break-glass専用）。
   ```javascript
   await SES.api.post('/api/identity-providers/' + provider.id + '/external-identities',
       { userId: <sys_user.id>, subject: '<IdPのsub>', emailSnapshot: '<任意。sys_user.emailと一致させる>' });
   ```
5. B〜Eが、それぞれ別のブラウザ（またはprivate window）でOIDCでログインし、通常の画面を使えることを確認する（IdP側のMFA assuranceが必要）。C〜Eの3名がloginできることが、§5.5の起動時検査の前提になる。

### 5.4 break-glass管理者Bの準備（BのOIDC session）

1. **BのMFA登録**。bootstrap中だけ、OIDCでログインした有効な管理者も `/mfa/setup` でTOTPを登録できる（通常はbreak-glass usernameにしか許されない）。BはOIDCでログインし、`/mfa/setup` で登録してrecovery codeを保管する。
2. **Bのlocal passwordを本人が変更する**（D1 review P3-d）。Bは同じOIDC sessionで、画面右上のメニューの「パスワード変更」から、現在のパスワード（Securityから受け取った初期値）と新しいパスワードを入力して保存する。初期値の控えは破棄する。こうしないと、Bを作成したA（とSecurity）がBのパスワードを知ったままになる。
3. **Securityが、TOTPを登録したのがAとBだけであることを確認する**（break-glass usernameに指定する前に行う）。
   ```sql
   SELECT created_at, username, uri, application_code FROM t_audit_log
    WHERE application_code IN ('BOOTSTRAP_MFA_ENABLED', 'BOOTSTRAP_MFA_VERIFIED') ORDER BY created_at;   -- admin と bg-second だけ
   SELECT u.username FROM t_user_mfa m JOIN sys_user u ON u.id = m.user_id
    WHERE m.encrypted_totp_secret IS NOT NULL AND m.enabled_at IS NOT NULL;                                -- admin と bg-second だけ
   ```
   想定外のユーザーが登録していれば、作業を止めて§5.8で調べる。

### 5.5 bootstrapの終了と完了marker

1. **break-glass usernameを決める（ちょうど2件）**: A（`admin`）とB。いずれも有効な `管理者` で、local passwordとMFA登録を持つこと。
2. **bootstrapを無効にして再起動する**。`SECURITY_BOOTSTRAP_ENABLED`・`SECURITY_BOOTSTRAP_EXPIRES_AT`（設定していれば `SECURITY_BOOTSTRAP_REOPEN_TICKET` も）を外し、`BREAK_GLASS_USERNAMES=admin,bg-second` を設定して再起動する。**`OIDC_TENANT_ID` は外さない**（§4.1）。LBのアクセス元制限は、この起動を確認してから通常の設定へ戻す。
3. **完全な起動検査が通ることを確認する**。`[bootstrap] 期限付きbootstrap modeが有効です` のWARN logが出ず、`ProductionSecurityConfigurationValidator` の例外（`本番security configurationが安全な既定値を満たしません`）で停止しないこと。readinessがUPになり、C〜EがOIDCでログインできること。完全な検査が見るbootstrap関連の条件は次のとおり。
   - break-glass usernameがちょうど2件で、どちらも有効な管理者かつMFA登録済み（未登録なら `break-glass usernameはMFA enrollment完了済みでなければなりません`）。
   - 設定tenant・issuerの有効なOIDC providerが登録済み（無ければ `設定tenant・issuerの有効なOIDC providerが登録されていません`）。
   - break-glass username以外でOIDCでログインできる有効な管理者が3名以上（不足なら `OIDCでログインできる有効な管理者（break-glass username以外）が3名以上必要です（...現在N名）`）。
   - `OIDC_TENANT_ID` が設定済み。
4. **完了markerが書かれたことを確認する**。完全な検査が初めて通った起動で、`m_system_config` に初回bootstrapの完了日時が書かれ、WARN log `[bootstrap] 初回bootstrapの完了markerを書き込みました（security.bootstrap.completed-at=...）` と監査 `BOOTSTRAP_COMPLETED` が残る。以後、bootstrapを再び有効にするには承認ticketが要る（§5.7）。markerはシステム設定画面には表示されず、画面から変更できない。
   ```sql
   SELECT config_value FROM m_system_config WHERE config_key = 'security.bootstrap.completed-at';   -- 完了日時（offset付きISO-8601）
   SELECT created_at, method, uri FROM t_audit_log WHERE application_code = 'BOOTSTRAP_COMPLETED';  -- 1行
   ```
   - markerを書けない場合は起動しない（`[bootstrap] 初回bootstrapの完了markerを記録できないため起動しません`。監査 `BOOTSTRAP_COMPLETION_MARK_FAILED` はbest-effort）。markerが無いままだと、本番稼働後のbootstrap再開が承認も監査も無く行えてしまい、しかも誰も気づけないため、fail-closedにしている。DBAがアプリaccountの `m_system_config` へのINSERT権限とDBの状態を確認してから再起動する。
5. **完全な検査が通らなかった場合**: markerはまだ書かれていないため、承認ticket無しでbootstrapを再開できる。新しい期限で `SECURITY_BOOTSTRAP_ENABLED=true` にし、**`BREAK_GLASS_USERNAMES` を再び空にして**（bootstrap中に設定したままだと起動しない）再起動し、不足を直して（例: Bの§5.4、C〜Eの作成・link）から2へ戻る。

### 5.6 運用上の注意・監査・アラート（Owner/Securityが確認する）

- **break-glass usernameに指定した管理者はOIDCではログインできなくなる**（OIDCのsessionもincident未束縛のbreak-glass sessionとして失効させる既存仕様）。break-glass管理者はIdP障害時のlocal login専用の口座になる。
- **OIDCでログインできる管理者（break-glass以外）を3名未満にしない**。退職・異動で無効化・削除・role変更する前に、後任を作成してlinkを承認する。3名未満になると、次の再起動で起動時検査により起動しない（break-glass管理者の無効化・MFA喪失も同じ）。復旧はDBA、またはbootstrapの再開（§5.7）。
- **アラート（必須）**: 監視で `t_audit_log.application_code` の `BOOTSTRAP_LOGIN_SUCCESS` と `BOOTSTRAP_REOPENED`、およびapp logの `[bootstrap]` で始まるWARN/ERRORにアラートを設定する。§5.5の完了後にこれらが出たら、承認済みの再開ticket（§5.7）が無い限りsecurity incidentとして扱う。あわせて `BOOTSTRAP_LOGIN_FAILURE`・`BOOTSTRAP_LOGIN_LOCKED`・`BOOTSTRAP_SESSION_MISMATCH`・`BOOTSTRAP_COMPLETION_MARK_FAILED` もアラートを推奨する。監視queryの例:
  ```sql
  SELECT created_at, username, method, uri, application_code FROM t_audit_log
   WHERE application_code IN ('BOOTSTRAP_LOGIN_SUCCESS', 'BOOTSTRAP_REOPENED') AND created_at >= ?   -- 前回確認時刻
   ORDER BY created_at;
  ```
- 監査（`t_audit_log`）に残るbootstrapのapplication code: `BOOTSTRAP_LOGIN_SUCCESS` / `BOOTSTRAP_LOGIN_FAILURE` / `BOOTSTRAP_LOGIN_LOCKED` / `BOOTSTRAP_LOGIN_EXPIRED`（期限切れ後のlogin拒否）/ `BOOTSTRAP_SESSION_EXPIRED`（期限切れsessionの失効）/ `BOOTSTRAP_SESSION_MISMATCH`（別の主体へ引き継がれた印の失効）/ `BOOTSTRAP_WRITE_ATTEMPT`・`BOOTSTRAP_WRITE`（bootstrap sessionの書込操作。実行前の必須監査と結果）/ `BOOTSTRAP_READ`（bootstrap sessionの参照）/ `BOOTSTRAP_SCOPE_DENIED`（入口外の要求）/ `BOOTSTRAP_MFA_ENABLED`・`BOOTSTRAP_MFA_VERIFIED` / `IDENTITY_PROVIDER_REGISTERED` / `BOOTSTRAP_COMPLETED`（完了marker）/ `BOOTSTRAP_COMPLETION_MARK_FAILED` / `BOOTSTRAP_REOPENED`（承認ticketでの再開）。起動時の監査（`BOOTSTRAP_COMPLETED` 等）は `method = 'STARTUP'`、`username` はNULLで、`uri` に `app.security.bootstrap;...` として詳細が入る。bootstrap終了後にSecurityが一覧を確認し、§5.0の口座の作成・link・MFA登録以外の操作が無いことを確かめる。
  ```sql
  SELECT created_at, username, method, uri, status, application_code FROM t_audit_log
   WHERE application_code LIKE 'BOOTSTRAP%' OR application_code IN ('IDENTITY_PROVIDER_REGISTERED', 'OIDC_BINDING_APPROVED')
   ORDER BY created_at;
  ```
- bootstrap中（再開を含む）のLB/ingressのアクセス元制限は必須（§4.2）。

### 5.7 完了後のbootstrap再開（承認必須）

本番稼働後のbootstrap再開は、**承認不要のbreak-glassに等しい**（`admin` のlocal loginと、管理者の作成・IdPと外部identityの登録の入口が開き、incidentの起票1名＋承認2名を経ずに管理者権限を作れる）。原則として行わず、IdP障害時はbreak-glass incidentを使う。break-glass自体が使えない場合（例: OIDC管理者が3名未満で起動しない、break-glass管理者が2名ともMFAを失った）に限り、承認を得て行う。

- コードでの強制: 完了marker（§5.5）が有る状態で `SECURITY_BOOTSTRAP_ENABLED=true` にすると、`SECURITY_BOOTSTRAP_REOPEN_TICKET` が無い限り起動しない（`[bootstrap] 初回bootstrapは完了済みです（completed-at=...）。...`）。ticketが有れば、起動時に必須監査 `BOOTSTRAP_REOPENED`（`uri` に `reopen-ticket=...;expires-at=...;completed-at=...`）を残してから起動し、監査を残せなければ起動しない。
- ticketの形式: 英字で始まり、英数字と `- _ . : # /` からなる4〜64文字で、数字を1文字以上含む（例 `SEC-1234`、`CHG0012345`）。形式はtypoを防ぐだけで、承認の有無はコードでは確かめられない。承認は次のflowで担保する。
- **承認flowの例**:
  1. 作業者が変更ticket（例 `SEC-1234`）を起票する。記載: 理由、作業内容（例: OIDC管理者の補充1名、break-glass管理者BのTOTP再登録）、作業者、作業端末のアクセス元（LBで許可する値）、期限（24時間以内）、終了確認者。
  2. Owner（業務上の必要性）とSecurity（手順・期限・アクセス元制限）の2名が承認する。起票者は承認者を兼ねない。
  3. SREがLB/ingressのアクセス元制限を入れ、`BREAK_GLASS_USERNAMES` を**空にし**（設定したままだと起動しない）、`SECURITY_BOOTSTRAP_ENABLED=true`・`SECURITY_BOOTSTRAP_EXPIRES_AT=<24時間以内>`・`SECURITY_BOOTSTRAP_REOPEN_TICKET=SEC-1234` で再起動する。`OIDC_TENANT_ID` は外さない。
  4. 起動logの `[bootstrap] 完了済みのbootstrapを承認ticket SEC-1234 で再開します（...）` と、監査 `BOOTSTRAP_REOPENED` を確認する。Securityは§5.6のアラートが鳴ったことを確認する（鳴らなければアラートの設定不備として直す）。
  5. ticketの作業内容だけを行う（§5.1〜§5.4の該当手順）。
  6. §5.5の2と同じく `SECURITY_BOOTSTRAP_*` を全て外し、`BREAK_GLASS_USERNAMES` を戻して再起動する。完了markerは既に有るため上書きされない（`BOOTSTRAP_COMPLETED` は出ない）。
  7. Securityが§5.6の監査SQLで、期間中の操作がticketの作業内容と一致することを確認してticketを閉じる。
- ticketを付けたまま再起動するたびに `BOOTSTRAP_REOPENED` が残る。期限が過ぎた場合は、同じticketの承認範囲内で新しい期限を設定して再起動する（範囲を超えるなら新しいticketを起票する）。
- DB権限を持つ者は完了markerを消してticket無しで再開できる。これはアプリの制御の対象外（DB権限はアプリの制御を迂回できる）ため、DB監査logで `m_system_config` の `security.bootstrap.completed-at` の変更・削除を監視する。

### 5.8 異常時の対応（侵害の疑い・TOTP喪失・lock）

**侵害の疑いの兆候**:
- §5.1の改密で「現在のパスワードが正しくありません」（初期パスワードを他者が先に変えた）。
- 作業者が行っていない `BOOTSTRAP_LOGIN_SUCCESS`・`BOOTSTRAP_LOGIN_FAILURE`、LBのlogに作業端末以外からの到達。
- 作業していないユーザー作成・`OIDC_BINDING_APPROVED`・`IDENTITY_PROVIDER_REGISTERED`・`BOOTSTRAP_MFA_ENABLED`、または `BOOTSTRAP_SESSION_MISMATCH`。

**侵害の疑いがある時の対応**:
1. 直ちにアプリを停止する（in-memoryのsessionは全て消える）。LBの遮断は維持する。
2. `t_audit_log`・LBのlog・app logを保全する。
3. DBAとSecurityが、general logを無効にした接続で状態を戻す（`<…>` は§5.3の3で調べたid）。
   ```sql
   -- admin: 新しい初期パスワードhash（Securityが新たに生成）・初回変更要求・lock解除
   UPDATE sys_user SET password = '<新しいBCrypt hash>', password_change_required = 1,
          failed_count = 0, locked_until = NULL WHERE username = 'admin';
   -- 対象ユーザー（admin等）のTOTP・未使用recovery code・永続sessionの解除（MfaService.resetと同じ内容）
   UPDATE t_user_mfa SET encrypted_totp_secret = NULL, secret_key_version = NULL, enabled_at = NULL,
          last_used_step = NULL, updated_at = CURRENT_TIMESTAMP
    WHERE tenant_id = 'default' AND user_id = <対象のid> AND deleted_flag = 0;
   UPDATE t_mfa_recovery_code SET revoked_at = NOW(), updated_at = CURRENT_TIMESTAMP
    WHERE tenant_id = 'default' AND user_id = <対象のid> AND used_at IS NULL AND revoked_at IS NULL AND deleted_flag = 0;
   UPDATE t_user_session SET revoked_at = NOW(), revoke_reason = 'BOOTSTRAP_COMPROMISE', updated_at = CURRENT_TIMESTAMP
    WHERE tenant_id = 'default' AND user_id = <対象のid> AND revoked_at IS NULL AND deleted_flag = 0;
   -- 想定外のユーザーは無効化し、想定外の外部identity linkは隔離する（OIDC loginはAPPROVED以外を拒否する）
   UPDATE sys_user SET status = 0 WHERE id IN (<想定外のユーザーのid>);
   UPDATE t_user_external_identity SET review_status = 'QUARANTINED' WHERE id IN (<想定外のlinkのid>);
   ```
4. 原因（初期パスワードの漏洩経路、LB制限の不備等）を調べて直してから、新しい期限で§5.1からやり直す。完了markerが書かれる前なら承認ticketは不要、書かれた後なら§5.7の承認が要る。

**TOTPの喪失**:
- recovery codeが有る: `/mfa/challenge` でrecovery codeを入力して検証し、`/mfa/setup` で登録し直す（新しいsecretとrecovery codeが発行される）。
- recovery codeも無い（bootstrap中）:
  - OIDC管理者（C〜E）が既にloginできる場合: その管理者のsession（bootstrap sessionではない）のconsoleで `await SES.api.post('/api/security/mfa/<対象のsys_user.id>/reset', {})` を実行する（管理者だけが使える既存API。対象の全sessionも失効する）。その後、本人が登録し直す（Aはbootstrap loginから、BはOIDC sessionから）。
  - まだadminしか居ない場合: DBAが上の `t_user_mfa`・`t_mfa_recovery_code`・`t_user_session` のSQLでadminの登録を解除し、adminが再loginして `/mfa/setup` で登録し直す。
- bootstrap終了後にbreak-glass管理者がTOTPとrecovery codeを両方失った場合: OIDC管理者が上のresetを行い、break-glass incident（起票C・承認D/E）を有効化して、本人がbreak-glass loginし `/mfa/setup` で登録し直す（未登録のbreak-glass loginはMFA登録へ進む既存の流れ）。再登録が済む前に再起動すると起動時検査（MFA enrollment完了済み）で止まるため、resetから再登録までを同じ作業枠で行う。止まった場合は§5.7の再開で登録する。

**adminのlock（5回失敗で30分）**:
- 30分待つ（JVMの時区が固定されていること。§4）。
- 急ぐ場合、または時区が未固定でlockが延びている場合は、DBAが解除する。
  ```sql
  UPDATE sys_user SET failed_count = 0, locked_until = NULL WHERE username = 'admin';
  ```
- 繰り返しlockされる場合は、作業端末以外から `/login` に到達している疑いがある。LBのアクセス元制限を確認し、侵害の疑いとして上の対応を検討する。

### 5.9 break-glass sessionでの初回パスワード変更

break-glass管理者のlocal passwordに初回変更要求（`password_change_required = 1`、またはsessionの変更要求）がある場合だけ、break-glass sessionでも `GET /password/change` と `PUT /api/profile/password`（とその画面の静的資源）をincidentのscope外で使える（D1 review P2-1）。それ以外の要求は従来どおりincidentのscopeで判定し、変更要求が無いsessionではこの2つもscopeで判定する（変更画面はaction keyを持たないため、scopeへ入れても通らない）。MFA認証後の `/` はincidentのscopeに `dashboard.view` が無いと403になるため、その場合は `/password/change` を直接開く。

## 6. ロールバック

- **初回導入**: 業務dataが無いため、アプリを停止し、DBAがschemaを削除して空で作り直せば導入前の状態へ戻る。artifactを差し替えて§3から再実行する。
- **2回目以降のrelease**: Flyway Community版にundoは無い。§2.5のbackup（PITR）からDBを戻し、旧artifactを起動する。
  - DBを戻さずに旧artifactだけを起動することはできない。DBが旧artifactより新しいと、起動時schema guardが「artifactに無いmigrationが適用済み」として起動を止める（意図した挙動）。
  - 新旧artifactを同時に稼働させない（rolling禁止。`scheduler-claim-fence-plan.md` §10.1）。

## 7. 参照

- `.kiro/specs/production-launch/feature-switches.md`（機能スイッチ）
- `.kiro/specs/scheduled-management-reporting/scheduler-claim-fence-plan.md` §10（drain・検出SQL）
- `ops/backup/runbooks/`（backup・restore）
- W1-B3 独立review（`FlywayProdFreshInstallMySqlTest` の前版と、本書の前提となった事実確認）
