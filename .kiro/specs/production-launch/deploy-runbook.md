# 本番配備runbook（初回: 空DBへの新規導入・全停止・専用Flyway job）

- 状態: **草案（SRE/DBA承認待ち）**。本書は承認・実施済みの手順ではない。本番は引き続きNO-GO。
- 対象: SES Manager Pro 初回本番リリース（S-NF統合候補）。以後のreleaseも同じ全停止方式を前提とする。
- 根拠となるユーザー決定（2026-11-13）:
  - P-2: 本番DBは未構築。初回は空DBへの新規導入（V1→LATEST）。
  - P-3: 全停止＋専用Flyway job。アプリinstanceは起動時にmigrationを実行しない。
  - 部署-1: V2の業務sampleはprod専用scriptで削除する（V2本体は変更しない）。修復queueの対応行も消す。
  - 部署-2: 修正したflyway-maven-pluginで `mvn flyway:migrate` を実行する。
  - 部署-3: 初期管理者パスワードは配備時にsecretからhashを注入し、初回ログインで変更させる。公開済みパスワードを残さない。
  - DBタイムゾーン: 本番MySQLのserverタイムゾーンはDBAが `+09:00` に設定する（コードは変更しない）。
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
  - 文書種別の追加（既存不具合の修正）: `V197__resume_ingestion_and_import_document_types.sql`（`m_document_type` に `RESUME_INGESTION`（履歴・スキルシート取込）と `IMPORT`（取込センターCSV）を INCOMING で入れ、catalog に `DOCUMENT:IMPORT`（NOT_ATTRIBUTABLE。取込CSVは本人を導出できない）を足す。既にある行は入れない）。これまではどの migration も2種別を作っておらず、スキルシート取込の upload は `error.document.invalidType`（400）で毎回失敗していた。保存年数（3年・TRANSACTION_DATE）は spec に根拠が無い暫定値で文書 owner の確認待ち。取込センターCSV（IMPORT）は取引日を設定しないため保存期限は NULL のままで、処分候補にはならない（保持期間は開放前に Privacy が書面で受理する条件のまま）。履歴取込の副本（RESUME_INGESTION）は、取込原本を `app.resume.retention-days` で purge する日次清掃（02:00）が保存期限を当日に合わせ、02:30 の `DocumentRetentionJob` が廃棄候補（PENDING）にし、管理者の承認・執行で処分する（2026-11-16 依頼者決定。legal hold 中の副本は変えない。plan §3.4.1「D4 と文書台帳の副本」）。**署名事項**：この副本の処分方針について、Privacy owner と文書 owner の書面署名を得る。**署名までは `RESUME_INGESTION` の副本の廃棄申請を承認も執行もしない**（FINAL-FIX (a)-3）。副本の調整は廃棄候補（PENDING）を作るだけで、削除は管理者の承認と執行を経る。そのため、署名を待つために scheduler（`APP_SCHEDULING_ENABLED`。中核jobと共用）を止めなくてよい。副本の調整だけを止める停止口 `RESUME_LEDGER_COPY_ALIGNMENT_ENABLED`（既定 `true`）もある。確認の SQL、停止口、有効化後の監視（失敗の metric・ERROR log）と定期確認は §4.3。
  - NF01 二者確認の NF07 catalog 登録（RB-NF01）: `V198__nf01_dual_confirmation_privacy_catalog.sql`（`m_pii_data_element` に、B0 の7表と自由記述2列（`business_comment`・`decision_comment`）の9件を `sys_user` に存在する tenant ごとに入れる。V196 と同じく構造行だけで、provider は未登録（NULL）、`subject_attribution`・owner・データ分類・inventory 状態は UNKNOWN、保持期間は持たない。既にある行は入れない）。新規導入では tenant `default` の文書以外の catalog 行は36件（V196 の27件＋9件）になる。値の正本は `privacy-retention-dsar/pii-inventory.md` の DB-141〜DB-147 で、保存期間等の値は D-02 の署名で確定する（署名は未取得。§1.7）。DSAR の検索ではこの9件が「要手動確認」（`PROVIDER_UNREGISTERED`）として出る。分類が UNKNOWN のため、NF07 hold 画面の「未分類の data element」（`unclassifiedElements`）と coverage report の未分類件数（`PrivacyCoverageReport` の `unknownAttributionCount`）にもこの9件が加わり、D-02 で分類を決めるまで0にならない（回帰ではない。B0 は hold の gate に接続する破壊的経路を持たないため、他の経路の判定は変わらない）。
  - NF-06 取込 stage 照合の索引（RB-NF05、FINAL review b-14）: `V200__nf06_document_version_storage_key_index.sql`（`t_document_version.storage_key` に非一意の索引 `idx_dv_storage_key` を足す。V1・V67 は変えない。行は変えない）。照合 batch（`ImportStageReconcileScheduler`、NF06 の OFF でも動く）は台帳1件ごとに同じ storage key の版を数えるため、従来は全件走査だった。V199 は欠番（RB で使わなかった予約番号）。
  - NF07 F1 の状態の CHECK（RB-NF07-HOLD）: `V201__nf07_privacy_status_check_constraints.sql`（`t_privacy_request` の `status`（`PrivacyCaseState` の全値）・`channel`・`delivery_method`（NULL 可）と `t_legal_hold.hold_kind`（D-05 で種類が決まるまで `UNKNOWN` だけ）に、コードの enum と同じ値の集合の CHECK を足す。行は作らない）。表は新規導入で空のため適用は失敗しない。V199 は欠番。
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

### 1.0 prod 必須設定（無い・形式が違うとアプリが起動しない。一覧の正本）

`SPRING_PROFILES_ACTIVE=prod` で起動するとき、次の1〜26の26項目のどれかが無い・形式が違うと、アプリは起動を中止する。機能スイッチの ON/OFF には関係しない。freee連携を使う場合（`FREEE_ENABLED=true`）は、さらに27〜30の4項目が要る。

- 一覧はコードを走査して作った（FINAL-FIX、2026-11-17）。走査した出処は3つ: prod profile の validator の必須項目、`@Value` のうち既定値が空・開発用で prod では拒否されるもの、`application-prod.yml` の `${...}` のうち既定値が空で prod では拒否されるもの。
- 既定値を全く持たない `@Value` と、既定値の無い `application-prod.yml` の `${...}` は、どちらも0件だった。どの項目も空か開発用の既定値を持つため、Spring の解決では止まらない。止めるのは validator である。
- `infra/ecs/task-definition.prod.json` は placeholder だけを書いた雛形で、正本はこの表である。値（secret）は本書・task definition・repository のどれにも書かない。
- **secret** 欄の「〇」は secret store（Secrets Manager・SSM）から注入する。

**DB・転送ヘッダー**

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 1 | `DB_URL`（`spring.datasource.url`） | アプリDBのJDBC URL | `jdbc:mysql://<host>:3306/ses_manager_db?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Tokyo`。`autoReconnect=true` / `autoReconnectForPools=true`（`yes` も。百分率符号化した値も）を付けない。host は1つだけ書く: multi-host（`h1,h2`・`[h1,h2]`・`address=(...)`）、`jdbc:mysql:loadbalance://`・`jdbc:mysql:replication://`・`jdbc:mysql+srv://`・`jdbc:mysqlx://` は使わない（どれも起動を中止する。RB-NF05）。query に `useConfigs`・`propertiesTransform`・切替え系の key（`failOverReadOnly`・`maxReconnects` 等）も書かない（driver が展開した後の有効な属性も検査し、`(effectiveAutoReconnect)` / `(connectionTopology)` で起動を中止する。RB-HYG）。staging で本番と同じ形式の `DB_URL` で起動し、guard が通ることを一度確かめる。未設定だと開発用の localhost へ接続し、起動時 schema guard（§4）で止まる | 〇（接続先） | `application.yml:122`、`ImportJdbcReconnectGuard`（`violations`・`multiHost`） |
| 2 | `DB_USERNAME`（`spring.datasource.username`） | アプリaccount（§1.2。DDL権限なし） | 既定は開発用の値。必ず上書きする | 〇 | `application.yml:123` |
| 3 | `DB_PASSWORD`（`spring.datasource.password`） | 同上 | 同上 | 〇 | `application.yml:124` |
| 4 | `TRUSTED_PROXIES`（`app.security.trusted-proxies`） | 転送ヘッダーを信頼する接続元（ALBのsubnet） | カンマ区切りのCIDR。IPv4は private（10/8・172.16/12・192.168/16）か 100.64/10 で /16 以下、IPv6は /48 以下、host部は0。`127.0.0.1` を入れない（SRE-3） | × | `application-prod.yml:76`、`ForwardedHeaderProductionValidator.java:67-105` |

**OIDC・MFA・session・break-glass**（毎回の起動で `ProductionSecurityConfigurationValidator` が検査する。§4.1）

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 5 | `OIDC_TENANT_ID`（`app.security.oidc.tenant-id`） | OIDC login・外部identity・MFA・sessionの照合tenant | `default`（SRE-1） | × | `application.yml:269`、validator `:120-122` |
| 6〜10 | `OIDC_ISSUER_URI`・`OIDC_AUTHORIZATION_URI`・`OIDC_TOKEN_URI`・`OIDC_JWK_SET_URI`・`OIDC_USER_INFO_URI` | IdPの固定metadata（5項目） | どれもHTTPS、hostがあり、userinfo（`user:pass@`）を含まない | × | `application-prod.yml:83-87`、validator `:355-385` |
| 11 | `OIDC_CLIENT_ID` | OIDC client | 空でない | × | `application-prod.yml:88`、validator `:358-366` |
| 12 | `OIDC_CLIENT_SECRET` | 同上 | 空でない | 〇 | `application-prod.yml:89`、validator `:358-366` |
| 13 | `MFA_ENCRYPTION_KEY` | MFA secretの暗号鍵（`MFA_CURRENT_KEY_VERSION` の既定 `v1` の鍵） | 32文字以上で、開発用の既定値でない | 〇 | `application-prod.yml:97`、validator `:150,284-298,400-404` |
| 14 | `SESSION_HASH_KEY` | 永続sessionのhash鍵 | 32文字以上で、開発用の既定値でない | 〇 | `application-prod.yml:101`、validator `:151,400-404` |
| 15 | `BREAK_GLASS_USERNAMES` | break-glass管理者のusername | bootstrap中（§4.2）は**空**でなければ起動しない。bootstrap終了後（§5.5）は**ちょうど2件**（カンマ区切り）。各名は ASCII の `[A-Za-z0-9._-]+` だけで、有効な管理者の `sys_user.username` と大文字小文字まで完全一致し、MFA登録済みであること | × | `application-prod.yml:82`、validator `:312-352` |

**アプリの secret**

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 16 | `BATCH_TOKEN_SECRET`（`app.batch.token-secret`） | 一括操作のpreview tokenのHMAC鍵 | 空でない（十分な長さの乱数） | 〇 | `application-prod.yml:113`、`BatchOperationServiceImpl.java:46-51` |
| 17 | `PWA_CONTEXT_SECRET`（`app.pwa.context-secret`） | PWA利用者文脈のHMAC鍵（NF04がOFFでも必要） | 空でなく、開発用の既定値でない | 〇 | `application-prod.yml:115`、`PwaUserContextService.java:28,39-45` |
| 18 | `BP_BANK_ACCOUNT_ENCRYPTION_KEY`（`bp.bank-account-encryption-key`） | BP口座番号の暗号鍵（AES。UTF-8の先頭32 byteを使う） | `dev-only` で始まらない32文字以上の乱数。**検査は起動完了の直後（`ApplicationReadyEvent`）に行うため、portを開いた後に停止する**。readiness（`/actuator/health/readiness`）がUPになるのはこの検査を通った後だけである（Spring Boot は `ApplicationReadyEvent` の listener を終えてから受付可へ切り替える）。確認は §4 の readiness のUPと、起動logに `bp.bank-account-encryption-key must be configured in prod` が無いこと。検査するのは `dev-only` で始まらないことだけで、長さは見ない（32文字以上は secret を作るときに守る） | 〇 | ymlに定義なし。`BpCompanyServiceImpl.java:61-74,339` |

**暗号鍵（key version と鍵の組）**

鍵はどれも、32 byte を `=` 無しの base64url で書く（空白・改行を含めない）。key version は `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` に従う。ただし環境変数名では `.`・`-` が `_` になるため、`v1` 等の英数字にする。

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 19 | `CERTIFICATION_NUMBER_CRYPTO_CURRENT_KEY_VERSION`（`certification.number-crypto.current-key-version`） | 資格番号の暗号鍵の現行version。NF03の子スイッチがOFFでも必要（`feature-switches.md` 4.1） | 上記のversion | × | ymlに定義なし。`CertificationNumberKeyProviderImpl.java:26,45,54-58` |
| 20 | `CERTIFICATION_NUMBER_CRYPTO_KEYS_<VERSION>`（`certification.number-crypto.keys.<version>`） | 同 鍵 | 上記の鍵 | 〇 | `CertificationNumberKeyProviderImpl.java:89,111-126` |
| 21 | `COMPLIANCE_CREDENTIAL_CURRENT_KEY_VERSION`（`compliance.gate.credential-crypto.current-key-version`） | G2外部Review資格credentialの暗号鍵の現行version | 上記のversion | × | `application.yml:529`、`ComplianceGateCredentialKeyProviderImpl.java:28,49-68` |
| 22 | `COMPLIANCE_GATE_CREDENTIAL_CRYPTO_KEYS_<VERSION>`（`compliance.gate.credential-crypto.keys.<version>`） | 同 鍵 | 上記の鍵。この名前で渡す（以前 `application.yml` のcommentにあった `COMPLIANCE_CREDENTIAL_KEY_V1` は配線されておらず、読まれなかった。FINAL-FIX でcommentを直した） | 〇 | `application.yml:530-532`、`ComplianceGateCredentialKeyProviderImpl.java:58-63,120-136` |
| 23 | `COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS`（`compliance.gate.fingerprint-key-tenants`） | reviewer fingerprintの鍵を持つtenantの一覧 | `default`（カンマ区切り） | × | `application.yml:542`、`ComplianceReviewerFingerprintKeyProviderImpl.java:167-199` |
| 24 | `COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_CURRENT_KEY_VERSION`（`compliance.gate.fingerprint-keys.default.current-key-version`） | reviewer fingerprintのHMAC鍵の現行version（tenant `default`） | 上記のversion | × | `ComplianceReviewerFingerprintKeyProviderImpl.java:65-75,141-160` |
| 25 | `COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_KEYS_<VERSION>`（`compliance.gate.fingerprint-keys.default.keys.<version>`） | 同 鍵 | 上記の鍵 | 〇 | 同上 |
| 26 | `ACCOUNTING_TENANT_IDS`（`app.accounting.tenants.ids`） | 定期処理の対象tenant（freeeの有無に関係しない） | 1件以上（SRE-1 により `default` の1件）。test以外の全profileで検査する | × | `application.yml:230`、`AccountingTenantInventoryStartupValidator.java:7-13` |

- **23〜25の注（OS環境変数で渡せなかった理由と対処。FINAL-FIX）**:
  - 原因は relaxed binding でも、名前の `-`・大文字小文字でもない。tenant を、Map型の property source にある property 名のうち `compliance.gate.fingerprint-keys.` で始まるものだけから列挙していた。
  - OS環境変数の名前は `COMPLIANCE_GATE_FINGERPRINT_KEYS_...` の形でこの接頭辞に一致しない。しかも `.`・`-` が `_` になり大文字化されるため、名前から tenant ID を復元できない。そのため 24・25 だけを環境変数で渡すと、`Fail-fast: missing compliance.gate.fingerprint-keys configuration in prod profile` で止まっていた。
  - tenant一覧（23）を足した。一覧にある tenant の version と鍵は `Environment#getProperty` の名前解決で読むため、24・25 は上の環境変数名でそのまま渡せる。
