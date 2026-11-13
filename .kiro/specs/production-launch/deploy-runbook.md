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
- 実装（配備バッチD1）:
  - job設定: `ops/flyway/flyway-prod.conf`（唯一の定義）。`pom.xml` の `prod-migrate` profileが読む。
  - prod専用script: `db/migration-prod/R__update_admin_password_bcrypt.sql`（初期管理者）、`db/migration-prod/R__remove_v2_sample_data.sql`（V2 sample削除）。
  - 初期管理者hashの検査: `com.ses.config.ProductionAdminInitialPasswordFlywayCallback`（job設定からclass名で登録）。
  - prodの起動時migrate禁止と起動時schema guard: `application-prod.yml`（`spring.flyway.enabled: false`）、`com.ses.config.ProductionSchemaGuardConfiguration` / `ProductionSchemaVersionGuard`。
  - 初回パスワード変更: `V186__sys_user_password_change_required.sql`、`PasswordChangeEnforcementFilter`、`/password/change`。
  - 期限付きbootstrap mode: `ProductionBootstrapProperties`（`app.security.bootstrap.*`）、`ProductionBootstrapMode`、`BootstrapSessionFilter`。IdPの登録API: `POST /api/identity-providers`（`IdentityProviderApiController`）。
  - 検証: `FlywayProdFreshInstallMySqlTest`（実MySQL、job設定そのものを読む）、`ProductionFlywayJobConfigurationTest`、`ProductionSchemaVersionGuardTest`、`PasswordChangeEnforcementIntegrationTest`、`ProductionBootstrapIntegrationTest`、`ProductionBootstrapModeTest`、`ProductionSecurityConfigurationValidatorBootstrapTest` ほか。

## 0. 全体の流れ

| 順 | 作業 | 実施者 | 完了条件 |
|---|---|---|---|
| 1 | 前提条件の確認（§1） | DBA / SRE / Security | §1の全項目が確認済み |
| 2 | 全停止とdrain（§2） | SRE | アプリ接続0件・scheduler停止 |
| 3 | 専用Flyway jobの実行と確認（§3） | SRE（DBA立会い） | §3.4の確認SQLが全て期待値 |
| 4 | アプリ起動（§4。初回はbootstrap mode） | SRE | schema guardのINFO log、bootstrapのWARN log、readiness OK |
| 5 | 初回bootstrap（§5） | 初期管理者担当 / IdP管理者 / Security | §5.4の完全な起動検査が通る |
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
  - **trigger（56件）とbinlog**: binlogが有効で `log_bin_trust_function_creators = 0` のとき、`CREATE TRIGGER` はSUPER相当の権限を要求する。RDS等でSUPERを渡せない場合は parameter group で `log_bin_trust_function_creators = 1` にするか、DBAが代替を決める。**testは `--skip-log-bin` かつrootで実行しており、この条件は検証していない**（DBA確定後に同条件のDB変種で検証する。W1-B3 review §9.4）。
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

### 1.5 未決事項（配備前に決定が必要）

1. **初回起動のbootstrap**: **決定済み（方式A: 期限付きbootstrap mode）**。空DBにはadmin 1件だけでMFA・IdP・外部identityも無く、通常のprod設定では最初の1人がログインできない（`ProductionSecurityConfigurationValidator` もbreak-glass管理者2件・MFA登録済みを起動条件にしている）。期限付きで初期adminのlocal loginだけを開け、bootstrapに必要な入口だけを使わせる（§4・§5）。運用上の前提（break-glass incidentの承認者数等）は§5.5を参照し、Owner/Securityが人数を確定する。
2. §1.2のmigrator権限・trigger/binlog・DEFINERの扱い（DBA）。
3. MySQLの版・collation・parameter group（DBA）。
4. job実行環境（Maven・JDK・依存取得経路。依存を事前に取得したlocal repositoryを使うか）（SRE）。

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

### 3.2 事前確認（任意・推奨）

```bash
mvn -B -Pprod-migrate clean compile flyway:info
```
- 全migrationが `Pending`、エラー無しであること（Java migration `74.3` とprodのRepeatable 2本を含む）。
- placeholderが未設定だとここで失敗する（`No value provided for placeholder`）。

### 3.3 migrate

