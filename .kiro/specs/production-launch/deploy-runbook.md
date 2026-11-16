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
  - NF07 F1 + PERM（W3-14）: `V190__nf07_privacy_f1_core.sql`（F1表。行は作らない）、`V191__nf07_privacy_permission_seed.sql`（privacy-admin・privacy.* の拒否・menu）。prodの起動検査 `ProductionSecurityConfigurationValidator` は、全体baseline `'*'` を持つgroup（privacy-admin以外）に privacy.* の拒否行が無ければ起動を止める（bootstrap中も同じ）。`privacy.retention.enabled` / `privacy.dsar.enabled` は既定OFFで、初回リリースはOFFのまま配備する。1つでもONにした起動では `PrivacyProductionConfigurationValidator`（W3-14b）が決定ID束縛（`privacy.approval.*`、D-01・D-09）、privacy-admin の有効な管理者2名以上（OIDCでログインできる管理者だけ。break-glass口座を除く。ロック中も数える。推奨は §5.0 の C〜E の3名）、privacy-admin 以外のgroupに `privacy.` 系の許可行が無いことを検査し、満たさなければ起動しない。決定の署名日前・失効だけなら起動は止めず、WARN `NF07_PRIVACY_STOPPED_AT_STARTUP` を出してNF07をOFFとして扱う（実行中に失効日を迎えた場合も同日から。metric `ses_privacy_decision_binding_state`。ONの手順: `feature-switches.md` 4.7）。新しいmigrationは無い。
  - NF-06 取込センター画面（W3-12）: `V193__nf06_import_center_menu_and_permission_seed.sql`（menu `import-center` を管理者だけへ付与、`import.metadata.manage` を管理者専用group（`role-admin`・`privacy-admin`）へ明示許可し、他の全groupで拒否する。利用者の割当は変えない）。機能スイッチNF06は既定OFFで、初回リリースはOFFのまま配備する。ONにした起動では admission 承認設定（`ImportAdmissionStartupValidator`）と行参照のHMAC鍵 `IMPORT_ROW_REFERENCE_HMAC_KEY`（`ImportRowReferenceKeyStartupValidator`）が揃わなければ起動しない。前提は単一法人（`m_organization_unit` の論理削除されていない組織の法人IDがちょうど1種類。status・有効期間は見ない。新規導入は0件のため、ONの前に法人を持つ組織を登録する）。ONの手順: `feature-switches.md` 4.8。
  - NF08 B2c-1（W3-13）: `V194__nf08_copilot_dispatch_claim_and_cost_ledger.sql`（summary dispatchのclaim列、費用ledgerの `t_ai_external_send_cost_bucket` / `t_ai_external_send_cost_entry`、`t_audit_log.idx_audit_dispatch_key`。行は作らない）。外部送信は未開放のままで、prodは `AI_EXTERNAL_SEND_ENABLED=true` を `dispatch.permitOnlyNotWired` で起動拒否する（B2c-2の完了と独立Reviewまで。設定では解除できない）。`AI_EXTERNAL_SEND_COST_LEDGER`（`ai.external-send-cost-ledger`）は既定の `persistent` のまま設定しない（`persistent` 以外は解除後も起動拒否）。
  - NF01 B0（W3-18）: `V195__nf01_dual_confirmation_coordinator.sql`（二者確認coordinatorの7表 `t_lifecycle_dual_submission` / `_submission_document` / `_submission_org_fence` / `_confirmation` / `_projection` / `_idempotency` / `_event`。行は作らない。projection以外の不変6表へUPDATE・DELETEを拒否するtrigger 12件を追加する。冪等性keyは `ascii_bin` で、CHECK `ck_dual_idem_key` が空文字と `[A-Za-z0-9._~-]` 以外の文字を拒否する）。アプリ側は `AppendOnlyMapper`・`AppendOnlySqlInjector` で同じ6表の更新・削除を拒否する。二者確認の画面・APIはまだ無く（W3-21a/b）、表は空のまま配備する。trigger件数は §1.2・§3.4 の 67件に含まれる（`DeployRunbookMigrationCountTest` がmigrationのCREATE/DROP TRIGGERから数えて照合する）。自由記述列の保存期間はPrivacyの署名事項（NF01計画 §10・§12 O-8）。
  - NF07 F2（read-only provider）: `V196__nf07_privacy_data_element_catalog_seed.sql`（`m_pii_data_element` に、`sys_user` に存在する tenant ごとの data element の構造行と `subject_attribution` だけを入れる。owner・データ分類・inventory 状態は UNKNOWN のまま、保持期間は持たない。既にある行は入れない）。新規導入では tenant `default` に文書以外の固定27件＋文書種別ごとの `DOCUMENT:<code>`（`m_document_type` の全種別。V197 の2種別を含む）。catalog API `GET /api/privacy/catalog` は `privacy.retention.enabled` がOFFの間404で、初回リリースはOFFのまま配備する。外部 provider（Gemini・CloudSign・freee・mailbox・backup）は登録しない。
  - 文書種別の追加（既存不具合の修正）: `V197__resume_ingestion_and_import_document_types.sql`（`m_document_type` に `RESUME_INGESTION`（履歴・スキルシート取込）と `IMPORT`（取込センターCSV）を INCOMING で入れ、catalog に `DOCUMENT:IMPORT`（NOT_ATTRIBUTABLE。取込CSVは本人を導出できない）を足す。既にある行は入れない）。これまではどの migration も2種別を作っておらず、スキルシート取込の upload は `error.document.invalidType`（400）で毎回失敗していた。保存年数（3年・TRANSACTION_DATE）は spec に根拠が無い暫定値で文書 owner の確認待ち。取込センターCSV（IMPORT）は取引日を設定しないため保存期限は NULL のままで、処分候補にはならない（保持期間は開放前に Privacy が書面で受理する条件のまま）。履歴取込の副本（RESUME_INGESTION）は、取込原本を `app.resume.retention-days` で purge する日次清理（02:00）が保存期限を当日に合わせ、02:30 の `DocumentRetentionJob` が廃棄候補（PENDING）にし、管理者の承認・執行で処分する（2026-11-16 依頼者決定。legal hold 中の副本は変えない。plan §3.4.1「D4 と文書台帳の副本」）。**署名事項**：この副本の処分方針について Privacy owner と文書 owner の書面署名を、scheduler の有効化（`APP_SCHEDULING_ENABLED=true`）の前に得る。署名が得られない場合は副本処理を外す変更を出してから有効化する。有効化後の監視（失敗の metric・ERROR log）と定期確認の SQL は §4.3。
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
  - **trigger（67件。V194までの55件＋V195の12件）とbinlog**: binlogが有効で `log_bin_trust_function_creators = 0` のとき、`CREATE TRIGGER` はSUPER相当の権限を要求する。RDS等でSUPERを渡せない場合は parameter group で `log_bin_trust_function_creators = 1` にするか、DBAが代替を決める。**testは `--skip-log-bin` かつrootで実行しており、この条件は検証していない**（DBA確定後に同条件のDB変種で検証する。W1-B3 review §9.4）。
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
- 実行順: V1〜V197（versioned 197件。SQL 196件＋Java migration `V74_3` 1件）→ Repeatable（説明の昇順: `crm contact reconciliation` → `remove v2 sample data` → `update admin password bcrypt`）。
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
 ORDER BY installed_rank DESC LIMIT 1;                                                         -- '197'
