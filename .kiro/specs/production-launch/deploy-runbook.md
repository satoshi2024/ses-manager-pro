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
- 実装（配備バッチD1）:
  - job設定: `ops/flyway/flyway-prod.conf`（唯一の定義）。`pom.xml` の `prod-migrate` profileが読む。
  - prod専用script: `db/migration-prod/R__update_admin_password_bcrypt.sql`（初期管理者）、`db/migration-prod/R__remove_v2_sample_data.sql`（V2 sample削除）。
  - 初期管理者hashの検査: `com.ses.config.ProductionAdminInitialPasswordFlywayCallback`（job設定からclass名で登録）。
  - prodの起動時migrate禁止と起動時schema guard: `application-prod.yml`（`spring.flyway.enabled: false`）、`com.ses.config.ProductionSchemaGuardConfiguration` / `ProductionSchemaVersionGuard`。
  - 初回パスワード変更: `V186__sys_user_password_change_required.sql`、`PasswordChangeEnforcementFilter`、`/password/change`。
  - 検証: `FlywayProdFreshInstallMySqlTest`（実MySQL、job設定そのものを読む）、`ProductionFlywayJobConfigurationTest`、`ProductionSchemaVersionGuardTest`、`PasswordChangeEnforcementIntegrationTest` ほか。

## 0. 全体の流れ

| 順 | 作業 | 実施者 | 完了条件 |
|---|---|---|---|
| 1 | 前提条件の確認（§1） | DBA / SRE / Security | §1の全項目が確認済み |
| 2 | 全停止とdrain（§2） | SRE | アプリ接続0件・scheduler停止 |
| 3 | 専用Flyway jobの実行と確認（§3） | SRE（DBA立会い） | §3.4の確認SQLが全て期待値 |
| 4 | アプリ起動（§4） | SRE | schema guardのINFO log、readiness OK |
| 5 | 初回ログインとパスワード変更（§5） | 初期管理者担当 | `password_change_required = 0` |
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

job実行環境へ渡す値は環境変数の次の4つだけ。POM・Maven property・`-D`・コマンド引数には書かない（`-X` の設定dumpやprocess一覧へ出さない）。

| 環境変数 | 内容 |
|---|---|
| `FLYWAY_URL` | `jdbc:mysql://<host>:3306/ses_manager_db?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Tokyo` |
| `FLYWAY_USER` / `FLYWAY_PASSWORD` | migrator account |
| `FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH` | 初期管理者パスワードのBCrypt hash |

- 初期管理者パスワードは、Securityが十分な長さの乱数で生成し、hashだけをsecret storeへ登録する。平文は初回ログイン担当者へ安全な経路で1回だけ渡す。
- hashはBCryptの `$2a$` / `$2b$` / `$2y$`、cost 10以上に限る。未設定・空・BCrypt以外（平文を誤って入れた場合を含む）・旧scriptが書いていた admin123 の公開hashは、jobがmigrationを1件も実行する前に失敗する（`ProductionAdminInitialPasswordFlywayCallback`）。値はlogへ出さない。
- **hashは毎回のjob実行に必要**。placeholderがRepeatableのchecksumに入るため、以後のreleaseでも同じsecretを渡す。値を変えると初期管理者scriptが再実行されるが、adminが初期状態でなければ何もしない（初回適用後は上書きしない）。
- 上の4つ以外の `FLYWAY_*` 環境変数は設定しない（`ops/flyway/flyway-prod.conf` の値より優先されるため）。`~/flyway.conf` もjob実行環境に置かない。
  ```bash
  env | grep '^FLYWAY_' | cut -d= -f1 | sort
  # 期待: FLYWAY_PASSWORD / FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH / FLYWAY_URL / FLYWAY_USER の4行だけ
  ```
- 秘匿上の注意: hashは `R__update_admin_password_bcrypt.sql` の `SET` 文1行だけに展開される。その文が失敗するのは値に引用符等が含まれる（＝BCryptではない）場合だけだが、jobのlogは秘匿扱いで保管する。job実行中はDBの general log を無効にする。`flyway_schema_history.checksum` にはhashを含む内容のCRC32が残る（hash自体は復元できない）。