```bash
mvn -B -Pprod-migrate clean compile flyway:migrate
```
- `compile` を先に行うのは、classpath location（`target/classes`）からJava migration（`db.migration.V74_3`）とcallback classを解決するため。
- 実行順: V1〜V186（versioned）→ Repeatable（説明の昇順: `crm contact reconciliation` → `remove v2 sample data` → `update admin password bcrypt`）。
- callback: `ProductionAdminInitialPasswordFlywayCallback`（hash検査）→ `LegacyDatabaseFlywayCallback`（空DBでは何もしない）。

### 3.4 適用結果の確認（DBA立会い）

```bash
mvn -B -Pprod-migrate flyway:validate   # 3.3と同じcheckout・同じ環境変数で実行
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
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE();           -- 56（静的計数。初回の検証環境の結果で確定する）
```

- `R__remove_v2_sample_data.sql` は「本scriptの成功履歴が無い」かつ「sample 5表がV2の行だけ」のときだけ削除する。条件を満たさない場合は何も削除せず成功扱いになるため、上の件数が0でなければ実データ混入の可能性として停止し、DBAと調査する（手作業で消さない）。

### 3.5 jobが失敗した場合

- **hash検査・placeholder未設定で失敗した場合**: migrationは1件も実行されていない（履歴表は空、またはそもそも無い）。環境変数を直して§3.3を再実行する。`flyway repair` は不要。
- **migrationの途中で失敗した場合（初回導入）**: MySQLのDDLはtransactionで戻らないため、失敗行（`success = 0`）と途中までのschemaが残る。初回は業務dataが無いので、`flyway repair` で続行せず、**DBAがschemaを削除して空で作り直し**、原因を直してから§3.3を最初からやり直す。`flyway clean` は使わない（設定で拒否している）。
- **2回目以降のreleaseで失敗した場合**: §2.5で取得したbackupから戻すことを基本とする。`CLAUDE.md` のrepair手順はlocal開発DB向けであり、本番では許可済みscriptの確認・backup・DBA承認を経てだけ使う。

## 4. アプリ起動（SRE）