-- versioned migrationの適用件数（本releaseの総件数）
SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success = 1;          -- 197
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

-- NF07 W-5（V191）: privacy-admin は1件、privacy.* の拒否は privacy-admin 以外の全group（新規導入では5件）、menu 2件
SELECT COUNT(*) FROM m_permission_group WHERE group_key = 'privacy-admin';                    -- 1
SELECT COUNT(*) FROM t_permission_group_action a JOIN m_permission_group g ON g.id = a.group_id
 WHERE a.action_key = 'privacy.*' AND a.deny_flag = 1 AND g.group_key <> 'privacy-admin';    -- 5
SELECT COUNT(*) FROM t_permission_group_action a JOIN m_permission_group g ON g.id = a.group_id
 WHERE g.group_key = 'privacy-admin' AND a.deny_flag = 0 AND a.action_key IN ('*', 'privacy.*'); -- 2
SELECT COUNT(*) FROM t_user_permission_group u JOIN m_permission_group g ON g.id = u.group_id
 WHERE g.group_key = 'privacy-admin';                                                        -- 0（割当は監査・通知付きの画面操作だけで行う）
SELECT menu_key, sort_order FROM m_menu WHERE menu_key IN ('privacy-retention', 'privacy-dsar') ORDER BY sort_order;
-- privacy-retention 260 / privacy-dsar 261
-- NF-06（V193）: menu 1件（管理者だけ）、import.metadata.manage の明示許可は管理者専用group 2件、拒否は他の全group（新規導入では4件）
SELECT m.menu_key, m.sort_order, r.role FROM m_menu m JOIN t_role_menu r ON r.menu_id = m.id
 WHERE m.menu_key = 'import-center';                                                         -- import-center 262 管理者 の1行
SELECT g.group_key FROM t_permission_group_action a JOIN m_permission_group g ON g.id = a.group_id
 WHERE a.action_key = 'import.metadata.manage' AND a.deny_flag = 0 ORDER BY g.group_key;    -- privacy-admin / role-admin
SELECT COUNT(*) FROM t_permission_group_action a JOIN m_permission_group g ON g.id = a.group_id
 WHERE a.action_key = 'import.metadata.manage' AND a.deny_flag = 1
   AND g.group_key NOT IN ('role-admin', 'privacy-admin');                                   -- 4
-- NF07 F1（V190）: 表は8件作られ、catalog（m_pii_data_element、V196 の seed）以外に行は1件も無い（法的な値はseedしない）
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN
 ('m_pii_data_element', 'm_retention_policy', 't_legal_hold', 't_privacy_projection_state', 't_privacy_request',
  't_privacy_request_subject_link', 't_privacy_action', 't_privacy_export_package');      -- 8
SELECT (SELECT COUNT(*) FROM m_retention_policy) + (SELECT COUNT(*) FROM t_legal_hold)
     + (SELECT COUNT(*) FROM t_privacy_request);                                             -- 0
-- NF07 F2（V196・V197）: catalog は構造行だけ。tenant default に文書以外の固定27件と、文書種別ごとに1件
-- （文書種別の数に依存しない数え方にする）。owner・分類・inventory は全て UNKNOWN
SELECT COUNT(*) FROM m_pii_data_element
 WHERE tenant_id = 'default' AND data_element_id NOT LIKE 'DOCUMENT:%';                    -- 27
SELECT COUNT(*) FROM m_document_type dt WHERE NOT EXISTS (SELECT 1 FROM m_pii_data_element e
 WHERE e.tenant_id = 'default' AND e.data_element_id = CONCAT('DOCUMENT:', dt.code));      -- 0（全文書種別に catalog 行がある）
-- 文書種別（V197）: 履歴・スキルシート取込と取込センターCSVは受領（INCOMING）として登録できる
SELECT code, direction FROM m_document_type WHERE code IN ('IMPORT', 'RESUME_INGESTION')
   AND deleted_flag = 0 ORDER BY code;
-- IMPORT INCOMING / RESUME_INGESTION INCOMING の2行
SELECT data_element_id FROM m_pii_data_element WHERE tenant_id = 'default' AND data_element_id LIKE 'DOCUMENT:%'
   AND subject_attribution = 'NOT_ATTRIBUTABLE' ORDER BY data_element_id;
-- DOCUMENT:IMPORT / DOCUMENT:MANAGEMENT_REPORT / DOCUMENT:SERVICE_REQUEST_ATTACHMENT の3行（本人を導出できない文書種別）
SELECT COUNT(*) FROM m_pii_data_element WHERE owner_state <> 'UNKNOWN' OR data_class <> 'UNKNOWN'
    OR inventory_status <> 'UNKNOWN' OR owner_ref IS NOT NULL;                                -- 0
SELECT COUNT(*) FROM m_pii_data_element WHERE subject_attribution = 'UNKNOWN'
   AND (provider_id IS NOT NULL OR data_element_id LIKE 'DOCUMENT:%' OR data_element_id LIKE 'INTEGRATION_HUB_%'
        OR data_element_id = 'EXTERNAL_API_READ_SNAPSHOT');                                  -- 0（gateに接続する要素は分類済み）
-- NF08 B2c-1（V194）: 費用ledgerの2表があり行は無い。外部送信は未開放のため、claim済み・READYのattemptも無い
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN
 ('t_ai_external_send_cost_bucket', 't_ai_external_send_cost_entry');                         -- 2
SELECT (SELECT COUNT(*) FROM t_ai_external_send_cost_bucket)
     + (SELECT COUNT(*) FROM t_ai_external_send_cost_entry);                                  -- 0
SELECT COUNT(*) FROM t_ai_recommendation_run WHERE dispatch_state <> 'NOT_APPLICABLE';       -- 0
SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
   AND table_name = 't_audit_log' AND index_name = 'idx_audit_dispatch_key';                  -- 3（3列の索引）
-- NF01 B0（V195）: 二者確認coordinatorの7表があり行は無い。不変6表（projection以外）にUPDATE・DELETEのtriggerが各1件、
-- projectionにはtriggerが無い。冪等性keyの文字種CHECKがある
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN
 ('t_lifecycle_dual_submission', 't_lifecycle_dual_submission_document', 't_lifecycle_dual_submission_org_fence',
  't_lifecycle_dual_confirmation', 't_lifecycle_dual_projection', 't_lifecycle_dual_idempotency',
  't_lifecycle_dual_event');                                                                   -- 7