- 一覧にある tenant の version・鍵が欠けると、起動時に止まる（`missing fingerprint current-key-version for tenant ...` / `missing fingerprint key for tenant ...`）。
- 従来の経路（設定file・`SPRING_APPLICATION_JSON` で `compliance.gate.fingerprint-keys.<tenant>.*` を渡す）も引き続き使える。その場合は23は要らない。`-D` は process一覧に出るため使わない。

**freee連携を使う場合だけ必須**（`FREEE_ENABLED=true`。prodの既定は `false`。FINAL-FIX）

- `FREEE_ENABLED`（`freee.enabled`）が `false` の間（prodの既定）:
  - 下の4項目が無くても起動する。給与・会計の freee 接続は認可の開始と code 交換を行わず、状態は MISCONFIGURED になる。
  - 接続が作られないため、会計送信・入金明細・勤怠連携は接続が無いものとして扱われ、freee を呼ばない。
  - 既存の接続行が残っていても、空の client 設定を freee へ送らない（FINAL-FIX P3-1）。
    - 給与側: token endpoint（code 交換・更新・失効）を `error.payroll.configIncomplete` で拒否する。接続行と状態は変えないため、解除（失効）も freee を有効にするまでできない。
    - 会計側: token の更新を行わず、元の401として扱う。
  - `ACCOUNTING_TENANT_IDS`（26）は freee の有無に関係なく要る。
- 公式host・HTTPS の検査は、ON・OFF のどちらでも行う（`freee.*-base-url` を上書きした場合だけ止まる）。
- `true` にすると、次の4項目のどれかが欠けたとき起動しない。

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 27 | `FREEE_CLIENT_ID`（`freee.client-id`） | freee会計・給与のOAuth client | 空でなく `dummy-client-id` でない | × | `application.yml:514`、`FreeeAccountingProvider.java:79-107`、`FreeeIntegrationServiceImpl.java:199-221` |
| 28 | `FREEE_CLIENT_SECRET`（`freee.client-secret`） | 同上 | 空でなく `dummy-client-secret` でない | 〇 | `application.yml:515`、同上 |
| 29 | `FREEE_REDIRECT_URI`（`freee.redirect-uri`） | OAuthのredirect URI | HTTPS | × | `application-prod.yml:163`、`FreeeIntegrationServiceImpl.java:199-221` |
| 30 | `FREEE_TOKEN_ENCRYPTION_KEY`（`freee.token-encryption-key`） | freee tokenの暗号鍵。会計接続も、専用鍵 `freee.token.encryption-key` を設定しなければ同じ鍵を使う | `change-me` で始まらない十分な長さの乱数 | 〇 | `application.yml:520`、`FreeeIntegrationServiceImpl.java:199-221`、`IntegrationConnectionServiceImpl.java:46` |

**検査の効き方と試験の範囲（注意）**
- 18 の検査は、prod かどうかを property `spring.profiles.active` の値で判定する。そのため `@ActiveProfiles("prod")` で起動する試験ではこの検査が走らず、CI はその未設定を検出しない。配備では task definition の `SPRING_PROFILES_ACTIVE=prod` で効く。
- 27〜30（freee）の検査は、FINAL-FIX で有効profileも見るようにした。CI は次の2つで確かめる。
  - `ProductionSecurityConfigurationTest`: freee が無効で4項目が無くても、prod の完全な context が起動する。
  - `FreeeProductionStartupTest`: freee が有効で4項目が無いと、prod の起動が止まる。
- FINAL-FIX までは、freee を使わない配備でも 27〜29 が必須だった。`application-prod.yml` の旧comment「client-id未設定でも起動は可能」は誤りだった。

**設定してはいけない値**（設定すると起動しない）:
- `SPRING_FLYWAY_ENABLED=true`（§4）。
- `LOCAL_LOGIN_ENABLED=true`、`OIDC_ENABLED=false`、`BREAK_GLASS_LOGIN_ENABLED=false`、`OIDC_REQUIRE_MFA_ASSURANCE=false`、`MFA_ENABLED=false`、`MFA_REQUIRED_FOR_BREAK_GLASS=false`、`ALLOW_UNTRACKED_SESSIONS=true`（validator `:114-148`）。
- `server.forward-headers-strategy` を `framework` 以外にする（`ForwardedHeaderProductionValidator.java:77`）。
- `ai.outbound-probe.enabled=true`（`AiOutboundProbeProdGuardConfig.java:18-22`）。
- `AI_EXTERNAL_SEND_ENABLED=true`（B2c-2 の完了まで常に拒否。冒頭の NF08 B2c-1 の項）。
- `freee.*-base-url` を公式host以外にする（`FreeeIntegrationServiceImpl.java:192-195`）。
- `CLOUDSIGN_ENABLED=true` のまま `CLOUDSIGN_CLIENT_ID` を設定しない、または `cloudsign.base-url` を公式のallow-list外にする（`CloudSignProperties.java:86-112`）。

**ONにした時だけ必須になる設定**（OFFのままなら不要。手順は各正本）:
- bootstrap: `SECURITY_BOOTSTRAP_EXPIRES_AT`（§4.2）。
- NF01 二者確認: `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF`（§1.7）。
- NF06: `IMPORT_ROW_REFERENCE_HMAC_KEY` と admission の承認設定（`feature-switches.md` 4.8.2）。
- NF07: 決定ID束縛 `PRIVACY_APPROVAL_*`。DSARでは加えて `PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE`（同 4.7）。
- NF05: `INTEGRATION_HUB_PUBLIC_ID_KEY`、`INTEGRATION_HUB_CRYPTO_CURRENT_KEY_VERSION` と鍵、truststore 等（DG-05）。RB-NF05 で次を加えた（どれも開ける方向だけで必須。OFFのままなら不要）:
  - `INTEGRATION_HUB_CRYPTO_CREDENTIAL_HASH_PEPPER`（`integration.hub.crypto.credential-hash-pepper`）: credential の保存用 HMAC の pepper。32 byte を `=` 無しの base64url で書き、暗号鍵（全 version）と復号後の byte 列が違う値にする。secret（〇）。inbound・outbound のどちらを開けても必須（N5-P3-2）。
  - `INTEGRATION_HUB_INBOUND_APPROVAL_DIGEST`（`integration.hub.public-api.approval.approval-digest`）: DG-05-IN の決定書に記載された承認内容の SHA-256。inbound を開けるときに必須。secret ではない（×）。
  - `INTEGRATION_HUB_OUTBOUND_APPROVAL_DIGEST`（`integration.hub.external-transport.approval.approval-digest`）: DG-05-OUT の決定書に記載された承認内容（truststore file の内容を含む）の SHA-256。outbound を開けるときに必須（×）。
  - digest の正規形・二人による再計算・golden vector は DG-05 草案 §1.1。不一致・未設定・形式外では起動しない（`IntegrationHubProductionEnablementPolicy`）。

**起動は止めないが運用で設定するもの**:
- **`TZ=Asia/Tokyo`（必須。§4）**: JVM のタイムゾーンを固定する。
  - 未設定だと JVM は UTC で動く。30分の account lock が約9時間30分続き、監査等の日時も9時間ずれる。
  - image（`Dockerfile`）も `TZ` と `JAVA_TOOL_OPTIONS` の `-Duser.timezone=Asia/Tokyo` を持つ。task definition でも `TZ` を設定する。
  - `validate-task-definition.ps1` は、`TZ` が無い場合と、`JAVA_TOOL_OPTIONS` を上書きして `-Duser.timezone` を落とした場合に Fail する。
- **`RESUME_LEDGER_COPY_ALIGNMENT_ENABLED=false`（初回配備の雛形の値）**: V197 の署名まで、副本の保存期限を縮めず、廃棄候補を毎日作り直さない。署名後に `true` にする（§4.3）。
- `FILE_SCANNER_HOST` / `FILE_SCANNER_PORT`: ClamAV。既定は `localhost:3310`。届かないとscanできず、uploadを公開できない。雛形は host が placeholder、port が `3310`。ClamAV の置き方（同じ task の sidecar か外部の service か）は要決定（§1.6 の6）。
- `METRICS_SCRAPER_ENABLED` / `METRICS_SCRAPER_USERNAME` / `METRICS_SCRAPER_PASSWORD`: metricsの取得用。adminのHTTP Basicは常に401になる（§1.7 SRE-9）。雛形は `METRICS_SCRAPER_ENABLED=true`、username が placeholder、password が secret。scrape 経路は要決定（§1.6 の7）。
- `SMTP_HOST` 等: 通知メール。

**環境変数ではないが起動時に検査されるDBの状態**: 次は §5 の手順で揃える。bootstrap中は、break-glass管理者とOIDC管理者の人数の検査を省略する（§4.2）。
- 有効なOIDC provider（`m_identity_provider`）。
- OIDCでログインできる管理者3名以上。
- default permission group 5件。
- 全体baseline `'*'` を持つgroupの `privacy.*` の拒否行。

### 1.1 DBサーバー（DBA）

- MySQL 8.0系（版はDBAが確定する。要決定（§1.6 の3・DBA-4）。testは `mysql:8.0.36`。確定後は `-Dses.flyway.fresh-install.mysql-image` で同じ版を検証する）。
- **serverタイムゾーン `+09:00`**（ユーザー決定）。my.cnf / parameter groupで `default_time_zone = '+09:00'` を設定し、次で確認する。
  ```sql
  SELECT @@global.time_zone, @@session.time_zone, NOW(), UTC_TIMESTAMP();
  -- 期待: @@global.time_zone = '+09:00'、NOW() が UTC_TIMESTAMP() の9時間後
  ```
  アプリのJDBC URLは `serverTimezone=Asia/Tokyo` を付ける（`application.yml` の既定 `DB_URL` と同じparameter）。jobの `FLYWAY_URL` にも同じparameterを付ける。
- database作成（DBAが空で作る）。文字コードは全表がV1等で `utf8mb4` / `utf8mb4_unicode_ci` を明示しているため、DBの既定も揃える（DBA確認）。
  ```sql
  CREATE DATABASE ses_manager_db CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
  ```
  確認（DBA。版と server の文字コードも配備記録に残す）:
  ```sql
  SELECT VERSION(), @@global.character_set_server, @@global.collation_server;  -- 版は §1.6 の3 の決定と一致
  SELECT default_character_set_name, default_collation_name FROM information_schema.schemata
   WHERE schema_name = 'ses_manager_db';                                         -- utf8mb4 / utf8mb4_unicode_ci
  ```