- 環境変数: `SPRING_PROFILES_ACTIVE=prod`（prod単独）、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`（アプリaccount）、その他prodのsecret。
- **`SPRING_FLYWAY_ENABLED` は設定しない**。`true` にしても `ProductionSchemaGuardConfiguration` がSpring BootのFlyway初期化でmigrateを拒否し、1件も適用せずに起動を止める。
- 初回は `APP_SCHEDULING_ENABLED=false` で1台だけ起動する。
- 起動時schema guard: DBの `flyway_schema_history` とartifactのmigrationを照合し、一致しなければTomcatのport・schedulerを開始する前に起動を中止する。
  - 成功: INFO log `[schema guard] DBのschemaはartifactの最新migration V186 と一致しています`
  - 失敗: ERROR log `[schema guard] DBのschemaがartifactと一致しないため起動を中止します。...`（未適用・失敗行・artifactに無いmigration・最新versionの不一致・一度も適用されていないRepeatableを列挙する）。jobの結果（§3.4）とartifactのSHA（§1.4）を確認する。
  - guardはplaceholderを置換しないため、アプリへ初期管理者hashを渡す必要は無い。placeholderを含むRepeatable（初期管理者）は「同じscriptの成功適用が履歴にある」ことだけを確認する。jobが `db/migration-prod` を落として実行された（`-Dflyway.locations` 等の上書き）場合は、prodのRepeatableが一度も適用されていないとして起動を止める。
- `/actuator/health/readiness` がUPであることを確認する。
- schedulerは、Report Owner等の署名後に `APP_SCHEDULING_ENABLED=true` で再起動して有効化する。機能スイッチは `feature-switches.md` に従い1機能ずつONにする。

### 4.1 初回起動（bootstrap mode）の環境変数

初回だけ、§5のbootstrapを行うために次を設定して起動する。それ以外のprodのsecurity設定（OIDCの固定metadata・client credential、MFA鍵、session hash key等）は通常どおり全て設定する（bootstrap中も起動時検査される）。

| 環境変数（property） | 値 |
|---|---|
| `SECURITY_BOOTSTRAP_ENABLED`（`app.security.bootstrap.enabled`） | `true` |
| `SECURITY_BOOTSTRAP_EXPIRES_AT`（`app.security.bootstrap.expires-at`） | 期限。**offset付きISO-8601**（例 `2026-11-14T18:00:00+09:00`）。起動時点から**24時間以内**の未来 |
| `OIDC_TENANT_ID`（`app.security.oidc.tenant-id`） | `default`（V2のadminと権限groupのtenant。未設定だとIdPの登録・OIDC loginができない） |
| `BREAK_GLASS_USERNAMES` | **設定しない（空）**。bootstrap中に設定すると起動しない（§5.4で設定する） |
| `LOCAL_LOGIN_ENABLED` | 設定しない（既定false）。bootstrap中も `true` は起動しない |

- 起動の拒否（fail-fast、Tomcatのportを開く前）: `SECURITY_BOOTSTRAP_ENABLED=true` なのに期限が未設定・offset無し・既に過ぎている・現在から24時間超。ERROR/例外messageは `[bootstrap] ...` で始まる。
- 起動時のWARN log: `[bootstrap] 期限付きbootstrap modeが有効です（期限: ...）` と `[bootstrap] 期限付きbootstrap mode中のため、break-glass管理者の登録検査を省略します`。この2行が出ていることを確認する。
- 期限を過ぎると、**再起動しなくても** `admin` のlocal loginは拒否され、既存のbootstrap sessionも次の要求で失効する（API 401 / 画面はlogin画面へ）。作業が終わらなかった場合は、新しい期限（24時間以内）を設定して再起動する。
- prod以外のprofileではbootstrap modeの設定は無視される（dev/testの挙動は変わらない）。

## 5. 初回bootstrap（期限付きbootstrap mode）

§4.1の設定で起動した後、次の順に行う。bootstrap中にlocal password loginできるのは初期管理者 `admin` だけで、他のユーザーのlocal loginは拒否される。OIDC loginは通常どおり使える。

### 5.1 adminのログイン・パスワード変更・MFA登録

1. 初回ログイン担当者が、§1.3で受け取った初期パスワードで `admin` としてログインする（bootstrap中だけlogin画面にusername/password formが出る）。
2. **パスワード変更が先**。全画面が `/password/change` へ、全APIが403（`パスワードの変更が必要です`）になる（`PasswordChangeEnforcementFilter`。通るのは変更画面・`PUT /api/profile/password`・logout・静的資源だけ）。現在のパスワード（初期パスワード）と新しいパスワード（8文字以上で英字と数字を含む）を入力して保存する。
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
| 権限group・role別menu | `permission.manage`（`/api/permission-groups/**`、`/api/role-menus/**`） |
| IdPと外部identity link | `identity-provider.*`（`/api/identity-providers`、`/api/identity-providers/{id}/external-identities`） |
| MFA・本人session・logout | `/mfa/setup`、`/mfa/challenge`、`/api/security/mfa/{status,setup,enable,verify}`、`/api/security/sessions/**`、`POST /logout` |
| パスワード変更 | `GET /password/change`、`PUT /api/profile/password` |
| 画面の静的資源・i18n | `/css/**`、`/js/**`（`/js/i18n.js` を含む）、`/lib/**`、`/img/**`、`/favicon.ico` |

この制限はbootstrap sessionだけに掛かる一時制限で、業務権限（baseline + deny）には影響しない。OIDCでログインした管理者のsessionには掛からない。

### 5.3 IdP・2人目以降の管理者・外部identityの登録（bootstrap session）

1. **IdPを登録する**。画面は無いため、`/user/list` を開いたままブラウザの開発者consoleで次を実行する（`SES.api` がCSRF tokenを付ける）。登録内容はrequestから受け取らず、起動設定（`OIDC_TENANT_ID` / `OIDC_ISSUER_URI` / `OIDC_CLIENT_ID`）から作られる。client secretはDBへ保存しない。
   ```javascript
   const provider = await SES.api.post('/api/identity-providers', {});   // 同じissuerが登録済みならその行を返す
   provider.id   // 以降の {providerId}
   ```
2. **管理者を作成する**。ユーザー管理画面で、OIDCでログインする管理者（role `管理者`）を作成する（作成時にroleの既定権限groupが割り当てられる）。パスワードはlocal loginで使う管理者（§5.4のbreak-glass管理者候補）だけが意味を持ち、Securityが生成した十分な長さの値にする。
3. **外部identity linkを登録・承認する**。IdP管理者から各管理者のOIDC `sub`（subject。emailではない）を受け取り、consoleで次を実行する（登録と同時にAPPROVEDになり、承認監査 `OIDC_BINDING_APPROVED` が残る）。
   ```javascript
   await SES.api.post('/api/identity-providers/' + provider.id + '/external-identities',
       { userId: <sys_user.id>, subject: '<IdPのsub>', emailSnapshot: '<任意。sys_user.emailと一致させる>' });
   ```
4. 作成した管理者は、それぞれOIDCでログインして通常の画面を使えることを確認する（IdP側のMFA assuranceが必要）。

### 5.4 break-glass管理者のMFA登録とbootstrapの終了

1. **break-glass管理者候補のMFA登録**。bootstrap中だけ、OIDCでログインした有効な管理者も `/mfa/setup` でTOTPを登録できる（通常はbreak-glass usernameにしか許されない）。break-glass管理者に指定する2人目は、bootstrap中にOIDCでログインして `/mfa/setup` で登録を済ませる。`admin` は§5.1で登録済み。
2. **break-glass usernameを決める（ちょうど2件）**。いずれも有効な `管理者` で、local password（§5.3の2で設定）とMFA登録を持つこと。推奨は `admin` と、§5.4の1で登録した1名。
3. **bootstrapを無効にして再起動する**。`SECURITY_BOOTSTRAP_ENABLED` を外し（`SECURITY_BOOTSTRAP_EXPIRES_AT` も外す）、`BREAK_GLASS_USERNAMES=<2名をカンマ区切り>` を設定して再起動する。
4. **完全な起動検査が通ることを確認する**。`[bootstrap]` のWARN logが出ず、`ProductionSecurityConfigurationValidator` の例外（`本番security configurationが安全な既定値を満たしません`）で停止しないこと。readinessがUPになり、OIDCで管理者がログインできること。break-glass管理者がMFA未登録なら、ここで `break-glass usernameはMFA enrollment完了済みでなければなりません` で起動が止まる（その場合はbootstrapを再度有効にして§5.4の1からやり直す）。

### 5.5 運用上の注意（Owner/Securityが確認する）

- **break-glass usernameに指定した管理者はOIDCではログインできなくなる**（OIDCのsessionもincident未束縛のbreak-glass sessionとして失効させる既存仕様）。break-glass管理者はIdP障害時のlocal login専用の口座になる。
- **break-glass incidentの起票と承認には、break-glass管理者以外の異なる3名の管理者（起票者＋承認者2名）がログインしている必要がある**。break-glass管理者2名とは別に、OIDCでログインできる管理者を少なくとも3名用意しないと、障害時にincidentを有効化できない（起動検査はこの人数を見ない）。
- 監査（`t_audit_log`）に残るbootstrapのapplication code: `BOOTSTRAP_LOGIN_SUCCESS` / `BOOTSTRAP_LOGIN_FAILURE` / `BOOTSTRAP_LOGIN_LOCKED` / `BOOTSTRAP_LOGIN_EXPIRED`（期限切れ後のlogin拒否）/ `BOOTSTRAP_SESSION_EXPIRED`（期限切れsessionの失効）/ `BOOTSTRAP_WRITE_ATTEMPT`・`BOOTSTRAP_WRITE`（bootstrap sessionの書込操作。実行前の必須監査と結果）/ `BOOTSTRAP_SCOPE_DENIED`（入口外の要求）/ `BOOTSTRAP_MFA_ENABLED`・`BOOTSTRAP_MFA_VERIFIED` / `IDENTITY_PROVIDER_REGISTERED`。bootstrap終了後にSecurityが一覧を確認する。
  ```sql
  SELECT created_at, username, method, uri, status, application_code FROM t_audit_log
   WHERE application_code LIKE 'BOOTSTRAP%' OR application_code IN ('IDENTITY_PROVIDER_REGISTERED', 'OIDC_BINDING_APPROVED')
   ORDER BY created_at;
  ```
- LB/ingressでbootstrap中のアクセス元を作業端末に限定することを推奨する（bootstrap中はadminのlocal loginが開いているため）。

### 5.6 break-glass sessionでの初回パスワード変更

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