SELECT (SELECT COUNT(*) FROM t_lifecycle_dual_submission) + (SELECT COUNT(*) FROM t_lifecycle_dual_submission_document)
     + (SELECT COUNT(*) FROM t_lifecycle_dual_submission_org_fence) + (SELECT COUNT(*) FROM t_lifecycle_dual_confirmation)
     + (SELECT COUNT(*) FROM t_lifecycle_dual_projection) + (SELECT COUNT(*) FROM t_lifecycle_dual_idempotency)
     + (SELECT COUNT(*) FROM t_lifecycle_dual_event);                                         -- 0
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()
   AND event_object_table LIKE 't\_lifecycle\_dual\_%';                                        -- 12
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()
   AND event_object_table = 't_lifecycle_dual_projection';                                     -- 0
SELECT COUNT(*) FROM information_schema.check_constraints WHERE constraint_schema = DATABASE()
   AND constraint_name = 'ck_dual_idem_key';                                                   -- 1

-- helper procedureが残っていない・trigger件数
SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE();           -- 0
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE();           -- 67（V194までの55件は2026-11-14 R-3予行演習（MySQL 8.0.36・offline job）の実測値で、静的計数の56は誤り。V195の12件を加えた値。V195込みの実測は未実施）
```

- `R__remove_v2_sample_data.sql` は「本scriptの成功履歴が無い」かつ「sample 5表がV2の行だけ」のときだけ削除する。条件を満たさない場合は何も削除せず成功扱いになるため、上の件数が0でなければ実データ混入の可能性として停止し、DBAと調査する（手作業で消さない）。

### 3.5 jobが失敗した場合

- **hash検査・placeholder未設定で失敗した場合**: migrationは1件も実行されていない（履歴表は空、またはそもそも無い）。環境変数を直して§3.3を再実行する。`flyway repair` は不要。
- **migrationの途中で失敗した場合（初回導入）**: MySQLのDDLはtransactionで戻らないため、失敗行（`success = 0`）と途中までのschemaが残る。初回は業務dataが無いので、`flyway repair` で続行せず、**DBAがschemaを削除して空で作り直し**、原因を直してから§3.3を最初からやり直す。`flyway clean` は使わない（設定で拒否している）。
- **2回目以降のreleaseで失敗した場合**: §2.5で取得したbackupから戻すことを基本とする。`CLAUDE.md` のrepair手順はlocal開発DB向けであり、本番では許可済みscriptの確認・backup・DBA承認を経てだけ使う。

### 3.6 NF07 Journal インスタンスの専用Flyway job（NF07-A1J）

NF07 の hold の正本となる独立 Journal インスタンス（schema `privacy_journal`）の DDL は、application DB とは別の location（`db/privacy-journal`）・別の履歴表（`flyway_schema_history_privacy_journal`）・別の job で適用する（`journal-witness-grant-design.md` §2.5）。版番号は application の `db/migration` と独立している（Journal は V1 から）。

- **初回リリースでは実行しない**。Journal インスタンスの作成・DB user・GRANT は外部作業（G-DBA / G-BKP。`journal-witness-grant-design.md` §3）で、本job は NF07 の hold 開始（A1H）を開放する前に、DBA がインスタンスを用意した後で実行する。`privacy.retention.enabled` は既定OFFのままでよい（journal 設定が無ければ application は Journal へ一度も接続しない）。
- 前提（DBA）: Journal は application DB・Witness と**別 instance**（`@@server_uuid` が異なる）。`innodb_flush_log_at_trx_commit = 1`、`sync_binlog = 1`、`log_bin = ON`、`read_only = OFF`、`super_read_only = OFF`、`default_storage_engine = InnoDB`。空の `privacy_journal` schema を作り（`CREATE DATABASE privacy_journal CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;`）、Journal を application DB の restore 対象に含めない。
- job 専用の principal は `nf07_journal_migrator`（`privacy_journal.*` への DDL と履歴表。application へは渡さない）。job 実行環境へ渡す値は環境変数 `FLYWAY_URL`（`jdbc:mysql://<journal-host>:3306/privacy_journal?sslMode=VERIFY_IDENTITY`）・`FLYWAY_USER`・`FLYWAY_PASSWORD` の3つだけ。application DB の job（§3.3）とは**別のshell・別の実行**にし、§1.3 の `FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH` を設定しない。§1.3 の `mvn -X` 禁止・`flyway.conf` / `settings.xml` の確認も同じく守る。
  ```bash
  env | grep '^FLYWAY_' | cut -d= -f1 | sort
  # 期待: FLYWAY_PASSWORD / FLYWAY_URL / FLYWAY_USER の3行だけ
  mvn -B -o -Pprivacy-journal-migrate clean compile flyway:info      # V1 が Pending、エラー無し
  mvn -B -o -Pprivacy-journal-migrate clean compile flyway:migrate
  ```
- 設定は `ops/flyway/flyway-privacy-journal.conf` だけに書く（location は `classpath:db/privacy-journal` だけ、application の callback を持たない、`baselineOnMigrate=false`、`cleanDisabled=true`）。`-Pprod-migrate` と同時に指定しない。
- 適用結果の確認（DBA立会い。Journal インスタンス上で実行）:
  ```sql
  SELECT version, script, success FROM flyway_schema_history_privacy_journal ORDER BY installed_rank;
  -- 期待: 1 / V1__privacy_journal_core.sql / 1 の1行だけ
  SELECT table_name, engine FROM information_schema.tables WHERE table_schema = 'privacy_journal' ORDER BY table_name;
  -- 期待: flyway_schema_history_privacy_journal・pj_action・pj_command・pj_event・pj_head・pj_lease、全て InnoDB
  SELECT COUNT(*) FROM pj_head;  -- 0
  ```