- **`innodb_print_all_deadlocks = ON`**（SRE-8・RR-15。parameter group / my.cnf。DBA-4）: deadlock の待機graphを error log へ出し、`LOCK_CONFLICT`（409）の原因を調べられるようにする。
  ```sql
  SELECT @@global.innodb_print_all_deadlocks;  -- 1
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
  - 確認（DBA。job の前に、権限を見られる管理用の account で実行する。`<migrator>`・`<host>` は実際の account）:
    ```sql
    SHOW GRANTS FOR '<migrator>'@'<host>';
    -- 期待: GRANT USAGE ON *.* と、`ses_manager_db`.* への上の14権限だけ（DBA-1 で SUPER 相当を渡すと決めた場合はその行も）。
    --       ALL PRIVILEGES、*.* への DDL、他の schema への権限が無い
    SELECT COUNT(*) FROM mysql.role_edges WHERE to_user = '<migrator>';
    -- 0。0 でなければ SHOW GRANTS FOR '<migrator>'@'<host>' USING <role> で role の権限も確かめる（USING 無しでは role の権限が出ない）
    SELECT @@global.log_bin, @@global.log_bin_trust_function_creators, @@global.binlog_format,
           @@global.gtid_mode, @@global.enforce_gtid_consistency;
    -- 期待: binlog_format = 'ROW'（DBA-3）。log_bin = 1 なら、log_bin_trust_function_creators = 1 か migrator の SUPER 相当（DBA-1 の決定どおり）
    ```
- **アプリ account**: 対象schemaへの `SELECT, INSERT, UPDATE, DELETE` だけ。DDL権限は渡さない。起動時schema guardが `flyway_schema_history` を読むため、そのSELECTも必要（schema単位のSELECTで足りる）。
  - 確認（DBA。配備前）:
    ```sql
    SHOW GRANTS FOR '<アプリaccount>'@'<host>';
    -- 期待: GRANT USAGE ON *.* と、`ses_manager_db`.* への SELECT, INSERT, UPDATE, DELETE だけ。PROCESS・DDL・他の schema への権限が無い
    SELECT COUNT(*) FROM mysql.role_edges WHERE to_user = '<アプリaccount>';   -- 0（0 でなければ USING で role の権限も確かめる）
    ```
    起動後（§4）に、全 instance が同じ user 名で接続していることを確かめる（PROCESS を持つ管理用の account で実行する）。
    ```sql
    SELECT user, COUNT(*) FROM information_schema.processlist WHERE db = 'ses_manager_db' GROUP BY user;
    -- アプリの接続はアプリaccount の1行にまとまる（別の user 名のアプリ接続が無い）。backup・管理用の account の行は別に出てよい。
    -- job の実行中でなければ migrator は出ない
    ```
  - **全 instance が同じアプリ account（同じ user 名）を使う**。Secrets Manager の alternating-users 方式（2つの user を交互に切り替える rotation）は使わない。password だけを替える single-user の rotation は使える（RB-NF05 review P3-2）。
    - 理由: NF06 の取込 stage 照合（`ImportStageReconciler`）は、結果不明の COMMIT を送った接続がまだ文を実行しているかを `information_schema.PROCESSLIST` で確かめる。PROCESS 権限の無い account には**同じ user 名の thread だけ**が見えるため、別の user で張った接続は見えず、照合は黙って時間だけの判定へ戻る（`feature-switches.md` 4.8.2 #10、a1a §6）。
    - PROCESS 権限は渡さない（同じ user なら不要。`ImportCsvIngestionRealMySqlTest` が業務権限だけの専用 user で確かめる）。`SHOW GLOBAL STATUS`（server の起動時刻）と `@@server_uuid` も権限なしで読める。
    - DBA が配備前に確認する（§1.7 NF06 行。未取得）。rotation の方式は要決定（§1.6 の9）。

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
- 秘匿上の注意: hashは `R__update_admin_password_bcrypt.sql` の `SET` 文1行だけに展開される。その文が失敗するのは値に引用符等が含まれる（＝BCryptではない）場合だけだが、jobのlogは秘匿扱いで保管する。job実行中はDBの general log を無効にする（DBA が job の直前と直後に `SELECT @@global.general_log;  -- 0` で確かめる。RDS 等では parameter group の `general_log` も同じ値であること）。`flyway_schema_history.checksum` にはhashを含む内容のCRC32が残る（hash自体は復元できない）。

### 1.4 artifactとjobの一致（SRE）

- jobはsource checkoutで `compile` したclass/resourceを使う。**jobのcommit SHAと、配備するimageのSHAを一致させる**。imageの配布経路（registry と digest の記録）は要決定（§1.6 の5）。docker save/load と digest の記録（決定XB-10）は backup tool image の決定で、アプリ image には決まっていない。
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
2. §1.2のmigrator権限・trigger/binlog・DEFINERの扱い（DBA。§1.7 の DBA-1）。
3. MySQLの版・collation・parameter group（DBA。DBA-4）。版を test の `mysql:8.0.36` 以外にする場合は、`-Dses.flyway.fresh-install.mysql-image` でその版を指定して MySQL の試験を再検証する（§1.1）。
4. job実行環境（Maven・JDK・依存取得経路。§1.5のlocal repositoryの準備方法）（SRE。SRE-11）。
5. **アプリ image の配布経路**（SRE）。docker save/load と digest の記録（決定 XB-10）は backup tool image（`ops/backup/README.md`「ツールイメージの build・配布」）についての決定である。Fargate の task はアプリ image を registry から pull し、`infra/ecs/task-definition.prod.json` は ECR の registry digest を前提にしている（ECR は未構築）。どの registry から、どの digest で配るかを決める（§1.4）。
6. **ClamAV の置き方**（SRE）。同じ task の sidecar か、外部の service か。これで `FILE_SCANNER_HOST` / `FILE_SCANNER_PORT`（§1.0）の値と task definition の形が決まる。ClamAV に届かないと upload を公開できない。
7. **Prometheus の scrape 経路**（SRE。SRE-7 の(4)・SRE-9）。ALB 経由の HTTPS か、同じ task の sidecar か（`forwarded-header-sanitize-notes.md` §3.6。sidecar 経路の扱いは未決）。
8. **`LOCK_CONFLICT` の alert の閾値と通知先**（SRE。SRE-8）。
9. **アプリ account の password rotation の方式**（DBA。§1.2）。同じ user 名のまま password だけを替える方式に限られる（alternating-users 方式は使わない）。方式と周期、全 instance へ反映する（task を再起動する）までの扱いを決める。
10. **RPO 15分・RTO 4時間の署名**（DBA-5）。値は決定済み（2026-11-13）で、要るのは DBA Lead と Operations の署名である。

### 1.7 配備前提と、機能スイッチをONにする前の署名（一覧。X3-4）

各所に分かれている前提の索引である。手順と根拠は各リンク先を正本とし、本節では繰り返さない。初回リリースは全スイッチOFFで配備し（`feature-switches.md` 3.1）、下表の署名が揃ったスイッチから1つずつONにする（同 3.2）。

**SRE の前提（初回配備から必要）**

| # | 前提 | 理由 | 正本 |
|---|---|---|---|
| SRE-1 | **単一tenantで配備する**（`OIDC_TENANT_ID=default`、`ACCOUNTING_TENANT_IDS` は1件）。多tenantで公開しない（RR-14） | `/api/users` の一覧・取得・更新・削除がtenant境界を持たず、多tenantでは管理者が他tenantの利用者を操作できる。外部アカウント関連の表・schedulerも単一tenantを前提にする。NF02のSLA営業日カレンダー（`m_work_calendar`）もtenant列を持たず、`ServiceSlaCalculator.findCalendarForScope` の4段目（全社共通）のカレンダーは全tenantで共用される（W3 review P3-N2-4。tenant列の追加はDDLが要るため未対応。多tenant化の前に対応する） | `.kiro/roadmap/2026-11-14-snf-nf01-fine-grained-epoch-plan.md` §9 RR-14 |
| SRE-2 | **inboundを開放するまで、LB/WAFで `/external-api/**` を遮断する**（D-1） | appのOFF時の404に頼らず入口で止める（認証前のrate制限が無く、届けばDB接続・CPUを消費する）。解除はDG-05-INの署名後に、承認済み送信元だけを通すruleへ置き換える（外すだけにしない） | `ops/security/runbooks/integration-hub-public-api.md` §0 |
| SRE-3 | `TRUSTED_PROXIES`（`app.security.trusted-proxies`）にALBのsubnet CIDRを設定する | 未設定・空・広すぎる範囲・構文不正では本番は起動しない。公開APIを開ける場合は `integration.hub.security.trusted-proxies` も同じALB経路にそろえる | `forwarded-header-sanitize-notes.md` §3.3 |
| SRE-4 | LB / reverse proxy の idle timeout を**130秒以上**にする。確認: クラウドの管理画面／CLI で ALB の load balancer 属性 `idle_timeout.timeout_seconds` が130以上。ALB の前に reverse proxy・CDN を置く場合は、その idle timeout も同じく確かめる | 取込センターの検証は同期要求で、最長120秒＋αの間1 byteも返さない。60秒等の既定では504で途切れる（NF06のONの条件） | `feature-switches.md` 4.8.2 の6 |
| SRE-5 | **単一instance、またはsticky session**で運用する。新旧artifactを同時に稼働させない（rolling禁止）。確認: (a) DB: 起動後に `SELECT COUNT(DISTINCT SUBSTRING_INDEX(host, ':', 1)) FROM information_schema.processlist WHERE user = '<アプリaccount>';` が1（接続元の task が1つ。PROCESS を持つ管理用の account で実行する）。(b) クラウドの管理画面／CLI: ECS service の desired count と running count が1、進行中の deployment が1件だけ、deployment configuration の maximum percent が100（新旧の task を重ねない。そのため minimum healthy percent は0になる） | sessionはin-memory（Spring Session無し）で、再起動で全員がlogoutする。NF04（B-NF04-3）、NF06の照合台帳（instance local）、NF08の外部送信（開放後も単一instance運用）が前提にする。NF10の定期レポートは新旧versionの混在を許さない | `mobile-pwa-self-service/first-release-user-and-operations-notes.md` §2.1、`feature-switches.md` 4.8.2 の9、`ai-management-copilot/external-send-approval-gate.md` §6、`scheduler-claim-fence-plan.md` §10、本書 §6（ロールバック） |
| SRE-6 | **表単位のrestore・rollbackをしない**（DBを戻すときは全体のPITRだけ。RR-13） | anchor表（`sys_user`・`t_engineer`・`m_organization_unit`・`t_document`・`t_lifecycle_task`）だけを古い行へ戻すと認可epochが巻き戻り、旧提出が復活し得る。全体のPITRは前後で一貫する | NF01細粒度epoch計画 §9 RR-13、`engineer-lifecycle-workflow/runbook.md`、`ops/backup/runbooks/` |
| SRE-7 | **転送ヘッダーの清浄化の公開前の措置**（SRE-3 以外の §3 の項目） | 次の4つ。<br>(1) WAFで `X-Forwarded-Prefix`・`X-Forwarded-Host`・`Forwarded`・`X-Forwarded-Ssl` のどれかを持つrequestを403にする。headerの有無で判定し、COUNTで始めてBLOCKにする（§3.1）。<br>(2) ALB: XFFは `append`、`xff_client_port` は無効、公開Host以外は既定404、ALBだけを許すsecurity group、target groupのhealth checkは `/actuator/health/readiness`（§3.2）。<br>(3) deploy前後の確認0〜7（§3.4）。<br>(4) Prometheusのscrape経路を決める（§3.6。plain HTTPでは302になる。`TRUSTED_PROXIES` に127.0.0.1を入れない）。経路は要決定（§1.6 の7） | `forwarded-header-sanitize-notes.md` §3.1・§3.2・§3.4・§3.6 |
| SRE-8 | **`LOCK_CONFLICT` の監視とalert**（初回配備から） | `/api/**` の 1213（deadlock）・1205（lock待ちtimeout）は409（PWAの書込みは503）へ変換され、WARN log `errorCode=LOCK_CONFLICT` だけが残る。5xxを数える監視では見えないため、この件数の監視とalertを置く。alertの閾値と通知先は要決定（§1.6 の8）。DBAは `innodb_print_all_deadlocks=ON`（RR-15）。`TransactionSystemException` に包まれた場合は500のまま | `engineer-lifecycle-workflow/runbook.md` §6.6、NF01細粒度epoch計画 §10（SRE）、`GlobalExceptionHandler.java:73-88` |
| SRE-9 | **metricsの取得は scraper 口座で行う**（`METRICS_SCRAPER_*`。§1.0） | `GET /actuator/prometheus` のHTTP Basic chainは、`admin` とbreak-glass名を、bootstrapの状態に関係なく常に401で拒否する（大小文字・前後空白を無視して照合。DOC-FINAL N-2）。admin口座でscrapeを設定しない。scrape経路は要決定（§1.6 の7） | `SecurityConfig.java:565-592`（`BreakGlassBasicRejectingProvider`） |
| SRE-10 | **§1.0 の1〜26を全て設定する**（secretはsecret storeから）。freeeを使う場合（`FREEE_ENABLED=true`）は27〜30も設定する | 1つでも欠けると初回起動が必ず失敗する（復旧はできるが停止が延びる）。登録する task definition を起動の前に `infra/ecs/offline/validate-task-definition.ps1` の実値の mode で照合する（呼出し方と検査の範囲は §4） | 本書 §1.0、`infra/ecs/task-definition.prod.json` |
| SRE-11 | **job実行環境（Maven・JDK・依存の取得経路）を決め、local repositoryを揃える** | 別の機械・別の `.m2` で job を動かすと、片方にだけ無い artifact で job が止まる。推奨は build と同じ機械・同じ `.m2`。要決定（§1.6 の4） | 本書 §1.5・§1.6 の4 |

**DBA の前提**（番号は署名一覧の DBA-1〜DBA-6 と同じ）
- **DBA-1**: migrator accountのtrigger権限は §1.2 にある。binlogが有効なときの `log_bin_trust_function_creators`・SUPER相当の扱いと、DEFINERとmigrator accountを残すことを含む。
- **DBA-2**: serverタイムゾーン `+09:00` と空schemaは §1.1 にある。
- **DBA-3**: **`binlog_format` は ROW（STATEMENT 不可）**。これは READ COMMITTED を宣言した transaction の前提である。
  - 対象: 退社完了の `LifecycleCaseServiceImpl.completeCase`、ポータル紐付け登録の `PortalEngineerLinkServiceImpl.register` 等。一覧は `WriterIsolationPolicyTest` の `READ_COMMITTED_WRITERS`。
  - binlog が STATEMENT だと、InnoDB は READ COMMITTED の transaction の書込みを拒否する（ERROR 1665）。そのため、これらの操作が全て失敗する。
  - MySQL 8 の既定は ROW である。§1.2 の一時表の条件（`enforce_gtid_consistency = ON` のとき binlog_format=ROW 等）とあわせて DBA が確かめる。
  - 確認: `SELECT @@global.binlog_format;  -- 'ROW'`
- **DBA-4**: MySQLの版・collation・parameter group を確定する（§1.1、要決定（§1.6 の3））。parameter group には `innodb_print_all_deadlocks = ON`（SRE-8・RR-15）も入れる。確認SQLは §1.1。
- **Backup・PITR**:
  - **DBA-5**: RPO 15分・RTO 4時間（2026-11-13 決定）について、DBA Lead と Operations の署名が **PENDING**（署名事項。§1.6 の10）。
  - **DBA-6**: prodの `.env.backup` を用意する（形式は `ops/backup/README.md` の「前提」「設定」）。
    - `CONTENT_HASH_TABLES`（未設定ではvalidateがFAILする）。本番の値はまだ文書に無い。DBAが決める。主キーを持つ表のカンマ区切りで、`flyway_schema_history` を含め、少数に保つ。
    - `FLYWAY_VERSION`: §3.4 の最新version照会の値を Flyway 形式（`V`・`_` を使わない）で書く。本 release は `201`。release ごとに §3 の後で更新する。未設定（`unknown`）や古い値で取った checkpoint は、復元検証で `flyway_schema_history` の成功行の最大と一致せず、通らない。
    - `BACKUP_REPOSITORY`・`MYSQL_HOST`・`MYSQL_PORT`・`MYSQL_DATABASE`・`MYSQL_USER` と、`secrets/` の 0600 の file（MySQL と restic の password、CA の capath）。
  - **初回 backup（§2 の5。§3 の完了直後）と BLOCKED の解除**: `ops/backup/README.md` は、production 固有値（HFP-03-PROD-001〜008。`.kiro/specs/database-backup-recovery/baseline.md` §4）が未確定の間、production への接続・復元を BLOCKED としている。DBA（と各 owner）が次の時点までに確定する。
    - 初回 backup の前: 001（MySQL の版・`@@server_uuid`・GTID・binlog・checksum・compression・TLS・保持期間。DBA-3・DBA-4 と同じ値）、002（全表の engine とアプリ以外の writer の有無）、003（uploads の置き場所（task definition の EFS）と atomic snapshot・versioning の可否）、004（replica・scheduler・drain・DDL change-lock の実際の手段。§2・SRE-5）、005（S3 互換 backend・IAM・versioning/immutability・容量と throughput）と、上の `.env.backup` の値。
    - backup の監視（`check --json`）を運用に入れる前: 008（alert routing・運用 owner・incident commander・drill owner）。
    - 初回の restore（restore drill・本番の restore）の前: 006（二者承認の identity・署名の検証と change-ticket の仕組み）、007（代表 profile。RTO の実測に使う）。
    - 解除の条件: 項目ごとに baseline §4 の「確定に必要な証跡」を配備記録に残し（001・002 は production に対する `preflight` の redacted な `--json` 出力で、`preflight` が 0 で終わること）、DBA が DBA-6 に署名した時点で、その項目の BLOCKED を解除する。解除するのは production への接続（full・binlog の archive・checkpoint）で、復元は従来どおり production とは別の recovery target へ行う（`ops/backup/README.md`「復元」）。001〜005 が揃うまでは初回 backup を取れず、§2 の5 を満たさないため §4 へ進まない。
  - 正本: `ops/backup/runbooks/restore-cutover.md`（RPO/RTO と署名欄、`CONTENT_HASH_TABLES` の要求）、`ops/backup/README.md`（`.env.backup` の形式）、`.kiro/specs/database-backup-recovery/baseline.md` §4（production 固有値と確定に要る証跡）。

**初回配備から有効になる部分の署名・受理**（機能スイッチでは止まらない。scheduler・通常運用の前提）

| 対象 | 要るもの | 正本 |
|---|---|---|
| NF04 のうちスイッチOFFでも止まらない部分（pwa-queueの保存経路、lock中の下書きの復元（local password sessionだけ）、台帳の清掃、restore-sessionのlistener）。スイッチはService Workerとmanifestだけを止める | 次のうちこの部分にかかる署名と真機受入が初回配備の前提になる（全行が未署名）。<br>(1) Privacy: 端末内30日保存（P3-13 を含む）。<br>(2) SRE/Ops: 単一instance・sticky session（SRE-5）。<br>(3) Owner/Security: B-NF04-4（失効の制限。決定待ち）。<br>(4) 業務の受入担当: 真機受入 S-01〜S-15 | `feature-switches.md` 4.4「次はOFFでも止めない」、`mobile-pwa-self-service/first-release-user-and-operations-notes.md` §4、`mobile-pwa-self-service/real-device-browser-acceptance-template.md` |
| 履歴取込の副本・取込センターCSV（文書種別 `RESUME_INGESTION` / `IMPORT`、V197） | 保存年数（3年・TRANSACTION_DATE）は暫定値で、文書ownerの確認待ち。<br>副本の処分方針について、Privacy owner と文書 owner の書面署名を得る。**署名までは `RESUME_INGESTION` の副本の廃棄申請を承認も執行もしない**（確認SQLは §4.3）。<br>副本の調整がするのは廃棄候補（PENDING）を作ることだけで、削除は管理者の承認と執行を経る。そのため、署名を待つために scheduler（`APP_SCHEDULING_ENABLED`）を止めなくてよい。この scheduler は file 清掃・契約更新・freee・消込等の中核jobと共用で、止めると通常運用が再開できない。<br>初回配備は `RESUME_LEDGER_COPY_ALIGNMENT_ENABLED=false`（雛形の値）で副本の調整だけを止め、署名後に `true` にする（§4.3）。<br>`IMPORT` の保持期間は、Privacy の書面受理まで開放の条件のまま | 冒頭の V197 の項、§4.3 |
| 顧客ポータル全画面（login・招待受諾・規約・customer・bp。`templates/portal/**`）のBootstrap参照の修正（RB-I18N `5a7e2bbd`）。機能スイッチと関係なく配備した時点で全画面に効く | 実ブラウザでの確認（**未取得**）。修正前はBootstrapのCSS・JSとも404で、`d-none`・モーダルが効かず全tab・全モーダルが常に表示されていた。修正後は設計どおり隠れて切り替わるため見た目が大きく変わる。確認する項目: login・MFA設定・招待受諾・規約同意の入力と送信、customerの各tab（検収・契約・請求・見積・注文請）の切替と検収・請求のモーダル、bpの各画面、header のボタンの視認性（濃色の背景に `btn-outline-secondary`）。desktop と 390px。担当: 業務の受入担当（顧客ポータル）。NF02の子スイッチ（問い合わせ）をONにする前の確認は別（`feature-switches.md` 4.3.1） | `feature-switches.md` 4.3.1 (4)、`StaticAssetLocalityTest`（参照先の実在） |

**機能スイッチをONにする前に要る署名・受理**（揃うまでOFFのまま。ONの手順は各正本）

| 機能（設定） | ONの前に要るもの | 正本 |
|---|---|---|
| NF01 本体（`FEATURE_NF01_ENABLED`）。`feature-switches.md` 4.2 にはONの条件が無いため、本行を索引とする | (1) NF01計画 §10 の署名（全て未署名）。<br>　・Identity/Security: 確認者は admin/HR（B-NF01-5）。1213/1205 を含む3回のbudgetと409の扱い。attemptのtimeout・error key・拒否eventの項目。RR-1・RR-2・RR-4・RR-8・RR-11・RR-14 の受理。O-1、session台帳のtenant基準、NF01外のIdP停止、失効失敗の通知先、行の法人ごとの失効（§5.13）。<br>　・SRE: RR-14、RR-13、失効のalert閾値とworker間隔、`LOCK_CONFLICT` の率の監視（SRE-8）。<br>　・Document Owner: SKILL_SHEET×単一ENGINEER、対象状態、epoch規則、scan_statusだけをscan根拠にすること、retention jobのservice経由、RR-8。<br>　・HR: REJECTを含む手順、担当・role変更後の再提出、世代の失効、外部失効のworkflow、RR-11、未連携のportal利用者、`released_at`。<br>　・DBA: V189/V195・trigger 12件のDEFINERとbinlog、charset、`ascii_bin` の冪等性key、`innodb_print_all_deadlocks=ON`（RR-15）、RR-13。<br>(2) lifecycle runbook §6.4 の実測と確認。<br>　・P3-1: M6 を5回以上、skip 0、両順序で1213/1205無し。<br>　・P3-9(4): 外部アカウントの事後登録が退社完了の要員Xを待ってから登録することを、両順序で `InnoDbRecordLockWaits` により実測する（RB-NF01 で M10 `ResignationRevocationOutboxMySqlTest` に両順序の試験を追加した。Runner の実測は未取得）。M10 と `AssetMySqlIntegrationTest` も5回以上。staging の実測（lifecycle runbook §6.4）は人が行う（未取得）。<br>　・P3-5: 案件IDの作成順を時間順の根拠にしてよいことをHRが確認する。<br>(3) 同 §6.6 の R-1〜R-3。<br>(4) W3-02 P3-3（`lifecycleType` をenumで検査しない）と w1b4 P3-5（tenantを持たない `selectByIdForUpdate(Long)`）は RB-NF01 で修正した（種別は正規の表記との完全一致だけを受け付け、テンプレートの種別との一致も検査する。tenantを持たない案件のlocking readは削除）。w1b4 P3-7（`error.concurrentModification` が bundle に無い）は FINAL-FIX `06321ab0` で4言語に追加済み（未処理ではない）。W3-16b P3-5（要員writerがFKの 6 S を 7 X より先に取ること）は M4 `EngineerScopeFenceMySqlTest` に要員の所属変更×統合の両順序を追加した（Runner の実測は未取得）。<br>W3-02 P2-1(b)（紐付け登録が退社完了を見落とす）と P2-2（証跡版一覧の文書ACL）は FINAL-FIX で修正した。M6 の新しいcaseを含め、Runnerの実測で確定する | NF01細粒度epoch計画 §10、`engineer-lifecycle-workflow/runbook.md` §6.4・§6.6、`feature-switches.md` 4.2 |
| NF01 二者確認（`FEATURE_NF01_DUAL_CONFIRMATION_ENABLED`） | (1) Privacy署名: comment 2列（`business_comment`・`decision_comment`）の保存期間と削除・匿名化経路（O-8）、**表示範囲がrole級**であること（本文を読めるのは案件を閲覧できる全ての管理者・HR）、**代理提出の差戻し理由は営業が要員へ伝える**運用（要員本人には「コメントあり」だけが見える。HRの手順に含める）。(2) **NF07 の PII inventory・data element catalog への B0 の7表と comment 2列の登録**: V198 で登録済み（`m_pii_data_element` の9件と `privacy-retention-dsar/pii-inventory.md` の DB-141〜DB-147。provider は未登録、分類・owner・inventory 状態は UNKNOWN で、保持期間は持たない）。保存期間等の値は D-02 の署名で確定する（**署名は未取得**）。分類が UNKNOWN のため、NF07 hold 画面の「未分類」と `PrivacyCoverageReport` の未分類件数にもこの9件が出て、D-02 で分類を決めるまで0にならない。(3) 下の §1.7.1 の手動確認9項目。(4) W3-21a・W3-21b のPASS。(5) **Security の判断（未取得）**: break-glass incident の scope に lifecycle の action を含められるため、break-glass 口座で二者確認の提出・確認ができる（W3-15 P3-7、`BreakGlassServiceImpl.java:316-330`）。許すか止めるかを決める。(6) 提出の discovery の存在 oracle（W3-21a P3-5・RB-NF01 review RB-P3-1）は RB-NF01 で計画の error 表どおりに修正した。提出者に見える文書は「文書ACL（SKILL_SHEET×ENGINEER link の文書ダウンロードの部分集合。ダウンロードは link のどれか1件（CUSTOMER link 等）が通れば足りるが、二者確認は ENGINEER link の分岐だけを見るため、ダウンロードより広くならない。RB-NF01 delta review D-P3-2）を満たし、tenantに存在する SKILL_SHEET で案件の要員を指す ENGINEER link を持つ」ものだけで、それ以外（不存在・ACL外・他の要員の文書・ENGINEER以外のlink・SKILL_SHEET以外・RECEIPT 等のダウンロードACLが厳しい種別・削除済みの不可視文書）は、削除・種別等の適格性を判定せず存在しない文書と同じ400 `error.document.notFound`（HTTPのstatusとbodyが同じことを `LifecycleDualConfirmationApiControllerTest` で固定）。discovery と attempt で同じ順。409 等の具体的な拒否は見える SKILL_SHEET についてだけ返す。(7) RB-NF01 で追加した MySQL 試験（M1 の要員 SHARED が PRIMARY に付くこと、M8 の実writer 4件）の Runner 実測（未取得）。prodは署名の参照 `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF` が無いと起動しない（内容は検証しない） | `feature-switches.md` 4.9.2、NF01細粒度epoch計画 §10・§12 O-8、§1.7.1 |
| NF03 資格・学習・skill gap（`FEATURE_NF03_ENABLED`）と子の資格番号（`FEATURE_NF03_CERTIFICATION_PII_ENABLED`） | `feature-switches.md` 4.6 には署名の一覧が無い。<br>本体: 業務Owner の承認（同 3.2）。<br>子: DG-07 の承認（同 3.4）。あわせて、証憑文書について Privacy が確認する（同 4.6.1）。<br>「マネージャー・営業は `certification.pii.view` を拒否される」ことを検証する試験（FINAL P3-4-1）は RB-NF02 で `ActionPermissionMatrixTest` へ加えた（HR・管理者は許可される対照つき。seed は現状どおり拒否で、seed の修正は無い）。Runner の fast で PASS を確認する。<br>資格番号の暗号鍵は、OFFでも§1.0 の19・20が要る | `feature-switches.md` 3.2・3.4・4.6 |
| NF04 本人セルフサービス（`FEATURE_NF04_ENABLED`。Service Worker・manifest） | 署名表の全行（現在すべて空欄）。<br>(1) 業務Owner: 利用者向けの文言（1章）。<br>(2) SRE/Ops: 単一instance・sticky session。<br>(3) Privacy: 端末内30日保存（P3-13）。<br>(4) Owner/Security: B-NF04-4 の決定、P1-N4-1 の案A、P1-W309-1 (a)。<br>(5) 業務の受入担当: 真機受入 S-01〜S-15。<br>スイッチOFFでも有効な部分の署名は、上の「初回配備から有効になる部分」の表にある | `mobile-pwa-self-service/first-release-user-and-operations-notes.md` §4、`mobile-pwa-self-service/real-device-browser-acceptance-template.md`、`feature-switches.md` 4.4 |
| NF10 定期管理レポート（`FEATURE_NF10_ENABLED`） | 全て**未取得**（一覧の詳細は `feature-switches.md` 4.5.1）。<br>(1) Report Owner の署名（ONの前）。<br>(2) alert rule の閾値・評価窓・重要度・通知経路（決める人: SRE。未決）と、`RETRY_EXHAUSTED_DISABLED` を受ける業務側の宛先と対応手順（決める人: Report Owner。未決）。計画 §10.6 はプレースホルダの草案だけで、値は決めていない。<br>(3) NTP の前提と、許容する本node・DBの時刻差（決める人: SRE。未決）。差は gauge `ses_report_schedule_clock_skew_seconds` で見える。WARN のコード既定5秒は決定ではない。<br>(4) tenant のタイムゾーンを変えるときの方針と、決まるまでの暫定の扱い（決める人: Report Owner、運用手順はSREと合意。未決）。<br>(5) 確定版の配布方針（決める人: Report Owner。未決）。<br>(6) 「12回」の数え方の確認: 引き継ぎ（completionに至らずlease期限を超えた試行）も連続失敗1回と数える（RB-NF10。Report Owner。未取得）。回数は同一論理runの中でだけ累積し、成功で0へ戻る（月を跨いで累積しない）。単一instanceでは実行中の処理は引き継がれない（process停止・再起動の後と、多instanceの他nodeだけが引き継ぐ）。<br>(7) 生成・配布の所要時間の見込み（Report Owner。未取得）: 正常な実行が30分（lease）を超え得るなら、数え方の定義ではなく B-NF10-2（lease延長はしない）を見直す（lease更新またはより長いlease）。数え方の定義は統括の判断で現状のままとした（計画 §14.2）。<br>(8) 排水草案の SRE/DBA 承認（計画 §10）。<br>completion-matrix の未完了の行も参照する。新旧versionを同時に動かさない（SRE-5） | `scheduled-management-reporting/scheduler-claim-fence-plan.md` §10・§10.6・§11・§14・§15、`scheduled-management-reporting/completion-matrix.md`、`feature-switches.md` 4.5・4.5.1 |
| NF02（`FEATURE_NF02_ENABLED`。子の顧客ポータルを含む） | DG-02（CS Owner。B3 休日の運用事項 H-1・H-2 を含む）と、当年度を含む全社共通カレンダーの登録 | `customer-success-service-desk/dg-02-first-release-decision-package.md`、`feature-switches.md` 4.3 |
| NF05 公開API（`integration.hub.public-api.enabled`・`integration.hub.external-transport.enabled`） | DG-05（inbound `DG-05-IN-*` と outbound `DG-05-OUT-*` を別々に署名・開放）。G3r の追加署名条件（未署名）を含む: **OUT-7**（G3sで置換。永続OFFを確認したOFF→ONだけの隔離、ON→ONのキュー維持と at-least-once、結果不明CLAIMEDの照合。**OFF書込成功と同じ bound-tenant fingerprint の確認がON変更の必須gate**）と **OUT-9**（P1-1/P1-2・P2-1〜3 の正式証拠を最終SHAで取得）。inboundはSRE-2の遮断の置換と合わせる。<br>FINAL review b-13 の項目（RB-NF05 で DG-05 草案 §1.1・§6 に反映）:<br>(a) 決定IDの承認内容 digest への束縛: **実装済み**。開ける方向の `INTEGRATION_HUB_INBOUND_APPROVAL_DIGEST` / `INTEGRATION_HUB_OUTBOUND_APPROVAL_DIGEST` が要る（§1.0）。署名者と転記者の二人が草案 §1.1 の正規形から再計算して照合する（**未実施**。署名の時に行う）。<br>(b) ownership の MySQL 試験: project・contract-status・invoice-status の分岐を `IntegrationHubV192AuditRetentionMySqlTest`（shard 3）に追加した。**Runner の証跡は未取得**。<br>(c) 実wireの TLS・XFF: JVM 内の loopback で fast 試験を追加した（`HttpsIntegrationHubWebhookTransportWireTlsTest`・`ExternalApiForwardedHeaderConnectorTest`）。実ネットワークの peer・ALB 経由の XFF・WAF の証跡は代替しない（**SRE が staging で取得、未取得**）。<br>(d) **SRE の負荷実測: 未取得**（人が行う。認証前の body 読取・AES-GCM・HMAC、rate 行と client 行の `FOR UPDATE`、5秒周期の状態 lock、bulk 検索を含む）。<br>あわせて N5-P3-2（credential hash を pepper 付き HMAC に。`INTEGRATION_HUB_CRYPTO_CREDENTIAL_HASH_PEPPER` が開放時に必須）と N5-P3-7（要求経路の lease 一括回収を間引き）を実装した。<br>RB-NF05 review で残った判断・受理（草案 §1.1）:<br>(e) **Security/Owner の判断（未取得）**: digest は鍵も有効期間も持たないため、**撤回・置換された旧決定（旧ID＋旧digest）を再び設定しても起動検査を通る**。防ぐのは配備記録と変更管理だけである。NF06・NF07 のように署名日・失効日を正規形へ入れる（正規形 v2）かを決める。<br>(f) **Owner/SRE の受理（未取得）**: truststore file の byte が変わること（同じ証明書の作り直しを含む）、`batch-size`・`fixed-delay-ms`・`unauthenticated-audit-max-per-minute` を変えることは、どれも新しい DG-05 決定（新しい決定ID・digest・署名）が要る運用負担になる | `integration-hub-public-api/dg-05-enablement-decision-draft.md`、`ops/security/runbooks/integration-hub-public-api.md` |
| NF06（`FEATURE_NF06_ENABLED`） | Data Ownerのadmission承認（設定の承認digestの照合）、Privacyによるraw CSVの保持期間とpreviewのマスク分類の書面受容。あわせて行参照鍵・V193の付与・単一法人・SRE-4・SRE-5。<br>FINAL review b-14 の項目（RB-NF05）:<br>(a) multi-host・failover・loadbalance・replication・SRV の JDBC URL: `ImportJdbcReconnectGuard` が起動時に拒否するようにし、4.8.2 #7 に書いた。透過再接続 proxy と Hikari 以外の pool は引き続き DBA/SRE が確認する。<br>(b) **W3-12 §6 の人手確認のうち未了のもの（未取得）**: LB の timeout 後の回復（検証中に LB が 504 や HTML の 503 を返した後、画面の「開く」で状態を取り直せること）、行参照鍵が欠けたときの preview・エラーCSV の 503（`error.import.rowReferenceUnavailable`）の画面表示。staging（実 MySQL）で人が確かめる。<br>(c) digest 正規形の golden vector: 4.8.2 #1 と a1a §10 に追加した（source code は ASCII 順）。二人による再計算そのものは署名の時に行う（未実施）。<br>(d) **DBA の確認（未取得）**: 全 instance が同じアプリ account（同じ user 名）を使い、alternating-users 方式の rotation をしないこと（§1.2、4.8.2 #10）。別の user で張った COMMIT 接続は照合から見えず、時間だけの判定へ戻る。<br>実装で埋めたもの: 照合器が COMMIT 接続の server thread の実行中は回収しない（記録後に server が再起動・切替えされた場合は接続IDを使わない。a1a §6）、`t_document_version.storage_key` の索引（V200）、エラーCSVの最終行 `#END`（集計時の除外は 4.8.2・a1a §11）、host 部に `;` を含む JDBC URL の拒否。MySQL の試験は追加したが **Runner の証跡は未取得** | `feature-switches.md` 4.8.2 |
| NF07 保持・保全（`PRIVACY_RETENTION_ENABLED`） | 決定ID束縛 D-01・D-09。hold開始の開放は、D-05 ownerの受理事項（§4.4末尾）と合わせる。<br>**解除の開放は、D-03・D-05 の署名だけでなく NF07-TRUST の実装が要る**（§4.5）。第1期は trust anchor registry が空で、失効 provider は常に UNKNOWN を返すため、解除の API は NF07-TRUST（registry と失効 provider）の実装まで404のままである。<br>NF07-TRUST のうち方式に依存しない部分は実装済み（RB-NF07-REG）: 接続点 `PrivacyTrustSource`（REG・REL・説明照会が共用）、REL の P3-6（Journal の解除 event に claim の digest、監査に claim）・P3-8（Ed25519 以外の鍵を登録で拒否）・P3-9（失効 provider が ACTIVE と答えるまで入口を閉じる）・P3-10（partition の一致）。**残る KMS/HSM の anchor registry と CRL/OCSP の失効 provider（`PrivacyTrustSource` の adapter）は、D-03 の方式が決まった後に実装する（未実装。D-03 の方式は未決定）**。**D-03 で選ぶ方式は Ed25519 の署名鍵を扱えること（必須。verifier は Ed25519 だけを検証し、他の算法の鍵は anchor として扱わない）**。退役した鍵の公開鍵も事後の検証のために保管する。<br>あわせて D-05 owner が「holdを開放した後、NF07-TRUST が終わるまで解除できない」ことを受理する（未取得）。<br>**policy の登録・有効化（NF07-REG。`POST /api/privacy/policies`・`/{id}/activate`）の開放は、D-01・D-02・D-03 の署名（D-02 は未取得）と NF07-TRUST の実装が要る**。解除と同じく、第1期は registry が空・失効 provider が常に UNKNOWN のため404のままで、決定IDだけを設定しても開かない。登録者と有効化者は別の privacy-admin（2名以上）。current より前に署名された revision への巻戻しは409（戻すには新しい revision として署名し直す。D-02 owner が別の運用を望む場合は再検討）。誤って登録した CANDIDATE は取り下げられる（`/{id}/withdraw`）。NF08 の外部送信はこの登録・有効化で対象 policy が `APPROVED_ACTIVE` になることを前提にする（計画 §8） | `feature-switches.md` 4.7・4.7.6、§4.4・§4.5 |
| NF07 本人請求（`PRIVACY_DSAR_ENABLED`） | 決定ID束縛 D-01・D-06・D-09（D-06 は DSAR SOP）と計画 §5.1 の追加前提（NF07-A2b で起動時の実検査に置換）: `privacy.dsar.due-days`（`PRIVACY_DSAR_DUE_DAYS`、1〜366）と `privacy.dsar.package-max-age`（`PRIVACY_DSAR_PACKAGE_MAX_AGE`、1時間〜366日）を D-06 の署名値で設定し、F2 provider を1件以上登録する（欠ければ `dsar.dueDaysUnset` / `dsar.packageMaxAgeUnset` / `dsar.providersUnregistered` で起動しない）。あわせて D-05 受理事項の (8) を D-06 owner と受理する: D14 の開示 package は hold で止めず、system は `package-max-age` を超えて内容を残さない（内容 hash と交付記録だけを残す。内容を残すなら交付の時点で system の外の保全手続で保管する）。<br>さらに次も要る（**いずれも未取得**。人が行う確認・受理で、実装では代えられない）。<br>(a) SELF 交付区分の確認（D-06 owner。F2 r4 P3-I）: WORK_REPORT・RECEIPT は第三者の情報を含み得る。**未取得**。<br>(b) 「承認者は承認の前に草案の全内容を取得できる」という不変条件の Privacy の受理。不変条件は計画 §1 の I-10 に記載済み（RB-NF07-HOLD）。**受理は未取得**。<br>(c) `feature-switches.md` 4.7.2 の人手確認 (a)(c) を行う。(a) は起動時の INFO log `DSARの設定値（D-06 の決定書と照合する）` の `dueDays`・`packageMaxAge` を D-06 の決定書と人が照合する（この2値は approval digest に入らない。digest に入れる v2 正規形の案は計画 §3.5.2 にあるが、決定書の digest の手順が変わるため未実装）。(c) は D-06 の redaction 規則を、承認者による承認前の確認が使える状態にしてから設定する。**未取得**。<br>`POST /resolution` の応答の警告（HYG-FINAL P3-F の残り）は実装済み（RB-NF07-HOLD）: 本人が HELD で結果が削除・利用停止なら、応答の `holdConflict` と画面の警告 toast で知らせる（記録は止めない）。<br>性能の残り（開放の条件ではない。後続の backlog）: 開示 package の孤児の照会（`PrivacyExportPackageMapper.selectOrphanFiles`、毎時 :15）は `t_file_security_metadata` の全表走査のまま（`file_kind` に索引が無く、消すには DDL が要る。HYG-FINAL P3-D の残り） | `feature-switches.md` 4.7.2 の #5 と人手確認、計画 §1 I-10、§4.4（D14 と受理事項） |
| NF08 外部送信（`AI_EXTERNAL_SEND_ENABLED`） | S-1〜S-8（DPA、DG-08、NF07 policy、外部送信決定、費用上限とGCP quota、`GATE-S17-G10-PROD`、modelリスク評価とcredential、DNS snapshot）と、**B2c-2 実装開始前の人手承認**（B2c-2 の着手条件）。prodはB2c-2の完了と独立Reviewまで、設定に関係なく起動を拒否する（冒頭のNF08 B2c-1の項）。<br>NF07-NF08-LINK は実装済み（RB-NF08-LINK）: 送信ごとに `AI_EXTERNAL_SEND_NF07_POLICY_ID` の NF07 policy が `APPROVED_ACTIVE` でなければ拒否する。LINK は S-1〜S-8 の代わりにならず、上の署名・承認は全て**未取得**。LINK が許可するには NF07 の行の policy 登録・有効化（D-02・D-03 と NF07-TRUST）と `PRIVACY_RETENTION_ENABLED=true` も要る。LINK の key の data element は V196 にある `AI_EXTERNAL_PROVIDER_COPY`（統括の決定、DDL なし）。**Privacy Owner がこの data element への束縛を確認する（未取得）**。<br>承認の再利用（W3-03 P3-2）: NF07 policy の再発行より前に署名した NF08 承認は LINK が拒否する。この防護は悪意の無い巻戻しだけを止め、意図的な内部者は止めない。受理（全て**未取得**、gate §7.4）:<br>(a) 方式と運用上の帰結（S-4 は NF07 envelope の発行日（JST 換算）以降に署名する。NF07 の再発行（D-03 の鍵 rotation を含む）のたびに NF08 の再署名まで外部送信が止まる）: Production Owner・Privacy Owner・D-03 owner。<br>(b) 残る穴 1（NF07 を再発行しない NF08 だけの置き換え）の運用規則: Production Owner・Privacy Owner。<br>(c) 残る穴 2（設定を変更できる者による内容の偽造）の HMAC: Security／CISO（S-7）。D-03 の鍵による署名にする場合は D-02・D-03 owner も。<br>(d) 残る穴 3（永続的な高水位）: DDL を伴う後続の開発 batch（版番号は総括が割り当てる）。<br>S-9（staging 観測）は NF07 の連鎖全体（NF07-TRUST の adapter、audience `PRODUCTION` の envelope と staging 用 anchor、REG の登録・有効化、`PRIVACY_RETENTION_ENABLED=true`）に依存し、準備する者と時期は**未定**（gate §8） | `ai-management-copilot/external-send-approval-gate.md` §7.4・§8、`.kiro/roadmap/2026-10-18-snf-nf08-c2-b2-technical-plan.md`（B2c-2 の開始条件） |

**書面受理（未取得）**

W1-B1（backup toolchain）で「書面での受理が要る」とされた事項である。受理の記録は見つかっていない（FINAL review §7 (a)-5）。Owner/SRE が書面で受理するか、backlog として明示する。

| # | 事項 | 出処 |
|---|---|---|
| W-1 | G-04: schema fingerprint が未実装（内容hashだけを実装した） | W1-B1 r2 報告 §5、W1-B1 review |
| W-2 | noclobber: 事前検査と open の間の微小な窓に、FIFO・device への symlink を置かれると開き得る | 同上 |
| W-3 | `tool-image.sh` の残り: 検査と実行の間に tag を付け替えられる（TOCTOU）。`pull_policy` は「1行以上あるか」しか見ない。flow-style の YAML を解釈しない | 同上 |
| W-4 | 「Essential は base の版を保つ」は厳密でない（依存解決で更新され得る） | 同上 |
| W-5 | apt lock の enforced 経路・不一致経路に単体試験が無い（初回 build で実測するだけ） | 同上 |
| W-6 | 同じ MSYS の別利用者・別 session の判定。`kill -0` の EPERM を「終了済み」として扱う | 同上 |
| W-7 | content ID が覆う設定の範囲（Env・Entrypoint・Cmd・WorkingDir・User・Labels だけで、Volumes・ExposedPorts 等を含まない） | 同上 |

#### 1.7.1 NF01 二者確認をONにする前の手動確認（browser、9項目。W3-21b review §6）

子スイッチをONにした staging（実MySQL）で、自動browser試験の代わりに次を確かめ、結果を記録する（完全な流れはMySQLが要るため、fastのbrowser試験には入っていない）。

1. **社内詳細（管理者）**: DUALタスクの行に「二者確認」が出て「完了」ボタンが無い → モーダルを開く → 候補はCLEANの版だけ → 提出 → 状態がPENDINGになる。DevToolsで要求に `X-XSRF-TOKEN` と `Idempotency-Key` があり、bodyに `documentVersionId` があること。
2. **確認・差戻し**: 提出者以外のHRまたは管理者で確認 → タスクがCOMPLETEDになり、後続タスクが繰り上がり、一覧が更新される。提出者本人がHRのときはボタンが出ない。営業・マネージャーには確認欄が無く「確認・差戻しは提出者以外の管理者またはHRが行います」だけが出る。理由を書かずに差戻すと警告dialogが出る。
3. **commentの表示範囲**: 他の営業・マネージャーで見ると「コメントあり」だけで、DevToolsの応答JSONに本文が無い。営業の代理提出の後、要員本人が `/my` で見ても「コメントあり」だけ。提出者本人とHRでは本文が出る。本文に `<img src=x onerror=alert(1)>` を書くと文字のまま表示される。
4. **本人画面 `/my/lifecycle`**: 未完了一覧のDUALタスクが詳細へリンクし、要員の提出が成功し、確認・差戻しの欄が無い。
5. **エラー表示**: 提出後に担当を変えてから確認すると409のmessageが出て状態が更新される。提出ボタンの連打で要求は1回だけ。通信を切って再送すると「処理済み」のtoastが出る（同じkey）。
6. **390px幅**: モーダルが全画面になり、ボタンが折り返し、候補の長いlabelが画面をはみ出さず、タスク表は横スクロールできる。dark・lightの両テーマで確かめる。
7. **テンプレート画面**: ONでは証跡種別に「二者確認」があり、OFFでは無い。OFFで既存のDUALテンプレートを編集すると「（現在は無効）」が出て、保存すると400のmessageが出る。
8. **子スイッチOFF**: 詳細画面のHTMLに `dualConfirmModal` と `lifecycle-dual.js` が無く、DUALの行は「二者確認は現在無効です」だけ。
9. **通知**: 提出後、「確認待ち」を受け取るのは管理者・HR・担当者だけで、範囲外の営業には届かない。本文に案件titleとcommentが無い。

### 1.8 本番DBの手作業の変更と、権限の設定を変える配備（RR-10・RR-2）

NF01細粒度epoch計画 §9 の残留リスクのうち、配備runbookに規則を書くとしたもの。表単位のrestoreの禁止（RR-13）は §1.7 の SRE-6 にある。

- **本番DBへ手作業の変更（DML・DDL）をしない（RR-10）**。NF01 の writer の閉包は guard test（Java・注釈SQL）で守っており、手動のSQLやDBの直接操作による変更は検出できない（認可epochが進まず、確認時の判定が古い前提のまま通り得る）。
  - 例外は、本書が手順として書いたものだけ: §3.5・§6 の初回導入の失敗時に schema を作り直すこと、§3.6 の Journal の作り直し、§4.5 の hold の開始者の書換え（D-05 owner の承認）、§5.1 の初期パスワード紛失時の `UPDATE`、§5.8 の侵害の疑い・`admin` の lock 解除。どれも DBA が行い、general log を無効にした接続で実行し、実行した文と結果を配備記録に残す（hash 等の値は残さない）。
  - 参照（SELECT）は本書の確認SQLの範囲で行ってよい。
  - 画面・API（監査が残る）で直せないデータの誤りは、手で直さずに Owner と DBA へ上げる。
- **権限に関わる設定を変える配備では、未確認の二者確認の提出を差し戻す（RR-2）**。`ORGANIZATION_SCOPE_ENABLED`（`organization.scope.enabled`）や機能スイッチ等の property の変更は認可epochを進めない。確認時の再評価で拒否側には効くが、2回の配備をまたいで元へ戻る変更（例: ある配備で外し、次の配備で戻す）は検出しない。
  1. 配備の前（§2 の1の前）に、未確認の提出を一覧して配備記録に残す。
     ```sql
     SELECT tenant_id, case_id, task_id, submission_id, updated_at FROM t_lifecycle_dual_projection
      WHERE state = 'PENDING' ORDER BY updated_at;
     ```
  2. 配備の後、一覧の提出を、確認者（提出者以外の管理者またはHR）が画面で差し戻す（理由に「配備で権限の設定が変わったため、再提出してください」と書く）。提出者が改めて提出する。DBは手で変えない（RR-10）。
  3. 対象は NF01 二者確認（`FEATURE_NF01_DUAL_CONFIRMATION_ENABLED`）をONにした後の配備である。初回リリースはOFFで、表は空のまま配備する。
  - この運用は NF01 計画 §10 の Identity/Security の受理事項（RR-2。**未取得**）である。起動時に property の指紋を比べて global epoch を進める改修は、同 §9 RR-2 の別の検討事項のまま。

## 2. 全停止とdrain（SRE）

初回導入では稼働中のinstanceは無い。§2.1〜§2.3は2回目以降のreleaseの手順で、初回は「アプリinstanceが1台も起動していないこと」の確認だけを行う。

1. LB/ingressを保守表示にし、新しいrequestを止める。
2. **scheduler drain**: 全instanceを `APP_SCHEDULING_ENABLED=false`（`app.scheduling.enabled=false`）で再起動し、`@Scheduled` を止める。定期管理レポートの実行中leaseが無いことを `scheduler-claim-fence-plan.md` §10.3 のQ1で確認する（0件、または全件がlease期限30分を超過）。手動でclearしない。
3. 全instanceを停止する（SIGTERM。`server.shutdown: graceful` で処理中requestを排水してから終了する）。
4. DBへのアプリ接続が0件であることを確認する。
   ```sql
   SELECT COUNT(*) FROM information_schema.processlist WHERE user = '<アプリaccount>';  -- 0
   ```
5. 2回目以降のreleaseではbackupを取得する（full dumpまたはPITR基準点。`ops/backup/runbooks/`）。初回は§3完了直後に取得する（前提は §1.7 の DBA-6: `.env.backup` の `FLYWAY_VERSION` を §3.4 の最新versionにし、production 固有値の BLOCKED を解除してから取る）。

- 権限に関わる設定（`ORGANIZATION_SCOPE_ENABLED`・機能スイッチ等）を変える release では、1の前に §1.8 の RR-2 の手順を行う。

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
- 実行順: V1〜V201（versioned 200件。SQL 199件＋Java migration `V74_3` 1件。V199 は欠番）→ Repeatable（説明の昇順: `crm contact reconciliation` → `remove v2 sample data` → `update admin password bcrypt`）。
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
 ORDER BY installed_rank DESC LIMIT 1;                                                         -- '201'
-- versioned migrationの適用件数（本releaseの総件数）
SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success = 1;          -- 200
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
-- NF07 F1 の状態の CHECK（V201）: request の status・channel・delivery_method と hold の hold_kind
SELECT COUNT(*) FROM information_schema.table_constraints WHERE constraint_schema = DATABASE()
   AND constraint_type = 'CHECK' AND constraint_name IN ('chk_privacy_request_status',
   'chk_privacy_request_channel', 'chk_privacy_request_delivery_method', 'chk_legal_hold_kind'); -- 4
-- NF07 F2（V196・V197）と NF01 二者確認の登録（V198）: catalog は構造行だけ。tenant default に文書以外の固定36件
-- （V196 の27件と V198 の9件）と、文書種別ごとに1件（文書種別の数に依存しない数え方にする）。owner・分類・inventory は全て UNKNOWN
SELECT COUNT(*) FROM m_pii_data_element
 WHERE tenant_id = 'default' AND data_element_id NOT LIKE 'DOCUMENT:%';                    -- 36
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
-- NF01 二者確認（V198）: B0 の7表と自由記述2列（business_comment・decision_comment）は provider 未登録・分類 UNKNOWN の
-- 構造行で、保持期間等の値を持たない（値は D-02 の署名で確定する。pii-inventory.md DB-141〜DB-147）
SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id = 'default' AND data_element_id LIKE 'LIFECYCLE_DUAL_%'
   AND provider_id IS NULL AND subject_attribution = 'UNKNOWN';                               -- 9
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
-- NF-06 照合の索引（V200）: storage_key の1列・非一意の索引
SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
   AND table_name = 't_document_version' AND index_name = 'idx_dv_storage_key'
   AND column_name = 'storage_key' AND non_unique = 1;                                         -- 1

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
- job 専用の principal は `nf07_journal_migrator`（`privacy_journal.*` への DDL と履歴表。application へは渡さない）。job 実行環境へ渡す値は環境変数 `FLYWAY_URL`（`jdbc:mysql://<journal-host>:3306/privacy_journal?sslMode=VERIFY_IDENTITY`。**`#` を含めない**: driver は `#` 以降を fragment として捨てるため、`#` の後ろに書いた `sslMode` 等は効かず、migrator principal の TLS の検証が黙って外れる。job 側に URL の検査は無いので、DBA が値を確かめる。RB-NF07-HOLD review P3-6）・`FLYWAY_USER`・`FLYWAY_PASSWORD` の3つだけ。application DB の job（§3.3）とは**別のshell・別の実行**にし、§1.3 の `FLYWAY_PLACEHOLDERS_ADMIN_INITIAL_PASSWORD_HASH` を設定しない。§1.3 の `mvn -X` 禁止・`flyway.conf` / `settings.xml` の確認も同じく守る。
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
- application の接続（hold 開始の開放時に設定。flag とは独立に、設定があれば起動時検査を行う）: `PRIVACY_JOURNAL_DATASOURCE_URL`（`jdbc:mysql://` だけ。`sslMode=VERIFY_IDENTITY` 必須、`autoReconnect`・タイムゾーン parameter・`socketTimeout`/`connectTimeout`・複数 host・URL 内の認証情報（`user@host`、`user=`・`password=`）・`%` による符号化・同じ parameter の重複・`#`（fragment。driver は `#` 以降を捨てるため、`#` の後ろの `sslMode` 等が効かなくなる。起動時の契約検査が `datasource.url.fragment` で拒否する）は不可）、`PRIVACY_JOURNAL_DATASOURCE_USERNAME` / `PRIVACY_JOURNAL_DATASOURCE_PASSWORD`（principal は1組だけ。hold 開始を使うなら `nf07_journal_appender`、効力確認だけなら `nf07_journal_reader` または appender。secret store から注入）、`PRIVACY_JOURNAL_JOURNALID`（`privacy.journal.journal-id`。`[A-Za-z0-9._-]{4,64}`）。任意: `PRIVACY_JOURNAL_DATASOURCE_MAXPOOLSIZE`（1〜8、既定4）、`PRIVACY_JOURNAL_DATASOURCE_CONNECTIONTIMEOUT`（上限30秒、既定5秒）、`PRIVACY_JOURNAL_EXPECTEDSCHEMA`（既定 `privacy_journal`）、`PRIVACY_JOURNAL_PENDINGTIMEOUT`（設定するなら append の在途時間の上限 U＝接続待ち＋append の transaction 上限10秒＋socket timeout 20秒に、30秒以上の余裕を足した値。既定の接続待ち5秒なら65秒以上。起動時の契約検査が強制するのは U より長いことだけで、余裕はこの設定値で持たせる。plan §3.4 A1H-1 実装記録「不変条件と余裕」）。socket timeout（20秒）と append の transaction 上限（10秒）は固定値で、設定 key は無い。
- 起動時検査（`PrivacyJournalStartupVerifier`）: `server_uuid` の相違、上の耐久設定、`DATABASE()`、principal の権限（`information_schema` と `CURRENT_ROLE()`。role 経由の GRANT は不可）、本節の履歴表が artifact と一致（版・script 名・checksum）、`t_privacy_projection_state.journal_id` の一致を確かめる。prod で `privacy.retention.enabled=true` なら1つでも不適合（設定欠落を含む）で起動しない。この flag は決定ID束縛の有効期間を見ない生の値で判定する（署名日前・失効で NF07 が OFF 扱いでも、ON を設定していれば Journal を要求する。意図的な保守側の判定）。OFF なら起動を続け、WARN `NF07_PRIVACY_JOURNAL_UNAVAILABLE` と固定の理由code（接続文字列・user名・passwordは出さない）を出す。driver の接続失敗（認証失敗を含む）も固定文言に置き換えるため、Hikari の log に principal・接続先は出ない。`com.zaxxer.hikari` の log を DEBUG にしない。
- 失敗した場合: Journal は業務dataを持たず、初回は空なので、DBA が `privacy_journal` schema を削除して空で作り直し、原因を直してから再実行する。`flyway repair` / `clean` は使わない。hold を一度でも記録した後の Journal は正本であり、作り直さない（B2 の recovery 手順による）。

## 4. アプリ起動（SRE）

- 環境変数: `SPRING_PROFILES_ACTIVE=prod`（prod単独）、`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`（アプリaccount）、その他prodの必須設定（一覧は §1.0。常に必須の26項目と、freeeを使う場合の4項目。security設定の注意は §4.1）。
- **`SPRING_FLYWAY_ENABLED` は設定しない**。`true` にしても `ProductionSchemaGuardConfiguration` がSpring BootのFlyway初期化でmigrateを拒否し、1件も適用せずに起動を止める。
- **JVMのタイムゾーンを `Asia/Tokyo` に固定する（必須）**。
  - FINAL-FIX から image（`Dockerfile`）は `TZ=Asia/Tokyo` と `JAVA_TOOL_OPTIONS` の `-Duser.timezone=Asia/Tokyo` を持ち、task definition の雛形も `TZ=Asia/Tokyo` を設定する（§1.0）。
  - `JAVA_TOOL_OPTIONS` を task definition で上書きする場合は、image の既定（`-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Tokyo`）を全て書く。
  - 起動logの `Picked up JAVA_TOOL_OPTIONS: ...` で確認する。
  - 理由: account lockは `AccountLockServiceImpl` が `Asia/Tokyo` の時計で `sys_user.locked_until` を書き、login時の判定（`LoginUser.isAccountNonLocked`）はJVMの既定タイムゾーンの `LocalDateTime.now()` と比べる。JVMがUTCのままだと30分のlockが約9時間30分続く（bootstrap中に `admin` がlockされると作業時間枠が実質閉じる。§5.6）。監査の `created_at` 等、JVMの既定タイムゾーンで日時を作る箇所は他にもある。testは `argLine` でタイムゾーンを固定しているが、本番は配備側で固定しない限り固定されない。DB側のタイムゾーン（§1.1、`+09:00`）とは別に必要。
- 初回は `APP_SCHEDULING_ENABLED=false` で1台だけ起動する（task definition の雛形の値。`SchedulingConfig` は未設定を有効とみなすため、外すと scheduler が動く）。
- **起動の前に、登録する task definition を照合する（SRE-10）**。`infra/ecs/offline/validate-task-definition.ps1` を実値の mode で実行し、0 で終わることを確かめる。証跡は既定で OS の一時 directory の下に作り、repository には書かない（設定の名前だけを書き、値は書かない）。`-EvidenceRoot` で配備記録の保管場所を指定する。PowerShell 7.5 以降か Windows PowerShell 5.1 で実行する。
  ```powershell
  # 初回起動（§4.2 の bootstrap 設定）
  pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Initial -TaskDefinitionPath <登録するJSON> -EvidenceRoot <repository外のdirectory>
  # §5.5 の bootstrap を終える起動と、以後の起動
  pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Steady -TaskDefinitionPath <登録するJSON> -EvidenceRoot <repository外のdirectory>
  # 雛形（repository の infra/ecs/task-definition.prod.json）だけを検査する場合は引数なし（-Mode Template）
  ```
  - 実値の mode が見るもの: §1.0 の1〜26（freee を使うなら27〜30）と運用設定（`FILE_SCANNER_*`・`METRICS_SCRAPER_*`）の名前、secret を environment に置いていないこと、§1.0 で禁止した値が無いこと、placeholder が残っていないこと、image が registry digest で固定されていること、EFS の ID、`TZ`、scraper 口座が `admin`・break-glass 名でないこと。`-Phase Initial` は加えて `APP_SCHEDULING_ENABLED=false`・`SECURITY_BOOTSTRAP_ENABLED=true`・期限（offset 付き ISO-8601 で未来）・空の `BREAK_GLASS_USERNAMES`・機能スイッチの環境変数が無いこと、`-Phase Steady` は bootstrap の設定が外れ `BREAK_GLASS_USERNAMES` がちょうど2名であること。
  - 見ないもの: secret の値（起動時の validator が見る）、ALB・security group、service の desired count と deployment の設定（SRE-5）。§5.7 の bootstrap 再開は対象外。
- 起動時schema guard: DBの `flyway_schema_history` とartifactのmigrationを照合し、一致しなければTomcatのport・schedulerを開始する前に起動を中止する。
  - 成功: INFO log `[schema guard] DBのschemaはartifactの最新migration V201 と一致しています`
  - 失敗: ERROR log `[schema guard] DBのschemaがartifactと一致しないため起動を中止します。...`（未適用・失敗行・artifactに無いmigration・最新versionの不一致・一度も適用されていないRepeatableを列挙する）。jobの結果（§3.4）とartifactのSHA（§1.4）を確認する。
  - guardはplaceholderを置換しないため、アプリへ初期管理者hashを渡す必要は無い。placeholderを含むRepeatable（初期管理者）は「同じscriptの成功適用が履歴にある」ことだけを確認する。jobが `db/migration-prod` を落として実行された（`-Dflyway.locations` 等の上書き）場合は、prodのRepeatableが一度も適用されていないとして起動を止める。
- `/actuator/health/readiness` がUPであることを確認する。UPであれば §1.0 の18 の鍵検査も通っている（検査に失敗した起動はUPにならずに停止する）。
- schedulerは、Report Owner等の署名後に `APP_SCHEDULING_ENABLED=true` で再起動して有効化する。
  - scheduler は file 清掃・契約更新・freee・消込等の中核jobと共用なので、有効化は通常運用の再開を意味する。
  - 履歴取込の清掃（02:00）は、文書台帳の副本を廃棄候補にする。ただし処分には管理者の承認と執行が要るため、V197 の署名事項は scheduler の有効化の前提にしない。
  - 署名までは、`RESUME_INGESTION` の副本の廃棄申請を承認しない（§4.3）。
- 機能スイッチは `feature-switches.md` に従い1機能ずつONにする。

### 4.1 常に必要なsecurity設定（初回・以後とも。外さない）

OIDCの固定metadata・client credential、MFA鍵、session hash key等のprodのsecurity設定は、bootstrap中も以後も全て設定する（毎回の起動時に `ProductionSecurityConfigurationValidator` が検査する。項目と形式の一覧は §1.0 の5〜15）。特に次は**初回だけでなく永久に必要**で、bootstrapを終える再起動（§5.5）でも外さない（D1 review P2-B）。

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

- `infra/ecs/task-definition.prod.json` の雛形はこの表の値を持つ。期限は `SECURITY_BOOTSTRAP_EXPIRES_AT_PLACEHOLDER` で、置き換えないと起動しない（offset付きISO-8601として読めない）。§5.5 で `SECURITY_BOOTSTRAP_ENABLED`・`SECURITY_BOOTSTRAP_EXPIRES_AT` を外し、`BREAK_GLASS_USERNAMES` に2名を入れる。

- **LB/ingressでアクセス元を作業端末に限定する（必須）**。bootstrap中は `admin` のlocal password loginが開いているため、次の危険がある（D1 review P3-k）。起動前にSREが制限を入れ、作業端末以外から `/login` に到達できないことを確認してから§5.1へ進む。bootstrapの再開（§5.7）でも同じ。
  - 初期パスワードの総当たり。
  - **lockによる妨害（DoS）**: login失敗が5回で `admin` が30分lockされる（`AccountLockService`）。`sys_user` の照合順序は大小文字を区別しないため、`ADMIN` 等の綴りでの失敗も `admin` の失敗として数えられる。前後に空白やタブを付けた入力（`" admin"` 等）も、認証と同じ trim 後の名前で数える（`LoginFailureHandler`。以前は入力値のまま数えて空振りし、lock を回避した推測ができた。DOC-FINAL delta review N-1）。`/login` に到達できる者は誰でも `admin` をlockでき、作業を止められる。JVMのタイムゾーンが固定されていないとlockが約9時間30分に延びる（§4）。
  - 先回りのパスワード変更: 初期パスワードが漏れていると、正規の担当者より先にloginしてパスワードを変え、bootstrapを乗っ取れる（兆候と対応は§5.8）。
- 起動の拒否（fail-fast、Tomcatのportを開く前）: `SECURITY_BOOTSTRAP_ENABLED=true` なのに期限が未設定・offset無し・既に過ぎている・現在から24時間超、または初回bootstrapの完了marker（§5.5）が有るのに承認ticketが無い・形式が不正・再開の監査を残せない。ERROR/例外messageは `[bootstrap] ...` で始まる。
- 起動時のWARN log: `[bootstrap] 期限付きbootstrap modeが有効です（期限: ...）` と `[bootstrap] 期限付きbootstrap mode中のため、break-glass管理者の登録検査とOIDC管理者の人数検査を省略します（期限: ...、再開ticket: なし（初回））`。この2行が出ていることを確認する。
- 期限を過ぎると、**再起動しなくても** `admin` のlocal loginは拒否され、既存のbootstrap sessionも次の要求で失効する（API 401 / 画面はlogin画面へ）。作業が終わらなかった場合は、新しい期限（24時間以内）を設定して再起動する（完了markerが書かれる前なので承認ticketは不要）。
- prod以外のprofileではbootstrap modeの設定は無視される（dev/testの挙動は変わらない）。

### 4.3 履歴取込の副本の処分（scheduler 有効化後の監視・定期確認）

処理の流れ（plan §3.4.1「D4 と文書台帳の副本」）:
1. 日次清掃（02:00）が、原本を purge した取込ジョブの副本（`RESUME_INGESTION`）の保存期限を当日に合わせる。
2. 02:30 の `DocumentRetentionJob` が、CONFIRMED の副本を廃棄候補（PENDING、`requested_by = -1`）にする。
3. 削除は、管理者の承認（APPROVED）と執行（DISPOSED）を経てはじめて起きる。副本の調整だけで、取り消せない削除が自動で起きることは無い。

**署名前の運用（FINAL-FIX (a)-3）**

V197 の署名事項（Privacy owner・文書 owner の書面署名）を得るまで:
- `RESUME_INGESTION` の副本の廃棄申請を**承認も執行もしない**。他の文書種別の廃棄申請は従来どおり扱う。
- scheduler（`APP_SCHEDULING_ENABLED`）は止めずに有効化してよい。
- 廃棄申請を承認・執行する前に、毎回、次の (0) の SQL で対象を確認する。
  - 承認しようとする申請が結果に出たら、承認しない。
  - 署名前に APPROVED になっていた申請は執行しない。理由を書いて却下する（`POST /api/documents/disposal/{requestId}/reject`、理由は必須）。
- 却下した副本は、次回の `DocumentRetentionJob` で再び廃棄候補になり得る。署名までは、同じ確認を続ける。

**副本の調整だけを止める停止口**（`RESUME_LEDGER_COPY_ALIGNMENT_ENABLED`、property `app.resume.ledger-copy-alignment.enabled`、既定 `true`）
- `false` にすると、日次清掃（02:00）のうち副本の保存期限を合わせる処理だけを止める。原本・抽出本文の purge と BP 取込の清掃は続く。scheduler 全体（`APP_SCHEDULING_ENABLED`）を止めずに済む。
- **初回配備は `false` で始め、Privacy owner と文書 owner の署名を得てから `true` にする**（`infra/ecs/task-definition.prod.json` の雛形は `false`。FINAL-FIX P3-3）。
  - 理由: `true` のままだと、署名の前に副本の `retention_until` が当日まで縮む。`false` に戻しても縮んだ値は戻らない。
  - さらに、却下した申請が翌日また廃棄候補になり、毎日の作業になる。
  - 署名前に「承認しない」運用（上の (0)）は、この停止口の後ろの二重の防御とする。
- コードの既定は `true` で、dev/test の挙動は変わらない。prod では雛形の値で止める。
- 既に保存期限を合わせた副本と、既にある廃棄申請は戻らない。(0) の確認は止めた後も続ける。
- 停止中は、上の定期確認の (2) が0行にならない。これは正常である。再開（`true`）すると、次の実行で拾い直す。
- 切替は再起動で反映する。停止中は tenant ごとに日次で INFO log `文書台帳の副本の保存期限の調整は停止中です` が出る。真偽値として解釈できない値（例 `ture`）では起動しない。

```sql
-- (0) 署名前に承認・執行してはいけない副本の廃棄申請（PENDING・APPROVED）。署名後はこの確認を外す
SELECT r.id AS request_id, r.status, r.requested_by, r.created_at, d.tenant_id, d.id AS document_id,
       d.status AS document_status, d.retention_until
  FROM t_document_disposal_request r JOIN t_document d ON d.id = r.document_id
 WHERE d.document_type = 'RESUME_INGESTION' AND r.deleted_flag = 0 AND d.deleted_flag = 0
   AND r.status IN ('PENDING', 'APPROVED')
 ORDER BY r.status, r.id;
```

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
- **app DB の照会が戻らないとき（RB-NF07-HOLD delta review D-P3-1）**: gate は application DB の `@@server_uuid` を照会する（query timeout 10秒、接続の network timeout 15秒）。これが戻らず上限を超えると、同じ transaction の中で gate を呼ぶ手動の経路（D1・D2 等）はその操作が接続切断のエラーで失敗として終わり、batch はその回が失敗する（UNAVAILABLE の通常の skip ではない。削除はしない）。続く場合は application DB への接続（half-open の TCP 等）を調べる。
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
| `PrivacyExportPackageService.purgeDue` の孤児の削除 | PURGED でない package の行を持たない `PRIVACY_EXPORT` の file と metadata（上の2行で消し損ねたもの。`created_at` を書いた application の時計で1時間の猶予の後。境界は application が同じ時計から計算して SQL へ渡し、DB の時計とは比べないため、JVM と DB の時刻帯が違っても猶予はずれない。review P2-1 (c)・HYG-FINAL P3-A） | 自動（毎時 :15 の同じ回） |

汎用の file 経路（`GET /api/files/{name}`・`POST /api/files/{name}/rescan`）は、package の行（PURGED を含む。tenant に関係なく）と、照会者の tenant の `PRIVACY_EXPORT` の metadata（論理削除済みを含む）のどちらかに当たる保存名を role に関係なく拒否する（review P2-1 (b)）。metadata を照会者の tenant に絞るのは一意索引 `uk_file_security_stored_name (tenant_id, stored_name)` を使い全表走査を避けるため（HYG-FINAL P3-D。DDL なし）で、他 tenant の metadata だけを持つ名前は、次の保存層の `load`・`rescan` が照会者の tenant の metadata を要して拒否する。保存層でも汎用の `load`・`rescan` は `PRIVACY_EXPORT` を開かず、package 専用の `loadServerGenerated` だけが読む（review P3-8）。

- **交付の記録の前提（HYG-FINAL P3-B）**: 交付の記録（`POST /api/privacy/dsar/{id}/delivery`）は、承認した package を専用ダウンロードで取得した印（`DSAR-DOWNLOAD:`）を要し、無ければ 409 `error.privacy.dsarDeliveryNotDownloaded`。画面は取得の前に「交付を記録」を出さない。取得しないまま期限切れ・PURGED になった承認 package は交付を記録できないため、作り直す（外れた承認は `DSAR-SUPERSEDED:` に残る）。交付記録の hash を、system から実際に取り出した内容に結び付けるため。
- **承認者による草案の確認の hash（HYG-FINAL P3-C）**: `GET /api/privacy/dsar/{id}/approve/preview` は内容 hash を `X-Content-Sha256` header で受け取り、URL の query には置かない（ALB・proxy の access log は query を残し、header は残さない）。URL に残るのは request ID と package ID だけ。
- **`PRIVACY_EXPORT` が INFECTED と判定されたとき（HYG-FINAL P3-F）**: 開示 package は server が業務 DB・file から読んだ値で生成する JSON で、利用者の upload ではない。INFECTED は、F2 provider が読んだ元の記録（業務表の値・添付の実体）に既知の signature と一致する文字列が入っていることを意味する。保存の失敗時に実体は quarantine にも残さず、拒否の結果（`scan_status`・`rejection_reason`・`scanner_version`・`scanned_at`）を書いた metadata の行が論理削除で残るだけなので、**調査は quarantine ではなく元の記録に対して行う**: (1) 論理削除済みの metadata の行で判定の時刻・理由・scanner の版を確かめる、(2) その case の本人と範囲から、元の業務表の行と添付を特定し、元の file の scan の状態（`t_file_security_metadata`）を確かめる、(3) 混入した値の除去・訂正は業務の手順で行い、system 側は草案を作り直すだけ（server 生成の種別は再scanの対象にしない）、(4) 誤検知なら scanner の定義の更新の後に作り直す。調査のために package の内容を system の外へ取り出さない。
- **手動対応の完了と hold（HYG-FINAL P3-F の残り、RB-NF07-HOLD）**: 開示以外の請求の手動対応の完了（`POST /api/privacy/dsar/{id}/resolution`）は、本人が HELD で結果が削除（DELETED）・利用停止（RESTRICTED）でも記録を止めない（system は処分しない）。記録の前は区画と確認の文で、記録の後は応答の `holdConflict`（と `holdState`）と画面の警告 toast で知らせる。警告が出たら、system の外で行った削除・利用停止が hold で保全すべき記録（D1〜D11 が守る原本）に及んでいないかを privacy-admin が確かめ、及んでいれば D-05 owner へ報告する。応答の `holdState` が `UNKNOWN` のとき（gate を読めなかった）は、画面が成功ではなく警告の toast を出す（RB-NF07-HOLD review P3-5）ので、区画を開き直して hold の状態を確かめる。

判断の理由:

1. **package は交付用の副本で、原本は別に保存されている。** package は F2 provider が業務表・file から読んだ値を草案として書き出したものである。読んだ元の行・実体は残り、hold は原本の側で D1〜D11 が守る。package を消しても、hold が保全する原本は1件も減らない。
2. **package 自体は hold の対象（subject / data element）ではない。** hold は catalog（`m_pii_data_element`）の data element と、F2 provider が本人に帰属させる行・実体に効く。package の表（`t_privacy_export_package`）は catalog に無く、F2 provider も解決しない（NF07 自身の作業データ。pii-inventory §6 に除外として記す）。ただし「catalog に無い」は現在の模型の結果で、D4 の「原本と副本は別 element」の考え方では副本も別 element になり得るため、実質の根拠は 1・3・4 である。package の実体の metadata（`t_file_security_metadata`）は catalog の `FILE_SECURITY_METADATA` に当たり、削除で論理削除される（その owner は privacy-admin で本人ではない）。gate に接続すると、hold を使った tenant では分類が UNCLASSIFIED（fail-closed）になり、その tenant の package を1件も消せなくなる。
3. **接続すると期限超過の保持になる。** D-06 が決める最長保存期間（`package-max-age`）と「交付記録と同時に PURGED」（plan §3.5）を超えて PII の副本が残り、plan の I-9（期限超過の保持を避ける）に反する。plan の I-4 は「NF07 自身が作った一時 package を交付記録・期限・case の終了で消すこと」を業務データの削除 writer の禁止の対象外とするだけで、hold との関係は決めていない（hold との関係は本節の 1〜4 で決める）。
4. **交付の証跡は残る。** package の行（内容 hash・作成者・承認者・承認日時・削除日時）と request の交付記録（方法・証跡の参照・承認した package の id と hash）は消さない。取得の印（`DSAR-DOWNLOAD:`）と、期限切れの作り直しで外れた承認（`DSAR-SUPERSEDED:`）も `t_privacy_action` に残る。何を・いつ・誰の承認で交付したかは hash で照合できる。

`PrivacyExportPackageServiceTest#本人がhold中でも開示packageの削除はgateを見ずに期限で消す` が、本人の SUBJECT hold（gate の `inspect` が HELD を返す）があっても、交付記録・作り直し・期限超過の3経路の削除が gate を照会しないことを固定する。

- **drain 中の遅れ**: 削除の scheduler は毎時だが、`APP_SCHEDULING_ENABLED=false`（drain・全停止）の間は動かない。終了した case・期限を過ぎた package の実体はその間残る（取得・交付記録は状態の確認で拒否される）。再開後の最初の回で消える。

**D-05 owner の受理事項（A1H-2。hold 開始の開放の署名と合わせて受理する）**: (1) 原本と副本の DATA_ELEMENT hold は別 element（上の「DATA_ELEMENT hold の対」）、(2) 外部にある実体は D10 の範囲外（上の「外部にある実体」）、(3) D7・D9 は1つの tenant の停止で全 tenant の kind を止める（過剰停止）、(4) D1 の READ_COMMITTED による最初の hold との順序の窓と、D1・D2 の本人の導出が文書行の lock より前であること（plan §3.4 A1H-2 実装記録の残余）、(5) 帰属できない element の行は SUBJECT hold で守られない（plan §3.4.3 規則6）、(6) D13 の PWA の台帳と freee の従業員対応の物理削除（上の表）、(7) 参照元の行が物理削除された実体は D10 で守れない（D11、plan §9 R-15）、(8) D14 の開示 package は hold で止めない（NF07-A2b。D-06 owner と合わせて受理する）。本人請求そのものが争いになり、交付した内容が証拠になる場合でも、system は `package-max-age` を超えて内容を残さず、内容 hash と交付記録だけを残す。内容を残すなら、交付の時点で system の外の保全手続で保管する。

### 4.5 NF07 保全holdの解除（NF07-REL。開放は D-03・D-05 の署名後）

`POST /api/privacy/holds/{holdId}/release` は、D-03・D-05 の決定ID が有効で、prod の verifier があり、解除 authority の trust anchor が登録され、**失効 provider が2つ以上の anchor（異なる authority）に ACTIVE を返す**まで 404 を返す（第1期の registry は空、失効 provider は常に UNKNOWN。実 registry と失効 provider は NF07-TRUST の D-03 方式の adapter）。数えるのは、ISSUER の役割の anchor と APPROVER の役割の anchor で authority・主体（`sys_user:<id>`）・鍵を共有しない1組で、同じ authority の2つの鍵（rotation の重複期間）や ISSUER だけの2件では開かない。anchor と失効状態は判定のたびに読むため、anchor を消せば再起動せずに閉じる。開放の後の手順は次のとおり。署名は system の外（D-03 で決める方式。Ed25519 の鍵を扱えること）で行い、system は鍵を持たない。

**手順**

1. privacy-admin（要求者）が hold 画面で解除する hold を選び、理由コード・ticket・理由を入力する。画面は束縛の全項目（tenant・partition・hold ID・hold revision・理由の hash・command ID（画面で発行）・解除の時点の journal head の revision と event hash・要求者・開始者）と署名依頼の JSON を表示する。
2. 署名依頼の JSON を発行者と承認者（要求者とは別の、trust anchor に登録された2名）に渡す。各人は下の正規形に Ed25519 で署名し、JSON の `issuer` / `approver` に `authorityId`・`keyId`・`issuedAt`・`expiresAt`・`signature`（base64）を記入する。
3. 要求者が署名済みの JSON を画面に貼り付けて送る。理由コード・ticket・理由を変えると理由の hash が変わるため署名し直す。journal head・hold revision・開始者が署名の後に変わった場合は 409 になるので、新しい値で署名し直す。同じ JSON の再送は元の結果を返す（Journal に重ねない。proof の期限の後でも同じ）。**claim を再署名すると同じ command ID でも 409**（`error.privacy.holdReleaseConflict`。Journal の解除 event は両 claim の digest を持つため、別の claim は別の command になる）。ACK 不明の後の再送には、最初に送った JSON をそのまま使う。新しく署名し直す場合は画面で新しい command ID を発行してから署名を依頼する。
4. 解除の後、Journal の解除 event の `action_id` は両 claim の digest（正規形は plan §4 の NF07-TRUST 実装記録）、監査 `NF07_HOLD_RELEASE_CLAIM` に発行者・承認者の claim（anchor・発行日時・期限・署名 hex）が1行ずつ残る。事後に「誰が署名したか」を確かめるときは、監査の claim から digest を計算して `action_id` と照合し、束縛の digest（`request_hash`）への署名を anchor の公開鍵で検証する（そのため D-03 の方式は退役した鍵の公開鍵も保管する）。

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
- **break-glass口座（A・B）の username は ASCII の英数字と `.`・`_`・`-` だけにする**（`[A-Za-z0-9._-]+`。§1.0 の15）。§5.3でBを作るときもこの形にする。`BREAK_GLASS_USERNAMES` には DB の username を大文字小文字まで同じに書く。全角・アクセント等を含む名前は、§5.5 の起動時検査で拒否される（DOC-FINAL r3 P3-2）。
- **`admin`（A）は削除・改名・無効化・role変更しない**（break-glass管理者Aとして使い続ける。D1 review N-9）。bootstrapのlocal login（`ProductionBootstrapMode.isBootstrapLoginAllowed`）と起動時検査（`validateBootstrap`）は、username `admin`・role `管理者`・有効（`status = 1`）の口座だけを初期管理者として認めるため、これを崩すとbootstrapの再開（§5.7）でも誰もlocal loginできず、break-glassも使えない状態では復旧がDBAによる口座の復元だけになる。ユーザー管理の既存guardは本人による自分自身の削除・無効化・role変更だけを止め、他の管理者による操作は止めない。定期確認（§5.6の集計と同じ頻度）:
  ```sql
  SELECT username, role, status, deleted_flag FROM sys_user
   WHERE username COLLATE utf8mb4_bin = 'admin';   -- 管理者 / 1 / 0 の1行
  ```

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

1. **break-glass usernameを決める（ちょうど2件）**: A（`admin`）とB。いずれも有効な `管理者` で、local passwordとMFA登録を持つこと。`BREAK_GLASS_USERNAMES` には `sys_user.username` をそのままの表記で設定する。`sys_user` の照合順序は大文字小文字を区別しないが、起動時検査は設定値とDBの表記の完全一致を要求し、違えば起動しない（`break-glass usernameは有効な管理者ユーザーのusername（大文字小文字も一致）でなければなりません: <設定値>`。D1 review N-1）。名前は ASCII の英数字と `.`・`_`・`-` だけにする（それ以外の文字を含む設定は `break-glass usernameはASCIIの英数字と「.」「_」「-」だけにしてください` で起動しない。実行時の判定は ASCII の大文字小文字だけを同一視するため、ASCII 以外の名前は DB の照合順序との細かな差に依存する。DOC-FINAL delta review P3-b）。設定前に次で確かめる。
   ```sql
   -- 設定する2件がそのままの表記で存在すること（2行）。照合順序を区別ありに切り替えて比較する
   SELECT username, role, status FROM sys_user
    WHERE username COLLATE utf8mb4_bin IN ('admin', 'bg-second') AND deleted_flag = 0;
   ```
2. **bootstrapを無効にして再起動する**。`SECURITY_BOOTSTRAP_ENABLED`・`SECURITY_BOOTSTRAP_EXPIRES_AT`（設定していれば `SECURITY_BOOTSTRAP_REOPEN_TICKET` も）を外し、`BREAK_GLASS_USERNAMES=admin,bg-second` を設定して再起動する。**`OIDC_TENANT_ID` は外さない**（§4.1）。再起動の前に、新しい task definition を `-Mode Actual -Phase Steady` で照合する（§4）。LBのアクセス元制限は、この起動を確認してから通常の設定へ戻す。
3. **完全な起動検査が通ることを確認する**。`[bootstrap] 期限付きbootstrap modeが有効です` のWARN logが出ず、`ProductionSecurityConfigurationValidator` の例外（`本番security configurationが安全な既定値を満たしません`）で停止しないこと。readinessがUPになり、C〜EがOIDCでログインできること。完全な検査が見るbootstrap関連の条件は次のとおり。
   - break-glass usernameがちょうど2件で、どちらも有効な管理者かつMFA登録済み（未登録なら `break-glass usernameはMFA enrollment完了済みでなければなりません`）。
   - 設定tenant・issuerの有効なOIDC providerが登録済み（無ければ `設定tenant・issuerの有効なOIDC providerが登録されていません`）。
   - break-glass username以外でOIDCでログインできる有効な管理者が3名以上（不足なら `OIDCでログインできる有効な管理者（break-glass username以外）が3名以上必要です（...現在N名）`）。
   - `OIDC_TENANT_ID` が設定済み。
4. **完了markerが書かれたことを確認する**。完全な検査が初めて通った起動で、`m_system_config` に初回bootstrapの完了日時が書かれ、WARN log `[bootstrap] 初回bootstrapの完了markerを書き込みました（security.bootstrap.completed-at=...）` と監査 `BOOTSTRAP_COMPLETED` が残る。以後、bootstrapを再び有効にするには承認ticketが要る（§5.7）。markerはシステム設定画面には表示されず、画面から変更できない。
   ```sql
   SELECT config_value FROM m_system_config WHERE config_key = 'security.bootstrap.completed-at';   -- 完了日時（offset付きISO-8601）
   SELECT created_at, method, uri FROM t_audit_log WHERE application_code = 'BOOTSTRAP_COMPLETED';  -- 全停止・単一instance起動なら1行（同時に起動した場合は2行になり得る）
   ```
   - markerは「必須監査 `BOOTSTRAP_COMPLETED` を残す → `m_system_config` へINSERTする」の順で書かれる（`ProductionBootstrapCompletionLatch.markCompletedIfAbsent`）。同時に2台をbootstrap無効で起動すると両方が監査を残し、INSERTは片方だけが成功する（もう片方は一意制約違反として何もしない）。markerが1行あれば問題ない（D1 review N-11）。監査とmarkerの対応を次で確かめる。
     ```sql
     SELECT (SELECT COUNT(*) FROM t_audit_log WHERE application_code = 'BOOTSTRAP_COMPLETED') AS completed_audits,
            (SELECT COUNT(*) FROM m_system_config WHERE config_key = 'security.bootstrap.completed-at') AS marker_rows;
     ```

     | completed_audits | marker_rows | 意味と対応 |
     |---|---|---|
     | 1以上 | 1 | 正常（2行以上は同時起動・再書込の痕跡） |
     | 1以上 | 0 | **書込の中断**（監査の後、INSERTの前に停止した）。bootstrapを無効にしたまま再起動してmarkerを書かせ、上のSQLで `marker_rows = 1` を確かめる。それまではbootstrapを有効にして起動しない（markerが無いため、承認ticket無しで初回bootstrapとして扱われる） |
     | 0 | 1 | 想定外（アプリ以外がmarkerを書いた疑い）。Securityが§5.7末尾のDB監査logを確認する |
     | 0 | 0 | 初回bootstrapは未完了 |
   - markerを書けない場合は起動しない（`[bootstrap] 初回bootstrapの完了markerを記録できないため起動しません`。監査 `BOOTSTRAP_COMPLETION_MARK_FAILED` はbest-effort）。markerが無いままだと、本番稼働後のbootstrap再開が承認も監査も無く行えてしまい、しかも誰も気づけないため、fail-closedにしている。DBAがアプリaccountの `m_system_config` へのINSERT権限とDBの状態を確認してから再起動する。
5. **完全な検査が通らなかった場合**: markerはまだ書かれていないため、承認ticket無しでbootstrapを再開できる。新しい期限で `SECURITY_BOOTSTRAP_ENABLED=true` にし、**`BREAK_GLASS_USERNAMES` を再び空にして**（bootstrap中に設定したままだと起動しない）再起動し、不足を直して（例: Bの§5.4、C〜Eの作成・link）から2へ戻る。

### 5.6 運用上の注意・監査・アラート（Owner/Securityが確認する）

- **break-glass usernameに指定した管理者はOIDCではログインできなくなる**（OIDCのsessionもincident未束縛のbreak-glass sessionとして失効させる既存仕様）。break-glass管理者はIdP障害時のlocal login専用の口座になる。
- **OIDCでログインできる管理者（break-glass以外）を3名未満にしない**。退職・異動で無効化・削除・role変更する前に、後任を作成してlinkを承認する。3名未満になると、次の再起動で起動時検査により起動しない（break-glass管理者の無効化・MFA喪失も同じ）。復旧はDBA、またはbootstrapの再開（§5.7）。無効化・role変更の前に、下のSQLで後任を含めて4名以上いることを確かめる。
- **OIDC管理者の人数の定期集計（必須。D1 review N-2）**: 3名未満になっても（退職・無効化・role変更・外部identity linkの隔離・providerの無効化）、検知されるのは次の再起動の起動時検査だけである。計画外の再起動（OOM・host障害）で初めて判明すると、break-glass incidentを起票・承認できず、復旧は§5.7かDBAになる。次の集計を**毎日**と、計画した再起動の前、管理者の無効化・削除・role変更・link隔離・providerの変更の後に実行し、3未満ならwarningのアラートを出す。条件は起動時検査（`ProductionSecurityConfigurationValidator.validateOidcAdministrators`、`UserExternalIdentityMapper.selectApprovedActiveUsernamesByRole`、`IdentityProviderMapper.selectEnabledByTenantAndIssuer`）と同じにしてある。
  ```sql
  -- <tenant> は OIDC_TENANT_ID、<issuer> は OIDC_ISSUER_URI、<bg1>/<bg2> は BREAK_GLASS_USERNAMES の2件
  SELECT COUNT(DISTINCT u.username) AS oidc_admins
    FROM t_user_external_identity x
    JOIN sys_user u ON u.id = x.user_id
    JOIN m_identity_provider p ON p.id = x.provider_id
   WHERE p.tenant_id = '<tenant>' AND p.issuer_uri = '<issuer>'
     AND p.enabled = 1 AND p.deleted_flag = 0 AND p.provider_type = 'OIDC'
     AND x.tenant_id = '<tenant>' AND x.review_status = 'APPROVED' AND x.deleted_flag = 0
     AND u.deleted_flag = 0 AND u.status = 1 AND u.role = '管理者'
     AND u.username NOT IN ('<bg1>', '<bg2>');   -- 3以上であること
  ```
  - 起動時検査は `sys_user.tenant_id` を見ない（D1 review N-6）ため、上のSQLも見ていない。単一tenant（`OIDC_TENANT_ID=default`、§1.7）の初回本番では差は出ない。
  - 将来のコード対策（未実装）: ユーザー管理画面で管理者の無効化・削除・role変更により3名未満になる場合のWARNまたはguard。
- **アラート（必須）**: 監視で `t_audit_log.application_code` の `BOOTSTRAP_LOGIN_SUCCESS` と `BOOTSTRAP_REOPENED`、およびapp logの `[bootstrap]` で始まるWARN/ERRORにアラートを設定する。§5.5の完了後にこれらが出たら、承認済みの再開ticket（§5.7）が無い限りsecurity incidentとして扱う。あわせて `BOOTSTRAP_LOGIN_FAILURE`・`BOOTSTRAP_LOGIN_LOCKED`・`BOOTSTRAP_SESSION_MISMATCH`・`BOOTSTRAP_COMPLETION_MARK_FAILED` もアラートを推奨する。監視queryの例:
  ```sql
  SELECT created_at, username, method, uri, application_code FROM t_audit_log
   WHERE application_code IN ('BOOTSTRAP_LOGIN_SUCCESS', 'BOOTSTRAP_REOPENED') AND created_at >= ?   -- 前回確認時刻
   ORDER BY created_at;
  ```
- **`BOOTSTRAP_REOPENED` とbootstrapのWARN logは再開の「試行」を示す**（D1 review N-4）。必須監査 `BOOTSTRAP_REOPENED` と WARN log（`[bootstrap] 完了済みのbootstrapを承認ticket ... で再開します`・`[bootstrap] 期限付きbootstrap modeが有効です（期限: ...）`）は `ProductionBootstrapMode` の初期化時（bean生成中）に出て、起動時schema guard（全singleton生成後）と `ProductionSecurityConfigurationValidator`（起動完了直前）はその後に動く。このため、ticketが正しくても後続の起動失敗（DBとartifactの不一致によるschema guardの停止、`BREAK_GLASS_USERNAMES` の空にし忘れによる validator の停止など）で、監査とlogだけが残りアプリは起動していないことがある。実際に再開したかは、同じ起動でreadinessがUPになり、起動失敗（`APPLICATION FAILED TO START`、schema guard・validatorの例外）が出ていないことで判断し、WARN logだけでは判断しない。アラートの扱い（承認済みticketが無ければsecurity incident）は変えない。拒否文言が `bootstrap再開の必須監査（BOOTSTRAP_REOPENED）を永続化できないため、bootstrap modeは起動しません` の場合は、真の原因（監査表とartifactのentityの列の不一致など）がcause chainにしか出ないため、先に§3.4の適用結果（`flyway_schema_history` とschema guardのlog）を確認する。
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
  4. 起動logの `[bootstrap] 完了済みのbootstrapを承認ticket SEC-1234 で再開します（...）` と、監査 `BOOTSTRAP_REOPENED` を確認する。Securityは§5.6のアラートが鳴ったことを確認する（鳴らなければアラートの設定不備として直す）。これらは再開の試行を示すだけなので、readinessがUPになり起動失敗が出ていないことも確かめる（§5.6）。
  5. ticketの作業内容だけを行う（§5.1〜§5.4の該当手順）。
  6. §5.5の2と同じく `SECURITY_BOOTSTRAP_*` を全て外し、`BREAK_GLASS_USERNAMES` を戻して再起動する。完了markerは既に有るため上書きされない（`BOOTSTRAP_COMPLETED` は出ない）。
  7. Securityが§5.6の監査SQLで、期間中の操作がticketの作業内容と一致することを確認してticketを閉じる。
- ticketを付けたまま再起動するたびに `BOOTSTRAP_REOPENED` が残る。期限が過ぎた場合は、同じticketの承認範囲内で新しい期限を設定して再起動する（範囲を超えるなら新しいticketを起票する）。
- DB権限を持つ者は完了markerを消してticket無しで再開できる。これはアプリの制御の対象外（DB権限はアプリの制御を迂回できる）ため、DB監査logで `m_system_config` の `security.bootstrap.completed-at` の変更・削除を監視する。

### 5.8 異常時の対応（侵害の疑い・TOTP喪失・lock）

**侵害の疑いの兆候**:
- §5.1のパスワード変更で「現在のパスワードが正しくありません」（初期パスワードを他者が先に変えた）。
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
- 30分待つ（JVMのタイムゾーンが固定されていること。§4）。
- 急ぐ場合、またはタイムゾーンが未固定でlockが延びている場合は、DBAが解除する。
  ```sql
  UPDATE sys_user SET failed_count = 0, locked_until = NULL WHERE username = 'admin';
  ```
- 繰り返しlockされる場合は、作業端末以外から `/login` に到達している疑いがある。LBのアクセス元制限を確認し、侵害の疑いとして上の対応を検討する。

**break-glass保持者の交代（例: Bの退職。D1 review N-9。草案・Owner/Securityの確認待ち）**:

承認済みの変更ticket（§5.7の承認flow）の下で行う。既存の手順の組み合わせで、新しいcode経路は使わない。案Aを推奨する。

- **案A（後任用の新しい口座を作り、bootstrap再開の作業枠で準備する）**: §5.3〜§5.5と同じ経路を使う。
  1. 通常運用中に、OIDC管理者（C〜E）のsessionで後任の口座（例 `bg-third`、role `管理者`）を作成する。初期パスワードはSecurityが生成して後任だけに渡す。続けて後任のIdPの `sub` で外部identity linkを登録・承認する（§5.3の4と同じAPI）。
  2. §5.7の承認flowでbootstrapを再開する（`BREAK_GLASS_USERNAMES` を空にする）。
  3. 後任がOIDCでログインし、`/mfa/setup` でTOTPを登録し、local passwordを本人が変更する（§5.4の1・2）。
  4. OIDC管理者のsessionで旧Bの口座を無効化し、`POST /api/security/mfa/<旧Bのsys_user.id>/reset` でTOTPとsessionを解除する。旧Bの外部identity linkは、DBAが上の `review_status = 'QUARANTINED'` のSQLで隔離する。
  5. Securityが§5.4の3のSQLで、TOTPの登録者が `admin` と後任だけになったことを確かめる。
  6. `SECURITY_BOOTSTRAP_*` を外し、`BREAK_GLASS_USERNAMES=admin,bg-third`（§5.5の1のとおりDBの表記どおり）で再起動し、§5.5の3の完全な起動検査が通ることを確かめる。
  7. Securityが§5.6の監査SQLで、期間中の操作がticketの範囲と一致することを確かめてticketを閉じる。
- **案B（同じ口座を後任へ引き継ぐ）**: 上の「bootstrap終了後にbreak-glass管理者がTOTPとrecovery codeを両方失った場合」と同じ経路を使う。
  1. OIDC管理者のsessionで、ユーザー管理画面から旧Bの口座（`bg-second`）に新しいパスワード（Securityが生成し後任だけに渡す）を設定し、`POST /api/security/mfa/<id>/reset` でTOTPとsessionを解除する。
  2. 同じ作業枠でbreak-glass incident（起票C・承認D/E）を有効化し、後任がbreak-glass loginして `/mfa/setup` で登録し、local passwordを本人が変更する。resetから再登録までの間に再起動すると、起動時検査（MFA enrollment完了済み）で止まる。
  3. 注意: incidentの起票はIdP障害の確認（`idpOutageConfirmed`）を必須とする。IdP障害が無い交代でこの経路を使うのは手続上の例外であり、Owner/Securityが事前に認め、incidentの理由欄にその旨を記す。旧Bの外部identity linkが残る場合は案Aの4と同じく隔離する。

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