### 1.4 artifactとjobの一致（SRE）

- jobはsource checkoutで `compile` したclass/resourceを使う。**jobのcommit SHAと、配備するimage（docker save/load、digest記録。決定XB-10）のSHAを一致させる**。
  ```bash
  git rev-parse HEAD   # imageの記録SHAと一致すること
  git status --porcelain   # 空であること
  ```
- `clean` を必ず付ける（`target/classes` に古いmigrationが残ると、jobとartifactのmigrationがずれる）。

### 1.5 未決事項（配備前に決定が必要）

1. **初回起動のbootstrap**: 現行の `ProductionSecurityConfigurationValidator`（prodのApplicationRunner）は、OIDC有効・通常local login無効・break-glass管理者usernameがちょうど2件でいずれもMFA登録済み、を起動条件にしている。空DBにはadmin 1件だけでMFAも無いため、**このままではprodのアプリは初回起動できず、初期adminでの初回ログイン（§5）にも到達できない**。break-glass incidentも異なる2名の承認を要する。bootstrapの方式（例: 期限付きbootstrap modeの追加、2人目の管理者とMFAの事前投入手順）はOwner/Securityの決定が要る。本書§4〜§5はその決定後に確定する。
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
  - 失敗: ERROR log `[schema guard] DBのschemaがartifactと一致しないため起動を中止します。...`（未適用・失敗行・artifactに無いmigration・最新versionの不一致を列挙する）。jobの結果（§3.4）とartifactのSHA（§1.4）を確認する。
  - guardはplaceholderを置換しないため、アプリへ初期管理者hashを渡す必要は無い。
- `/actuator/health/readiness` がUPであることを確認する。
- schedulerは、Report Owner等の署名後に `APP_SCHEDULING_ENABLED=true` で再起動して有効化する。機能スイッチは `feature-switches.md` に従い1機能ずつONにする。

## 5. 初回ログインとパスワード変更

§1.5の1（bootstrap）が決まるまで確定しない。決定後、初期adminがlocal password login（通常local loginまたはbreak-glass）できる状態であることを前提に次を行う。

1. 初回ログイン担当者が、§1.3で受け取った初期パスワードで `admin` としてログインする。
2. MFAが必要な場合はMFAを先に完了する。その後、全画面が `/password/change` へ、全APIが403（`パスワードの変更が必要です`）になる（`PasswordChangeEnforcementFilter`。通るのは変更画面・`PUT /api/profile/password`・logout・静的資源だけ）。
3. 変更画面で現在のパスワード（初期パスワード）と新しいパスワードを入力して保存する。パスワード方針は8文字以上で英字と数字を含む。
4. 確認:
   ```sql
   SELECT password_change_required FROM sys_user WHERE username = 'admin';  -- 0
   ```
5. 初期パスワードの控えを破棄する。secret storeのhashは以後のjob実行で必要なため残す（adminの変更後は使われない）。
- OIDCでログインした場合はlocal passwordを使わないため、この変更要求の対象外である。初期adminのlocal passwordは初回のlocal loginで必ず変更させる。
- 既知の制約: break-glass sessionではincidentのscopeに変更画面・変更APIが含まれないと403になる。初期adminの初回ログインをbreak-glass経由で行う場合は、bootstrapの決定（§1.5）で扱う。
- 初回ログイン前に初期パスワードを紛失した場合: jobを再実行しても上書きしない（一度だけ）。Securityが新しいhashを作り、DBAが立会いの下で次を実行する（general logを無効にした接続で行い、値を履歴に残さない）。
  ```sql
  UPDATE sys_user SET password = '<新しいBCrypt hash>', password_change_required = 1,
         failed_count = 0, locked_until = NULL
   WHERE username = 'admin' AND password_change_required = 1;
  ```

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