- application の接続（hold 開始の開放時に設定。flag とは独立に、設定があれば起動時検査を行う）: `PRIVACY_JOURNAL_DATASOURCE_URL`（`jdbc:mysql://` だけ。`sslMode=VERIFY_IDENTITY` 必須、`autoReconnect`・時区 parameter・`socketTimeout`/`connectTimeout`・複数 host・URL 内の認証情報（`user@host`、`user=`・`password=`）・`%` による符号化・同じ parameter の重複は不可）、`PRIVACY_JOURNAL_DATASOURCE_USERNAME` / `PRIVACY_JOURNAL_DATASOURCE_PASSWORD`（principal は1組だけ。hold 開始を使うなら `nf07_journal_appender`、効力確認だけなら `nf07_journal_reader` または appender。secret store から注入）、`PRIVACY_JOURNAL_JOURNALID`（`privacy.journal.journal-id`。`[A-Za-z0-9._-]{4,64}`）。任意: `PRIVACY_JOURNAL_DATASOURCE_MAXPOOLSIZE`（1〜8、既定4）、`PRIVACY_JOURNAL_DATASOURCE_CONNECTIONTIMEOUT`（上限30秒、既定5秒）、`PRIVACY_JOURNAL_EXPECTEDSCHEMA`（既定 `privacy_journal`）、`PRIVACY_JOURNAL_PENDINGTIMEOUT`（設定するなら append の在途時間の上限 U＝接続待ち＋append の transaction 上限10秒＋socket timeout 20秒に、30秒以上の余裕を足した値。既定の接続待ち5秒なら65秒以上。起動時の契約検査が強制するのは U より長いことだけで、余裕はこの設定値で持たせる。plan §3.4 A1H-1 実装記録「不変条件と余裕」）。socket timeout（20秒）と append の transaction 上限（10秒）は固定値で、設定 key は無い。
- 起動時検査（`PrivacyJournalStartupVerifier`）: `server_uuid` の相違、上の耐久設定、`DATABASE()`、principal の権限（`information_schema` と `CURRENT_ROLE()`。role 経由の GRANT は不可）、本節の履歴表が artifact と一致（版・script 名・checksum）、`t_privacy_projection_state.journal_id` の一致を確かめる。prod で `privacy.retention.enabled=true` なら1つでも不適合（設定欠落を含む）で起動しない。この flag は決定ID束縛の有効期間を見ない生の値で判定する（署名日前・失効で NF07 が OFF 扱いでも、ON を設定していれば Journal を要求する。意図的な保守側の判定）。OFF なら起動を続け、WARN `NF07_PRIVACY_JOURNAL_UNAVAILABLE` と固定の理由code（接続文字列・user名・passwordは出さない）を出す。driver の接続失敗（認証失敗を含む）も固定文言に置き換えるため、Hikari の log に principal・接続先は出ない。`com.zaxxer.hikari` の log を DEBUG にしない。
- 失敗した場合: Journal は業務dataを持たず、初回は空なので、DBA が `privacy_journal` schema を削除して空で作り直し、原因を直してから再実行する。`flyway repair` / `clean` は使わない。hold を一度でも記録した後の Journal は正本であり、作り直さない（B2 の recovery 手順による）。

## 4. アプリ起動（SRE）

- 環境変数: `SPRING_PROFILES_ACTIVE=prod`（prod単独）、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`（アプリaccount）、その他prodのsecret（§4.1）。
- **`SPRING_FLYWAY_ENABLED` は設定しない**。`true` にしても `ProductionSchemaGuardConfiguration` がSpring BootのFlyway初期化でmigrateを拒否し、1件も適用せずに起動を止める。
- **JVMの時区を `Asia/Tokyo` に固定する（必須）**。`JAVA_TOOL_OPTIONS` に `-Duser.timezone=Asia/Tokyo` を加える（imageの既定 `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` を消さないよう、両方を書く）か、container環境変数 `TZ=Asia/Tokyo` を設定する。起動logの `Picked up JAVA_TOOL_OPTIONS: ...` で確認する。
  - 理由: account lockは `AccountLockServiceImpl` が `Asia/Tokyo` の時計で `sys_user.locked_until` を書き、login時の判定（`LoginUser.isAccountNonLocked`）はJVMの既定時区の `LocalDateTime.now()` と比べる。JVMがUTCのままだと30分のlockが約9時間30分続く（bootstrap中に `admin` がlockされると作業時間枠が実質閉じる。§5.6）。監査の `created_at` 等、JVMの既定時区で日時を作る箇所は他にもある。testは `argLine` で時区を固定しているが、本番は配備側で固定しない限り固定されない。DB側の時区（§1.1、`+09:00`）とは別に必要。
- 初回は `APP_SCHEDULING_ENABLED=false` で1台だけ起動する。
- 起動時schema guard: DBの `flyway_schema_history` とartifactのmigrationを照合し、一致しなければTomcatのport・schedulerを開始する前に起動を中止する。
  - 成功: INFO log `[schema guard] DBのschemaはartifactの最新migration V197 と一致しています`
  - 失敗: ERROR log `[schema guard] DBのschemaがartifactと一致しないため起動を中止します。...`（未適用・失敗行・artifactに無いmigration・最新versionの不一致・一度も適用されていないRepeatableを列挙する）。jobの結果（§3.4）とartifactのSHA（§1.4）を確認する。
  - guardはplaceholderを置換しないため、アプリへ初期管理者hashを渡す必要は無い。placeholderを含むRepeatable（初期管理者）は「同じscriptの成功適用が履歴にある」ことだけを確認する。jobが `db/migration-prod` を落として実行された（`-Dflyway.locations` 等の上書き）場合は、prodのRepeatableが一度も適用されていないとして起動を止める。
- `/actuator/health/readiness` がUPであることを確認する。
- schedulerは、Report Owner等の署名後に `APP_SCHEDULING_ENABLED=true` で再起動して有効化する。履歴取込の清理（02:00）は文書台帳の副本を処分対象にするため、Privacy owner・文書 owner の書面署名（V197 の行の署名事項）もこの前提に含める。機能スイッチは `feature-switches.md` に従い1機能ずつONにする。

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

### 4.3 履歴取込の副本の処分（scheduler 有効化後の監視・定期確認）

前提: V197 の行の署名事項（Privacy owner・文書 owner の書面署名）を scheduler の有効化の前に得ていること。日次清理（02:00）が原本を purge した取込ジョブの副本（`RESUME_INGESTION`）の保存期限を当日に合わせ、02:30 の `DocumentRetentionJob` が CONFIRMED の副本を廃棄候補（PENDING、`requested_by = -1`）にする。処分は管理者の承認・執行で行う（plan §3.4.1「D4 と文書台帳の副本」）。

- **アラート**: metric `ses.resume.ledger.retention.failures`（tag `stage`: `document`＝副本ごとの失敗、`batch`＝照会自体の失敗）が1件でも増えたら調べる。同時に app log の ERROR `履歴取込原本の purge に合わせた文書台帳の副本の処理に失敗した副本があります`（件数）または `文書台帳の副本の保存期限を合わせられませんでした`（照会の失敗）が出る。失敗した副本は翌日の実行で再試行されるため、2日続けて増える場合は DB の lock 待ち・障害を疑う。legal hold 中の副本は失敗に数えず、副本ごとの WARN だけを出す。
- **定期確認（週次）**: 次の SQL を実行する。

```sql
-- (1) 保存期限を過ぎたが D3 が拾わない副本（AMENDED・CANCELLED）。0行でなければ管理者が手動の廃棄申請（D2）を行う
SELECT d.tenant_id, d.id, d.status, d.retention_until FROM t_document d
 WHERE d.document_type = 'RESUME_INGESTION' AND d.deleted_flag = 0 AND d.status IN ('AMENDED', 'CANCELLED')
   AND d.retention_until <= CURRENT_DATE AND d.legal_hold_flag = 0
   AND NOT EXISTS (SELECT 1 FROM t_document_disposal_request r WHERE r.document_id = d.id
                    AND r.status IN ('PENDING', 'APPROVED'))
 ORDER BY d.tenant_id, d.id;
-- (2) 原本を purge 済みなのに保存期限を合わせられていない副本（legal hold 中を除く）。0行でなければ上のアラートと合わせて調べる
SELECT d.tenant_id, d.id, d.status, d.retention_until FROM t_document d
 WHERE d.document_type = 'RESUME_INGESTION' AND d.deleted_flag = 0 AND d.legal_hold_flag = 0
   AND d.status IN ('DRAFT', 'CONFIRMED', 'AMENDED', 'CANCELLED')
   AND (d.status = 'DRAFT' OR d.retention_until IS NULL OR d.retention_until > CURRENT_DATE)
   AND EXISTS (SELECT 1 FROM t_document_link l JOIN t_resume_ingestion r
                 ON r.id = l.target_id AND r.tenant_id = l.tenant_id
               WHERE l.document_id = d.id AND l.tenant_id = d.tenant_id AND l.target_type = 'RESUME_INGESTION'
                 AND r.stored_file_name IS NULL AND r.status IN ('確定済', '却下'))
 ORDER BY d.tenant_id, d.id;
```

### 4.4 NF07 保全holdと破壊的経路（D1〜D14。NF07-A1H-2、D14 は NF07-A2b）

hold 機構（`t_privacy_projection_state` に行がある tenant）を一度も使っていない間は、どの経路も従来どおり動き、増える照会は gate の1回だけである（journal の設定が無い prod でも同じ）。hold を使った tenant では、gate が Journal と一致を確かめられない（`UNAVAILABLE`）と、その tenant の gate 対象の削除が止まる。gate と経路は `privacy.retention.enabled` に依存しない（OFF に戻しても掛けた hold は守る）。plan §3.4.1・§3.4.3。

| # | 経路 | hold の扱い | 当たったとき |
|---|---|---|---|
| D1・D2 | 文書の廃棄執行・廃棄申請（手動） | 同じ tx の中・更新の前に `evaluateLocked`。`legal_hold_flag` と独立に判定し、`legal_hold_flag` が先に当たれば従来の 400 | 409 `error.privacy.holdActive`（HELD・分類 UNKNOWN・本人の導出失敗。D2 は UNAVAILABLE も同じ key）。管理者だけが実行する D1 の UNAVAILABLE は 409 `error.privacy.holdUnverifiable`（時間をおいて再実行）。`legal_hold_flag` は立てない |
| D3 | `DocumentRetentionJob`（02:30） | 文書の tenant ごとに回の初めに1回 | 申請を作らず件数を WARN。履歴取込の副本（`DOCUMENT:RESUME_INGESTION`）も同じ本人導出で止まる |
| D4・D5 | `ResumeRetentionCleanupServiceImpl`（02:00） | tenant ごとに1回（D4 と D5 で共有） | 行を残し件数を WARN。帰属しない行は SUBJECT hold では消える |
| D6 | `AiRecommendationRetentionServiceImpl` | tenant ごとに1回（scheduler の Bean に依らない） | 本人の run だけを除いて消す。DATA_ELEMENT hold・UNKNOWN・UNAVAILABLE はその回を消さない |
| D7 | `IntegrationHubRetentionPurgeScheduler` の record | 回の初めに1回（hold を使った全 tenant） | その kind を全体で止める（record に tenant が無いため） |
| D8 | 同 nonce | 接続しない（個人情報を含まない） | — |
| D9 | `ExternalApiReadSnapshotPurgeService` | 回の初めに1回（全 tenant） | その回を消さない |
| D10 | `FileCleanupService.cleanupOrphanFiles`（両 scheduler） | `PrivacyHoldFileReferenceProvider` が hold の対象の保存名を参照に足す | gate が UNAVAILABLE・catalog に行の無い element・保存先が表の列なのに解決できない element の場合は例外で清掃のその回を中止（1件も消さない）。実体が外部 service 側にある element は清掃を止めない（下の「外部にある実体」） |
| D14 | DSAR の開示 package の削除（`PrivacyExportPackagePurgeScheduler` 毎時 :15、交付の記録、期限切れ草案の作り直し。NF07-A2b） | 接続しない（対象外。下の「D14」） | — |

- **監視**: metric `ses.privacy.hold.path.skipped`（tag `path`＝D1〜D9、`verdict`＝`HELD`/`UNCLASSIFIED`/`UNAVAILABLE`）、`ses.privacy.hold.file.cleanup.aborted`（tag `stage`＝`gate`/`resolve`。D10 の中止）、`ses.privacy.hold.file.unprotectable`（tag `element`。D10 で守れない外部の実体）、gate の `ses.privacy.hold.gate.unavailable`。WARN `NF07_PRIVACY_HOLD_PATH_SKIPPED`（batch）・`NF07_PRIVACY_HOLD_PATH_REFUSED`（D1・D2）・`NF07_PRIVACY_HOLD_SUBJECTS_UNRESOLVED`・`NF07_PRIVACY_HOLD_FILE_CLEANUP_ABORTED`・`NF07_PRIVACY_HOLD_FILE_UNPROTECTABLE`。`UNAVAILABLE` が続くと期限超過の保持になるため、閾値と通報先は SRE が決める（plan §3.4.3 の7、UNKNOWN）。D7・D9 は1つの tenant の停止で全 tenant の kind が止まるため、`path=D7`・`path=D9` も監視に入れる。D10 の中止が続くと孤児清掃（PII を含む取込の一時 CSV・quarantine の失敗実体を含む）が全 tenant で止まるため、`ses.privacy.hold.file.cleanup.aborted` も同じく監視する。log に tenant・subject は出ない。
- **回の途中で開始された hold**: batch 経路は回の初めの snapshot を使うため、次の回から効く（最大で1回分の実行時間）。
- **DATA_ELEMENT hold の対**: 履歴取込の原本（`RESUME_INGESTION_ORIGINAL`）だけを hold しても文書台帳の副本（`DOCUMENT:RESUME_INGESTION`）は D1〜D3 で止まらない。副本も止めるなら両方に DATA_ELEMENT hold を掛ける（D-05 owner の受理事項。F2 delta review P3-G）。
- **外部にある実体（A1H-2 review P2-1）**: catalog の `storage_ref` が application の表の列でない `FILE_OBJECT`（`CLOUDSIGN_REMOTE_DOCUMENT`・`FREEE_REMOTE_RECORD`・`MAILBOX_SOURCE_MESSAGE`）は upload root に実体を持たず、孤児清掃では守れも消せもしない。DATA_ELEMENT hold を掛けても D10 は清掃を止めず、WARN `NF07_PRIVACY_HOLD_FILE_UNPROTECTABLE` と metric `ses.privacy.hold.file.unprotectable` を出す。外部 service 側の保全（CloudSign・freee・mailbox での削除の停止）は D10 の範囲外で、hold を掛けた privacy-admin が外部の運用で行う（D-05 owner の受理事項）。catalog に行の無い element と、保存先が表の列なのに解決の規則を持たない element への hold は、従来どおり清掃を中止させる。
- **tenant の最初の hold の前（review P3-2）**: D1 は READ_COMMITTED で動くため、hold を一度も使っていない tenant の最初の hold の開始と同時に走った廃棄執行は順序を保証しない（tx 1回分）。最初の hold を開始する前に、その tenant で実行中の廃棄執行が無いことを確かめる。2件目以降の hold は追随位置の行の lock で直列化される。
- **新しい tenant（review P3-10）**: catalog（V196）は migration の時点で `sys_user` にあった tenant の行だけを持つ。後から作った tenant で hold を開始すると、catalog に行の無い接続経路の element は全て UNCLASSIFIED になり、その tenant の経路が止まり、D7・D9 は全 tenant で止まる。新しい tenant の最初の hold の前に、その tenant の catalog の行を用意する（coverage report の `CATALOG_MISSING` が0件であることを確かめる）。prod の初回導入は tenant `default` だけ。
- **legacy upload 移行との関係（review P3-4）**: `LegacyUploadMigrationService` も同じ provider 一覧を受け取るため、held な本人の legacy file（upload root の直下）は移行の対象になり、読み取り・scan の後に `quarantine/` か `published/` へ移され `t_file_security_metadata` の行が作られる（削除はしない。provider の例外では移行を中止する）。作られた metadata の行は `FileSecurityMetadataReferenceProvider` が以後も参照に数えるため、hold の解除後もその file は孤児清掃の対象に戻らず、本来の保存期限を超えて残る。prod は空 DB への新規導入で legacy file が無いため、影響は無い。legacy file を持つ環境で hold を使う前に移行を済ませる。

**D11: 業務 record の削除で参照が外れる実体**（各 `FileReferenceProvider` が論理削除行を参照に数えるか。2026-11-16 時点）

| provider | 読む列 | 論理削除行 | その他 |
|---|---|---|---|
| `BpAvailabilityFileReferenceProvider` | `t_bp_availability_ingestion.stored_file_name` | 数えない | 貼付け（`PASTE`）を除く |
| `DocumentArchiveFileReferenceProvider` | `t_document_version.storage_key` | 数えない | 末尾の名前も足す |
| `EngineerFileReferenceProvider` | `t_engineer.photo_url` | 数えない | — |
| `FileSecurityMetadataReferenceProvider` | `t_file_security_metadata.stored_name` | 数えない | `FileStorageServiceImpl.delete` が metadata を論理削除する |
| `ProjectIngestionFileReferenceProvider` | `t_project_ingestion.stored_file_name` | 数えない | 現在の tenant・`EML` だけ |
| `ProposalFileReferenceProvider` | `t_proposal.skill_sheet_path` | 数えない | — |
| `ResumeIngestionFileReferenceProvider` | `t_resume_ingestion.stored_file_name` | 数えない | 現在の tenant・却下以外だけ（却下したジョブの原本は孤児になる） |
| `ServiceRequestFileReferenceProvider` | `t_service_attachment_link` → `t_document_version.storage_key` | 版は数えない | link を物理削除した後の版は守らない |
| `PrivacyHoldFileReferenceProvider`（A1H-2） | hold の対象（F2 provider の解決。論理削除行・状態を問わない） | 数える | 参照を足すだけ |

held な本人・element の実体は、論理削除・却下・link の削除で既存の provider から外れても `PrivacyHoldFileReferenceProvider` が守る（`PrivacyHoldFileReferenceProviderTest`）。守れないのは、参照元の行が物理削除され F2 provider が解決できなくなった実体（plan §9 R-15 の残余）。

**D12: 対象外**（業務 record に結び付く前の新規実体の補償削除。hold の対象になり得るデータになる前の実体）: `DocumentServiceImpl` の import・rollback 補償、`DocumentMetadataCommitService`、`ResumeIngestionServiceImpl.createJob`、`FileStorageServiceImpl.store` の失敗時の quarantine 実体、`ImportRawTempFileStore`（取込要求ごとの一時 CSV）、`ImportStageReconcileScheduler` → `DocumentServiceImpl.discardImportStage`（commit されなかった取込 stage の実体と台帳 file）、`CloudSignArtifactService` の一時 file（原本は別に保存）。

**D13: 他 NF の破壊的経路**（A1H-2 では gate に接続しない。対象外の理由と、D-05 owner の受理が要るもの）

| 経路 | 消すもの | 判断 |
|---|---|---|
| `PwaClientMutationCleanupScheduler`（`app.pwa.cleanup-cron`、既定30日） | `t_pwa_client_mutation`（`user_id`・`response_json`）の物理削除 | **残余（D-05 owner の受理事項）**。端末の再送制御用の短期の台帳で、元の業務 record は別に保存される（論理削除で残り F2 provider が返す）。catalog に element が無い（未登録）。接続する場合は後の batch で catalog の element（`user_id` で SYS_USER へ帰属）と cleanup scheduler への gate を足す |
| `PwaClientMutationLedgerService.abandon` | 処理中の1行の物理削除 | 対象外（要求の中の補償。D12 と同じ種類） |
| `FreeeIntegrationServiceImpl.unlink`・`link` | `t_freee_employee_link`（要員と freee 従業員IDの対応）の物理削除 | **残余（D-05 owner の受理事項）**。管理者の設定操作で保持期限による消去ではない。unlink は監査を残さない。ENGINEER の SUBJECT hold では対応が証拠になり得るため、後の batch で少なくとも unlink を両方の ID 付きで監査に残すか、held な本人には 409 を返す |
| `EngineerChangeRequestApprovalAdapter.applyCareers` | 承認した変更申請による `t_engineer_career` の置換 | 対象外（本人の申請による現在値の訂正で、保持期限による消去ではない）。hold 中の訂正の扱いは DSAR（訂正請求、A2）と合わせて D-05 で決める |
| 業務 CRUD の明細・対応表の物理削除・置換（勤怠の休憩、作業実績の日次、請求明細、権限・スキルの対応 等） | 現在値の編集 | 対象外（業務上の編集で保持期限による消去ではない。主要 entity は論理削除で行が残り、F2 provider が論理削除済みを含めて返す） |
| MFA・portal の資格情報の初期化（列の NULL 化） | secret | 対象外（保持する個人データではない） |
| `db/migration-prod/R__remove_v2_sample_data.sql` | V2 の見本行 | 対象外（初回・FRESH の状態でだけ動き、hold の対象のデータが存在する前） |

**D14: DSAR の開示 package の削除（NF07-A2b。gate に接続しない＝対象外）**

| 経路 | 消すもの | 起動 |
|---|---|---|
| `PrivacyExportPackagePurgeScheduler` → `PrivacyExportPackageService.purgeDue` | `purge_after`（`privacy.dsar.package-max-age`）を過ぎた package と、終了状態（CONFIRMED・BLOCKED・CANCELLED 等）の case に残った package の実体（`FileStorageService.delete`。package の行は消さず PURGED にする） | 自動（毎時 :15。flag で止めない） |
| `PrivacyExportPackageService.recordDelivery` | 交付を記録した package の実体（同じ tx の最後。消せなければ交付記録ごと巻き戻す） | privacy-admin の手動 |
| `PrivacyExportPackageService.preview`（作り直し） | 期限切れの草案の実体（作り直しの前に行を PURGED にし、実体は CAS・記録・監査の後に消す） | privacy-admin の手動 |
| `FileStorageServiceImpl.store`（草案の保存の scan 失敗） | server 生成の種別（`PRIVACY_EXPORT`）は再scanの対象にせず、quarantine の実体を消して拒否を書いた metadata を論理削除する（review P2-1 (a)） | 同一要求内（D12 と同じ種類） |
| 同 `preview` の記録の失敗時 | 保存した後に package の行を書けなかった実体（best-effort で消す） | 同一要求内（D12 と同じ種類） |
| `PrivacyExportPackageService.purgeDue` の孤児の削除 | PURGED でない package の行を持たない `PRIVACY_EXPORT` の file と metadata（上の2行で消し損ねたもの。DB の時計で1時間の猶予の後。review P2-1 (c)） | 自動（毎時 :15 の同じ回） |

汎用の file 経路（`GET /api/files/{name}`・`POST /api/files/{name}/rescan`）は、package の行（PURGED を含む）と `PRIVACY_EXPORT` の metadata（論理削除済みを含む）のどちらかに当たる保存名を role・tenant に関係なく拒否する（review P2-1 (b)）。保存層でも汎用の `load`・`rescan` は `PRIVACY_EXPORT` を開かず、package 専用の `loadServerGenerated` だけが読む（review P3-8）。

判断の理由:

1. **package は交付用の副本で、原本は別に保存されている。** package は F2 provider が業務表・file から読んだ値を草案として書き出したものである。読んだ元の行・実体は残り、hold は原本の側で D1〜D11 が守る。package を消しても、hold が保全する原本は1件も減らない。
2. **package 自体は hold の対象（subject / data element）ではない。** hold は catalog（`m_pii_data_element`）の data element と、F2 provider が本人に帰属させる行・実体に効く。package の表（`t_privacy_export_package`）は catalog に無く、F2 provider も解決しない（NF07 自身の作業データ。pii-inventory §6 に除外として記す）。ただし「catalog に無い」は現在の模型の結果で、D4 の「原本と副本は別 element」の考え方では副本も別 element になり得るため、実質の根拠は 1・3・4 である。package の実体の metadata（`t_file_security_metadata`）は catalog の `FILE_SECURITY_METADATA` に当たり、削除で論理削除される（その owner は privacy-admin で本人ではない）。gate に接続すると、hold を使った tenant では分類が UNCLASSIFIED（fail-closed）になり、その tenant の package を1件も消せなくなる。
3. **接続すると期限超過の保持になる。** D-06 が決める最長保存期間（`package-max-age`）と「交付記録と同時に PURGED」（plan §3.5）を超えて PII の副本が残り、plan の I-9（期限超過の保持を避ける）に反する。plan の I-4 は「NF07 自身が作った一時 package を交付記録・期限・case の終了で消すこと」を業務データの削除 writer の禁止の対象外とするだけで、hold との関係は決めていない（hold との関係は本節の 1〜4 で決める）。
4. **交付の証跡は残る。** package の行（内容 hash・作成者・承認者・承認日時・削除日時）と request の交付記録（方法・証跡の参照・承認した package の id と hash）は消さない。取得の印（`DSAR-DOWNLOAD:`）と、期限切れの作り直しで外れた承認（`DSAR-SUPERSEDED:`）も `t_privacy_action` に残る。何を・いつ・誰の承認で交付したかは hash で照合できる。

`PrivacyExportPackageServiceTest#本人がhold中でも開示packageの削除はgateを見ずに期限で消す` が、本人の SUBJECT hold（gate の `inspect` が HELD を返す）があっても、交付記録・作り直し・期限超過の3経路の削除が gate を照会しないことを固定する。

- **drain 中の遅れ**: 削除の scheduler は毎時だが、`APP_SCHEDULING_ENABLED=false`（drain・全停止）の間は動かない。終了した case・期限を過ぎた package の実体はその間残る（取得・交付記録は状態の確認で拒否される）。再開後の最初の回で消える。

**D-05 owner の受理事項（A1H-2。hold 開始の開放の署名と合わせて受理する）**: (1) 原本と副本の DATA_ELEMENT hold は別 element（上の「DATA_ELEMENT hold の対」）、(2) 外部にある実体は D10 の範囲外（上の「外部にある実体」）、(3) D7・D9 は1つの tenant の停止で全 tenant の kind を止める（過剰停止）、(4) D1 の READ_COMMITTED による最初の hold との順序の窓と、D1・D2 の本人の導出が文書行の lock より前であること（plan §3.4 A1H-2 実装記録の残余）、(5) 帰属できない element の行は SUBJECT hold で守られない（plan §3.4.3 規則6）、(6) D13 の PWA の台帳と freee の従業員対応の物理削除（上の表）、(7) 参照元の行が物理削除された実体は D10 で守れない（D11、plan §9 R-15）、(8) D14 の開示 package は hold で止めない（NF07-A2b。D-06 owner と合わせて受理する）。本人請求そのものが争いになり、交付した内容が証拠になる場合でも、system は `package-max-age` を超えて内容を残さず、内容 hash と交付記録だけを残す。内容を残すなら、交付の時点で system の外の保全手続で保管する。

### 4.5 NF07 保全holdの解除（NF07-REL。開放は D-03・D-05 の署名後）

`POST /api/privacy/holds/{holdId}/release` は、D-03・D-05 の決定ID が有効で、prod の verifier があり、解除 authority の trust anchor が登録されるまで 404 を返す（第1期の registry は空、失効 provider は常に UNKNOWN。実 registry と失効 provider は NF07-TRUST）。開放の後の手順は次のとおり。署名は system の外（D-03 で決める方式）で行い、system は鍵を持たない。

**手順**

1. privacy-admin（要求者）が hold 画面で解除する hold を選び、理由コード・ticket・理由を入力する。画面は束縛の全項目（tenant・partition・hold ID・hold revision・理由の hash・command ID（画面で発行）・解除の時点の journal head の revision と event hash・要求者・開始者）と署名依頼の JSON を表示する。
2. 署名依頼の JSON を発行者と承認者（要求者とは別の、trust anchor に登録された2名）に渡す。各人は下の正規形に Ed25519 で署名し、JSON の `issuer` / `approver` に `authorityId`・`keyId`・`issuedAt`・`expiresAt`・`signature`（base64）を記入する。
3. 要求者が署名済みの JSON を画面に貼り付けて送る。理由コード・ticket・理由を変えると理由の hash が変わるため署名し直す。journal head・hold revision・開始者が署名の後に変わった場合は 409 になるので、新しい値で署名し直す。同じ JSON の再送は元の結果を返す（Journal に重ねない）。

**正規形**（全て `<UTF-8 の byte 長>:<値>` の field を区切り無しで連結し、SHA-256 は小文字 hex）

- 理由の hash: `NF07-HOLD-RELEASE-REASON-V1`・理由コード・ticket・理由本文。
- 束縛の digest: `NF07-HOLD-RELEASE-BINDING-V1`・tenant・subjectRef（partition。DATA_ELEMENT hold は `DATA_ELEMENT#<id>`）・hold ID・hold revision（10進）・`HOLD_RELEASE`・理由の hash・command ID・journal head の revision（10進）・journal head の event hash・要求者・開始者。
- 署名の対象（Ed25519 にそのまま渡す byte 列。hash を取ってから署名しない）: `NF07-HOLD-RELEASE-AUTHORITY-V1`・`PRODUCTION`・`HOLD_RELEASE`・役割（`ISSUER` / `APPROVER`）・authority ID・key ID・発行日時・期限・束縛の digest。
- 日時は ISO-8601 の UTC で、Java の `Instant.toString()` と同じ形（例: `2026-11-16T02:00:00Z`。小数秒が 0 なら書かない。`.000Z` で書いた値は system が正規形に直してから検証するため、署名の対象と一致しない）。
- 主体（要求者・開始者・trust anchor の主体）は `sys_user:<sys_user.id>`（先頭の 0・大文字は不可）。開始者が不明の hold は `sys_user:0`。

**test vector**（`HoldReleaseProofVerifierTest` が Java の実装と照合する。値は Node.js で独立に計算した）

| 項目 | 値 |
|---|---|
| 入力 | 理由コード `LITIGATION_CLOSED`、ticket `TICKET-2001`、理由 `訴訟が終結したため保全を解除する`、tenant `default`、subjectRef `ENGINEER:7`、hold ID `ph-` + `a`×32、hold revision `1`、command ID `hr-` + `b`×32、journal head revision `1`、event hash `c`×64、要求者 `sys_user:1`、開始者 `sys_user:2`、役割 `ISSUER`、authority `issuer-a`、key `k1`、発行日時 `2026-11-16T02:00:00Z`、期限 `2026-11-16T04:00:00Z` |
| 理由の hash | `8e3ccdd899ebae58d63b1319e0820aea0d7ff751194d7d57bee7d8873f5dacd5` |
| 束縛の digest | `cc4d333d9dd9ab7078a337c9464ae944da3204234bad2ac993e0b71109e1fb9a` |
| 署名の対象の SHA-256（確認用） | `549567a997a2a84a5ecc5bca965d7bb8f3b403b96c57444fedc935a6833daa18` |

**D-05「解除の承認者規則」の設定と運用**

| 設定 | 値 | 既定 | 意味 |
|---|---|---|---|
| `privacy.hold.release.starter-separation` | `REQUIRED` / `NOT_REQUIRED` | `REQUIRED` | 開始者が分かっている hold で、発行者・承認者を開始者と別の主体にする（要求者は開始者と同じでもよい） |
| `privacy.hold.release.unknown-starter` | `REJECT` / `ACCEPT` | `REJECT` | 開始者が不明の hold の解除。`ACCEPT` なら開始者との分離は判定しない |

- 解釈できない値は既定（厳しい側）として扱う。値を変えるのは D-05 の署名で決めた場合だけ。
- 開始者の自動の復元: hold 開始の ACK が不明（503）で終わり reconciler が Journal から行を復元した場合、reconciler は開始の予約で append より前に書かれる開始要求の監査（`NF07_HOLD_START_REQUESTED`。hold ID で引ける）の username から `sys_user.id` を引き、開始者として記録する。監査が無い・username が1つに定まらない・`sys_user` の行が1つでない・tenant が違う・監査の実行者 id（`human_user_id`）と一致しない場合は不明（`sys_user:0`）のまま残し、以後の reconciler の回でも同じ条件で引き直す（pending の途中は書き換えない）。戻した行は監査 `NF07_HOLD_RECONCILE_STARTER_RESTORED` に残る。戻らない行は reconciler の WARN（`NF07_PRIVACY_HOLD_RECONCILER`、`outcome=STARTER_UNKNOWN`）と metric `ses.privacy.hold.reconciler.outcomes` に出る。
- 開始者が不明のまま（一覧の開始者が `sys_user:0`）の hold の解除は、既定では 409（`error.privacy.holdReleaseStarterUnknown`）になる。開始の service が同じ event を後から projection し直したとき（`adoptReconciledMetadata`）にも値は戻るが、開始の要求が 503 で終わった場合にこの経路は通らないため、待っても戻らない。運用は次のどちらか。
  1. 監査付きのデータ修正の手続き: 開始要求の監査で開始者を確かめ、変更票（hold ID・旧値 0・新値・根拠の監査 id）を D-05 owner が承認したうえで、DBA が `t_legal_hold.requested_by` を書き換える。書き換えは projection の値で Journal では確かめられないため（plan §11 の残余）、作業の記録を必ず残す。書き換えた後は新しい開始者で署名し直す。
  2. D-05 の規則で `unknown-starter=ACCEPT` にする（開始者との分離を判定しないことを D-05 owner が署名で受理した場合だけ。全ての開始者が不明の hold に効く。解除の予約から append までの間（解除の pending がある間）は、reconciler も開始の service も開始者を書き換えない）。
- 画面の理由の hash は Web Crypto（`crypto.subtle`）で計算するため、https か localhost の画面でだけ表示される。それ以外（http）では「計算できません」と表示され、署名依頼の JSON の `reasonHash` は `null` になる。その場合は上の正規形で理由の hash を計算して記入する（`HoldReleaseProofVerifierTest` と `PrivacyHoldReleaseHashJsTest` が同じ test vector と照合する）。
- 理由本文に対になっていない surrogate（不正な文字）を含む要求は 400 になる（Java と Web Crypto で byte 列が一致しないため）。
- 帰属できない element の DATA_ELEMENT hold は、tenant に ACTIVE な SUBJECT hold がある間は解除できない（409 `error.privacy.holdReleaseDependency`）。受理するか DDL lane で選択の保存を足すかは plan §11 の未決事項。

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
