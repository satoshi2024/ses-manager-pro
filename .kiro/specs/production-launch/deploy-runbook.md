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
  - NF07 F1 + PERM（W3-14）: `V190__nf07_privacy_f1_core.sql`（F1表。行は作らない）、`V191__nf07_privacy_permission_seed.sql`（privacy-admin・privacy.* の拒否・menu）。prodの起動検査 `ProductionSecurityConfigurationValidator` は、全体baseline `'*'` を持つgroup（privacy-admin以外）に privacy.* の拒否行が無ければ起動を止める（bootstrap中も同じ）。prod では保持・保全（`NF07_RETENTION`）と本人請求（`NF07_DSAR`）はどちらも既定 `CLOSED` で配備され、画面「機能の開放」（`/feature-enablement`、行為者は `privacy-admin` のみ）で署名の組 `NF07_DECISION_BINDING` 等の二人確認と開放申請・承認を経て開く（旧い `PRIVACY_RETENTION_ENABLED` / `PRIVACY_DSAR_ENABLED` / `PRIVACY_APPROVAL_*` 等を prod に置くと起動しない。§1.0・§1.7）。`PrivacyProductionConfigurationValidator` は開放単位の評価器として決定ID束縛・`privacy-admin` の有効な OIDC 管理者2名以上（break-glass口座を除く。ロック中も数える。推奨は §5.0 の C〜E の3名）・他 group に `privacy.` 系の許可行が無いこと・Journal 等を検査し、不成立でも **prod の起動は止めず**、開けない・開いていれば自動閉鎖する（起動時は WARN `NF07_PRIVACY_STOPPED_AT_STARTUP`。手順: `feature-switches.md` §4.7）。新しいmigrationは無い。
  - NF-06 取込センター画面（W3-12）: `V193__nf06_import_center_menu_and_permission_seed.sql`（menu `import-center` を管理者だけへ付与、`import.metadata.manage` を管理者専用group（`role-admin`・`privacy-admin`）へ明示許可し、他の全groupで拒否する。利用者の割当は変えない）。prod では開放単位 `NF06` は既定 `CLOSED` で配備され、画面 `/feature-enablement` で開く（旧い `FEATURE_NF06_ENABLED`・`IMPORT_ADMISSION_*` を prod に置くと起動しない。§1.0・§1.7）。`ImportCenterEnablementConditions` が署名 `NF06_ADMISSION`・行参照のHMAC鍵 `IMPORT_ROW_REFERENCE_HMAC_KEY`・`import.metadata.manage`・単一法人（`m_organization_unit` の論理削除されていない組織の法人IDがちょうど1種類。新規導入は0件のため開く前に登録する）を評価し、不成立でも prod の起動は止めない（手順: `feature-switches.md` §4.8）。
  - NF08 B2c-1（W3-13）: `V194__nf08_copilot_dispatch_claim_and_cost_ledger.sql`（summary dispatchのclaim列、費用ledgerの `t_ai_external_send_cost_bucket` / `t_ai_external_send_cost_entry`、`t_audit_log.idx_audit_dispatch_key`。行は作らない）。外部送信は未開放のままで、prodは `AI_EXTERNAL_SEND_ENABLED=true` を `dispatch.permitOnlyNotWired` で起動拒否する（B2c-2の完了と独立Reviewまで。設定では解除できない）。`AI_EXTERNAL_SEND_COST_LEDGER`（`ai.external-send-cost-ledger`）は既定の `persistent` のまま設定しない（`persistent` 以外は解除後も起動拒否）。
  - NF01 B0（W3-18）: `V195__nf01_dual_confirmation_coordinator.sql`（二者確認coordinatorの7表 `t_lifecycle_dual_submission` / `_submission_document` / `_submission_org_fence` / `_confirmation` / `_projection` / `_idempotency` / `_event`。行は作らない。projection以外の不変6表へUPDATE・DELETEを拒否するtrigger 12件を追加する。冪等性keyは `ascii_bin` で、CHECK `ck_dual_idem_key` が空文字と `[A-Za-z0-9._~-]` 以外の文字を拒否する）。アプリ側は `AppendOnlyMapper`・`AppendOnlySqlInjector` で同じ6表の更新・削除を拒否する。二者確認の画面・API（W3-21a/b）は子単位 `NF01_DUAL_CONFIRMATION` が閉じる間 400 で拒否し、表は空のまま配備する。trigger件数は §1.2・§3.4 の 73件に含まれる（`DeployRunbookMigrationCountTest` がmigrationのCREATE/DROP TRIGGERから数えて照合する）。自由記述列の保存期間はPrivacyの署名事項（NF01計画 §10・§12 O-8）。
  - NF07 F2（read-only provider）: `V196__nf07_privacy_data_element_catalog_seed.sql`（`m_pii_data_element` に、`sys_user` に存在する tenant ごとの data element の構造行と `subject_attribution` だけを入れる。owner・データ分類・inventory 状態は UNKNOWN のまま、保持期間は持たない。既にある行は入れない）。新規導入では tenant `default` に文書以外の固定27件＋文書種別ごとの `DOCUMENT:<code>`（`m_document_type` の全種別。V197 の2種別を含む）。catalog API `GET /api/privacy/catalog` は `NF07_RETENTION` が閉じる間 404 で、`/feature-enablement` で開いた後に利用できる（§1.7）。外部 provider（Gemini・CloudSign・freee・mailbox・backup）は登録しない。
  - 文書種別の追加（既存不具合の修正）: `V197__resume_ingestion_and_import_document_types.sql`（`m_document_type` に `RESUME_INGESTION`（履歴・スキルシート取込）と `IMPORT`（取込センターCSV）を INCOMING で入れ、catalog に `DOCUMENT:IMPORT`（NOT_ATTRIBUTABLE。取込CSVは本人を導出できない）を足す。既にある行は入れない）。これまではどの migration も2種別を作っておらず、スキルシート取込の upload は `error.document.invalidType`（400）で毎回失敗していた。保存年数（3年・TRANSACTION_DATE）は spec に根拠が無い暫定値で文書 owner の確認待ち。取込センターCSV（IMPORT）は取引日を設定しないため保存期限は NULL のままで、処分候補にはならない（保持期間は開放前に Privacy が書面で受理する条件のまま）。履歴取込の副本（RESUME_INGESTION）は、取込原本を `app.resume.retention-days` で purge する日次清掃（02:00）が保存期限を当日に合わせ、02:30 の `DocumentRetentionJob` が廃棄候補（PENDING）にし、管理者の承認・執行で処分する（2026-11-16 依頼者決定。legal hold 中の副本は変えない。plan §3.4.1「D4 と文書台帳の副本」）。**署名事項**：この副本の処分方針について、Privacy owner と文書 owner の書面署名を得る。**署名までは `RESUME_INGESTION` の副本の廃棄申請を承認も執行もしない**（FINAL-FIX (a)-3）。副本の調整は廃棄候補（PENDING）を作るだけで、削除は管理者の承認と執行を経る。そのため、署名を待つために scheduler（`APP_SCHEDULING_ENABLED`。中核jobと共用）を止めなくてよい。副本の調整だけを止める停止口 `RESUME_LEDGER_COPY_ALIGNMENT_ENABLED`（既定 `true`）もある。確認の SQL、停止口、有効化後の監視（失敗の metric・ERROR log）と定期確認は §4.3。
  - NF01 二者確認の NF07 catalog 登録（RB-NF01）: `V198__nf01_dual_confirmation_privacy_catalog.sql`（`m_pii_data_element` に、B0 の7表と自由記述2列（`business_comment`・`decision_comment`）の9件を `sys_user` に存在する tenant ごとに入れる。V196 と同じく構造行だけで、provider は未登録（NULL）、`subject_attribution`・owner・データ分類・inventory 状態は UNKNOWN、保持期間は持たない。既にある行は入れない）。新規導入では tenant `default` の文書以外の catalog 行は36件（V196 の27件＋9件）になる（V203 の後は38件。V203 の行と §3.4）。値の正本は `privacy-retention-dsar/pii-inventory.md` の DB-141〜DB-147 で、保存期間等の値は D-02 の署名で確定する（署名は未取得。§1.7）。DSAR の検索ではこの9件が「要手動確認」（`PROVIDER_UNREGISTERED`）として出る。分類が UNKNOWN のため、NF07 hold 画面の「未分類の data element」（`unclassifiedElements`）と coverage report の未分類件数（`PrivacyCoverageReport` の `unknownAttributionCount`）にもこの9件が加わり、D-02 で分類を決めるまで0にならない（回帰ではない。B0 は hold の gate に接続する破壊的経路を持たないため、他の経路の判定は変わらない）。
  - NF-06 取込 stage 照合の索引（RB-NF05、FINAL review b-14）: `V200__nf06_document_version_storage_key_index.sql`（`t_document_version.storage_key` に非一意の索引 `idx_dv_storage_key` を足す。V1・V67 は変えない。行は変えない）。照合 batch（`ImportStageReconcileScheduler`、NF06 の OFF でも動く）は台帳1件ごとに同じ storage key の版を数えるため、従来は全件走査だった。V199 は欠番（RB で使わなかった予約番号）。
  - NF07 F1 の状態の CHECK（RB-NF07-HOLD）: `V201__nf07_privacy_status_check_constraints.sql`（`t_privacy_request` の `status`（`PrivacyCaseState` の全値）・`channel`・`delivery_method`（NULL 可）と `t_legal_hold.hold_kind`（D-05 で種類が決まるまで `UNKNOWN` だけ）に、コードの enum と同じ値の集合の CHECK を足す。行は作らない）。表は新規導入で空のため適用は失敗しない。V199 は欠番。
  - backup の書込み静止の信号表（RB-QUIESCE BWQ-001）: `V202__backup_write_quiesce_signal.sql`（静止要求 `sys_backup_quiesce_request`・app の instance `sys_backup_quiesce_instance`・応答 `sys_backup_quiesce_ack` の3表。行・trigger・routine は作らない。時刻の列は全て UTC の `DATETIME(6)` で、DEFAULT に `CURRENT_TIMESTAMP` を使わない。同時に有効な要求を1件に限る生成列 `active_slot`（STORED）と一意索引 `uk_bq_request_active` を持つ。tenant 列・個人データは持たない）。表は新規導入で空のため適用は失敗しない。backup account の表単位の権限（要求の表への `INSERT`・`UPDATE`）は V202 の適用の後（§3.4）・初回 backup（§2 の5）の前に付ける（§1.2・§3.4）。設計は `.kiro/specs/backup-write-quiesce/`。
  - backup の書込み静止（RB-QUIESCE BWQ-002〜012・015。migration は上の V202 だけ）: app は `com.ses.backup.quiesce` の gate（`BackupWriteGate`）と静止応答（`BackupQuiesceResponder`。専用の thread と、業務の pool と別の制御用・heartbeat 用の接続 pool）で信号表の静止要求を読み、更新の要求を 503 にし（`BackupWriteGateFilter`。参照は続く）、scheduler・async を待たせ、処理中の書込みを排水してから応答（ack）し、解除で再開する。静止中の業務の接続は読取り専用になり（`BackupQuiesceAwareDataSource`。最後の防線）、uploads の変更は storage 層でも拒否する。prod の起動時検査は `BackupQuiesceProductionValidator`（停止口の ticket と `readOnlyPropagatesToServer`）。設定は §1.0 の31〜41。Flyway job の callback `ProductionBackupDdlFreezeFlywayCallback` は backup と同じ DDL 凍結の lock を取る（§3.3）。backup tool の provider は `quiesce-db`（ONLINE。定期の checkpoint・full。`.env.backup` の `QUIESCE_PROVIDER=quiesce-db`。§1.7）と `quiesce-app-stopped`（app の停止を DB の信号表で確かめる。`--app-stopped` の明示でだけ選ぶ。§2 の5）。配備の手順は §2 の0（backup の定期起動の停止）・§2 の4・5・§3.4（表単位の GRANT）・§4.7（instance の確認・backup の再開・障害の切分け）、監視は `ops/backup/runbooks/backup-health-monitoring.md`、署名と本番の前の実測は §1.7 の BQ-1〜BQ-5。試験: `BackupWriteGateTest`・`BackupQuiesceResponderTest`・`BackupWriteGateMockMvcTest`・`BackupQuiesceProductionContextStartTest`・`UploadsMutationInventoryTest`（fast）、`BackupQuiesceResponderMySqlTest`・`BackupWriteQuiesceMySqlTest`・`BackupQuiesceProviderContractMySqlTest`・`ProductionBackupDdlFreezeFlywayCallbackMySqlTest`（mysql shard 3）、`ops/backup/tests/quiesce-db-test.sh` ほかの shell の試験、CI の backup integration（DB の protocol を話す shell の responder）。
  - 機能の開放を system 内で行う（FE-A01〜FE-E05）: `V203__feature_enablement_console.sql`（世代 `t_feature_enablement_generation`・申請 `t_feature_enablement_request`・開放状態 `t_feature_enablement_state`・承認で変える設定 `t_feature_enablement_setting`・署名 `t_feature_sign_off`・署名の延長の記録 `t_feature_sign_off_extension`・署名の組 `t_feature_sign_off_bundle`・組の member `t_feature_sign_off_bundle_member`・履歴 `t_feature_enablement_event` の9表。開放状態・申請・署名の行は作らない（行の無い開放単位は CLOSED）。追記専用の3表（履歴・組の member・延長の記録）の UPDATE・DELETE を拒否する trigger 6件を足す（アプリ側は `AppendOnlyMapper`）。時刻の列は `DATETIME(6)` で DEFAULT に `CURRENT_TIMESTAMP` を使わない。未決の申請・確認済みの署名・確認済みの組を1件に限る生成列（STORED）と一意索引、申請者≠承認者・登録者≠確認者の CHECK、開放状態・設定・延長の記録を承認した申請の申請者・決定者と一致させる複合 FK、組の member の種別・tenant を署名の行と一致させる複合 FK を持つ。menu `feature-enablement` を管理者だけへ付与し、`t_permission_group_action` には足さない。`m_pii_data_element` に署名者の氏名と署名の文書の2件（`FEATURE_SIGN_OFF_SIGNER_NAME`・`FEATURE_SIGN_OFF_DOCUMENT`。provider は未登録、owner・データ分類・inventory 状態は UNKNOWN、`subject_attribution` は消去の経路（§4.4 の D17）を hold の gate が判定できるよう `NOT_ATTRIBUTABLE`）を `sys_user` に存在する tenant ごとに入れる。新規導入では tenant `default` の文書以外の catalog 行は38件（V196 の27件＋V198 の9件＋2件）になる。値の正本は `privacy-retention-dsar/pii-inventory.md` の DB-151〜DB-159）。表は新規導入で空のため適用は失敗しない。設計は `.kiro/specs/feature-enablement-console/`。画面（`/feature-enablement`）と API（`/api/feature-enablement`）は管理者専用（行為者は OIDC＋MFA の有効な管理者で、NF07 はさらに `privacy-admin`。break-glass・bootstrap の session では使えない）。
  - 検証: `FlywayProdFreshInstallMySqlTest`（実MySQL、job設定そのものを読む）、`ProductionFlywayJobConfigurationTest`、`ProductionSchemaVersionGuardTest`、`PasswordChangeEnforcementIntegrationTest`、`ProductionBootstrapIntegrationTest`、`ProductionBootstrapModeTest`、`ProductionBootstrapPersistenceTest`、`ProductionSecurityConfigurationValidatorBootstrapTest` ほか。

## 0. 全体の流れ

| 順 | 作業 | 実施者 | 完了条件 |
|---|---|---|---|
| 1 | 前提条件の確認（§1） | DBA / SRE / Security | §1の初回配備（go-live）の必須前提が全て確認済み |
| 2 | 全停止とdrain（§2） | SRE | backup の定期起動の停止・アプリ接続0件・scheduler停止・`--app-stopped` の full が VALID（初回の full は §3.4 の後） |
| 3 | 専用Flyway jobの実行と確認（§3） | SRE（DBA立会い） | §3.4の確認SQLが全て期待値、backup account の表単位の GRANT |
| 4 | アプリ起動（§4。初回はbootstrap mode） | SRE | schema guardのINFO log、bootstrapのWARN log、readiness OK、backup の再開と最初の ONLINE の checkpoint が VALID（§4.7） |
| 5 | 初回bootstrap（§5） | 初期管理者担当 / IdP管理者 / Security | §5.5の完全な起動検査が通り、完了markerが書かれる |
| 6 | 失敗時・ロールバック（§6） | SRE / DBA | — |

- 5 の完了時点で全 13 件の開放単位（NF01〜NF07・NF10）は `CLOSED` のままであり、**go-live（利用者への公開）の必須前提は §1.7 の「A. 初回配備（go-live）の必須前提（基盤・中核機能・常時有効の部分）」が全て揃うこと**である（1つでも欠ける間は go-live しない）。各 S-NF 機能は go-live の後に、それぞれの署名・基盤準備（§1.7 の B）が揃った機能から画面「機能の開放」（`/feature-enablement`、§5.5.1・`feature-switches.md` §3）で 1 件ずつ開く。ただし将来の `NF05_OUTBOUND` 開放に備え、4 の初回起動から以後の全ての起動で同じ `INTEGRATION_HUB_BOUND_TENANT_ID` を設定し続ける。

## 1. 前提条件（配備前に確認する）

### 1.0 prod 必須設定（無い・形式が違うとアプリが起動しない。一覧の正本）

`SPRING_PROFILES_ACTIVE=prod` で起動するとき、次の1〜26の26項目のどれかが無い・形式が違うと、アプリは起動を中止する。機能スイッチの ON/OFF には関係しない。freee連携を使う場合（`FREEE_ENABLED=true`）は、さらに27〜30の4項目が要る。backup の書込み静止の31〜41の11項目は既定値を持つため無くても起動するが、値・組合せ・範囲が違うと起動しない（雛形は31を `true` で明示する）。機能の開放の緊急の総スイッチ42も既定値を持つが、値が `true`・`false` 以外なら起動しない（雛形は `true` で明示する）。

- 一覧はコードを走査して作った（FINAL-FIX、2026-11-17）。走査した出処は3つ: prod profile の validator の必須項目、`@Value` のうち既定値が空・開発用で prod では拒否されるもの、`application-prod.yml` の `${...}` のうち既定値が空で prod では拒否されるもの。
- 既定値を全く持たない `@Value` と、既定値の無い `application-prod.yml` の `${...}` は、どちらも0件だった。どの項目も空か開発用の既定値を持つため、Spring の解決では止まらない。止めるのは validator である。
- `infra/ecs/task-definition.prod.json` は placeholder だけを書いた雛形で、正本はこの表である。値（secret）は本書・task definition・repository のどれにも書かない。
- **secret** 欄の「〇」は secret store（Secrets Manager・SSM）から注入する。

**DB・転送ヘッダー**

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 1 | `DB_URL`（`spring.datasource.url`） | アプリDBのJDBC URL | `jdbc:mysql://<host>:3306/ses_manager_db?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Tokyo`。`autoReconnect=true` / `autoReconnectForPools=true`（`yes` も。百分率符号化した値も）を付けない。host は1つだけ書く: multi-host（`h1,h2`・`[h1,h2]`・`address=(...)`）、`jdbc:mysql:loadbalance://`・`jdbc:mysql:replication://`・`jdbc:mysql+srv://`・`jdbc:mysqlx://` は使わない（どれも起動を中止する。RB-NF05）。query に `useConfigs`・`propertiesTransform`・切替え系の key（`failOverReadOnly`・`maxReconnects` 等）も書かない（driver が展開した後の有効な属性も検査し、`(effectiveAutoReconnect)` / `(connectionTopology)` で起動を中止する。RB-HYG）。`readOnlyPropagatesToServer=false`（`no` 等の有効でない値も）を query・`spring.datasource.hikari.data-source-properties` のどちらにも付けない: backup の書込み静止の最後の防線（静止中の業務の接続の読取り専用）が DB に届かなくなるため、driver の展開後の属性まで見て起動を中止する（`BackupQuiesceProductionValidator`。RB-QUIESCE）。staging で本番と同じ形式の `DB_URL` で起動し、guard が通ることを一度確かめる。未設定だと開発用の localhost へ接続し、起動時 schema guard（§4）で止まる | 〇（接続先） | `application.yml:122`、`ImportJdbcReconnectGuard`（`violations`・`multiHost`） |
| 2 | `DB_USERNAME`（`spring.datasource.username`） | アプリaccount（§1.2。DDL権限なし） | 既定は開発用の値。必ず上書きする | 〇 | `application.yml:123` |
| 3 | `DB_PASSWORD`（`spring.datasource.password`） | 同上 | 同上 | 〇 | `application.yml:124` |
| 4 | `TRUSTED_PROXIES`（`app.security.trusted-proxies`） | 転送ヘッダーを信頼する接続元（ALBのsubnet） | カンマ区切りのCIDR。IPv4は private（10/8・172.16/12・192.168/16）か 100.64/10 で /16 以下、IPv6は /48 以下、host部は0。`127.0.0.1` を入れない（SRE-3） | × | `application-prod.yml:76`、`ForwardedHeaderProductionValidator.java:67-105` |

**OIDC・MFA・session・break-glass**（毎回の起動で `ProductionSecurityConfigurationValidator` が検査する。§4.1）

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 5 | `OIDC_TENANT_ID`（`app.security.oidc.tenant-id`） | OIDC login・外部identity・MFA・sessionの照合tenant | `default`（SRE-1） | × | `application.yml:287`、validator `:125-127` |
| 6〜10 | `OIDC_ISSUER_URI`・`OIDC_AUTHORIZATION_URI`・`OIDC_TOKEN_URI`・`OIDC_JWK_SET_URI`・`OIDC_USER_INFO_URI` | IdPの固定metadata（5項目） | どれもHTTPS、hostがあり、userinfo（`user:pass@`）を含まない | × | `application-prod.yml:83-87`、validator `:360-390` |
| 11 | `OIDC_CLIENT_ID` | OIDC client | 空でない | × | `application-prod.yml:88`、validator `:363-371` |
| 12 | `OIDC_CLIENT_SECRET` | 同上 | 空でない | 〇 | `application-prod.yml:89`、validator `:363-371` |
| 13 | `MFA_ENCRYPTION_KEY` | MFA secretの暗号鍵（`MFA_CURRENT_KEY_VERSION` の既定 `v1` の鍵） | 32文字以上で、開発用の既定値でない | 〇 | `application-prod.yml:97`、validator `:155,289-303,405-409` |
| 14 | `SESSION_HASH_KEY` | 永続sessionのhash鍵 | 32文字以上で、開発用の既定値でない | 〇 | `application-prod.yml:101`、validator `:156,405-409` |
| 15 | `BREAK_GLASS_USERNAMES` | break-glass管理者のusername | bootstrap中（§4.2）は**空**でなければ起動しない。bootstrap終了後（§5.5）は**ちょうど2件**（カンマ区切り）。各名は ASCII の `[A-Za-z0-9._-]+` だけで、有効な管理者の `sys_user.username` と大文字小文字まで完全一致し、MFA登録済みであること | × | `application-prod.yml:82`、validator `:317-357` |

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
| 21 | `COMPLIANCE_CREDENTIAL_CURRENT_KEY_VERSION`（`compliance.gate.credential-crypto.current-key-version`） | G2外部Review資格credentialの暗号鍵の現行version | 上記のversion | × | `application.yml:547`、`ComplianceGateCredentialKeyProviderImpl.java:28,49-68` |
| 22 | `COMPLIANCE_GATE_CREDENTIAL_CRYPTO_KEYS_<VERSION>`（`compliance.gate.credential-crypto.keys.<version>`） | 同 鍵 | 上記の鍵。この名前で渡す（以前 `application.yml` のcommentにあった `COMPLIANCE_CREDENTIAL_KEY_V1` は配線されておらず、読まれなかった。FINAL-FIX でcommentを直した） | 〇 | `application.yml:548-550`、`ComplianceGateCredentialKeyProviderImpl.java:58-63,120-136` |
| 23 | `COMPLIANCE_GATE_FINGERPRINT_KEY_TENANTS`（`compliance.gate.fingerprint-key-tenants`） | reviewer fingerprintの鍵を持つtenantの一覧 | `default`（カンマ区切り） | × | `application.yml:560`、`ComplianceReviewerFingerprintKeyProviderImpl.java:167-199` |
| 24 | `COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_CURRENT_KEY_VERSION`（`compliance.gate.fingerprint-keys.default.current-key-version`） | reviewer fingerprintのHMAC鍵の現行version（tenant `default`） | 上記のversion | × | `ComplianceReviewerFingerprintKeyProviderImpl.java:65-75,141-160` |
| 25 | `COMPLIANCE_GATE_FINGERPRINT_KEYS_DEFAULT_KEYS_<VERSION>`（`compliance.gate.fingerprint-keys.default.keys.<version>`） | 同 鍵 | 上記の鍵 | 〇 | 同上 |
| 26 | `ACCOUNTING_TENANT_IDS`（`app.accounting.tenants.ids`） | 定期処理の対象tenant（freeeの有無に関係しない） | 1件以上（SRE-1 により `default` の1件）。test以外の全profileで検査する | × | `application.yml:230`、`AccountingTenantInventoryStartupValidator.java:7-13` |

- **23〜25の注（OS環境変数で渡せなかった理由と対処。FINAL-FIX）**:
  - 原因は relaxed binding でも、名前の `-`・大文字小文字でもない。tenant を、Map型の property source にある property 名のうち `compliance.gate.fingerprint-keys.` で始まるものだけから列挙していた。
  - OS環境変数の名前は `COMPLIANCE_GATE_FINGERPRINT_KEYS_...` の形でこの接頭辞に一致しない。しかも `.`・`-` が `_` になり大文字化されるため、名前から tenant ID を復元できない。そのため 24・25 だけを環境変数で渡すと、`Fail-fast: missing compliance.gate.fingerprint-keys configuration in prod profile` で止まっていた。
  - tenant一覧（23）を足した。一覧にある tenant の version と鍵は `Environment#getProperty` の名前解決で読むため、24・25 は上の環境変数名でそのまま渡せる。
- 一覧にある tenant の version・鍵が欠けると、起動時に止まる（`missing fingerprint current-key-version for tenant ...` / `missing fingerprint key for tenant ...`）。
- 従来の経路（設定file・`SPRING_APPLICATION_JSON` で `compliance.gate.fingerprint-keys.<tenant>.*` を渡す）もアプリは読むが、本番では使わず、環境変数名（23〜25）で渡す。task definition の照合（§4）は、中身を見られない経路を FAIL にする: `SPRING_CONFIG_*`（environment・secrets のどちらでも）、secret store から渡す `SPRING_APPLICATION_JSON`、`SPRING_APPLICATION_JSON` の別の綴り（`spring_application_json` 等）。environment の `SPRING_APPLICATION_JSON` に `compliance.*` を書いた場合も、鍵が task definition に平文で残るため FAIL にする。`-D` は process一覧に出るため使わない。

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
| 27 | `FREEE_CLIENT_ID`（`freee.client-id`） | freee会計・給与のOAuth client | 空でなく `dummy-client-id` でない | × | `application.yml:532`、`FreeeAccountingProvider.java:79-107`、`FreeeIntegrationServiceImpl.java:199-221` |
| 28 | `FREEE_CLIENT_SECRET`（`freee.client-secret`） | 同上 | 空でなく `dummy-client-secret` でない | 〇 | `application.yml:533`、同上 |
| 29 | `FREEE_REDIRECT_URI`（`freee.redirect-uri`） | OAuthのredirect URI | HTTPS | × | `application-prod.yml:163`、`FreeeIntegrationServiceImpl.java:199-221` |
| 30 | `FREEE_TOKEN_ENCRYPTION_KEY`（`freee.token-encryption-key`） | freee tokenの暗号鍵。会計接続も、専用鍵 `freee.token.encryption-key` を設定しなければ同じ鍵を使う | `change-me` で始まらない十分な長さの乱数 | 〇 | `application.yml:538`、`FreeeIntegrationServiceImpl.java:199-221`、`IntegrationConnectionServiceImpl.java:46` |

**backup の書込み静止**（RB-QUIESCE。設計は `.kiro/specs/backup-write-quiesce/design.md` §12。31〜41 は既定値を持ち、無くても起動する）

- 範囲と相互の関係は全 profile で bind の時に `BackupQuiesceProperties` が検査し、外れると起動を中止する。停止口（31・32）と最後の防線の JDBC 設定（1 の `readOnlyPropagatesToServer`）は prod の起動時検査 `BackupQuiesceProductionValidator` が port を開く前（instance の登録の前）に検査する。prod の判定は有効な profile で行う。
- どれも secret ではない（×）。secrets・正規名（`APP_BACKUP_QUIESCE_*`・`APP_BACKUPQUIESCE_*`）・`SPRING_APPLICATION_JSON` の `app.backup-quiesce` で渡すと、task definition の照合（`validate-task-definition.ps1`）が FAIL にする。時間の値は照合のため `<整数><単位>`（単位は `ms`・`s`・`m`・`h`・`d`）で書く（アプリは ISO-8601 と単位の無い数（ミリ秒）も読むが、照合は拒否する）。
- 雛形（`infra/ecs/task-definition.prod.json`）は `BACKUP_QUIESCE_ENABLED=true` を明示し、app の container に `stopTimeout: 120` を置く。照合は雛形・実値のどちらの mode でも、`healthCheck.startPeriod` が 40 の値＋120 秒以上（既定の値で 180）、`stopTimeout` が `SHUTDOWN_TIMEOUT`＋60 秒以上（既定 90。graceful 停止・`taskExecutor` の待ち 30 秒・STOPPED の前の排水の待ち 10 秒の余裕。Fargate の上限は 120 のため `SHUTDOWN_TIMEOUT` は 60 秒以下にする）であることを確かめる。`SPRING_LIFECYCLE_TIMEOUT_PER_SHUTDOWN_PHASE` 等の正規名で停止の時間を変えることも FAIL にする。**`SHUTDOWN_TIMEOUT` も照合の対象**で、app の container の `environment` に（secrets ではなく）、31〜41 と同じ `<整数><単位>`（例 `30s`。雛形の値）で置く。`PT30S`（ISO-8601）・`30S`（大文字の単位）・単位の無い数は、アプリは起動しても照合が FAIL にする（`stopTimeout` の計算に使うため。2c review P3-6）。

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 31 | `BACKUP_QUIESCE_ENABLED`（`app.backup-quiesce.enabled`） | backup の書込み静止の静止応答 | 本番は `true`（既定 `true`。雛形も `true`）。`false` は停止口で、prod では 32 と組でだけ起動する（下の「停止口の使い方」）。値は `true` / `false` で書く | × | `application.yml:250`、`BackupQuiesceProductionValidator` |
| 32 | `BACKUP_QUIESCE_DISABLED_TICKET`（`app.backup-quiesce.disabled-ticket`） | 停止口を使う承認済みの変更 ticket | `^[A-Z][A-Z0-9]+-[0-9]+$`（例 `CHG-1234`）。31 が `false` のときだけ設定する（`true` に戻したら消す。照合は `true` と組の ticket を FAIL にする）。ticket が無い・形式が違うと prod は起動しない | × | `application.yml:251`、`BackupQuiesceProductionValidator` |
| 33 | `BACKUP_QUIESCE_POLL_INTERVAL` | 静止要求の読取り間隔 | 既定 `1s`。200ms 以上で 34 より短い | × | `application.yml:252`、`BackupQuiesceProperties` |
| 34 | `BACKUP_QUIESCE_HEARTBEAT_INTERVAL` | heartbeat の間隔 | 既定 `5s`。35 より短い | × | `application.yml:253`、同上 |
| 35 | `BACKUP_QUIESCE_FENCE_AFTER` | heartbeat を書けない時に自分で更新を止める（self-fence）までの時間 | 既定 `20s`。30 秒以下で、35 − 34 が 13 秒（制御用の接続の取得 3 秒＋socket 10 秒）より長い | × | `application.yml:254`、同上 |
| 36 | `BACKUP_QUIESCE_DRAIN_TIMEOUT_CHECKPOINT` | checkpoint の排水の上限（U-3） | 既定 `30s`。正の値で 38 より短い | × | `application.yml:255`、同上 |
| 37 | `BACKUP_QUIESCE_DRAIN_TIMEOUT_FULL` | full の排水の上限（U-3） | 既定 `600s`。正の値で 39 より短い | × | `application.yml:256`、同上 |
| 38 | `BACKUP_QUIESCE_MAX_HOLD_CHECKPOINT` | checkpoint の最大保持時間（U-1） | 既定 `300s`。30 秒〜900 秒 | × | `application.yml:257`、同上 |
| 39 | `BACKUP_QUIESCE_MAX_HOLD_FULL` | full の最大保持時間（U-1） | 既定 `3600s`。60 秒〜4 時間 | × | `application.yml:258`、同上 |
| 40 | `BACKUP_QUIESCE_STARTUP_WAIT_MAX` | 静止の最中に起動した時に解除を待つ上限 | 既定 `60s`。10 秒〜120 秒。ECS の `healthCheck.startPeriod` はこの値＋120 秒以上にする | × | `application.yml:259`、同上 |
| 41 | `BACKUP_QUIESCE_RETENTION` | 信号表の解除済みの行の保存期間（U-12） | 既定 `30d`。1 日〜365 日 | × | `application.yml:260`、同上 |

**停止口の使い方**（U-7。31 を `false` にする緊急の手段）
- 使う場面: 静止応答の不具合で業務に支障が出て、修正の release まで静止応答を外す必要がある時だけ。承認済みの変更 ticket を取り、task definition に `BACKUP_QUIESCE_ENABLED=false` と `BACKUP_QUIESCE_DISABLED_TICKET=<ticket>` を入れ、`validate-task-definition.ps1 -Mode Actual` で組を確かめてから配備し直す。
- 停止口の間: アプリは instance の登録と heartbeat を続け（`sys_backup_quiesce_instance.gate_state='DISABLED'`）、gate は閉じず、静止の要求には `REFUSED`（`DISABLED`）を返す。backup の provider は ONLINE・停止確認（`--app-stopped`）のどちらも、停止口の行があれば失敗する。**そのため停止口の間は checkpoint と full が全て失敗し、RPO の alert になる**。起動の時に ERROR `errorCode=BACKUP_QUIESCE_DISABLED`（ticket を含む）を出し、gauge `ses_backup_quiesce_disabled` が 1 になる（どちらも critical の alert の条件）。ticket と期間を配備記録に残す。
- 戻す手順: `BACKUP_QUIESCE_ENABLED=true` にして `BACKUP_QUIESCE_DISABLED_TICKET` を消し、配備し直す。停止口の task が通常に停止すれば instance の行は `STOPPED` になる。異常に止まった（SIGKILL・task の喪失等で `STOPPED` にならない）停止口の行が残ると、heartbeat の古さによらず全ての backup が失敗し続ける。この場合は DBA が ECS で task の停止と processlist の0件を確かめてから、その行だけを手の SQL で `STOPPED` にする（SQL と手順は §4.7 の「停止口の行の片付け」。§1.8 の RR-10 の例外の一覧にある。設計は `backup-write-quiesce/design.md` §9.4）。その後、次の checkpoint が成功することを確かめる。

**機能の開放**（feature-enablement-console design §10。FE-A08〜FE-E02。42 は既定値を持ち、無くても起動するが、値が違うと起動しない）

- 全 13 件の開放単位（NF01・NF01 二者確認・NF02・NF02 顧客ポータル・NF03・NF03 資格番号・NF04・NF05 inbound・NF05 outbound・NF06・NF07 保持・保全・NF07 DSAR・NF10）は配備の設定ではなく system の中（画面 `/feature-enablement` の申請と別の管理者の承認）で開く。配備の設定に置けるスイッチは 42 の緊急の総スイッチだけである（D-5）。検査は `FeatureEnablementProductionValidator` が port を開く前に行い（prod の判定は `Environment.acceptsProfiles`。42 の値の形式は全 profile で検査する）、message には変数名だけを出して値を出さない。
- 42 の `false` は全ての開放単位を次の起動で一括閉鎖し（`AUTO_CLOSED(MASTER_SWITCH_CLOSED)`）、未決の申請を全て `CANCELLED` にし、NF05 outbound の起動 barrier も `v1|OFF|0|<指紋>` に書き直す。`false` の間は開放・設定変更の申請と承認が 409 になり、一覧・履歴の閲覧と署名・組の登録・確認・取消しはできる。**誤って `false` にして起動したときの代償**: `true` に戻して再起動しても閉じた開放単位は **1 件も自動では開かず**、開いていた全ての開放単位で二人承認（申請＋承認。NF05 はさらに再起動と隔離完了の確認）をやり直す必要がある（J-11）。起動の時に ERROR `errorCode=FEATURE_ENABLEMENT_MASTER_CLOSED` を出し、gauge `ses_feature_enablement_master_allowed` が 0 になる。閉じる方向の操作のため ticket は要さないが、理由と期間を配備記録に残す。
- 全ての遷移で標準出力へ出す構造化ログ `FEATURE_ENABLEMENT_EVENT` は PITR 後の外部照合（`ops/backup/runbooks/restore-cutover.md` §4.7）に使うため、CloudWatch Logs の log group（例 `/ecs/ses-manager-prod`）の保持期間は **PITR で戻り得る binlog の保持期間以上**に設定する（§1.7 SRE-12）。
- **設定してはいけない旧い変数・運用値の上書き**（値に依らず `false` や空文字でも prod は起動せず、`validate-task-definition.ps1` も `Initial`・`Steady` の両 phase で `environment`・`secrets`・`SPRING_APPLICATION_JSON` を検査して FAIL にする。一覧の一致は `FeatureEnablementLegacySettingTest` が固定する）:
  - 運用値の上書き: `APP_FEATURE_ENABLEMENT_*`（総スイッチの正規名 `APP_FEATURE_ENABLEMENT_ALLOWED` を含む）、42 以外の `FEATURE_ENABLEMENT_*`、`SPRING_APPLICATION_JSON` の `app.feature-enablement`・`FEATURE_ENABLEMENT_*`、同じ名前の system property。運用値（`source`・`refresh-interval`・`max-staleness`・`evaluation-interval`・`evaluation-initial-delay`・`journal-reverify-interval`・`sign-off-purge-cron`）は `application*.yml` の値だけで動かす（prod の `source` は `database` 固定）。
  - 全 13 開放単位の旧い開閉変数（`FEATURE_NF01_ENABLED`・`FEATURE_NF01_DUAL_CONFIRMATION_ENABLED`・`FEATURE_NF02_ENABLED`・`FEATURE_NF02_CUSTOMER_PORTAL_ENABLED`・`FEATURE_NF03_ENABLED`・`FEATURE_NF03_CERTIFICATION_PII_ENABLED`・`FEATURE_NF04_ENABLED`・`FEATURE_NF06_ENABLED`・`FEATURE_NF10_ENABLED`・`PRIVACY_RETENTION_ENABLED`・`PRIVACY_DSAR_ENABLED`・`INTEGRATION_HUB_PUBLIC_API_ENABLED`・`INTEGRATION_HUB_EXTERNAL_TRANSPORT_ENABLED`、および対応する `app.features.*`・`privacy.*`・`integration.hub.*` の全ての綴り）。
  - 署名・設定へ移した承認の値の旧い変数（`LEGACY_SETTING_RULES`）: `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF`（画面の署名 `NF01_DUAL_PRIVACY`）、`IMPORT_ADMISSION_*`・`app.import-center.admission.*`（署名 `NF06_ADMISSION`）、`FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`（画面「設定」タブの `nf02-customer-portal.visible-since`）、`PRIVACY_APPROVAL_*`・`privacy.approval.*`（`PRIVACY_APPROVAL_EXPIRY_WARNING_DAYS` を含む。prod の警告日数は `/system-config` の `feature-enablement.expiry-warning-days`）・`PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE`（組 `NF07_DECISION_BINDING` と `NF07_D06`）、`INTEGRATION_HUB_INBOUND_DECISION_ID`・`INTEGRATION_HUB_INBOUND_APPROVAL_DIGEST`・`INTEGRATION_HUB_OUTBOUND_DECISION_ID`・`INTEGRATION_HUB_OUTBOUND_APPROVAL_DIGEST`・`integration.hub.(public-api|external-transport).approval.(decision-id|approval-digest)`（署名 `NF05_DG05_IN`・`NF05_DG05_OUT`）。
- NF05 は開く変更が次の再起動で反映し、閉じる変更は即時に反映する（D-2。§5.5.1・`feature-switches.md` §4.10）。法人の未解決（法人が NULL・不一致の業務の行）は起動時と定期評価の NF05 条件として判定し、app 全体の起動は止めずに NF05 を `AUTO_CLOSED(LEGAL_ENTITY_UNRESOLVED)` にする（NF05 owner の受理事項・**未取得**）。確定閉鎖後の未送信配信は DLQ `STARTUP_REVALIDATION_REQUIRED` に移り、再開後に `integration-hub-public-api.md` §13.3 の bulk replay で戻せる。起動時に開で実行中に閉じた公開 API への要求は、backup 静止中は 503 `BACKUP_QUIESCED`、静止外は 404 になる（起動時から閉の要求は静止中も 404）。

| # | 環境変数（property） | 用途 | 形式・条件 | secret | 出処 |
|---|---|---|---|---|---|
| 42 | `FEATURE_ENABLEMENT_ALLOWED`（`app.feature-enablement.allowed`） | 緊急の総スイッチ。`false` で全ての開放単位を閉じ、system の中でも開けない | 本番は `true`（既定 `true`。雛形も `true`）。値は原文で `true` か `false` だけ（`yes`・`on`・`1`・大文字・空白は起動しない）。`false` は緊急の停止と、backup からの復旧（PITR）の後の最初の起動（`restore-cutover.md` §4.7）だけに使う | × | `application.yml`・`application-prod.yml` の `app.feature-enablement.allowed`、`FeatureEnablementProductionValidator` |

**検査の効き方と試験の範囲（注意）**
- 18 の検査は、prod かどうかを property `spring.profiles.active` の値で判定する。そのため `@ActiveProfiles("prod")` で起動する試験ではこの検査が走らず、CI はその未設定を検出しない。配備では task definition の `SPRING_PROFILES_ACTIVE=prod` で効く。
- 27〜30（freee）の検査は、FINAL-FIX で有効profileも見るようにした。CI は次の2つで確かめる。
  - `ProductionSecurityConfigurationTest`: freee が無効で4項目が無くても、prod の完全な context が起動する。
  - `FreeeProductionStartupTest`: freee が有効で4項目が無いと、prod の起動が止まる。
- FINAL-FIX までは、freee を使わない配備でも 27〜29 が必須だった。`application-prod.yml` の旧comment「client-id未設定でも起動は可能」は誤りだった。

**設定してはいけない値**（設定すると起動しない）:
- `SPRING_FLYWAY_ENABLED=true`（§4）。
- `LOCAL_LOGIN_ENABLED=true`、`OIDC_ENABLED=false`、`BREAK_GLASS_LOGIN_ENABLED=false`、`OIDC_REQUIRE_MFA_ASSURANCE=false`、`MFA_ENABLED=false`、`MFA_REQUIRED_FOR_BREAK_GLASS=false`、`ALLOW_UNTRACKED_SESSIONS=true`（validator `:119-153`）。
- `server.forward-headers-strategy` を `framework` 以外にする（`ForwardedHeaderProductionValidator.java:77`）。
- `ai.outbound-probe.enabled=true`（`AiOutboundProbeProdGuardConfig.java:18-22`）。
- `AI_EXTERNAL_SEND_ENABLED=true`（B2c-2 の完了まで常に拒否。冒頭の NF08 B2c-1 の項）。
- `freee.*-base-url` を公式host以外にする（`FreeeIntegrationServiceImpl.java:192-195`）。
- `CLOUDSIGN_ENABLED=true` のまま `CLOUDSIGN_CLIENT_ID` を設定しない、または `cloudsign.base-url` を公式のallow-list外にする（`CloudSignProperties.java:86-112`）。
- `BACKUP_QUIESCE_DISABLED_TICKET`（形式 `^[A-Z][A-Z0-9]+-[0-9]+$`）無しの `BACKUP_QUIESCE_ENABLED=false`（31・32。`BackupQuiesceProductionValidator`）。
- `DB_URL` の query または `spring.datasource.hikari.data-source-properties` の `readOnlyPropagatesToServer=false`（driver の展開後の属性を含む。1。`BackupQuiesceProductionValidator`）。
- `BACKUP_QUIESCE_*` を範囲の外・相互の関係に反する値にする（33〜41。`BackupQuiesceProperties`）。

**画面「機能の開放」で対象機能を開く時だけ必須になる配備設定**（閉のままなら不要。署名の後に人が設定する。承認 ID・digest 等の署名・設定へ移した値は配備の設定に置かず、置くと起動しない）:
- bootstrap: `SECURITY_BOOTSTRAP_EXPIRES_AT`（§4.2。初回起動のみ）。
- NF06（`NF06` を開く前に設定）: `IMPORT_ROW_REFERENCE_HMAC_KEY`（secret store から注入。`ImportCenterEnablementConditions` が検査し、欠けても起動自体は止めず `NF06` を開けない。admission の承認は画面の署名 `NF06_ADMISSION` に登録する。`feature-switches.md` §4.8）。
- NF07 保持・保全（`NF07_RETENTION` を開く前に設定）: Journal の接続 `PRIVACY_JOURNAL_DATASOURCE_URL`・`PRIVACY_JOURNAL_DATASOURCE_USERNAME`・`PRIVACY_JOURNAL_DATASOURCE_PASSWORD`（secret store）・`PRIVACY_JOURNAL_JOURNALID`（形式と任意の項目は §3.6）。Journal が使えない（設定の欠落・reader の principal を含む）と `NF07_RETENTION` は開けない・開いていれば自動閉鎖する（起動は止めない。hold の効力確認は reader でも動き、reader で hold の開始・解除を試みると 503 `error.privacy.journalUnavailable` で pending を残さずに終わる）。接続は起動時に要るため、設定の追加は再起動を要する（決定 ID 束縛と DSAR 期限値は画面の組 `NF07_DECISION_BINDING` に登録する）。
- NF07 の副入口 `REL`（hold 解除）・`REG`（policy の登録・有効化）を開ける場合（D-03 = AWS KMS。NF07-TRUST）: `PRIVACY_TRUST_KMS_ANCHOR_FILE`（trust 文書の file の絶対 path）と `PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE`（5分〜31日）。任意で `PRIVACY_TRUST_KMS_RELOAD_INTERVAL`（既定 `30s`、1秒〜5分）。`PRIVACY_TRUST_KMS_ANCHOR_FILE` を設定すると起動時に検査し、設定・文書の構造的不備があれば ERROR `NF07_TRUST_STRUCTURE_INVALID` を出して副入口 `REL`・`REG` だけを閉じたまま起動する（起動は止めず、`NF07_RETENTION` も閉じない。§4.6）。値は secret ではない（×）が、file は EFS に置いて読取り専用で mount し、書込みは D-03 owner の手順に限る。
- NF05（`NF05_INBOUND`・`NF05_OUTBOUND` を開く前に設定）: `INTEGRATION_HUB_PUBLIC_ID_KEY`（〇）、`INTEGRATION_HUB_CRYPTO_CURRENT_KEY_VERSION`（×）と鍵 `INTEGRATION_HUB_CRYPTO_KEYS_<VERSION>`（〇）、`INTEGRATION_HUB_CRYPTO_CREDENTIAL_HASH_PEPPER`（〇。32 byte を `=` 無しの base64url で書き、全 version の暗号鍵と復号後の byte 列が異なる値にする。inbound・outbound のどちらを開くにも必須）、承認された送信元 CIDR・送信先 allow-list 等、および outbound では `INTEGRATION_HUB_TRUSTSTORE_PATH`（×）・`INTEGRATION_HUB_TRUSTSTORE_PASSWORD`（〇）・`integration.hub.provider.mode=HTTPS`。`INTEGRATION_HUB_BOUND_TENANT_ID`（×）は outbound の `NF05_OUTBOUND_OFF` 記録のため、**初回の起動から全ての起動で同じ値**を設定する（§1.7）。決定 ID と `approvalDigest` は配備設定には置かず、画面の署名 `NF05_DG05_IN`・`NF05_DG05_OUT` に登録する（正規形・二人による再計算・golden vector は DG-05 草案 §1.1）。truststore や digest 対象の配備設定を後から変える手順（再起動 2 回）は §5.5.1。

**起動は止めないが運用で設定するもの**:
- **`TZ=Asia/Tokyo`（必須。§4）**: JVM のタイムゾーンを固定する。
  - 未設定だと JVM は UTC で動く。30分の account lock が約9時間30分続き、監査等の日時も9時間ずれる。
  - image（`Dockerfile`）も `TZ` と `JAVA_TOOL_OPTIONS` の `-Duser.timezone=Asia/Tokyo` を持つ。task definition でも `TZ` を設定する。
  - `validate-task-definition.ps1` は、`TZ` が無い場合と、`JAVA_TOOL_OPTIONS` を上書きして `-Duser.timezone` を落とした場合に Fail する。
- **`RESUME_LEDGER_COPY_ALIGNMENT_ENABLED=false`（初回配備の雛形の値）**: V197 の署名まで、副本の保存期限を縮めず、廃棄候補を毎日作り直さない。署名後に `true` にする（§4.3）。
- `FILE_SCANNER_HOST` / `FILE_SCANNER_PORT`: ClamAV。既定は `localhost:3310`。届かないとscanできず、uploadを公開できない。ClamAV は同じ task の sidecar で動かす（決定済み（2026-11-18）。§1.6 の6）ため、雛形は `FILE_SCANNER_HOST=127.0.0.1`・`FILE_SCANNER_PORT=3310`。
- `METRICS_SCRAPER_ENABLED` / `METRICS_SCRAPER_USERNAME` / `METRICS_SCRAPER_PASSWORD`: metricsの取得用。adminのHTTP Basicは常に401になる（§1.7 SRE-9）。scrape は ALB 経由の HTTPS で scraper 口座を使う（決定済み（2026-11-18）。§1.6 の7）ため、雛形は `METRICS_SCRAPER_ENABLED=true`、username が placeholder、password が secret。
- **`UPLOAD_BASE_PATH=/app/uploads/current`（必須。決定済み（2026-11-18）。§1.6 の11 (f)）**: app の upload root（`app.upload.base-path`。既定 `./uploads`）を、uploads の access point の root の下の相対 symlink `current` にする。restore の後、停止中に1回の rename で版を切り替えるためである。未設定だと app は mount root（`/app/uploads`）を直接使い、切り替える pointer が無くなる。`current` は app の初回起動より前に作る（§1.6 の11 (f)。確認は §4）。`validate-task-definition.ps1` は雛形・実値のどちらの mode でも、この名前と値が大文字小文字・空白まで一致しないとき（未設定、secrets に置いた場合、`APP_UPLOAD_BASE_PATH` 等の正規名や `SPRING_APPLICATION_JSON` で上書きした場合を含む）に Fail する。
- `SMTP_HOST` 等: 通知メール。

**環境変数ではないが起動時に検査されるDBの状態**: 次は §5 の手順で揃える。bootstrap中は、break-glass管理者とOIDC管理者の人数の検査を省略する（§4.2）。
- 有効なOIDC provider（`m_identity_provider`）。
- OIDCでログインできる管理者3名以上。
- default permission group 5件。
- 全体baseline `'*'` を持つgroupの `privacy.*` の拒否行。

### 1.1 DBサーバー（DBA）

- **MySQL 8.4 LTS**（決定済み（2026-11-18）。§1.6 の3・DBA-4。exact 版の候補は 8.4.13。8.0 系の community support は 2026-04-30 に終わった（同日の決定の根拠））。
  - 試験の現状（事実）: 実 MySQL の試験の image は `src/test/java/com/ses/test/MySqlTestImages.java` の1か所で決まり、既定は `mysql:8.4.13`（RB-MYSQL84。#122 で合入済み）。CI の `mysql-test` の shard は既定のまま動き、証拠の step が image の digest を記録し、既定以外の MySQL image があれば失敗する。CI の `backup-integration` も digest 固定の `mysql:8.4.13` で動く（`ops/backup/tests/docker-compose.integration.yml`。`MySqlTestImagesTest` が既定以外の image を拒否する）。
  - **backup tool の 8.4 への適合（RB-W）は合入済み**: tool image の client・mysqlbinlog・mysqldump は 8.4.13（component `mysql-8.4-lts`）、`ops/backup/preflight.sh` は server と client が 8.4 系列であることを確かめ、full の起点は `CHANGE REPLICATION SOURCE TO` から読み、`restore.sh` は `RESET BINARY LOGS AND GTIDS` を使う（MySQL 8.4.0 の release notes: `RESET MASTER` は `RESET BINARY LOGS AND GTIDS` に、`SHOW MASTER STATUS` は `SHOW BINARY LOG STATUS` に置き換えられ、`PURGE MASTER LOGS`・`SHOW MASTER LOGS` とともに削除された。8.2.0 から deprecated。https://dev.mysql.com/doc/relnotes/mysql/8.4/en/news-8-4-0.html 、https://dev.mysql.com/doc/refman/8.4/en/reset-binary-logs-and-gtids.html ）。MySQL 8.4 での backup・PITR は、CI の backup integration（実 MySQL 8.4.13）で取得した（2026-11-21）。ただし **本番相当の実測は未取得** で、取得するまで本番での 8.4 の backup・PITR は未検証として扱う（§1.7 の W 表の前の段落。記述はそこに1つにまとめる）。初回 backup の `preflight` の出力（§1.7 の「Backup 開始の前提」の 001）が 0 で終わることを §2 の5 で確かめる。
  - recovery target（restore 先）は本番と**同じ exact 版・同じ `lower_case_table_names`** にする（backup tool は backup 時と復元検証時の server の版や `lower_case_table_names` が違うと schema fingerprint が一致せず FAIL にし、cutover できない。DBA-4）。
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
  - **trigger（73件）とbinlog**: binlogが有効で `log_bin_trust_function_creators = 0` のとき、`CREATE TRIGGER` はSUPER相当の権限を要求する。RDS等でSUPERを渡せない場合は parameter group で `log_bin_trust_function_creators = 1` にするか、DBAが代替を決める。**testは `--skip-log-bin` かつrootで実行しており、この条件は検証していない**（配備前のDBA項目。§1.7 の DBA-1。W1-B3 review §9.4）。
    - 件数の根拠: 73件は、versioned migration の `CREATE TRIGGER` と `DROP TRIGGER` を版の順に適用した正味の件数で、`DeployRunbookMigrationCountTest` が本書の値（ここと §3.4）と照合する。V194までは55件で、2026-11-14 の R-3 予行演習（MySQL 8.0.36・offline job）の実測と一致する。V195 は `CREATE TRIGGER` 12件・`DROP TRIGGER` 0件で、不変6表の UPDATE・DELETE に各1件付く（実MySQLでは `AuthorizationEpochSchemaMySqlTest`（mysql shard 1）が表と event の組を確かめる）。V203 は `CREATE TRIGGER` 6件・`DROP TRIGGER` 0件で、追記専用の3表（`t_feature_enablement_event`・`t_feature_sign_off_bundle_member`・`t_feature_sign_off_extension`）の UPDATE・DELETE に各1件付く（実MySQLでは `FeatureEnablementSchemaMySqlTest`（mysql shard 1）が拒否を確かめる）。V195・V203を含めた総数の実測は**未取得**で、配備前の DBA-1 の検証（§1.7）と §3.4 で確かめる。
  - **DEFINER**: triggerは `DEFINER = migrator` で作られる。migrator accountを削除・lockするとtriggerの付いた表への書き込みが `ERROR 1449`（definer不在）で失敗する。migrator accountは残す（loginだけ無効化する等はDBAが判断する）。
  - binlogが有効で `enforce_gtid_consistency = ON` の場合、transaction内の一時表はbinlog_format=ROW等の条件が要る。DBAが設定を確認する。
  - 確認（DBA。job の前に、権限を見られる管理用の account で実行する。`<migrator>`・`<host>` は実際の account）:
    ```sql
    SHOW GRANTS FOR '<migrator>'@'<host>';
    -- 期待: GRANT USAGE ON *.* と、`ses_manager_db`.* への上の14権限だけ（DBA-1 で SUPER 相当を渡すと決めた場合はその行も）。
    --       ALL PRIVILEGES、*.* への DDL、他の schema への権限が無い
    SELECT COUNT(*) FROM mysql.role_edges WHERE to_user = '<migrator>' AND to_host = '<host>';
    -- 0。0 でなければ SHOW GRANTS FOR '<migrator>'@'<host>' USING <role> で role の権限も確かめる（USING 無しでは role の権限が出ない）
    SELECT @@global.mandatory_roles;
    -- 空。値があれば、その role は全 account に付くため、その権限も上と同じく確かめる
    SELECT @@global.log_bin, @@global.log_bin_trust_function_creators, @@global.binlog_format,
           @@global.gtid_mode, @@global.enforce_gtid_consistency;
    -- 期待: binlog_format = 'ROW'（DBA-3）。log_bin = 1 なら、log_bin_trust_function_creators = 1 か migrator の SUPER 相当（DBA-1 の決定どおり）
    ```
- **アプリ account**: 対象schemaへの `SELECT, INSERT, UPDATE, DELETE` だけ。DDL権限は渡さない。起動時schema guardが `flyway_schema_history` を読むため、そのSELECTも必要（schema単位のSELECTで足りる）。
  - 確認（DBA。配備前）:
    ```sql
    SHOW GRANTS FOR '<アプリaccount>'@'<host>';
    -- 期待: GRANT USAGE ON *.* と、`ses_manager_db`.* への SELECT, INSERT, UPDATE, DELETE だけ。PROCESS・DDL・他の schema への権限が無い
    SELECT COUNT(*) FROM mysql.role_edges WHERE to_user = '<アプリaccount>' AND to_host = '<host>';   -- 0（0 でなければ USING で role の権限も確かめる）
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
    - DBA が配備前に確認する（§1.7 NF06 行。未取得）。
  - **backup の書込み静止の制御用・heartbeat 用の接続も同じアプリ account を使う**（RB-QUIESCE。backup-write-quiesce design.md §4・§5.2）。静止応答は業務の pool と別の小さな pool（`ses-backup-quiesce-control` 最大2、`ses-backup-quiesce-heartbeat` 最大1。業務の pool とは別に、task ごとに最大3本の接続が加わる。`max_connections` の見積りに足す）を持つが、user は `DB_USERNAME` と同じで、別の user を作らない（別にすると SRE-5 の (a)・上の processlist の確認・NF06 の照合・静止応答の接続元の観測が崩れる）。
  - **アプリ account の host の型は1つにする**（RB-QUIESCE）。PROCESS の無い account に見えるのは同じ account（`user@host` の組）の thread だけで、同じ user 名でも host の型が違う別の account の thread は見えない。静止応答は同じ account の接続元を観測して応答に書き、backup の provider は観測した接続元が応答した instance の接続元だけであることを確かめる（同 design §5.4）。DBA が配備前に確かめる（§1.7 の 004a）。
    ```sql
    SELECT user, host FROM mysql.user WHERE user = '<アプリaccount>';   -- 1行
    ```
  - **password の rotation**（決定済み（2026-11-18）。§1.6 の9）: 手動の single-user rotation と MySQL の二重 password で行う。保守の時間帯に、release の節目に合わせて行う。DBA が general log を無効にした接続で実行し、password は記録しない。
    ```sql
    -- 1. 新しい password を足す（古い password も有効なまま残る）
    ALTER USER '<アプリaccount>'@'<host>' IDENTIFIED BY '<新しいpassword>' RETAIN CURRENT PASSWORD;
    -- 2. secret store の値を新しい password に替え、全 task を再起動する（ECS は secret を起動時に環境変数へ入れるため、
    --    再起動するまで app は古い password で接続を張る。in-memory の session は全員 logout になる）
    -- 3. 全 task が再起動した後（processlist のアプリaccount の接続が全て再起動後のもの）に古い password を消す
    ALTER USER '<アプリaccount>'@'<host>' DISCARD OLD PASSWORD;
    ```
  - **NF05 の起動の前の読取り**（機能の開放。feature-enablement-console design §9.1・§9.6。FE-D02）: prod の起動のたびに、Spring の context を作る前（`EnvironmentPostProcessor`）に、同じアプリ account（`DB_USERNAME`・`DB_PASSWORD`。新しい権限・新しい account・新しい secret は要らない）で JDBC の接続を1本だけ開き（pool を作らない。接続・socket・照会の timeout は5秒、read only）、`t_feature_enablement_state`（NF05 の2行）・`t_feature_sign_off`（NF05 の確認済みの署名）・`m_system_config`（barrier の行）・`t_audit_log`（`reference_type='NF05'` の `NF05_OUTBOUND_OFF` の有無。`idx_auditlog_reference` を使う）・NF05 の保存期間の対象の8表（`t_api_delivery`・`t_api_delivery_replay_audit`・`t_api_nonce_replay`・`t_api_read_snapshot`・`t_inbound_event`・`t_api_idempotency_record`・`t_inbound_event_replay`・`t_external_api_audit`。`LIMIT 1` の有無。review rb-fe-d P3-8・P3-N2）を読んで閉じる。書かない。schema の検査（`ProductionSchemaVersionGuard`）より前に動くため、表・列が無い（artifact と schema が違う）時と接続の失敗は NF05 を閉じたまま起動する（`READ_FAILED`。WARN `errorCode=NF05_BOOT_READ_FAILED`。app はその後 schema の検査で止まる）。上の processlist の確認では、起動の直後にアプリ account の短い接続が1本増える（照会の timeout の時は Connector/J が照会を止めるため別の接続で `KILL QUERY` を送ることがあり、その間は2本になる）。最悪の所要は接続の5秒と照会の全体の期限20秒の約25秒で、ECS の `healthCheck.startPeriod` はこれを含めて余裕を取る。失敗の log は SQLState と vendor の code を出す（review rb-fe-d P3-9）。**DBA と Security の受理: 未取得**（Spring context の前にアプリ DB account で短い読取り接続を開くこと・接続の本数と timeout。§1.7 の DBA-1・Security の確認事項）。
- **backup 用の account**（正本は `ops/backup/README.md`「権限分離」の2つの表。権限の一覧はそこから写さず、表のとおりに与える）:
  - **source 側の account**（「source 側の account の権限」）: 対象 DB の `SELECT`・`SHOW VIEW`・`TRIGGER`・`EVENT`、global の `SHOW_ROUTINE`・`RELOAD`・`REPLICATION CLIENT`・`REPLICATION SLAVE`。**`PROCESS` は与えない**（mysqldump に `--no-tablespaces` を付けるため要らない。2026-11-18 利用者決定）。dump role と binlog role の分離は上線後で、現状は1つの account が表の全権限を持つ。
    - **書込み静止の要求の表への表単位の `INSERT`・`UPDATE`**（RB-QUIESCE。U-11 決定済み（2026-11-21）。README の同じ表の行）: `GRANT INSERT, UPDATE ON ses_manager_db.sys_backup_quiesce_request TO '<backup account>'@'<host>';`。provider（`quiesce-db`・`quiesce-app-stopped`）が静止要求の登録・lease の延長・verify の要求・解除に使う。信号表の他の2表（`sys_backup_quiesce_instance`・`sys_backup_quiesce_ack`）は schema 単位の `SELECT` で読む。他の表への書込み・`DELETE`・`PROCESS` は足さない（`GET_LOCK` は権限が要らない）。専用の account は作らない。
    - **付ける時期は §3.4**（V202 が表を作った後・初回 backup（§2 の5）の前）。存在しない表への表単位の GRANT は通らないため（CI の MySQL 8.4.13 で拒否を実測した。2026-11-22。backup-write-quiesce design.md §19・§1.7 の BQ-5）、配備の前の DBA-1 の確認には含めない。restore で新しい source にした後も、backup の再開の前に付け直す（`mysql.tables_priv` は dump に入らない。`ops/backup/runbooks/restore-cutover.md` §4.6）。
    - backup account が業務 DB の1表の書き手になることは baseline の PROD-002 に記録し、Security と DBA が署名する（§1.7 の BQ-1。**未取得**）。
  - **restore target role**（「restore target role の権限」。recovery target 側）: 対象 DB の `CREATE`・`DROP`・`ALTER`・`INDEX`・`REFERENCES`・`SELECT`・`INSERT`・`UPDATE`・`DELETE`・`LOCK TABLES`・`CREATE VIEW`・`SHOW VIEW`・`TRIGGER`・`CREATE ROUTINE`・`ALTER ROUTINE`・`EXECUTE`・`EVENT`、`ses_recovery_control.*` の `ALL`、global の `RELOAD`・`SYSTEM_VARIABLES_ADMIN`・`REPLICATION_APPLIER`・`SHOW_ROUTINE`・`SET_ANY_DEFINER`（DEFINER の account が復元先に無いときは `ALLOW_NONEXISTENT_DEFINER` も）。recovery target の前提は DBA-1・DBA-4。
  - 確認は上と同じく `SHOW GRANTS FOR '<backup account>'@'<host>';` と `mysql.role_edges`（DBA-1 と同じ日に DBA が確かめる。要求の表への表単位の権限は §3.4 で付けた後に確かめる）。preflight が検査する権限は要求の表への表単位の `INSERT`・`UPDATE` だけで（`QUIESCE_PROVIDER` が `quiesce-db` 等の DB の provider のとき。無ければ exit 19 で `checks.quiesce.request_table_grant` が `false`）、他の権限は検査しないため、不足は最初の full・checkpoint・archive の失敗で分かる（本番相当の権限での実測は**未取得**。provider の SQL が README の権限だけで動き、業務の表と応答の表へは書けないことは `BackupQuiesceProviderContractMySqlTest`（mysql shard 3）が確かめる。CI で取得済み（2026-11-22、PR #128 の run 44979797399 の mysql shard 3、mysql:8.4.13。§1.7 の BQ-5））。

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

- jobはsource checkoutで `compile` したclass/resourceを使う。**jobのcommit SHAと、配備するimageのSHAを一致させる**。
- **image の配布**（決定済み（2026-11-18）。§1.6 の5）: アプリ image は ECR の private repository へ push し、task definition では registry digest（`<registry>/<repository>@sha256:<64桁>`）で指す。tag では指さない。docker save/load と digest の記録（決定 XB-10）は backup tool image だけの決定である。ClamAV の sidecar の image も ECR へ写して digest で指すのは、RB-DEPLOY の初期設計である（決定には含まれない。SRE が確かめる。§1.6 の6）。
  - 配備記録には、次を1行にまとめて残す: 配備する commit SHA（`git rev-parse HEAD`）、`infra/ecs/image-lock.json` の `gitRevision`・`jarSha256`、ECR の repository と tag（commit SHA を tag にする）、push 後の registry digest、ClamAV の image の digest、登録した task definition の revision。
  - 照合: task definition の image の digest が記録の digest と同じで、ECR でその digest に commit SHA の tag が付いていること（クラウドの管理画面／CLI で、repository の image 一覧から digest と tag を見る）。tag の付け替えを防ぐため、repository の tag の不変（immutable）の設定を SRE が確かめる。
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
3. MySQLの版・collation・parameter group（DBA。DBA-4）。**版は決定済み（2026-11-18）: 8.4 LTS（exact 版の候補は 8.4.13）**。試験側（RB-MYSQL84。#122）と backup tool 側（RB-W）の 8.4 への適合は合入済み。8.4 での backup・PITR は CI の backup integration（実 MySQL 8.4.13）で取得済みで、本番相当の実測は**未取得**（§1.1、§1.7 の W 表の前の段落）。collation・parameter group は DBA-4 で確定する。
4. job実行環境（Maven・JDK・依存取得経路。§1.5のlocal repositoryの準備方法）（SRE。SRE-11）。
5. **アプリ image の配布経路**（SRE）: **決定済み（2026-11-18）**。ECR の private repository と registry digest で配る（Fargate）。docker save/load の XB-10 は backup tool image だけに適用する。記録と照合の方法は §1.4。ECR は新しく作る基盤である（それまでは private registry が無かった）。
6. **ClamAV の置き方**（SRE）: **決定済み（2026-11-18）: 同じ task の sidecar（`clamav`）で動かし、task の memory を増やす**。そのため `FILE_SCANNER_HOST=127.0.0.1`・`FILE_SCANNER_PORT=3310`。`infra/ecs/task-definition.prod.json`・`validate-task-definition.ps1`・`ContainerHealthCheckCommandGuardTest`・`ProductionTaskDefinitionTemplateTest` が下の形を検査する。
   - **RB-DEPLOY の初期設計（決定には含まれない。SRE が確かめ、実測で見直す）**: task 2048 CPU / 6144 MiB（app 1024 / 2048 の hard limit、clamav 1024 / 4096）。sidecar は essential にしない（clamd が止まっている間、app は動き続け、upload は quarantine に留まる）。clamav の root filesystem は読取り専用で、書込み先は自分の bind volume（`clamav-db` = `/var/lib/clamav`、`clamav-run` = `/run/clamav`、`clamav-log` = `/var/log/clamav`、`clamav-tmp` = `/tmp`。Fargate の ephemeral storage）だけ。uploads・privacy-trust・app の `tmp` は mount しない。secret と app の設定を渡さず、port を公開しない。image は ECR へ写して digest で指し、health の command は `clamdcheck.sh`（公式 image の script）。
   - 分かっていること: clamd は signature を全て memory に載せ、再読込の間は新旧の2組を持ち得る。signature の取得（freshclam）には、ClamAV の配布元か社内 mirror への外向きの経路が要る。task の再起動で signature は消え、起動のたびに取り直す（取り終わるまで upload は quarantine のまま）。clamd の INSTREAM は暗号化されないため、clamd は task の中の loopback だけで受ける。
   - **clamd が止まっていた間の upload は、復旧しても自動では再scanされない**。clamd の復旧（signature の読込み完了）の後、管理者が quarantine の file を再scanする（`POST /api/files/{storedName}/rescan`。権限は管理者だけ（`file.scan.retry`）。server が生成した file は対象外で、生成し直す）。対象は次で一覧する（DBA の確認SQLの範囲の SELECT）。
     ```sql
     SELECT stored_name, file_kind, created_at FROM t_file_security_metadata
      WHERE tenant_id = 'default' AND storage_state = 'QUARANTINED' AND scan_status = 'UNAVAILABLE' AND deleted_flag = 0
      ORDER BY created_at;
     ```
     画面は無いため、管理者の session の開発者 console で `await SES.api.post('/api/files/' + encodeURIComponent(<stored_name>) + '/rescan', {})` を1件ずつ実行する（`SES.api` が CSRF token を付ける）。
   - **SRE が確かめること（未取得）**: 上の初期設計の値（memory の実測と task の大きさ、essential にしないこと）、signature の取得経路（NAT・proxy・社内 mirror）と更新の頻度、起動から signature を読み終わるまでの時間（health check の `startPeriod` 600秒で足りるか）、写した image に health の command があること、写した image の `clamd.conf` で `TCPAddr 127.0.0.1` に固定すること（既定は全 interface で受ける。task の security group は ALB からの 8080 だけを通すため外からは届かないが、固定する）、読取り専用の root filesystem と上の4つの書込み先で clamd・freshclam が起動すること（他に書込み先があれば起動に失敗する）、clamd が止まったまま戻らないときの検知と再起動の方法（essential でない container は ECS が自動で再起動しない）、image（engine）の更新の頻度と ECR への写し方。
7. **Prometheus の scrape 経路**（SRE。SRE-7 の(4)・SRE-9）: **決定済み（2026-11-18）**。ALB 経由の HTTPS で scraper 口座（`METRICS_SCRAPER_*`）を使う。設定する項目は SRE-9。sidecar の経路は使わない。
8. **`LOCK_CONFLICT` の alert の閾値と通知先**（SRE。SRE-8）: **決定済み（2026-11-18）**。CloudWatch Logs の metric filter で件数を数え、5分間に5件以上で運用担当へ通知し、日次で集計する。運用2週間後に実測で閾値を見直す。通知先（SNS topic 等）は SRE の配備項目。設定の例は SRE-8。
9. **アプリ account の password rotation の方式**（DBA。§1.2）: **決定済み（2026-11-18）**。手動の single-user rotation と MySQL の二重 password（`RETAIN CURRENT PASSWORD` → secret を替えて全 task を再起動 → `DISCARD OLD PASSWORD`）。保守の時間帯に release の節目に合わせて行う。手順の SQL は §1.2。
10. **RPO 15分・RTO 4時間の署名**（DBA-5）: **決定済み（2026-11-18）**。配備日に目標値として署名し、達成は restore drill の実測待ちと注記する。署名そのものは**未取得**。
11. **NF07 の trust 文書の置き方**（SRE・D-03 owner）: **決定済み（2026-11-18）**。EFS の volume に置き、app は `/app/privacy-trust` に読取り専用で mount する（task definition の `privacy-trust`。uploads と別の access point。`containerPath` に `/`・`/app`・`/app/config*`・`/app/uploads*` は使えず、`validate-task-definition.ps1` が FAIL にする）。file は D-03 owner が更新する（app からは書かない）。初回の起動（bootstrap。§4.2）は `PRIVACY_TRUST_KMS_ANCHOR_FILE` を空のまま行う（`validate-task-definition.ps1` の `-Phase Initial` が強制する）。NF07 の副入口 `REL`（hold 解除）・`REG`（policy の登録・有効化）を開ける前に、`-Phase Steady` の task definition でその mount の下の file の絶対 path と `PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE` を設定して再起動する（trust 文書と D-03 の署名は**未取得**。NF07-TRUST の手順と設定の正本は §4.6）。mount の path（`/app/privacy-trust`）と uploads と別の access point にすることは RB-DEPLOY の初期設計である。
   - **SRE と D-03 owner が確かめること（未取得）**: D-03 owner が書くための別の経路（書込みのできる access point と、そこへ届く管理用の端末）、書くときは一時 file に書いてから rename で置き換えること、EFS の backup の扱い。Fargate の EFS volume で NFS の mount option（`soft`・`timeo` 等）を指定できるかを確かめ、指定できない場合は、EFS の障害時に trust 文書と uploads の読取りが止まり得ることと `PRIVACY_TRUST_KMS_RELOAD_INTERVAL` の扱いを評価する（NF07-TRUST の節の mount option の確認項と合わせる）。
   - **Security が確かめること（未取得）**: app の読取り専用の mount は app の container だけの制約で、IAM の境界ではない。ECS は task role の credential を task の中の全 container（信頼できない upload を扱う clamav の sidecar を含む）へ渡し、trust 文書は EFS に書ける者が anchor を足せる。一方で、backup tool は uploads を読み、同じ file system に staging を書き、restore は uploads へ書き戻す。そのため次を確かめる。
     - (a) EFS の file system policy で、`elasticfilesystem:AccessPointArn` を条件に access point ごとに許す。`ClientWrite` は task role（uploads の access point）、backup の role（backup の access point。(d)）、restore の主体（uploads の access point。この file system へ書き戻すときだけ。(f)）、D-03 owner の書込み用 role（trust の書込み用の access point）に限る。trust の読取り用の access point と file system の root（access point を通らない mount）への `ClientWrite` は誰にも与えず、trust の書込み用の access point への `ClientWrite` は D-03 owner の role だけに与える。task role には trust の access point へ `ClientMount` だけを与える。identity policy 側にも file system 全体への `ClientWrite` を付けない（同じ account では identity policy 側の Allow でも通り得るため、両方を見る。下の例は明示の Deny でも塞ぐ）。access point を作れる権限（`elasticfilesystem:CreateAccessPoint`）と、file system policy を書き換え・消せる権限（`elasticfilesystem:PutFileSystemPolicy`・`elasticfilesystem:DeleteFileSystemPolicy`）を持つ主体も確かめる（trust の directory を root にする access point を新しく作られる、または policy そのものを変えられると、下の条件では止まらない）。
     - (b) trust の access point（読取り用・書込み用。どちらも root は `/ses-manager/privacy-trust`）の root は、他のどの access point の root とも包含関係に無い。uploads と backup の access point の root は互いを含んでよい（例: backup は `/ses-manager/files`、uploads は `/ses-manager/files/uploads`。(d)）。
     - (c) task role は最小の権限にする（app は AWS の API を呼ばない。上の EFS の権限の他に要るものは、使う場合の ECS Exec 等に限る）。
     - (d) backup の経路（HFP-03-PROD-003 と一緒に決める。§1.7 の「Backup 開始の前提」）:
       - backup tool は書込み静止の間に uploads を staging（`UPLOADS_STAGING_PARENT`）へ `cp -a` で写す（`ops/backup/backup-full.sh` の `uploads-local.sh snapshot` の呼出し。15分ごとの checkpoint も同じ。`create-checkpoint.sh` の同じ呼出し）。uploads と staging は同じ filesystem でなければならず、この判定は2つの path の device 番号（`stat -L -c %d`）の一致で行う（`providers/uploads-local.sh:96-106`。preflight も `UPLOADS_STAGING_PARENT` が設定されていれば同じ判定をし、違えば exit 16。`preflight.sh` の uploads topology の検査）。同じ EFS でも access point を別々に mount すると mount ごとに device 番号が分かれる見込みで（Linux の NFS client と efs-utils の挙動からの推定。実測は未取得）、uploads と staging を別の mount から渡すと初回 backup が FAIL する。そのため uploads と staging は**同じ mount** から backup の container へ渡す。
       - backup は access point を1つだけ使う。例: backup の access point の root を `/ses-manager/files` とし、その下に `uploads/`（app の uploads の access point の root）と `backup-staging/` を置く。trust（`/ses-manager/privacy-trust`）はこの下に置かない（(b)）。backup の host はこの access point を1回だけ IAM で `/mnt/ses-files` に mount し（例: efs-utils の `tls,iam,accesspoint=<fsap-backup-files>`）、compose の uploads（`:ro`）と staging には、その mount の下の2つの directory を bind で渡す（同じ mount の下なら device 番号は同じ）。mount 先の `/mnt/ses-files` は `ops/backup/docker-compose.yml` が固定で使う path で、変えるなら compose も同じ change で変える。host にこの mount が無いまま起動すると docker が空の directory を作り、backup と preflight は「current がありません」で失敗する（fail-closed）。staging は `backup-data`（container の `/var/lib/ses-backup`。named volume で、uploads とは別の filesystem）の下に置かない。
       - 対応（`ops/backup/docker-compose.yml:45-46` が固定で bind する）: host の `/mnt/ses-files/uploads` → container の `/app/uploads`（`:ro`）、host の `/mnt/ses-files/backup-staging` → container の `/var/lib/ses-uploads-staging`。`.env.backup` は `UPLOADS_ROOT=/app/uploads`・`UPLOADS_POINTER=current`・`UPLOADS_STAGING_PARENT=/var/lib/ses-uploads-staging` にする。`UPLOADS_DIR` は廃止した（残っていると full・checkpoint・preflight が失敗する）。`current` の解き方は (f)。`.env.backup` の key と compose の書き方の正本は `ops/backup/README.md`「設定」「uploads の配置と mount」。
       - backup の access point の POSIX user（uid・gid）は、uploads の access point と同じ app の uid・gid にする（**決定済み（2026-11-18）**。例: image の `ses` user の uid 10001 とその gid。`Dockerfile:12,21`）。backup の access point の root は uploads を含み、POSIX user も同じため、backup の host は EFS 上の uploads を app と同じ権限で読み書きできる。backup が uploads を変えないことは、IAM でも POSIX の権限でも止まらず、compose の uploads を `:ro` で bind すること（container の中だけの制約）と、backup の host の運用上の管理（host に入れる者、host 上の mount の扱い）だけによる。これは Security が受け入れる事項である（**未取得**）。
       - 同じ uid のため、次は問題にならない。app は普通の file・directory を container の umask に従って作るが、取込の照合台帳（`ImportStageReconciliationLedger` の書込み）と取込の一時 file（`ImportRawTempFileStore.create`）は `Files.createTempFile` のため 0600 になる。backup は同じ uid でこれも読める。backup の container は root で動き（`ops/backup/Dockerfile` に `USER` が無い）、`cp -a` は所有者を保てないと失敗するが、staging の file は同じ uid・gid で作られ、同じ値への chown になる。restore の書き戻しや backup tool の `umask 077` で 0700 になった directory も、同じ uid のため backup と app が読める。
       - 目録（例）: `/ses-manager/files`（backup の access point の root）、`/ses-manager/files/uploads`（uploads の access point の root）、`/ses-manager/files/backup-staging`、`/ses-manager/files/uploads/v0` と `current -> v0`（(f)）。owner はどれも app の uid・gid、mode は例えば 0750（`backup-staging` は 0700）。
         - 誰が・いつ: storage owner（担当: production owner（利用者）。決定済み（2026-11-22、利用者））が、§2 より前（初回 backup（§2 の5）と app の初回起動（§4）より前）に作る。
         - 作り方: access point の root は各 access point の CreationInfo（owner・mode）で作る（EFS は、無い root directory を、その access point を通した最初の mount で作る。`/ses-manager/files` から作るため backup の access point を先に使う。親の directory が無い場合の作られ方は SRE が確かめる）。`backup-staging` は backup の host から backup の access point を通して作る。`v0` と `current` は uploads の access point を通して作る（(f)）。
         - file system policy との関係: 当てる前は IAM で mount できる者なら誰でも書ける。当てた後は access point を通らない書込みが誰にもできず（`DenyWriteWithoutAccessPoint`。`ClientRootAccess` を持つ主体も無い）、uploads の access point へ書けるのは task role と restore の主体だけになる（下の例）。上の作り方はどれも access point を通すため、policy の前後で同じ手順で作れる。policy を当てた後に `v0`・`current` を作る・確かめるときは restore の主体で mount する。
       - **SRE が staging で確かめること（未取得）**: compose で渡した uploads と staging の device 番号が一致すること（host で `stat -L -c %d /mnt/ses-files/uploads/current /mnt/ses-files/backup-staging`。別の access point を別々に mount すると一致しないことも記録する）。container の中の証拠は `ops/backup/image/tool-image.sh compose run --rm preflight` の JSON の `checks.uploads` で、期待は `status: "ok"`・`version: "v0"`（初回）・`staging_same_filesystem: true`。0600 の file（NF06 の取込台帳）と restore の後の 0700 の directory を含む uploads で backup（full・checkpoint）が通ること、container の中の uploads が読取り専用であること（書込みが `Read-only file system` で失敗すること）、空の uploads（初回の `v0`）で backup が通ること（RB-W で修正済み（X-1）。staging での実測は未取得）。
     - (e) 設計の選択肢（SRE・Security が確かめる。未決）: trust 文書を uploads と別の EFS file system に置く。backup・restore の主体が trust の file system に一切触れず、backup は uploads を含む上の directory の access point を mount しても trust に届かないため、policy も単純になる。その場合 trust の file system の backup は別に決める（EFS の backup か、D-03 owner の手元の正本から書き直す）。選ぶ場合は task definition の `privacy-trust` の `fileSystemId` を別の file system にする。validator の実値の mode はそのまま通るが、雛形（`infra/ecs/task-definition.prod.json`）は2つの volume が同じ `fs-PLACEHOLDER` で、validator の Template mode もそれを求める（`validate-task-definition.ps1:270`）ため、雛形の placeholder と validator を一緒に変える。
     - (f) restore の書き戻しと uploads の pointer（HFP-03-PROD-003）: **決定済み（2026-11-18）: A（`current` symlink）**。
       - app の upload root は `UPLOAD_BASE_PATH=/app/uploads/current`（§1.0。雛形に入れ、validator が両方の mode で一致を求める）。`current` は uploads の access point の root の下の symlink で、version directory を指す（初回は `current -> v0`）。restore は新しい version directory（例 `v1`）を uploads の access point から書き（`ops/backup/uploads-cutover.sh stage`）、app と scheduler の停止中に1回の rename で切り替える（`uploads-cutover.sh switch`。中身は `ln -sT` の一時 symlink と `mv -T`。戻すときは逆向きの switch）。DB の接続先の切替えと同じ change で行う（`database-backup-recovery/requirements.md:130,133`、同 `design.md` §10）。DB の cutover・rollback との順序と、起動前の DB と pointer の対の確認は `ops/backup/runbooks/restore-cutover.md` §4.5.1 を正本とする（switch は `cutover.sh` の前、rollback は `rollback-cutover.sh` と逆向きの switch を対で、`write-enabled` の後は uploads も戻さない）。
       - symlink の前提: 相対で単段の名前（`^[A-Za-z0-9][A-Za-z0-9._-]*$`。`/` と `..` を含まない）。絶対 path は backup の host や container で別のものを指し、`..` は解いた側の namespace で mount の外へ出る。app の path の検査は字句の比較（`toAbsolutePath().normalize()` と `startsWith`）で、`current` は kernel が解く。
       - 初回の配備: app の**初回起動より前に**、uploads の access point を通して空の `v0` と `current -> v0` を作る（作る者・時期・policy との関係は (d) の目録）。app は起動時に `<upload root>/documents/quarantine` 等を `createDirectories` で作る（`LocalDocumentStorage.java:34-35`）ため、`current` が無いまま起動すると `current` が実体の directory として作られ、pointer が黙って失われる。確かめ方は §2 の5・§4（`current` が symlink で、指す先が単段の名前の実体の directory であること。下の command か `ops/backup/uploads-cutover.sh status --root <uploads の根>`）。validator は EFS の中身を見ない。
         ```bash
         t=$(readlink /mnt/ses-files/uploads/current) && [[ "$t" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
           && test -d "/mnt/ses-files/uploads/$t" && ! test -L "/mnt/ses-files/uploads/$t" && echo "current -> $t OK"
         ```
       - app 側の事実: DB に保存される path（`t_contract_document.pdf_path` は upload root を頭に付けた path。`ContractDocumentServiceImpl.create`）は `/app/uploads/current/...` になり、切り替えても有効なまま。孤児 file の掃除（`FileCleanupServiceImpl.cleanupOrphanFiles`、`:53-91`）と旧 upload の移行（`LegacyUploadMigrationService.migrate`、`:84`）は `current` の先（その時の version）の直下・`quarantine`・`published` の regular file だけを再帰せずに見るため、兄弟の旧版は走査されず消されない。
       - backup・restore・cutover の実装（RB-W。合入済み）: backup は `current` がその時に指す version directory を写し、どの version かを metadata・index・結果に `uploads_pointer` として残す（`lib/uploads-pointer.sh`）。`current` は container の中の `:ro` の bind で単段の名前として解き、host の側では symlink を辿らない（compose の bind 元に `current` を含めない）。version directory の実 path を固定で渡すと、切替えの後に直し忘れたとき旧版を黙って写し続けるため、そうしない。restore は `restore.sh` が作業 directory に `uploads_ready` を作り、backup した時の pointer と版を `uploads_backup_pointer` として出す（`restore.sh:240-272`）。本番の uploads へは `uploads-cutover.sh` の stage（新しい version directory を作る）・switch（pointer を切り替える）・status（確かめる。読むだけ）で出す。手順と順序の正本は `ops/backup/runbooks/restore-cutover.md` §4.5.1、command の性質は `ops/backup/README.md`「uploads の版の切替え」。旧版と残りの削除は §4.4 の D15。
       - backup の role と restore の主体は別の role にする（同じ role にすると、下の例の `DenyBackupWriteOutsideFiles` と `DenyRestoreWriteOutsideUploads` が全ての書込みを止める）。restore の書き戻し先が別の file system（recovery target 側）なら、restore の主体の許可はその file system の policy に置く。
       - 採らなかった選択肢:
         - B（access point を pointer にする）: 切り替えるたびに uploads の access point を作り直し、file system policy を書き換え（`TaskWritesUploads` 等の条件が uploads の access point の ARN を持つため `PutFileSystemPolicy` が要る）、ECS の revision を作り、restore 専用の access point も要る。RTO の中で IAM・ECS・EFS を変えることになる。また `UPLOAD_BASE_PATH` を使わない構成のため、後から A・C に移ると既存の file の移動と、DB に保存した path（`t_contract_document.pdf_path` 等。読むときに `startsWith(root)` で検査する。`ContractDocumentServiceImpl` の `verifySourcePdf`・`download`）の書換えが要る。
         - C（固定名の実体 directory を停止中に rename で入れ替える）: 2回の rename になり原子的でない（`database-backup-recovery/design.md` §10 の atomic rename と合わない）。2回の間に app が起動すると空の directory を作り、file の無い状態で動く。
     - file system policy の例（uploads と trust を同じ file system に置く場合。ARN・ID は placeholder。各 role は別に決める。実 AWS では検証していないため、Security が確かめる）。否定の条件（`StringNotEquals`）は条件 key が無い要求（access point を通らない mount）にも当たるため、主体ごとの Deny は root への直接の mount の書込みも止める。例に無い主体（identity policy で書込みを許された role 等）の root への書込みは `DenyWriteWithoutAccessPoint` が止める。
       ```json
       {
         "Version": "2012-10-17",
         "Statement": [
           {
             "Sid": "TaskMountsTrustReadOnly",
             "Effect": "Allow",
             "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:role/<ecsTaskRole>" },
             "Action": "elasticfilesystem:ClientMount",
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-privacy-trust>" },
               "Bool": { "aws:SecureTransport": "true" }
             }
           },
           {
             "Sid": "TaskWritesUploads",
             "Effect": "Allow",
             "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:role/<ecsTaskRole>" },
             "Action": ["elasticfilesystem:ClientMount", "elasticfilesystem:ClientWrite"],
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-uploads>" },
               "Bool": { "aws:SecureTransport": "true" }
             }
           },
           {
             "Sid": "DenyTaskWriteOutsideUploads",
             "Effect": "Deny",
             "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:role/<ecsTaskRole>" },
             "Action": "elasticfilesystem:ClientWrite",
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringNotEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-uploads>" }
             }
           },
           {
             "Sid": "DenyWriteThroughTrustReader",
             "Effect": "Deny",
             "Principal": { "AWS": "*" },
             "Action": "elasticfilesystem:ClientWrite",
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-privacy-trust>" }
             }
           },
           {
             "Sid": "DenyWriteWithoutAccessPoint",
             "Effect": "Deny",
             "Principal": { "AWS": "*" },
             "Action": "elasticfilesystem:ClientWrite",
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "Null": { "elasticfilesystem:AccessPointArn": "true" }
             }
           },
           {
             "Sid": "TrustWriterOnly",
             "Effect": "Allow",
             "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:role/<D-03 owner の書込み用 role>" },
             "Action": ["elasticfilesystem:ClientMount", "elasticfilesystem:ClientWrite"],
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-privacy-trust-writer>" },
               "Bool": { "aws:SecureTransport": "true" }
             }
           },
           {
             "Sid": "DenyTrustWriterAccessPointToOthers",
             "Effect": "Deny",
             "Principal": { "AWS": "*" },
             "Action": "elasticfilesystem:ClientWrite",
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-privacy-trust-writer>" },
               "ArnNotEquals": { "aws:PrincipalArn": "arn:aws:iam::<ACCOUNT_ID>:role/<D-03 owner の書込み用 role>" }
             }
           },
           {
             "Sid": "BackupMountsFiles",
             "Effect": "Allow",
             "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:role/<backup role>" },
             "Action": ["elasticfilesystem:ClientMount", "elasticfilesystem:ClientWrite"],
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-backup-files>" },
               "Bool": { "aws:SecureTransport": "true" }
             }
           },
           {
             "Sid": "DenyBackupWriteOutsideFiles",
             "Effect": "Deny",
             "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:role/<backup role>" },
             "Action": "elasticfilesystem:ClientWrite",
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringNotEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-backup-files>" }
             }
           },
           {
             "Sid": "RestoreWritesUploads",
             "Effect": "Allow",
             "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:role/<restore role>" },
             "Action": ["elasticfilesystem:ClientMount", "elasticfilesystem:ClientWrite"],
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-uploads>" },
               "Bool": { "aws:SecureTransport": "true" }
             }
           },
           {
             "Sid": "DenyRestoreWriteOutsideUploads",
             "Effect": "Deny",
             "Principal": { "AWS": "arn:aws:iam::<ACCOUNT_ID>:role/<restore role>" },
             "Action": "elasticfilesystem:ClientWrite",
             "Resource": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:file-system/<fs-id>",
             "Condition": {
               "StringNotEquals": { "elasticfilesystem:AccessPointArn": "arn:aws:elasticfilesystem:<region>:<ACCOUNT_ID>:access-point/<fsap-uploads>" }
             }
           }
         ]
       }
       ```
     - `ClientWrite` は task role と restore の主体（uploads の access point）、backup の role（backup の access point）、D-03 owner の書込み用 role（trust の書込み用の access point）だけに与える。trust の読取り用の access point と file system の root へは誰にも与えず（`DenyWriteThroughTrustReader`・`DenyWriteWithoutAccessPoint`）、trust の書込み用の access point へは D-03 owner の role 以外に与えない（`DenyTrustWriterAccessPointToOthers`）。task role・backup の role・restore の主体は自分の access point の外へ書けない（`DenyTaskWriteOutsideUploads`・`DenyBackupWriteOutsideFiles`・`DenyRestoreWriteOutsideUploads`）。そのため task role（clamav を含む）は trust を読むだけで書けない。backup の access point の root は uploads を含み、POSIX user も app と同じため、backup が uploads を変えないことは IAM でも POSIX の権限でも止まらず、compose の `:ro` と backup の host の運用だけによる（(d)。Security の受入れ事項）。
     - `DenyTrustWriterAccessPointToOthers` の `aws:PrincipalArn` が EFS の client の要求で評価されるかは、Security が実機で確かめる（**未取得**）。評価されない場合は条件 key が無いため `ArnNotEquals` が成り立ち、この Deny が D-03 owner の書込みも止める（fail-closed。最初に trust 文書を書くときに分かる）。D-03 owner の role の ARN に path がある場合（例: IAM Identity Center の `aws-reserved/sso.amazonaws.com/...`）は、path を含む完全な ARN を書くか `ArnLike` にする。

### 1.7 初回配備（go-live）の必須前提と、画面「機能の開放」で各機能を開く前提（一覧。X3-4・FE-E03）

各所に分かれている前提の索引である。手順と根拠は各リンク先を正本とし、本節では繰り返さない。

- **基本方針（2026-11-23〜25 利用者決定 `feature-enablement-console` D-1。2026-11-22 の「task definition で初回から全スイッチ ON」を置き換えた）**:
  - prod では全 13 件の開放単位（`NF01`・`NF01_DUAL_CONFIRMATION`・`NF02`・`NF02_CUSTOMER_PORTAL`・`NF03`・`NF03_CERTIFICATION_PII`・`NF04`・`NF05_INBOUND`・`NF05_OUTBOUND`・`NF06`・`NF07_RETENTION`・`NF07_DSAR`・`NF10`）は初期状態 `CLOSED` で配備され、旧い開閉の環境変数や移した承認設定を task definition に置くと `FeatureEnablementProductionValidator` と `validate-task-definition.ps1` の両方が拒否する（§1.0「機能の開放」）。
  - したがって **初回配備（go-live。利用者への公開）の必須前提は、下の「A. 初回配備（go-live）の必須前提（基盤・中核機能・常時有効の部分）」だけ**である（1 つでも欠ける間は go-live せず、本番は **NO-GO** のまま。人手の項目は全て**未取得**）。
  - 個別の S-NF 機能は go-live の時点で開いている必要は無く、下の「B. 画面『機能の開放』（`/feature-enablement`）で各開放単位を開く前提」にある基盤準備と署名・受理（`FeatureEnablementCatalog` の `SignOffKind` / `BundleKind`）が揃った機能から、画面で二人承認により 1 件ずつ開く（§5.5.1・`feature-switches.md` §3）。NF08 は目録の外で、B2c-2 の完了と独立 Review まで prod が起動を拒否し続ける。
  - 例外として、`INTEGRATION_HUB_BOUND_TENANT_ID`（`integration.hub.topology.bound-tenant-id`）だけは将来の `NF05_OUTBOUND` 開放の前提（`NF05_OUTBOUND_OFF` の指紋一致）のため、**初回の起動（§4・§4.2）から以後の全ての起動で同じ値を設定し続ける**（機能スイッチではないため `-Phase Initial`・`Steady` とも通る）。

#### A. 初回配備（go-live）の必須前提（基盤・中核機能・常時有効の部分）

**SRE の前提（初回配備から必要）**

| # | 前提 | 理由 | 正本 |
|---|---|---|---|
| SRE-1 | **単一tenantで配備する**（`OIDC_TENANT_ID=default`、`ACCOUNTING_TENANT_IDS` は1件）。多tenantで公開しない（RR-14） | `/api/users` の一覧・取得・更新・削除がtenant境界を持たず、多tenantでは管理者が他tenantの利用者を操作できる。外部アカウント関連の表・schedulerも単一tenantを前提にする。NF02のSLA営業日カレンダー（`m_work_calendar`）もtenant列を持たず、`ServiceSlaCalculator.findCalendarForScope` の4段目（全社共通）のカレンダーは全tenantで共用される（W3 review P3-N2-4。tenant列の追加はDDLが要るため未対応。多tenant化の前に対応する） | `.kiro/roadmap/2026-11-14-snf-nf01-fine-grained-epoch-plan.md` §9 RR-14 |
| SRE-2 | **`NF05_INBOUND` を開いて再起動するまで、LB/WAFで `/external-api/**` を遮断する**（D-1） | appの閉時の404に頼らず入口で止める（認証前のrate制限が無く、届けばDB接続・CPUを消費する）。初回配備（go-live）は遮断のまま公開し、後日 `NF05_INBOUND` を二人承認＋再起動で開いた後にだけ、承認済み送信元CIDRだけを通すruleへ置き換える（外すだけにしない） | `ops/security/runbooks/integration-hub-public-api.md` §0 |
| SRE-3 | `TRUSTED_PROXIES`（`app.security.trusted-proxies`）にALBのsubnet CIDRを設定する | 未設定・空・広すぎる範囲・構文不正では本番は起動しない。公開APIを開ける場合は `integration.hub.security.trusted-proxies` も同じALB経路にそろえる | `forwarded-header-sanitize-notes.md` §3.3 |
| SRE-4 | LB / reverse proxy の idle timeout を**130秒以上**にする。確認: クラウドの管理画面／CLI で ALB の load balancer 属性 `idle_timeout.timeout_seconds` が130以上。ALB の前に reverse proxy・CDN を置く場合は、その idle timeout も同じく確かめる | 取込センターの検証（最長120秒＋α）等の長い同期要求が60秒等の既定で504で途切れないように初回配備から揃える | `feature-switches.md` §4.8 |
| SRE-5 | **単一instance、またはsticky session**で運用する。新旧artifactを同時に稼働させない（rolling禁止）。確認: (a) DB: 起動後に `SELECT COUNT(DISTINCT SUBSTRING_INDEX(host, ':', 1)) FROM information_schema.processlist WHERE user = '<アプリaccount>';` が1（接続元の task が1つ。PROCESS を持つ管理用の account で実行する。RDS Proxy 等の中継を挟むと全接続が中継の IP に見えて数えられないため、その場合は (b) だけで確かめる）。(b) クラウドの管理画面／CLI: ECS service の desired count と running count が1、進行中の deployment が1件だけ、deployment configuration の maximum percent が100（新旧の task を重ねない。そのため minimum healthy percent は0になる） | sessionはin-memory（Spring Session無し）で、再起動で全員がlogoutする。NF04（常時有効の保存・復元を含む）、NF06の照合台帳（instance local）、NF08の外部送信（開放後も単一instance運用）、NF10の定期レポートが前提にする | `mobile-pwa-self-service/first-release-user-and-operations-notes.md` §2.1、`feature-switches.md` §4.8、`ai-management-copilot/external-send-approval-gate.md` §6、`scheduler-claim-fence-plan.md` §10、本書 §6 |
| SRE-6 | **表単位のrestore・rollbackをしない**（DBを戻すときは全体のPITRだけ。RR-13） | anchor表（`sys_user`・`t_engineer`・`m_organization_unit`・`t_document`・`t_lifecycle_task`）だけを古い行へ戻すと認可epochが巻き戻り、旧提出が復活し得る。全体のPITRは前後で一貫する | NF01細粒度epoch計画 §9 RR-13、`engineer-lifecycle-workflow/runbook.md`、`ops/backup/runbooks/` |
| SRE-7 | **転送ヘッダーの清浄化の公開前の措置**（SRE-3 以外の §3 の項目） | 次の4つ。<br>(1) WAFで `X-Forwarded-Prefix`・`X-Forwarded-Host`・`Forwarded`・`X-Forwarded-Ssl` のどれかを持つrequestを403にする。headerの有無で判定し、COUNTで始めてBLOCKにする（§3.1）。<br>(2) ALB: XFFは `append`、`xff_client_port` は無効、公開Host以外は既定404、ALBだけを許すsecurity group、target groupのhealth checkは `/actuator/health/readiness`（§3.2）。<br>(3) deploy前後の確認0〜5（§3.4）。2（OIDC の `redirect_uri`）は初回は本書 §5.3 の5でまとめて行う。6・7 は staging で人が実測する（**未取得**）。<br>(4) Prometheusのscrape経路（§3.6）: ALB 経由の HTTPS に決定済み（2026-11-18。§1.6 の7）。設定する項目は SRE-9 | `forwarded-header-sanitize-notes.md` §3.1・§3.2・§3.4・§3.6 |
| SRE-8 | **`LOCK_CONFLICT` の監視とalert**（初回配備から） | `/api/**` の 1213（deadlock）・1205（lock待ちtimeout）は409（PWAの書込みは503）へ変換され、WARN log `errorCode=LOCK_CONFLICT` だけが残る。5xxを数える監視では見えないため、この件数の監視とalertを置く。DBAは `innodb_print_all_deadlocks=ON`（RR-15）。`TransactionSystemException` に包まれた場合は500のまま。<br>**方式は決定済み（2026-11-18。§1.6 の8）**: CloudWatch Logs `/ecs/ses-manager-prod` に filter pattern `"errorCode=LOCK_CONFLICT"`、metric `SES/App` の `LockConflictCount`、alarm（統計 Sum、期間300秒、5以上で1回）、日次集計 `filter @message like /errorCode=LOCK_CONFLICT/ \| stats count(*) by bin(1d)` | `engineer-lifecycle-workflow/runbook.md` §6.6、NF01細粒度epoch計画 §10（SRE）、`GlobalExceptionHandler.handleLockConflict` |
| SRE-9 | **metricsの取得は scraper 口座で行う**（`METRICS_SCRAPER_*`。§1.0） | `GET /actuator/prometheus` のHTTP Basic chainは、`admin` とbreak-glass名を常に401で拒否する。ALB 経由の HTTPS で採集端 CIDR だけを通し、scraper 口座で 200・他送信元は 404/403・`admin`/break-glass 名は 401 を確かめる | `SecurityConfig`（`BreakGlassBasicRejectingProvider`） |
| SRE-10 | **§1.0 の1〜26を全て設定する**（secretはsecret storeから）。freeeを使う場合（`FREEE_ENABLED=true`）は27〜30も設定する。あわせて初回の起動から同じ `INTEGRATION_HUB_BOUND_TENANT_ID` を設定する | 1つでも欠けると初回起動が必ず失敗する。登録する task definition を起動の前に `infra/ecs/offline/validate-task-definition.ps1 -Mode Actual` で照合する（§4） | 本書 §1.0、`infra/ecs/task-definition.prod.json` |
| SRE-11 | **job実行環境（Maven・JDK・依存の取得経路）を決め、local repositoryを揃える** | 別の機械・別の `.m2` で job を動かすと、片方にだけ無い artifact で job が止まる。推奨は build と同じ機械・同じ `.m2`。要決定（§1.6 の4） | 本書 §1.5・§1.6 の4 |
| SRE-12 | **CloudWatch Logs（`/ecs/ses-manager-prod`）の保持期間を、PITR で戻り得る binlog の保持期間以上にする**（**未取得**） | PITR の後は DB の履歴表が復旧点に戻るため、復旧点より後に取り消された署名や閉鎖された開放単位を外部ログの `FEATURE_ENABLEMENT_EVENT` から照合する（review P3-N4） | `ops/backup/runbooks/restore-cutover.md` §4.7、`.kiro/specs/feature-enablement-console/design.md` §13.1 |

**DBA の前提**（番号は署名一覧の DBA-1〜DBA-6 と同じ）
- **DBA-1**: migrator accountのtrigger権限は §1.2 にある。binlogが有効なときの `log_bin_trust_function_creators`・SUPER相当の扱いと、DEFINERとmigrator accountを残すことを含む。
  - backup 用の account も同じ日に確かめる（§1.2 の「backup 用の account」。権限は `ops/backup/README.md`「権限分離」の「source 側の account の権限」（`PROCESS` は与えない）と「restore target role の権限」（`SET_ANY_DEFINER` 等を含む）の2つの表のとおり）。ただし書込み静止の要求の表（`sys_backup_quiesce_request`）への表単位の `INSERT`・`UPDATE` は、配備の前には表が無いためここでは確かめず、§3.4 で付けて確かめる（RB-QUIESCE）。
  - **recovery target（restore 先）の前提**（RB-W の W-1 の DBA の前提をここに統合した。**DBA の確認は未取得**）: binlog が有効なら `log_bin_trust_function_creators = ON`（restore target role は SUPER を持たないため。stored function と trigger の再現に要る）。DEFINER の account（migrator）を復元先に作るか、restore target role に `ALLOW_NONEXISTENT_DEFINER` を与える（作ることを推奨。account が無い trigger・view は実行時に失敗する）。migrator には `SYSTEM_USER` を与えない（与えると restore target role にも `SYSTEM_USER` が要る）。版と `lower_case_table_names` は DBA-4。
  - 配備前の検証（**未実施**）: 決めた設定と同じ条件（binlog 有効、`log_bin_trust_function_creators` を決めた値、root でない migrator account と §1.2 の権限）の検証用MySQL（本番ではない）に §3.3 の job を1回通し、§3.4 の routine 0件・trigger 73件を確かめる。CI の MySQL 試験は `--skip-log-bin` かつ root で動き、この条件を検証しない。あわせて NF05 の起動前の読取り（§1.2「NF05 の起動の前の読取り」）を DBA と Security が確認・受理し、確定閉鎖後の未送信配信の DLQ 移行照会が `t_api_delivery` の索引 `idx_api_delivery_due` を使うことを `EXPLAIN` で確かめる（実装統合 review rb-fe-int。**未取得**）。
- **DBA-2**: serverタイムゾーン `+09:00` と空schemaは §1.1 にある。
- **DBA-3**: **`binlog_format` は ROW（STATEMENT 不可）**。これは READ COMMITTED を宣言した transaction の前提である。
  - 対象: 退社完了の `LifecycleCaseServiceImpl.completeCase`、ポータル紐付け登録の `PortalEngineerLinkServiceImpl.register` 等。一覧は `WriterIsolationPolicyTest` の `READ_COMMITTED_WRITERS`。
  - binlog が STATEMENT だと、InnoDB は READ COMMITTED の transaction の書込みを拒否する（ERROR 1665）。そのため、これらの操作が全て失敗する。
  - MySQL 8 の既定は ROW である。§1.2 の一時表の条件（`enforce_gtid_consistency = ON` のとき binlog_format=ROW 等）とあわせて DBA が確かめる。
  - 確認: `SELECT @@global.binlog_format;  -- 'ROW'`
- **DBA-4**: MySQLの版・collation・parameter group を確定する（§1.1・§1.6 の3）。版は決定済み（2026-11-18）: 8.4 LTS（exact 版の候補は 8.4.13。recovery target も同じ exact 版・同じ `lower_case_table_names`。違うと schema fingerprint が一致せず cutover できない。RB-W の W-1 の前提をここに統合した）。試験側（RB-MYSQL84。#122）と backup tool 側（RB-W）の 8.4 への適合は合入済みで、8.4 での backup・PITR は CI（実 MySQL 8.4.13）で取得済み、本番相当の実測は**未取得**（§1.1）。parameter group には `innodb_print_all_deadlocks = ON`（SRE-8・RR-15）も入れる。確認SQLは §1.1。
- **Backup・PITR**:
  - **DBA-5**: RPO 15分・RTO 4時間（2026-11-13 決定）の署名。署名の方法は決定済み（2026-11-18。§1.6 の10）: 配備日に目標値として署名し、達成は restore drill の実測待ちと注記する。DBA Lead と Operations の署名は**未取得**（PENDING）。
  - **DBA-6**: prodの `.env.backup` の `CONTENT_HASH_TABLES` の本番値を決める（未設定ではvalidateがFAILする）。本番の値はまだ決まっていない（**未取得**）。主キーを持つ表のカンマ区切りで、`flyway_schema_history` を含め、少数に保つ。
    - 草案: `dba-prelaunch-draft.md` §1（統括の起草。候補 A（最小）・B（推奨）、選んだ理由、表の存在・主キー・行数の確認 SQL。DBA の確認の前で、値は DBA が確定する）。DBA-1〜DBA-4 の確認 SQL の一覧（runbook の節の引用と、runbook に無い SQL の補い）も同 §2 にある。
  - 正本: `ops/backup/runbooks/restore-cutover.md`（RPO/RTO と署名欄、`CONTENT_HASH_TABLES` の要求）、`ops/backup/README.md`（`.env.backup` の形式）。

**Backup 開始の前提（BLOCKED の解除。初回 backup（§2 の5。§3 の完了直後）の前）**

署名一覧の DBA-6 とは別の項目である。`ops/backup/README.md` は、production 固有値（HFP-03-PROD-001〜008。`.kiro/specs/database-backup-recovery/baseline.md` §4）が未確定の間、production への接続・復元を BLOCKED としている。各値は baseline §4 の owner が確定して証跡を出し、DBA がまとめて配備記録に残す（SRE・DBA を兼ねる場合も、006・008 は Security・運用組織の事項である）。
- `.env.backup`（形式は `ops/backup/README.md` の「前提」「設定」）:
  - `CONTENT_HASH_TABLES`（DBA-6）。
  - `FLYWAY_VERSION`: §3.4 の最新version照会の値を Flyway 形式（`V`・`_` を使わない）で書く。本 release は `203`。release ごとに §3 の後で更新する。未設定（`unknown`）や古い値で取った checkpoint は、復元検証で `flyway_schema_history` の成功行の最大と一致せず、通らない。
  - `BACKUP_REPOSITORY`・`MYSQL_HOST`・`MYSQL_PORT`・`MYSQL_DATABASE`・`MYSQL_USER`。password は `secrets/` の 0600 の file（MySQL と restic）、CA は hashed CA の directory（`secrets/mysql-capath/`）。
  - uploads: `UPLOADS_ROOT=/app/uploads`・`UPLOADS_POINTER=current`・`UPLOADS_STAGING_PARENT=/var/lib/ses-uploads-staging`（§1.6 の11 (d)。compose の bind 先と同じ値）。`UPLOADS_DIR` は書かない（廃止した。残っていると full・checkpoint・preflight が失敗する）。
  - 書込み静止（RB-QUIESCE。変数の一覧と既定は `ops/backup/README.md`「設定」の「書込み静止の provider と設定」）: `QUIESCE_PROVIDER=quiesce-db`（定期の checkpoint・full。未設定の既定 `quiesce-local` は隔離環境向けで、heartbeat の directory を求めるため本番では失敗する）。`QUIESCE_PROVIDER=quiesce-app-stopped` は書かない（書くと job が始まらない。app の停止を確かめる full は §2 の5 で `--app-stopped` を付けて明示する）。閾値（`QUIESCE_STALE_SECONDS`・`QUIESCE_DEAD_SECONDS`・`QUIESCE_LEASE_*` 等）は既定のままにする（app 側の §1.0 の33〜41 を変えたときは README の式を満たすか確かめる。preflight の `checks.quiesce` と provider が app の申告値と式を照合し、合わなければ失敗する）。`REPLICA_HEARTBEAT_DIR`・`SCHEDULER_ACK_DIR` は書かない（`quiesce-local` だけが使う）。
- 確定する時点と owner（baseline §4 の「確定に必要な証跡」の欄）:
  - 初回 backup の前: 001（MySQL の版・`@@server_uuid`・GTID・binlog・checksum・compression・TLS・保持期間。DBA-3・DBA-4 と同じ値。production owner）、002（全表の engine とアプリ以外の writer の有無。backup account が書込み静止の要求の表 `sys_backup_quiesce_request` だけを書くこと（U-11）を含める。production owner。この writer の受理は BQ-1 の署名）、003（uploads の置き場所（task definition の EFS）と atomic snapshot・versioning の可否、backup の host が uploads と staging を同じ mount で使う access point と POSIX user（app と同じ）、restore の書き戻しと pointer（A に決定済み）。§1.6 の11 の (d)・(f)。storage owner。担当: production owner（利用者）。決定済み（2026-11-22、利用者）。staging での device 番号の確認（§1.6 の11 (d)）は SRE が行い、production owner が証跡をまとめる。要る証跡: 2つの access point の設定（root・POSIX user・CreationInfo）、目録の `ls -la` と `readlink <uploads>/current` の出力、staging での device 番号の一致（`stat -L -c %d`）の記録。どれも**未取得**）、004a（replica・scheduler・drain・DDL change-lock の実際の手段の inventory。RB-QUIESCE で 004 を 004a・004b に分けた。deployment owner。担当: production owner（利用者）。決定済み（2026-11-22、利用者）。004b の deployment owner（SRE）とは担当を分けた。下の SRE-5・BQ-2 等の実測と設定は SRE が行い、production owner が inventory と証跡をまとめる。次を含め、どれも**未取得**: ECS service の desired count と deployment configuration（SRE-5）、app の container の `stopTimeout`（雛形は 120。Fargate の既定と上限を確かめ、graceful の停止が STOPPED を書くまでに収まること）、通常の起動時間（`healthCheck.startPeriod` が `BACKUP_QUIESCE_STARTUP_WAIT_MAX`＋120 秒を満たし余裕があること）、アプリの DB 接続が中継（RDS Proxy 等）を経由しないこと、アプリ account の host の型が1つであること（§1.2）、backup host の定期起動（15分ごとの checkpoint・02:00 の full）の止め方と再開の仕方（§2 の0・§4.7）、静止中の 503 を利用者へ伝える方法（§4.7）、staging の EFS での uploads の snapshot の時間の実測（BQ-2。U-1））、005（S3 互換 backend とその IAM・versioning/immutability・容量と throughput。baseline §4 のとおり。infra owner。担当: production owner（利用者）。決定済み（2026-11-22、利用者）。**未取得**）と、上の `.env.backup`。
  - EFS の file system policy・access point・task role の権限（§1.6 の11 の (a)〜(f)）は 003・005 には含めず、Security が確かめる（**未取得**）。backup の host はその access point が無いと uploads と staging を同じ mount で使えず、policy を後から当てると mount の可否が変わるため、初回 backup の前に揃える。
  - §4 の後（初回 backup の条件にしない）: 004b（app の起動の後の最初の ONLINE の checkpoint（`quiesce-db`）が VALID になった記録。§4.7 の4。deployment owner。担当: SRE（決定済み（2026-11-22、利用者））。**未取得**）。初回 backup（§2 の5）は app が動く前に `--app-stopped` で取るため、最初の ONLINE の checkpoint は §4 の後にしか取れない。004b が揃うまで、本番の RPO 15分の達成を宣言しない。
  - backup の監視（`check --json`）を運用に入れる前: 008（alert routing・運用 owner・incident commander・drill owner。運用組織。担当: incident commander は production owner（利用者）、他の3つは SRE）。004b・006・007・008 の担当の役割は決定済み（2026-11-22、利用者。下の「初回リリースの後に揃える事項の担当」）。決まったのは担当だけで、事項はどれも**未取得**。
  - 初回の restore（restore drill・本番の restore）の前: 006（二者承認の identity・署名の検証と change-ticket の仕組み。security owner。担当: Security）、007（代表 profile。RTO の実測に使う。production owner。担当: production owner（利用者））。どちらも**未取得**。uploads の切替えの手順（stage・switch・status と、DB の cutover・rollback との順序、起動前の DB と pointer の対の確認）は `ops/backup/runbooks/restore-cutover.md` §4.5.1 に書いてある（RB-W）。初回の restore drill でこの順序を一度通し、記録する（**未取得**）。
- 初回 backup の前の手順（`ops/backup/README.md`「ツールイメージの build・配布」と「実行」）: backup tool image の content ID（v2。RB-W）で compose の pin をし直し、配布先で `tool-image.sh load` → `tool-image.sh check` が一致し、`tool-image.sh compose run --rm preflight` が 0 で終わること。
  - **RB-QUIESCE で tool image の content ID が変わった**（image に入る `providers/`・`lib/`・job の script が変わったため）。RB-QUIESCE より前の commit から build・記録した image（RB-W の検証で作ったもの等）は、content ID が compose の pin と一致せず使えず、`quiesce-db`・`quiesce-app-stopped` の provider も持たない。配備する commit から README「ツールイメージの build・配布」の手順の 2〜5（`build-record` → compose の `x-ses-backup-tool-content-id` の pin の commit → `save` → 各 host で `load` → `check`）をやり直し、記録した content ID・tar の SHA-256 を配備記録に残す（lock を変える場合だけ 1 から）。
  - 書込み静止の preflight（§3.4 の GRANT の後。初回は §2 の5 の前）: JSON の `checks.quiesce` が `provider: "quiesce-db"`・`status: "ok"`・`signal_tables: 3`・`request_table_grant: true`。V202 の前と GRANT の前は exit 19 になる。
- 解除の条件: 項目ごとに owner の証跡を配備記録に残し（001・002 は production に対する `preflight` の redacted な `--json` 出力）、DBA がまとめた記録を確かめた時点で、その項目の BLOCKED を解除する。解除するのは production への接続（full・binlog の archive・checkpoint）で、復元は従来どおり production とは別の recovery target へ行う（`ops/backup/README.md`「復元」）。001〜003・004a・005 が揃うまでは初回 backup を取れず、§2 の5 を満たさないため §4 へ進まない。004b は §4 の後の最初の ONLINE の checkpoint で揃うもので、初回 backup と §4 の条件にしない（条件が循環しない。backup-write-quiesce requirements BWQ-AC-011-02）。
- **backup tool の MySQL 8.4 への適合（RB-W）は合入済み**（§1.1）: `ops/backup/preflight.sh` は 8.4 系列の server と client を受け付ける。8.4 での backup・PITR は CI の backup integration（実 MySQL 8.4.13）で取得済みで、本番相当の実測は**未取得**（§1.7 の W 表の前の段落）。初回 backup の前に production に対する `preflight` が 0 で終わること（001 の証跡）を確かめ、0 にならなければ §4 へ進まない。
- 正本: `.kiro/specs/database-backup-recovery/baseline.md` §4（production 固有値・owner・確定に要る証跡）、`ops/backup/README.md`。

**初回リリースの後に揃える事項の担当（役割の分担は決定済み（2026-11-22、利用者））**

2026-11-22 の利用者回答（担当者がまだ居ないため、先に owner を指定する）を受け、同日に利用者が下の役割の担当を決めた（**決定済み（2026-11-22、利用者）**）。担当は役割だけで書き、人の名前は書かない。決まったのは担当だけで、事項はどれも**未取得**のまま。事項の内容と証跡の正本は baseline §4 の各行で、本表は担当の索引である。配備記録にも、名前ではなく役割の担当として残す。

| 事項 | 役割 | 何をする | いつまでに | 状態 |
|---|---|---|---|---|
| HFP-03-PROD-004b | deployment owner | app の起動（§4）の後の最初の ONLINE の checkpoint（`quiesce-db`）が VALID になったことを配備記録に残す（§4.7 の4）。揃うまで本番の RPO 15分の達成を宣言しない | §4 の後の最初の checkpoint（初回 backup と §4 の条件にはしない） | 担当: SRE。**未取得** |
| HFP-03-PROD-008 | alert routing の owner | backup の監視（`check --json`）と書込み静止の alert（`backup-write-quiesce/design.md` §13.3）の通知先・経路を決めて設定する | backup の監視を運用に入れる前 | 担当: SRE。**未取得** |
| HFP-03-PROD-008 | 運用 owner | backup の日々の運用（15分ごとの checkpoint・02:00 の full の定期起動、失敗の時の再実行、§4.7 の確認）に責任を持つ | 同上 | 担当: SRE。**未取得** |
| HFP-03-PROD-008 | incident commander | restore・cutover を判断する（RPO・RTO を超えた場合も含む。`ops/backup/runbooks/restore-cutover.md` §1）。D15 の旧版の保存の延長も判断する（`d15-uploads-old-versions-options.md` の a-1） | 同上。遅くとも初回の restore（restore drill を含む）の前 | 担当: production owner（利用者）。**未取得** |
| HFP-03-PROD-008 | drill owner | 四半期ごとの restore drill を計画し、実施・記録する（RTO の実測。uploads の切替えの順序を通す。上の「初回の restore の前」） | 初回の restore drill の前 | 担当: SRE。**未取得** |
| HFP-03-PROD-006 | security owner | 二者承認の identity・署名の検証と change-ticket の仕組みを決める（restore の承認に使う） | 初回の restore（restore drill・本番の restore）の前 | 担当: Security。**未取得** |
| HFP-03-PROD-007 | production owner | 代表 profile（DB と uploads の大きさ・binlog の量・環境の性能等。RTO の実測に使う）を決める | 初回の restore（restore drill・本番の restore）の前 | 担当: production owner（利用者）。**未取得** |

**backup の書込み静止（RB-QUIESCE）の署名・受理と、本番の前の実測**（どれも人・Runner・CI が行う。設計は `.kiro/specs/backup-write-quiesce/design.md`、手順は `tasks.md`）

| # | 事項 | 要るもの | 状態 | 正本 |
|---|---|---|---|---|
| BQ-1 | backup account が業務 DB の1表（`sys_backup_quiesce_request`）の書き手になること（U-11。§1.2） | Security と DBA の署名（baseline の PROD-002 の記録の上で） | **未取得** | design §4、baseline PROD-002 |
| BQ-2 | staging の EFS での uploads の snapshot の時間（U-1。004a の一部） | SRE が 1,000件・5,000件の fixture で測り、実測の件数の上限と uploads の増え方から checkpoint の上限（300秒）に届く時期の見込みを配備記録に書く。届く前に uploads の方式の見直し（後の batch）を始める | **未取得**（本番の前・BQ-4 の前） | design §10.1・§19 |
| BQ-3 | MySQL 8.4 での prod の起動（prod profile・`BACKUP_QUIESCE_ENABLED=true`・bootstrap の再開 ticket `SECURITY_BOOTSTRAP_REOPEN_TICKET` 付き。2b review 第8点 c） | SRE が staging で（BQ-4 と同じ機会でよい）起動し、readiness が UP になること、起動の監査 `BOOTSTRAP_REOPENED` が書けること（instance の登録の前は業務の接続が読取り専用で、起動の途中の書込みが拒否されれば起動が止まる）、instance の行が `RUNNING`・`OPEN` になることを記録する。tasks の BWQ-005・BWQ-015 を完了にする条件 | **未取得**。手元の環境（MySQL 8.4.13、dev・prod、dummy 設定）で app 側を確認済み（2026-11-22、統括の記録）。staging の実施は未取得で、手元の環境の結果は代わりにならない | tasks BWQ-005・BWQ-015、design L-13・§20.11 |
| BQ-4 | staging での通しの Demo（tasks BWQ-014。**2026-11-22 利用者決定: RB-QUIESCE の PR では行わず、本番の前（go-live の前）に SRE が staging で行う**）: §2 の0 → drain → 停止 → GRANT の確認 → `--app-stopped` の full → §3 → §4 → 定期起動の再開 → 最初の ONLINE の checkpoint の順を、staging の MySQL 8.4 と app で通す。隔離環境（Runner の Docker 等）で通しても、この項は満たさない | SRE の実施記録と各段の log・JSON の証跡（`quiesce.json` は投影だけ。`ops/backup/README.md`「書込み静止の記録（quiesce.json）」） | **未取得**（取れるまで本番は NO-GO のまま）。手元の環境（MySQL 8.4.13、dev・prod、dummy 設定）で app 側を確認済み（2026-11-22、統括の記録）。staging の実施は未取得で、手元の環境の結果は代わりにならない。静止中の起動の「待ち」は prod の profile でだけ確かめられる（prod 以外は待たない。design §20.11 の F-1） | tasks BWQ-014、design §20.11 |
| BQ-5 | 実装の時に実測で確かめる前提（design §19）のうち CI で取るもの: 表を作る前の表単位の GRANT（mysql shard 3 の `BackupQuiesceProviderContractMySqlTest` の log）、mysqldump 8.4 の `--single-transaction --source-data=2` の起点の lock の取り方と待ち（backup integration の `full-dump-start-lock.txt`）、STORED の生成列 `active_slot` の dump・復元・schema fingerprint（同 `quiesce-generated-column.txt`） | CI の証跡（統括が CI の後に design §19 へ書き戻す） | **取得済み**（2026-11-22、PR #128 の head `9448aaf1`・run 44979797399。design §19 に書き戻した）: 表を作る前の表単位の GRANT は拒否（`Table '…sys_backup_quiesce_request' doesn't exist`。そのため §3.4 で migration の後に付ける順が必須）、mysqldump 8.4 の起点の lock の待ちは全て 0（小さい fixture。長い SELECT と重なる場合は BQ-4 の staging で見る）、`active_slot` は復元の後も `_utf8mb4` の同じ生成式で schema fingerprint・content hash とも PASS。CI の値は隔離環境のもので、BQ-2〜BQ-4 の代わりにはならない | design §19 |

- staging で prod の profile の起動と、画面 `/feature-enablement` での 1 件の開放単位の申請・承認・閉鎖を人が確かめること（FE-E05 の Demo。**未取得**）も go-live 前の確認に含める。NF05 の外部 API の静止中の 503 の受理（U-6）は下の「B. 画面『機能の開放』で各開放単位を開く前提」の `NF05_INBOUND` の行にある。

**初回配備から有効になる部分の署名・受理**（画面「機能の開放」では止まらないため、**初回配備（go-live）の必須前提**。全て**未取得**）

| 対象 | 要るもの | 正本 |
|---|---|---|
| NF04 のうち `NF04` が閉でも止まらない常時有効の部分（pwa-queueの保存経路、lock中の下書きの復元（local password sessionだけ）、台帳の清掃、restore-sessionのlistener。`NF04` はService Workerとmanifestだけを止める） | 次のうちこの部分にかかる署名と真機受入が初回配備（go-live）の前提になる（全行が**未取得**）。<br>(1) Privacy: 端末内30日保存（P3-13 を含む）。<br>(2) SRE/Ops: 単一instance・sticky session（SRE-5）。<br>(3) Owner/Security: B-NF04-4（失効の制限。決定待ち）、P1-N4-1 案A、P1-W309-1 (a)。<br>(4) 業務の受入担当: 真機受入 S-01〜S-15 | `feature-switches.md` §4.4、`mobile-pwa-self-service/first-release-user-and-operations-notes.md` §4、`mobile-pwa-self-service/real-device-browser-acceptance-template.md` |
| 履歴取込の副本・取込センターCSV（文書種別 `RESUME_INGESTION` / `IMPORT`、V197） | 保存年数（3年・TRANSACTION_DATE）は暫定値で、文書ownerの確認待ち。<br>副本の処分方針について、Privacy owner と文書 owner の書面署名を得る（**未取得**）。**署名までは `RESUME_INGESTION` の副本の廃棄申請を承認も執行もしない**（確認SQLは §4.3）。<br>初回配備は `RESUME_LEDGER_COPY_ALIGNMENT_ENABLED=false`（雛形の値）で副本の調整だけを止め、署名後に `true` にする（§4.3）。<br>`IMPORT` の保持期間は `NF06` を開く前の条件（下の表の `NF06_PRIVACY_RAW_CSV`） | 冒頭の V197 の項、§4.3 |
| 顧客ポータル全画面（login・招待受諾・規約・customer・bp。`templates/portal/**`）のBootstrap参照の修正（RB-I18N `5a7e2bbd`）。`NF02_CUSTOMER_PORTAL` の開閉と関係なく配備した時点で全画面に効く | 実ブラウザでの確認（**未取得**）。login・MFA設定・招待受諾・規約同意の入力と送信、customerの各tab（検収・契約・請求・見積・注文請）の切替と検収・請求のモーダル、bpの各画面、header のボタンの視認性（desktop と 390px。担当: 業務の受入担当）。問い合わせタブ・モーダルの確認は `NF02_CUSTOMER_PORTAL` を開く前の前提（下の表の `NF02_PORTAL_BROWSER_CHECK`） | `feature-switches.md` §4.3.1、`StaticAssetLocalityTest` |

#### B. 画面「機能の開放」（`/feature-enablement`）で各開放単位を開く前提と署名種別の対応

各開放単位は go-live の時点では `CLOSED` のままで、下の基盤準備と目録（`FeatureEnablementCatalog`）の署名種別（`[決]`＝`DECISION`、`[証]`＝`EVIDENCE`）の確認が揃った機能から 1 件ずつ二人承認で開く（手順は §5.5.1・`feature-switches.md` §3。どれも**未取得**）。

**対象機能を開く前に整える基盤・DB の準備**（go-live の必須前提ではなく、該当する開放単位を開く前に行う。ただし `INTEGRATION_HUB_BOUND_TENANT_ID` だけは初回の起動から一貫して設定する）

| 準備 | 対象の開放単位 | owner | 何をする | 正本 | 状態 |
|---|---|---|---|---|---|
| NF05 outbound の OFF の記録 | `NF05_OUTBOUND` | SRE（確認 SQL は DBA） | 初回の起動から全ての起動に同じ `INTEGRATION_HUB_BOUND_TENANT_ID` を設定し、`NF05_OUTBOUND` を申請・承認する前の起動の後に下の SQL で状態の行と監査 `NF05_OUTBOUND_OFF` を確かめる（評価器も `barrier.offRecorded` として自動検査する） | `ops/security/runbooks/integration-hub-public-api.md` §13.1 | **未取得** |
| NF07 の Journal instance と専用 Flyway job | `NF07_RETENTION` | DBA・SRE | 別 instance に `privacy_journal` を用意し（§3.6）、専用 job を実行して `PRIVACY_JOURNAL_*`（`nf07_journal_appender`）を task definition に入れて再起動する（評価器が `journal.*` を自動検査する） | §3.6、§1.0 | **未取得** |
| NF07 の `privacy-admin` の有効な OIDC 管理者 2 名以上（推奨 3 名） | `NF07_RETENTION`・`NF07_DSAR` | Owner・Security（対象者確認）・管理者（画面割当）・DBA（SQL確認） | bootstrap 完了後に OIDC 管理者を `privacy-admin` に割り当て、確認 SQL で 2 名以上を確かめる（評価器が `privacyAdmins.*` を自動検査する） | `feature-switches.md` §4.7.4 | **未取得** |
| NF07 の trust 文書と KMS 鍵 | 副入口 `REL`・`REG` | D-03 owner・Security・SRE | KMS key（`ECC_NIST_EDWARDS25519`）と EFS 上の trust 文書を用意し、`PRIVACY_TRUST_KMS_ANCHOR_FILE`・`PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE` を設定して再起動する | §1.6 の 11、§4.6 | **未取得** |
| NF06 の法人を持つ組織（単一法人）と行参照鍵 | `NF06` | DBA・SRE・管理者 | `IMPORT_ROW_REFERENCE_HMAC_KEY` を設定し、法人 ID を持つ組織を登録して `COUNT(DISTINCT legal_entity_id) = 1` を確かめる（評価器が自動検査する） | `feature-switches.md` §4.8 | **未取得** |
| NF02 の全社共通カレンダー | `NF02` | DBA・SRE・管理者 | 当年度を含む全社共通カレンダーを登録し、法人既定と祝日・会社休日を確かめる（評価器が開放時に `calendar.companyWide` を自動検査する） | `feature-switches.md` §4.3 | **未取得** |

`NF05_OUTBOUND` の OFF 記録の確認 SQL（DBA。`NF05_OUTBOUND` を申請する前に本番 DB で読むだけ）:

```sql
SELECT config_value FROM m_system_config WHERE config_key = 'integration.hub.outbound.runtime.v1';
-- 期待: 1行で 'v1|OFF|0|<指紋>'。<指紋> は bound-tenant-id の UTF-8 の SHA-256 の小文字 hex 64桁
SELECT id, created_at, after_state FROM t_audit_log
 WHERE application_code = 'NF05_OUTBOUND_OFF' ORDER BY id DESC LIMIT 1;
-- 期待: after_state = 'OFF' の行がある
```

**開放単位・副入口ごとの署名種別と開く前に要る事項**（全て**未取得**）

| 開放単位 / 副入口（再起動要否） | 目録の署名種別・組・鍵（`FeatureEnablementCatalog`） | 開く前に要る署名・受理・実測の内容 | 正本 |
|---|---|---|---|
| `NF01` 本体<br>（再起動不要） | `[決]` `NF01_PLAN_SECURITY`<br>`[決]` `NF01_PLAN_SRE`<br>`[決]` `NF01_PLAN_DOCUMENT_OWNER`<br>`[決]` `NF01_PLAN_HR`<br>`[決]` `NF01_PLAN_DBA`<br>`[証]` `NF01_STAGING_VERIFICATION`<br>`[証]` `NF01_RUNNER_EVIDENCE` | (1) NF01計画 §10 の 5 部門署名（Identity/Security・SRE・Document Owner・HR・DBA。RR-1・RR-2・RR-4・RR-8・RR-11・RR-13・RR-14・RR-15、O-1、B-NF01-5 等）。<br>(2) `NF01_STAGING_VERIFICATION`: lifecycle runbook §6.4・§6.6（R-1〜R-3、P3-5 の HR 確認、staging 実測。担当: production owner）。<br>(3) `NF01_RUNNER_EVIDENCE`: M6・M10 `ResignationRevocationOutboxMySqlTest`・`AssetMySqlIntegrationTest` 5回以上 skip 0、M4 `EngineerScopeFenceMySqlTest` 両順序実測。公開前の read-only preflight PASS | NF01細粒度epoch計画 §10、`engineer-lifecycle-workflow/runbook.md` §5・§6.4・§6.6、`feature-switches.md` §4.2 |
| `NF01_DUAL_CONFIRMATION`<br>（親 `NF01`。再起動不要） | `[決]` `NF01_DUAL_PRIVACY`（参照形式検査あり）<br>`[決]` `NF01_DUAL_BREAK_GLASS_DECISION`<br>`[証]` `NF01_DUAL_MANUAL_CHECK`<br>`[証]` `NF01_DUAL_RUNNER_EVIDENCE`<br>`[証]` `NF01_DUAL_REVIEW_PASS`<br>＋評価器: 証跡種別 SQL (1)(2) が 0 件 | (1) `NF01_DUAL_PRIVACY`: comment 2列（`business_comment`・`decision_comment`）の保存期間・削除/匿名化経路（O-8）・role級の表示範囲・営業代理提出時の差戻し理由伝達（V198 の catalog 9 件の保存期間は D-02 で確定）。<br>(2) `NF01_DUAL_BREAK_GLASS_DECISION`: break-glass incident scope で二者確認を許すか止めるかの Security 判断（W3-15 P3-7）。<br>(3) `NF01_DUAL_MANUAL_CHECK`: 下の §1.7.1 の手動確認 9 項目（担当: production owner）。<br>(4) `NF01_DUAL_RUNNER_EVIDENCE`: M1 の要員 SHARED が PRIMARY に付くこと・M8 実 writer 4 件の Runner 実測。<br>(5) `NF01_DUAL_REVIEW_PASS`: W3-21a・W3-21b の PASS | `feature-switches.md` §4.9、NF01細粒度epoch計画 §10・§12 O-8、§1.7.1 |
| `NF02` 本体<br>（再起動不要） | `[決]` `NF02_DG02`<br>＋開放時条件: `calendar.companyWide` | DG-02（CS Owner。B3 休日の H-1・H-2 を含む。`dg-02-first-release-decision-package.md` §0 の要再確認項目を含む）、`g2r-browser-manual-acceptance.md`「第一段階」、当年度を含む全社共通カレンダーの登録 | `customer-success-service-desk/dg-02-first-release-decision-package.md`、`g2r-browser-manual-acceptance.md`、`feature-switches.md` §4.3 |
| `NF02_CUSTOMER_PORTAL`<br>（親 `NF02`。再起動不要） | `[決]` `NF02_PORTAL_PUBLICATION`<br>`[決]` `NF02_PORTAL_DG02_AC`<br>`[決]` `NF02_PORTAL_VISIBLE_SINCE_DECISION`（`visibleSince`）<br>`[証]` `NF02_PORTAL_BROWSER_CHECK`<br>（条件付き）`[証]` `NF02_PORTAL_INVENTORY` | 公開の Owner 承認（`NF02_PORTAL_PUBLICATION`）、DG-02-A/C の署名（`NF02_PORTAL_DG02_AC`）、公開開始時刻を設定するかの CS Owner 決定（`NF02_PORTAL_VISIBLE_SINCE_DECISION`。設定変更は画面「設定」タブの `nf02-customer-portal.visible-since` で二人承認し、時刻を入れる場合は社内登録分の棚卸し `NF02_PORTAL_INVENTORY` が必須）、問い合わせ起票・CSAT・詳細・返信モーダルの実ブラウザ確認（`NF02_PORTAL_BROWSER_CHECK`、`g2r-browser-manual-acceptance.md`「第二段階」） | `feature-switches.md` §4.3.1、`dg-02-first-release-decision-package.md`、`g2r-browser-manual-acceptance.md` |
| `NF03` 本体<br>（再起動不要） | `[決]` `NF03_BUSINESS_OWNER` | 業務 Owner の開放承認（資格番号の暗号鍵 §1.0 の 19・20 は常時必須） | `feature-switches.md` §4.6、`certification-learning-skill-gap/first-release-scope-confirmation.md` |
| `NF03_CERTIFICATION_PII`<br>（親 `NF03`。再起動不要） | `[決]` `NF03_DG07`<br>`[証]` `NF03_EVIDENCE_PRIVACY` | `NF03_DG07`（`CERTIFICATION_PII` の保持年数・破棄方針の承認）、`NF03_EVIDENCE_PRIVACY`（`CERTIFICATION_EVIDENCE` 証憑文書の扱い確認。`first-release-scope-confirmation.md` §0・§2.1） | `feature-switches.md` §4.6、`first-release-scope-confirmation.md` |
| `NF04` PWA shell<br>（再起動不要） | `[決]` `NF04_BUSINESS_OWNER`<br>`[決]` `NF04_SECURITY`<br>`[証]` `NF04_SRE`<br>`[証]` `NF04_PRIVACY`<br>`[証]` `NF04_DEVICE_ACCEPTANCE` | (1) `NF04_BUSINESS_OWNER`: 利用者向け文言（1章）。(2) `NF04_SRE`: 単一 instance・sticky session。(3) `NF04_PRIVACY`: 端末内30日保存（P3-13）。(4) `NF04_SECURITY`: B-NF04-4・P1-N4-1 案A・P1-W309-1 (a)。(5) `NF04_DEVICE_ACCEPTANCE`: `NF04` 開（SW・manifest 有効）での真機受入 S-01〜S-15（常時有効部分の go-live 前提と合わせて確認可） | `mobile-pwa-self-service/first-release-user-and-operations-notes.md` §4、`real-device-browser-acceptance-template.md`、`feature-switches.md` §4.4 |
| `NF05_INBOUND`<br>（**開は次の再起動**・閉は即時） | 鍵: `INTEGRATION_HUB_PUBLIC_ID_KEY`・`CRYPTO_CURRENT_KEY_VERSION`・`CRYPTO_CREDENTIAL_HASH_PEPPER`<br>`[決]` `NF05_DG05_IN`（`approvalDigest`）<br>`[決]` `NF05_DIGEST_RULE_DECISION`<br>`[証]` `NF05_IN_SRE_WAF`<br>`[証]` `NF05_SRE_LOAD`<br>`[証]` `NF05_STAGING_WIRE`<br>`[証]` `NF05_QUIESCE_ACCEPTANCE`<br>`[証]` `NF05_CLIENT_NOTICE` | `DG-05-IN-*` 署名と二人による digest 再計算照合（`NF05_DG05_IN`）、署名日・失効日を正規形 v2 に入れるかの Security/Owner 判断（`NF05_DIGEST_RULE_DECISION`）、SRE-2 の LB/WAF 送信元 rule と XFF 検証（`NF05_IN_SRE_WAF`。再起動で `OPEN` を確認した後に切り替える）、SRE 負荷実測（`NF05_SRE_LOAD`）、staging 実ネットワーク TLS・XFF・WAF 証跡と ownership MySQL 試験実測（`NF05_STAGING_WIRE`）、backup 静止中の 503 `BACKUP_QUIESCED` の NF05 owner 受理（`NF05_QUIESCE_ACCEPTANCE`、U-6）と外部 client への周知（`NF05_CLIENT_NOTICE`）。法人の未解決がある場合は起動を止めず `AUTO_CLOSED(LEGAL_ENTITY_UNRESOLVED)` になる（NF05 owner 受理事項） | `integration-hub-public-api/dg-05-enablement-decision-draft.md`、`ops/security/runbooks/integration-hub-public-api.md`、`feature-switches.md` §4.10 |
| `NF05_OUTBOUND`<br>（**開は次の再起動**・閉は即時） | 鍵: 上記 3 件＋`INTEGRATION_HUB_TRUSTSTORE_PATH`・`TRUSTSTORE_PASSWORD`<br>遷移前提: `barrier.offRecorded`<br>`[決]` `NF05_DG05_OUT`（`approvalDigest`）<br>`[決]` `NF05_DIGEST_RULE_DECISION`<br>`[証]` `NF05_OUT_G3R`<br>`[証]` `NF05_OUT_CHANGE_ACCEPTANCE`<br>`[証]` `NF05_SRE_LOAD`<br>`[証]` `NF05_STAGING_WIRE` | `DG-05-OUT-*` 署名と二人による digest 再計算照合（`NF05_DG05_OUT`）、G3r 追加条件 OUT-7（OFF 書込成功＋同一 `bound-tenant-id` 指紋の確認による OFF→ON 隔離）・OUT-9 最終 SHA 証跡（`NF05_OUT_G3R`）、truststore byte・`batch-size`・`fixed-delay-ms`・`unauthenticated-audit-max-per-minute` の変更が新 DG-05 決定と 2 回再起動（§5.5.1）を要することの Owner/SRE 受理（`NF05_OUT_CHANGE_ACCEPTANCE`）、および `NF05_DIGEST_RULE_DECISION`・`NF05_SRE_LOAD`・`NF05_STAGING_WIRE` | 同上 |
| `NF06`<br>（再起動不要） | 鍵: `IMPORT_ROW_REFERENCE_HMAC_KEY`<br>`[決]` `NF06_ADMISSION`（`decisionId`・`tenantId`・`sourceCodes`・`approvalDigest`）<br>`[証]` `NF06_PRIVACY_RAW_CSV`<br>`[証]` `NF06_SRE_LB`<br>`[証]` `NF06_DBA_ACCOUNT`<br>`[証]` `NF06_STAGING_CHECK`<br>＋評価器: V193 付与・単一法人 | Data Owner の admission 決定と二人による ASCII 順 digest 再計算（`NF06_ADMISSION`）、raw CSV（`IMPORT` 文書）の保持期間と preview マスク分類の Privacy 書面受容（`NF06_PRIVACY_RAW_CSV`）、LB idle timeout 130 秒以上・透過再接続 proxy 無し・単一 instance 確認（`NF06_SRE_LB`）、全 instance 同一アプリ DB user 名・alternating-users rotation 不使用の DBA 確認（`NF06_DBA_ACCOUNT`）、staging（実 MySQL）での LB timeout 後回復と行参照鍵欠落時 503 表示の人手確認および Runner 実測（`NF06_STAGING_CHECK`。担当: production owner） | `feature-switches.md` §4.8、`data-migration-import-center/a1a-ingress-operations.md` §10・§14 |
| `NF07_RETENTION`<br>（行為者 `privacy-admin`。再起動不要） | 鍵: `PRIVACY_JOURNAL_*` 4 項目<br>組 `NF07_DECISION_BINDING`: `[決]` `NF07_D01`・`[決]` `NF07_D09`（`tenantId`・`capabilities`・`approvalDigest`）<br>`[証]` `NF07_BRIDGE`<br>＋評価器: `privacy-admin` 2 名以上・Journal appender 適合 | D-01・D-09 の署名と二人による digest 再計算照合（組 `NF07_DECISION_BINDING`）、production bridge W-1〜W-12 接続確認（`NF07_BRIDGE`）、Journal instance と専用 Flyway job（§3.6）、`privacy-admin` 2 名以上（推奨 3 名） | `feature-switches.md` §4.7、`privacy-retention-dsar/production-bridge-phased-plan.md`、§3.6・§4.4 |
| ├ 副入口 `HOLD_START` | `[決]` `NF07_D05`<br>`[証]` `NF07_D05_OWNER_ACCEPTANCE` | D-05 署名（`NF07_D05`）と、§4.4 末尾 (1)〜(9)・§4.5 の署名者氏名/文書（R-18）・DATA_ELEMENT hold 依存・D-03/trust 整備まで解除不可であることの D-05 owner 受理（`NF07_D05_OWNER_ACCEPTANCE`） | §4.4・§4.5 |
| ├ 副入口 `REL`（hold 解除） | `[決]` `NF07_D03`・`NF07_D05`<br>`[証]` `NF07_TRUST_KMS`<br>`[証]` `NF07_TRUST_STAGING`<br>＋EFS 上の有効な trust 文書 | D-03 署名（`NF07_D03`）、KMS key（`ECC_NIST_EDWARDS25519`）作成・key policy・退役鍵保管方針・EFS 書込み経路と権限の確認（`NF07_TRUST_KMS`）、staging での実 KMS 署名検証・Fargate mount option と失効反映遅れ/EFS 不達時の閉鎖時間の実測（`NF07_TRUST_STAGING`、§4.6） | §4.5・§4.6、`feature-switches.md` §4.7.5 |
| └ 副入口 `REG`（policy 登録・有効化） | `[決]` `NF07_D01`・`NF07_D02`・`NF07_D03`<br>＋EFS 上の有効な trust 文書 | D-02（保存期間等の値）の署名（`NF07_D02`）と D-01・D-03・trust 文書（NF08 外部送信の前提でもある） | `feature-switches.md` §4.7.5、§4.6 |
| `NF07_DSAR`<br>（行為者 `privacy-admin`。再起動不要） | 組 `NF07_DECISION_BINDING`: `[決]` `NF07_D01`・`NF07_D06`（`dueDays`・`packageMaxAge`）・`NF07_D09`（`dsar` を含む）<br>`[証]` `NF07_DSAR_HOLD_ACCEPTANCE`<br>`[証]` `NF07_DSAR_SELF_DELIVERY`<br>`[証]` `NF07_DSAR_I10`<br>`[証]` `NF07_DSAR_REDACTION` | 組の確認時の `dueDays`・`packageMaxAge` と D-06 決定書の目視照合、§4.4 の (8) D14 開示 package 非保全・(9) D15 旧版 uploads 非到達の D-05/D-06 owner 受理（`NF07_DSAR_HOLD_ACCEPTANCE`）、WORK_REPORT・RECEIPT の SELF 交付区分確認（`NF07_DSAR_SELF_DELIVERY`）、承認前の草案全内容取得 I-10 の Privacy 書面受理（`NF07_DSAR_I10`）、承認前確認が使える状態になってから redaction 規則を設定する確認（`NF07_DSAR_REDACTION`） | `feature-switches.md` §4.7.2、計画 §1 I-10、§4.4 |
| `NF10`<br>（再起動不要） | `[決]` `NF10_REPORT_OWNER`<br>`[決]` `NF10_SRE`<br>`[決]` `NF10_TZ_POLICY`<br>`[決]` `NF10_DRAIN_APPROVAL` | `NF10_REPORT_OWNER`（開放署名、確定版配布方針、「12回」連続失敗の数え方と30分 lease に対する所要時間見込み、`RETRY_EXHAUSTED_DISABLED` の業務宛先と対応手順）、`NF10_SRE`（alert rule と NTP・時刻差閾値）、`NF10_TZ_POLICY`（tenant timezone 変更方針）、`NF10_DRAIN_APPROVAL`（排水・検出 SQL・復旧手順承認）。定期配布には `APP_SCHEDULING_ENABLED=true` が必要 | `scheduled-management-reporting/scheduler-claim-fence-plan.md` §10・§11・§14・§15、`feature-switches.md` §4.5 |
| NF08 外部送信<br>（**目録外・常時 OFF**） | `AI_EXTERNAL_SEND_ENABLED` は設定禁止（`true` は起動拒否） | 初回リリース後も B2c-2 着手前の利用者承認（**未取得**）→ B2c-2 実装・独立 Review → S-1〜S-9 と NF07-REG（`AI_EXTERNAL_PROVIDER_COPY` の `APPROVED_ACTIVE`）が全て揃うまで開放しない | `ai-management-copilot/external-send-approval-gate.md` §7.4・§8 |

**W1-B1（backup toolchain）の既知の弱点 W-1〜W-7（RB-W で修正。W-1・W-3 は実測で確定する。未取得）**

W1-B1 で「書面での受理が要る」とされた事項である（FINAL review §7 (a)-5）。2026-11-18 の決定（上線前に修正する）により RB-W で修正した。W-2・W-4〜W-7 は修正済みで、書面での受理は要らない（残余の実測は各行）。W-1・W-3 は修正済みだが、各行の前提が実測で確かめられるまでは「修正済み（実測で確定。未取得）」とし、受理不要とはしない。各行の残余はどれも fail-closed（照合できなければ止まる）で、その意味は **backup job が止まる（RPO が成り立たない）か、復元を cutover できない** ことである。人が行う確認（Runner の実測・DBA の確認）は「未取得」と書いた。W-1 の DBA の前提（復元先の版と `lower_case_table_names`、DEFINER の権限と account、`log_bin_trust_function_creators`）は DBA-1・DBA-4 に統合した（署名一覧は DBA の行の1つだけ）。規則と手順の正本は `ops/backup/README.md`。

あわせて RB-W の追加修正で次を行った。(1) pipefail 下でパイプの後段が途中で読むのをやめ、前段の SIGPIPE でパイプ全体が失敗に見える不具合を、schema fingerprint 以外（binlog の一覧を引く archive・snapshot・checkpoint、plan の binlog 解決、CHECK TABLE の判定、content ID の検査）も直し、本番 script 全体を走査する試験で固定した（commit `bcd1aa26`・`7a032e78`）。(2) backup 工具を MySQL 8.4 LTS に合わせた（commit `54391fa4`・`ddb3e627`・`5bf5aca7`。client 8.4.13 と component `mysql-8.4-lts` だけを受け付ける、`CHANGE REPLICATION SOURCE TO` の起点の読み取り、`RESET BINARY LOGS AND GTIDS`、preflight の 8.4 系列の検査、integration の `mysql:8.4.13`、8.4 で削除された構文を使わないことの試験）。(3) uploads の pointer と切替え・空の uploads・同一 mount などの追加修正 C（commit `de384a91`・`f06d405f`・`82e8ccfb`・`8ec6eba3`・`3789f03f`・`e516be60`・`af45e321`・`2181352f`。uploads を `UPLOADS_ROOT`・`UPLOADS_POINTER`（`current`）の指す版から backup し `UPLOADS_DIR` を廃止、空の uploads の inventory を `[]` にする（X-1）、uploads と staging を1つの mount から渡し preflight も同じ mount でなければ exit 16、`uploads-cutover.sh`（stage・switch・status）、pipefail の走査器の見落とし・`common::trap_remove`・restore が target を変える前に入力を検査すること・preflight の `binlog_format`・dump の起点の読取りの1実装（P3-1・P3-2・P3-4・P3-6・P3-7）、`safe_extract::copy` の失敗の伝播、承認 claim が JSON として読めないときの拒否、CI の harness の 6 件の失敗（W-2 の行））。あわせて uploads の同一 filesystem の判定を st_dev にしたこと（commit `3fb849fb`）、uploads snapshot の失敗時の EXIT trap（commit `fb13669f`）、mysqldump の `--no-tablespaces`（commit `9e8ae033`）も行った。8.4.13 の工具 image の build と backup integration（PITR）は CI で取得した（2026-11-21。PR #125 の run 44877142560 と main `5ddddc2b` の run 44887898852 の `backup integration (real MySQL PITR)`）。digest 固定の実 MySQL 8.4.13 ×2 と client 8.4.13 で、次が PASS した: preflight、full、binlog の archive（`--stop-never`）、checkpoint、restore（full と binlog の replay）、validate-restore（`READY_FOR_CUTOVER`。schema fingerprint の照合を含む）、migrator 相当の利用者が作った trigger・view・function の DEFINER を保った往復（restore target role は README の権限だけ）、uploads の stage→switch→rollback、schema fingerprint の正規化規則の境界、restore drill（`rto_ok`・`rpo_ok`）。本番相当の実測（本番規模の schema（約 294 表・73 trigger）での restore drill と、本番の権限・network・EFS 上での実行）は**未取得**で、取得するまでは本番での 8.4 の backup・PITR は未検証として扱う。W-4 の初回 build の record の確認も未取得（W-4 の行）。

| # | 事項 | 状態（RB-W） | 出処 |
|---|---|---|---|
| W-1 | G-04: schema fingerprint が未実装（内容hashだけを実装した） | RB-W で修正（commit `dd0be321`、追加修正 `bcd1aa26`・`2d0f425b`・`cf420345`）。**修正済み（実測で確定。未取得）**。full / checkpoint が静止区間内に、information_schema の DB の既定の文字集合・照合順序・table・列・索引・制約・外部キー・CHECK・trigger・routine・view を正規化した hash（v2。列の並び順と routine / trigger の `DATABASE_COLLATION` を含み、コメント・統計は含まない）を設定によらず記録し、計算できなければ VALID にしない。復元検証は記録なし・算出方式違い・不一致を FAIL にし、`cutover.sh`・`restore-drill.sh` も PASS を検査する。`restore.sh` は復元 DB を記録した照合順序で作る。pipefail 下の SIGPIPE で「table が見えない」と誤判定する不具合（本番規模の schema で backup が止まり得た）は追加修正で直した。前提（**DBA の確認は未取得**。署名は DBA の行で行う）: 復元先の版と `lower_case_table_names` は DBA-4、restore target role の権限（README「restore target role の権限」）・DEFINER の account・`log_bin_trust_function_creators` は DBA-1、source 側の backup account の権限（README「source 側の account の権限」。`PROCESS` は不要。2026-11-18 利用者決定。migration が tablespace を使わないことが前提で、試験が確かめる）は §1.2 の「backup 用の account」と DBA-1。本番相当の権限での実測は未取得。dump role と binlog role の分離は上線後（2026-11-18 利用者決定）。現状は一つの account が表の全権限を持つ。確定に要る実測: MySQL 8.4 の backup integration での照合 PASS・正規化規則の境界・migrator が作った trigger・view・function の DEFINER を保った往復と、外部キー・CHECK を含む schema fingerprint の一致は CI で取得した（2026-11-21。W 表の前の段落）。本番規模の schema（約 294 表・73 trigger）での restore drill の PASS は**未取得**（Runner） | W1-B1 r2 報告 §5、W1-B1 review、RB-W review |
| W-2 | noclobber: 事前検査と open の間の微小な窓に、FIFO・device への symlink を置かれると開き得る | RB-W で修正（commit `0017700f`。前提の検査の試験 `de384a91`）。修正済み・受理不要。runner は dd の `conv=excl`・`iflag=nonblock` と `mktemp` を前提として確かめ、満たさなければ BLOCKED（2）で止まる（harness の case 39）。この前提を加えたときに harness の timeout 不在の case の検証用 PATH を直さず、PR #125 の CI で 6 件が失敗していた（`de384a91` で直した）。事前検査と noclobber をやめ、`dd conv=excl`（1 回の `open(O_CREAT\|O_EXCL)`）だけで作成する。名前に何かがあれば種類を問わず失敗し symlink も辿らないため、検査と open の間の窓は無い。作成後の確認は O_NONBLOCK・上限付きの読み取り。残余: 作成後に WORK の書込み権限者が名前を差し替えることは事後の内容照合でだけ検出する（前提は r2 と同じ）。Windows 実機（Git for Windows の dd）での実測は**未取得** | 同上 |
| W-3 | `tool-image.sh` の残り: 検査と実行の間に tag を付け替えられる（TOCTOU）。`pull_policy` は「1行以上あるか」しか見ない。flow-style の YAML を解釈しない | RB-W で修正（commit `e7607a8d`）。**修正済み（実測で確定。未取得）**。(a) tag を 1 回だけ image ID へ解決して照合し、compose は照合した image ID に固定した一時 override で起動する。(b) service ごとに merge を解決した実効値で `pull_policy: never`・image・build / extends の不在を検査する。(c) flow style などの解釈しない書き方は理由を示して拒否する（production compose は block style に書き換えた）。前提: docker compose が override の `image: "sha256:<id>"` でその image を起動すること（**Runner の実測が未取得**。起動できなければ backup の全 job が起動に失敗し（fail-closed）、代わりの手段は無い） | 同上 |
| W-4 | 「Essential は base の版を保つ」は厳密でない（依存解決で更新され得る） | RB-W で修正（commit `a95d7a69`）。修正済み・受理不要。説明を事実に直し（依存解決で更新され得る。正本は `dpkg-packages.tsv`、全版の固定は `apt-packages.lock`）、Essential の base からの変化を `essential-packages.tsv` に記録する。Dockerfile の最後の RUN と `tool-image.sh record` が image の実際の dpkg の版と `dpkg-packages.tsv` を照合し、不一致なら失敗する。残余: `apt-packages.lock` は現在空（record-only）で、Essential を含む Debian パッケージの版はまだ固定していない（初回 build の record を確認して lock に反映するまで）。初回 build での実測（**Runner、未取得**） | 同上 |
| W-5 | apt lock の enforced 経路・不一致経路に単体試験が無い（初回 build で実測するだけ） | RB-W で修正（commit `733402a4`）。修正済み・受理不要。system path を `INSTALL_TOOLCHAIN_SYSROOT`（既定は空 = 実際の path。Dockerfile は設定しない）で注入でき、enforced・不一致・空の lock の経路を単体試験する。残余なし（実 apt での確認は W-4 の初回 build の実測と同じ） | 同上 |
| W-6 | 同じ MSYS の別利用者・別 session の判定。`kill -0` の EPERM を「終了済み」として扱う | RB-W で修正（commit `9ab3b3b6`）。修正済み・受理不要。`kill -0` の失敗は ESRCH だけを終了済みとし、EPERM 等は実行中として扱う。所有マーカーに `owner=`（uid）と `session=`（MSYS は Windows の session ID と昇格状態）を記録し、PID 名前空間・利用者・session がすべて一致するときだけ `kill -0` で判定する（それ以外は終了記録を要求する）。残余: session を取得できない Windows 環境では自動退避せず手動の退避になる。RB-W より前のマーカーは 1 回だけ手動の退避が要る。Windows PITR での実測（**Runner、未取得**） | 同上 |
| W-7 | content ID が覆う設定の範囲（Env・Entrypoint・Cmd・WorkingDir・User・Labels だけで、Volumes・ExposedPorts 等を含まない） | RB-W で修正（commit `d28c424f`）。修正済み・受理不要。content ID（v2）に os / arch・Shell・OnBuild・StopSignal・Volumes・ExposedPorts・Healthcheck を加え、項目ごとに正規化する（要素ごとの `{{json}}`、map は key の昇順、`null` と空は同じ。Config 全体の json や store・版で変わる項目は使わない）。残余（fail-closed）: Docker の版で項目を描画できないと照合できずに失敗する。unit の fake docker は template を解釈しないため、template から項目を消す変更は静的な検査だけが検出する。実 Docker で VOLUME・EXPOSE・HEALTHCHECK・STOPSIGNAL だけを足した派生 image の content ID が変わることと、classic / containerd の store 間の save / load での一致は **Runner の実測が未取得** | 同上 |

#### 1.7.1 NF01 二者確認を開く前の手動確認（browser、9項目。署名 `NF01_DUAL_MANUAL_CHECK` の証跡。W3-21b review §6）

画面 `/feature-enablement` で本番の `NF01_DUAL_CONFIRMATION` を開く前に、子単位を開いた staging（実MySQL）で自動browser試験の代わりに次を確かめ、結果を証拠の署名 `NF01_DUAL_MANUAL_CHECK`（`PRODUCTION_OWNER`）として記録する（完全な流れはMySQLが要るため、fastのbrowser試験には入っていない）。担当: production owner（利用者）（決定済み（2026-11-22、利用者））。9項目はどれも**未取得**。

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
  - 例外は、本書が手順として書いたものだけ: §1.2 のアプリ account の password の rotation（`ALTER USER … RETAIN CURRENT PASSWORD` / `DISCARD OLD PASSWORD`）、§3.5・§6 の初回導入の失敗時に schema を作り直すこと、§3.6 の Journal の作り直し、§4.5 の hold の開始者の書換え（D-05 owner の承認）、§5.1 の初期パスワード紛失時の `UPDATE`、§5.8 の各手順（侵害の疑い、TOTP の喪失、break-glass 保持者の交代、`admin` の lock 解除）、§3.4 の backup account への表単位の GRANT（restore で新しい source にした後の付け直しを含む）、§4.7 の backup の書込み静止の保持（静止要求）の手での終了と停止口の行の片付けの2つの SQL（RB-QUIESCE。backup-write-quiesce design.md §9.4。信号表は業務データでも認可 epoch の対象でもなく、NF01 の writer の閉包に関わらない）。どれも DBA が行い、general log を無効にした接続で実行し、実行した文と結果を配備記録に残す（password・hash 等の値は残さない。password を含む文は値を `<...>` に置き換えて残す）。
  - 参照（SELECT）は本書の確認SQLの範囲で行ってよい。
  - 画面・API（監査が残る）で直せないデータの誤りは、手で直さずに Owner と DBA へ上げる。
- **権限に関わる設定を変える配備では、未確認の二者確認の提出を差し戻す（RR-2）**。`ORGANIZATION_SCOPE_ENABLED`（`organization.scope.enabled`）や機能スイッチ等の property の変更は認可epochを進めない。確認時の再評価で拒否側には効くが、2回の配備をまたいで元へ戻る変更（例: ある配備で外し、次の配備で戻す）は検出しない。
  1. 配備の途中、§2 の4（アプリ接続0件）の後、§3 の前に、未確認の提出を一覧して配備記録に残す（停止の前に一覧すると、停止までの間の提出が漏れる）。
     ```sql
     SELECT tenant_id, case_id, task_id, submission_id, updated_at FROM t_lifecycle_dual_projection
      WHERE state = 'PENDING' ORDER BY updated_at;
     ```
  2. 配備の後、一覧の提出を、確認者（提出者以外の管理者またはHR）が画面で差し戻す（理由に「配備で権限の設定が変わったため、再提出してください」と書く）。提出者が改めて提出する。DBは手で変えない（RR-10）。
  3. 対象は画面 `/feature-enablement` で `NF01_DUAL_CONFIRMATION` を一度でも開いた後の配備である（未開放・初回の配備では表が空のため一覧は 0 件）。
  - この運用は NF01 計画 §10 の Identity/Security の受理事項（RR-2。**未取得**）である。起動時に property の指紋を比べて global epoch を進める改修は、同 §9 RR-2 の別の検討事項のまま。

## 2. 全停止とdrain（SRE）

初回導入では稼働中のinstanceは無い。§2.1〜§2.3は2回目以降のreleaseの手順で、初回は「アプリinstanceが1台も起動していないこと」の確認だけを行う。

0. **backup の定期起動を止める**（RB-QUIESCE。U-5。backup-write-quiesce design.md §8・§11）: backup host の checkpoint（15分ごと）と full（02:00）の定期起動を止め（止め方は §1.7 の 004a の inventory で決めたもの）、実行中の job が終わったことを確かめる（`ops/backup/image/tool-image.sh compose ps` に `checkpoint`・`backup` の container が無いこと）。binlog の archive（`binlog`）は止めない。止めた時刻を配備記録に残し、§4.7 で再開するまでの checkpoint の停止による RPO の alert（`check-backup.sh` の checkpoint 30分超）は計画停止として抑止する。初回導入では、定期起動がまだ動いていないことを確かめる。
   - 理由: backup の静止と Flyway job（§3）の DDL を重ねない。Flyway job は backup の保持 process と同じ DDL 凍結の lock（`ses_backup_ddl_freeze`）を取るため、止め忘れても DDL と静止は重ならない（job が最長120秒待つか、取れずに1件も適用せずに失敗する。§3.5）が、止めてから進める。DBA の手作業の DDL も同じ lock を先に取る（§3.5）。
   - ONLINE の checkpoint・full の最中に app を止めると、その回は VALID にならない（必要な instance が0になる）。job の終わりを待ってから 1 へ進む。
1. LB/ingressを保守表示にし、新しいrequestを止める。
2. **scheduler drain**: 全instanceを `APP_SCHEDULING_ENABLED=false`（`app.scheduling.enabled=false`）で再起動し、`@Scheduled` を止める。定期管理レポートの実行中leaseが無いことを `scheduler-claim-fence-plan.md` §10.3 のQ1で確認する（0件、または全件がlease期限30分を超過）。手動でclearしない。drain 用の task definition は `-Phase Steady -Scheduler Disabled` で照合し（§4）、release の後に常態へ戻す revision は、scheduler を有効化した後（§4）なら `-Scheduler Enabled` で照合する（戻し忘れると中核jobが黙って止まる）。
3. 全instanceを停止する（SIGTERM。`server.shutdown: graceful` で処理中requestを排水してから終了する）。graceful の停止では、業務の接続 pool を閉じた後に信号表の instance の行が `STOPPED` になる（RB-QUIESCE。停止の時の排水が10秒で終わらなければ `STOPPED` を書かず WARN `errorCode=BACKUP_QUIESCE_STOP_UNCONFIRMED` を出す。`stopTimeout` の内に終わらず SIGKILL された場合も `STOPPED` にならない）。
4. DBへのアプリ接続が0件であることを確認する。
   ```sql
   SELECT COUNT(*) FROM information_schema.processlist WHERE user = '<アプリaccount>';  -- 0
   ```
   2回目以降の release では、信号表の instance の行も確かめる（RB-QUIESCE。5 の `--app-stopped` の判定と同じ。参照だけ）。
   ```sql
   SELECT instance_id, status, gate_state, TIMESTAMPDIFF(SECOND, last_beat_at, UTC_TIMESTAMP(6)) AS beat_age_seconds
     FROM sys_backup_quiesce_instance WHERE status <> 'STOPPED';
   -- 期待は0行（全て STOPPED）。行がある場合は beat_age_seconds が 300 を超えるまで待つ
   ```
   - `STOPPED` にならずに止まった instance（SIGKILL・task の喪失・3 の `BACKUP_QUIESCE_STOP_UNCONFIRMED`）の行が残ると、5 は最後の heartbeat から `QUIESCE_DEAD_SECONDS`（300秒）の間「停止していない app の instance があります」で失敗する。300秒を待ってから 5 へ進む（backup-write-quiesce design.md L-19）。
   - `gate_state = 'DISABLED'` の行（停止口で動いていた instance）は待っても外れない。§4.7 の「停止口の行の片付け」を行う。
5. **backup を取る**。2回目以降の release は §3 の前、初回は §3 の完了直後（§3.4 の表単位の GRANT の後、§4 の前）。どちらも app の停止を DB の信号表で確かめる full（RB-QUIESCE。provider `quiesce-app-stopped`。backup-write-quiesce design.md §9）で取る。
   ```bash
   ops/backup/image/tool-image.sh compose run --rm backup --app-stopped
   ```
   - 前提: 0〜4 が済んでいること（アプリ接続0件と instance の行）。§1.7 の「Backup 開始の前提」（production 固有値の BLOCKED の解除。`.env.backup` の `FLYWAY_VERSION` は、初回は §3.4 の最新 version、2回目以降は §3 の前なので今の version のまま）。backup account の要求の表への表単位の `INSERT`・`UPDATE`（初回は §3.4 で付ける。2回目以降は付いたままで、full の前に `ops/backup/image/tool-image.sh compose run --rm preflight` を実行し、`checks.quiesce` の `status: "ok"`・`request_table_grant: true` で確かめる。restore の後は付け直す。full も始めに同じ preflight を実行し、不合格なら始めない）。
   - `--app-stopped` は明示の引数でだけ選ぶ（`.env.backup` の `QUIESCE_PROVIDER` は `quiesce-db` のまま。`QUIESCE_PROVIDER=quiesce-app-stopped` を環境変数で渡すと job は始まらない）。定期の full・checkpoint には付けない。
   - 成功の確認: 結果の JSON（job の stdout）の `status: "VALID"`・`quiesce.provider: "quiesce-app-stopped"`・`quiesce.mode: "APP_STOPPED"`・`quiesce.request_id` を配備記録に残す（`quiesce.json` の全体は貼らない。`ops/backup/README.md`「書込み静止の記録（quiesce.json）」）。
   - 失敗した場合: 「停止していない app の instance があります」は 4 の instance の行（300秒の待ち）、「停止口…の instance の行があります」は §4.7 の片付け、「他の静止要求が有効です」は残った要求（§4.7 の確認の SQL。backup の job が落ちた後なら lease の60秒で切れる）を確かめる。「DDL 凍結の lock（ses_backup_ddl_freeze）を取得できません」は、Flyway job（§3）・DBA の DDL・他の backup の job が lock を持っていないかを確かめる。
   - **この full が終わるまで app を起動しない**: 保持の間に起動した app は port を開く前に `errorCode=BACKUP_QUIESCE_STARTUP_ABORT`（「app の停止を確かめる backup（APP_STOPPED、要求の番号 …）の保持中です」）で起動を中止する（ECS が起動し直す。§4.7）。
   - 初回の backup の前に、uploads の目録（§1.6 の11 の (d)・(f)）ができていることを backup の host で確かめる。`current` が symlink で、指す先が単段の名前で、その名前の実体の directory（symlink でない）があること。
     ```bash
     test -L /mnt/ses-files/uploads/current && readlink /mnt/ses-files/uploads/current   # 期待: v0（「/」と「..」を含まない名前）
     t=$(readlink /mnt/ses-files/uploads/current) && [[ "$t" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] \
       && test -d "/mnt/ses-files/uploads/$t" && ! test -L "/mnt/ses-files/uploads/$t" && echo "current -> $t OK"
     test -d /mnt/ses-files/backup-staging
     ```
     （path は `ops/backup/docker-compose.yml` が bind する固定の path。`readlink` に `-f` を付けない。同じ確認は `ops/backup/uploads-cutover.sh status --root /mnt/ses-files/uploads` でもでき、期待は rc 0・`target=v0`、`leftover=` と `error=` が無いこと。読むだけ）
   - 続けて backup の host で `ops/backup/image/tool-image.sh compose run --rm preflight` を実行し、0 で終わり、JSON の `checks.uploads` が `status: "ok"`・`version: "v0"`・`staging_same_filesystem: true` であることを配備記録に残す（§1.6 の11 (d) の SRE の確認。§1.7 の「Backup 開始の前提」の 001 と同じ出力）。同じ出力の `checks.quiesce` が `provider: "quiesce-db"`・`status: "ok"`・`signal_tables: 3`・`request_table_grant: true` であることも残す（RB-QUIESCE。初回は §3.4 の GRANT の後に実行する。V202 の前・GRANT の前は exit 19）。

- 権限に関わる設定（`ORGANIZATION_SCOPE_ENABLED`・機能スイッチ等）を変える release では、4の後（アプリ接続0件）、§3 の前に §1.8 の RR-2 の1を行い、配備の後に2を行う。

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
- 実行順: V1〜V203（versioned 202件。SQL 201件＋Java migration `V74_3` 1件。V199 は欠番）→ Repeatable（説明の昇順: `crm contact reconciliation` → `remove v2 sample data` → `update admin password bcrypt`）。
- callback: `ProductionAdminInitialPasswordFlywayCallback`（hash検査）→ `ProductionBackupDdlFreezeFlywayCallback`（backup の DDL 凍結の lock）→ `LegacyDatabaseFlywayCallback`（空DBでは何もしない）。
- **backup の DDL 凍結の lock（RB-QUIESCE BWQ-008。U-5）**: migrate（と repair）の前に、job は専用の DB 接続で `SELECT GET_LOCK('ses_backup_ddl_freeze', 120)` を実行し、backup の保持 process が同じ lock を持っていれば最長 120 秒待つ。取れなければ `backup が実行中のため migration を始めません（ses_backup_ddl_freeze）` で始まる message で失敗し、migration は1件も適用しない（§3.5）。lock は job の終わり（成功と migration の失敗のどちらでも）で返し、job の process が落ちた場合は MySQL が返す。待ち時間はコードの定数で、環境変数では変えない。§2 の0 で backup の定期起動を止め、実行中の job の終わりを確かめていれば待たずに取れる。2回目以降の release では §2 の5 の full（`--app-stopped`）が終わった後に job を始める（full の保持 process が lock を持つ間は job が待つ）。
- 注意: Flyway は履歴表（`flyway_schema_history`）が無い DB では、migrate の前（lock を取る前）に履歴表を作る。これは初回の空の DB だけで起き、業務の表には触れない。

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
 ORDER BY installed_rank DESC LIMIT 1;                                                         -- '203'
-- versioned migrationの適用件数（本releaseの総件数）
SELECT COUNT(*) FROM flyway_schema_history WHERE version IS NOT NULL AND success = 1;          -- 202
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
-- NF07 F2（V196・V197）と NF01 二者確認の登録（V198）と機能の開放の署名（V203）: catalog は構造行だけ。tenant default に文書以外の固定38件
-- （V196 の27件と V198 の9件と V203 の2件）と、文書種別ごとに1件（文書種別の数に依存しない数え方にする）。owner・分類・inventory は全て UNKNOWN
SELECT COUNT(*) FROM m_pii_data_element
 WHERE tenant_id = 'default' AND data_element_id NOT LIKE 'DOCUMENT:%';                    -- 38
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
-- backup の書込み静止の信号表（V202）: 3表があり空で、有効枠 active_slot は STORED の生成列で一意索引を持つ
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN
 ('sys_backup_quiesce_request', 'sys_backup_quiesce_instance', 'sys_backup_quiesce_ack');   -- 3
SELECT (SELECT COUNT(*) FROM sys_backup_quiesce_request) + (SELECT COUNT(*) FROM sys_backup_quiesce_instance)
     + (SELECT COUNT(*) FROM sys_backup_quiesce_ack);                                         -- 0
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
   AND table_name = 'sys_backup_quiesce_request' AND column_name = 'active_slot'
   AND extra LIKE '%STORED GENERATED%';                                                        -- 1
SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE()
   AND table_name = 'sys_backup_quiesce_request' AND index_name = 'uk_bq_request_active'
   AND column_name = 'active_slot' AND non_unique = 0;                                         -- 1

-- 機能の開放（V203）: 9表があり空で、未決の申請・確認済みの署名・確認済みの組の生成列は STORED、追記専用の3表に trigger が各2件
SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name LIKE 't\_feature\_%';   -- 9
SELECT (SELECT COUNT(*) FROM t_feature_enablement_generation) + (SELECT COUNT(*) FROM t_feature_enablement_request)
     + (SELECT COUNT(*) FROM t_feature_enablement_state) + (SELECT COUNT(*) FROM t_feature_enablement_setting)
     + (SELECT COUNT(*) FROM t_feature_sign_off) + (SELECT COUNT(*) FROM t_feature_sign_off_extension)
     + (SELECT COUNT(*) FROM t_feature_sign_off_bundle) + (SELECT COUNT(*) FROM t_feature_sign_off_bundle_member)
     + (SELECT COUNT(*) FROM t_feature_enablement_event);                                     -- 0
SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
   AND table_name LIKE 't\_feature\_%' AND column_name IN ('pending_slot', 'active_slot')
   AND extra LIKE '%STORED GENERATED%';                                                        -- 3
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()
   AND event_object_table IN ('t_feature_enablement_event', 't_feature_sign_off_bundle_member',
   't_feature_sign_off_extension');                                                          -- 6
SELECT COUNT(*) FROM m_menu m JOIN t_role_menu r ON r.menu_id = m.id WHERE m.menu_key = 'feature-enablement'; -- 1（管理者だけ）

-- helper procedureが残っていない・trigger件数
SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema = DATABASE();           -- 0
SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE();           -- 73（根拠は §1.2。V194までの55件は2026-11-14 R-3予行演習（MySQL 8.0.36・offline job）の実測と一致し、V195の12件とV203の6件を加えた値。V195・V203込みの実測は未取得）
-- 73でない場合の切り分け: 表ごとの件数を migration の CREATE TRIGGER と比べる（V195 の6表は各2件、projection は0件。V203 の追記専用3表は各2件）
SELECT event_object_table, COUNT(*) FROM information_schema.triggers WHERE trigger_schema = DATABASE()
 GROUP BY event_object_table ORDER BY event_object_table;
```

- `R__remove_v2_sample_data.sql` は「本scriptの成功履歴が無い」かつ「sample 5表がV2の行だけ」のときだけ削除する。条件を満たさない場合は何も削除せず成功扱いになるため、上の件数が0でなければ実データ混入の可能性として停止し、DBAと調査する（手作業で消さない）。
- **backup account への表単位の GRANT**（RB-QUIESCE。U-11。§1.2）: 初回は、上の確認が全て期待値になった後、§2 の5 の初回 backup の前に、DBA が管理用の account で付ける（general log を無効にした接続で行い、実行した文と結果を配備記録に残す。§1.8 の RR-10 の例外）。V202 が表を作った後でなければ付けない（存在しない表への表単位の GRANT は通らない。CI の MySQL 8.4.13 で拒否を実測した。§1.7 の BQ-5）。2回目以降の release では付いたままで、確かめるだけ（restore で新しい source にした後は付け直す。`ops/backup/runbooks/restore-cutover.md` §4.6）。
  ```sql
  GRANT INSERT, UPDATE ON ses_manager_db.sys_backup_quiesce_request TO '<backup account>'@'<host>';
  SHOW GRANTS FOR '<backup account>'@'<host>';
  -- §1.2 の schema 単位・global の行に加えて、sys_backup_quiesce_request への表単位の INSERT, UPDATE の行が1つある。
  -- 他の表への INSERT・UPDATE・DELETE と PROCESS が無い
  ```
  続けて backup の host で `ops/backup/image/tool-image.sh compose run --rm preflight` を実行し、0 で終わり、JSON の `checks.quiesce` が `provider: "quiesce-db"`・`status: "ok"`・`signal_tables: 3`・`request_table_grant: true` であることを配備記録に残す（§2 の5 の preflight と同じ）。初回はこの後に §2 の5 の full を取る。

### 3.5 jobが失敗した場合

- **backup の DDL 凍結の lock を取れずに失敗した場合**（`backup が実行中のため migration を始めません（ses_backup_ddl_freeze）`）: migration は1件も適用されていない（初回の空の DB では履歴表だけが空のまま在り得る）。backup の timer が止まり、実行中の backup job が終わったこと（`tool-image.sh compose ps`）を確かめてから §3.3 を再実行する。`flyway repair` は不要。`lock（ses_backup_ddl_freeze）を確かめられないため` で始まる message は DB の接続・権限の失敗で、接続を直してから再実行する。DBA の手作業の DDL も、同じ lock を先に取ってから行う（`SELECT GET_LOCK('ses_backup_ddl_freeze', 0);` が 1 を返すこと。backup-write-quiesce design.md §8）。
- **migration は終わったが job の log に `errorCode=BACKUP_DDL_FREEZE_LOST`（`migration の途中で DDL 凍結の lock（ses_backup_ddl_freeze）が失われた可能性があります`）の WARN が出た場合**（RB-QUIESCE。2c review P3-1）: job の終わりの `RELEASE_LOCK` で lock を持ち続けていたと確かめられなかった（lock の接続が migration の途中で閉じられた等。job は lock の session の `wait_timeout` を 86400 秒にしているが、network の切断等では閉じ得る）。migration の結果は Flyway の出力のとおりで、job の失敗にはしない。(1) backup の timer が止まっていたこと（§2 の初め）と、job の開始から終了までの間に backup の job（checkpoint・full）が動いていないこと（backup host の job の記録と index の `consistency_time_utc`）を確かめる。(2) その間に VALID になった checkpoint・full があれば、DDL の途中の schema を写した可能性があるため、配備記録に「復元に使わない」と番号つきで残し、§4 の後に full を手で取り直す（`tool-image.sh compose run --rm backup`）。(3) DBA が DB の `wait_timeout`・接続の経路（idle の接続を切る中継が無いこと）を確かめる。
- **hash検査・placeholder未設定で失敗した場合**: migrationは1件も実行されていない（履歴表は空、またはそもそも無い）。環境変数を直して§3.3を再実行する。`flyway repair` は不要。
- **migrationの途中で失敗した場合（初回導入）**: MySQLのDDLはtransactionで戻らないため、失敗行（`success = 0`）と途中までのschemaが残る。初回は業務dataが無いので、`flyway repair` で続行せず、**DBAがschemaを削除して空で作り直し**、原因を直してから§3.3を最初からやり直す。`flyway clean` は使わない（設定で拒否している）。
- **2回目以降のreleaseで失敗した場合**: §2.5で取得したbackupから戻すことを基本とする。`CLAUDE.md` のrepair手順はlocal開発DB向けであり、本番では許可済みscriptの確認・backup・DBA承認を経てだけ使う。

### 3.6 NF07 Journal インスタンスの専用Flyway job（NF07-A1J）

NF07 の hold の正本となる独立 Journal インスタンス（schema `privacy_journal`）の DDL は、application DB とは別の location（`db/privacy-journal`）・別の履歴表（`flyway_schema_history_privacy_journal`）・別の job で適用する（`journal-witness-grant-design.md` §2.5）。版番号は application の `db/migration` と独立している（Journal は V1 から）。

- **画面 `/feature-enablement` で `NF07_RETENTION` を開く前に実行する**（go-live 自体の必須前提ではない。§1.7）。Journal インスタンスの作成・DB user・GRANT は外部作業（G-DBA / G-BKP。`journal-witness-grant-design.md` §3）で、本job は `NF07_RETENTION` を開放する前（`PRIVACY_JOURNAL_*` を入れた起動の前）に、DBA がインスタンスを用意した後で実行する（インスタンスの用意・本job の実行とも**未取得**）。初回の起動（bootstrap）と `NF07_RETENTION` 開放前の配備では journal の設定を入れなくてよい（journal 設定が無ければ application は Journal へ一度も接続しない）。
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
- 起動時検査と開放条件の評価（`PrivacyJournalStartupVerifier`、FE-C03）: `server_uuid` の相違、上の耐久設定、`DATABASE()`、principal の権限（`information_schema` と `CURRENT_ROLE()`。role 経由の GRANT は不可）、本節の履歴表が artifact と一致（版・script 名・checksum）、`t_privacy_projection_state.journal_id` の一致を確かめる。不適合（設定欠落・reader principal を含む）があっても **prod の起動自体は止めず**、WARN `NF07_PRIVACY_JOURNAL_UNAVAILABLE`（reader のみ適合なら `NF07_PRIVACY_JOURNAL_APPENDER_UNAVAILABLE`）と固定の理由 code を出し、開放単位 `NF07_RETENTION` を開けない（開いていれば自動閉鎖する。hold の効力確認は reader でも動く）。driver の接続失敗（認証失敗を含む）も固定文言に置き換えるため、Hikari の log に principal・接続先は出ない。`com.zaxxer.hikari` の log を DEBUG にしない。
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
- **§2 の5 の full（`--app-stopped`）が終わってから起動する**（RB-QUIESCE）。保持の間に起動した app は port を開く前に `errorCode=BACKUP_QUIESCE_STARTUP_ABORT` で起動を中止する（§4.7）。`BACKUP_QUIESCE_ENABLED` は `true`（§1.0 の31。雛形の値）のまま起動する。
- **起動の前に upload root の `current` を確かめる（初回は必須。restore の切替えの後も同じ）**。uploads の access point を mount した host（backup の host なら `/mnt/ses-files/uploads`）で `test -L <uploads>/current && readlink <uploads>/current` を実行し、symlink であって、指す先が単段の名前（`^[A-Za-z0-9][A-Za-z0-9._-]*$`。`/` と `..` を含まない。初回は `v0`）で、その名前の実体の directory（symlink でない）があることを確かめる（§1.6 の11 (f) の command、または `ops/backup/uploads-cutover.sh status --root <uploads>` が rc 0 で `target=<版>`、`leftover=` と `error=` が無いこと）。無い・実体の directory・絶対 path・`..` を含む・指す先が無い場合は起動しない。`current` が無いまま起動すると app が実体の directory として作り、pointer が黙って失われる（§1.6 の11 (f)）。restore の切替えの後は、あわせて DB と pointer が一対であること（`ops/backup/runbooks/restore-cutover.md` §4.5.1 の 7）を確かめてから app と scheduler を起動する。task definition の `UPLOAD_BASE_PATH=/app/uploads/current` は validator が見るが、EFS の中身は見ない。
- **起動の前に、登録する task definition を照合する（SRE-10）**。`infra/ecs/offline/validate-task-definition.ps1` を実値の mode で実行し、0 で終わることを確かめる。証跡は既定で OS の一時 directory の下に作り、repository には書かない。証跡には設定の名前と期待した状態だけを書き、設定の値（ID・host・ARN 等）は書き写さない。`-EvidenceRoot` で配備記録の保管場所を指定する。PowerShell 7.5 以降か Windows PowerShell 5.1 で実行する（内蔵の負例自己検査は実行中と同じ PowerShell で子 process を起こすため、pwsh の無い 5.1 だけの環境でもそのまま動く。省くなら `-SkipInlineNegativeSelfTest`）。登録する JSON からは `_offlineAcceptanceNotes` を消しておく（ECS は未知の field を拒否し、実値の mode も FAIL にする）。
  ```powershell
  # 初回起動（§4.2 の bootstrap 設定。scheduler は止める）。期限は起動の直前に設定して照合する（24時間を越えると FAIL）
  pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Initial -TaskDefinitionPath <登録するJSON> -EvidenceRoot <repository外のdirectory>
  # §5.5 の bootstrap を終える起動と以後の起動。-Scheduler は必須で APP_SCHEDULING_ENABLED の期待値を指定する:
  #   scheduler の署名（下の「schedulerは」）の前と、release の drain 用（§2 の2）は Disabled、署名後の常態は Enabled
  pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Steady -Scheduler Disabled -TaskDefinitionPath <登録するJSON> -EvidenceRoot <repository外のdirectory>
  pwsh infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Steady -Scheduler Enabled -TaskDefinitionPath <登録するJSON> -EvidenceRoot <repository外のdirectory>
  # pwsh の無い Windows PowerShell 5.1 だけの環境（引数は上と同じ）
  powershell -NoProfile -ExecutionPolicy Bypass -File infra/ecs/offline/validate-task-definition.ps1 -Mode Actual -Phase Initial -TaskDefinitionPath <登録するJSON> -EvidenceRoot <repository外のdirectory>
  # 雛形（repository の infra/ecs/task-definition.prod.json）だけを検査する場合は引数なし（-Mode Template）
  ```
  - 実値の mode が見るもの: §1.0 の1〜26（freee を使うなら27〜30）と運用設定の名前、設定の名前が英数字と `_`・`.`・`-` だけであること（`app.features[nf01].enabled` 等は FAIL）、同じ設定を2回（environment と secrets の両方、別の綴りを含む）書いていないこと、secret を environment に置いていないこと（property の正規名の綴り（`SPRING_DATASOURCE_PASSWORD` 等）と、secret らしい名前（`*_SECRET`・`*_PASSWORD`・`*_KEY`・`*_KEYS_*`・`*_PEPPER`。key version の `*_CURRENT_KEY_VERSION` を除く）を含む）、§1.0 で禁止した値が無いこと、値で判定する設定（scheduler・bootstrap・`FEATURE_ENABLEMENT_ALLOWED`・禁止値の対象等）を secrets に置いていないこと、`SPRING_PROFILES_ACTIVE` が大文字小文字まで `prod` で `SPRING_PROFILES_INCLUDE`・`SPRING_CONFIG_*` が無いこと、`environmentFiles`・`volumesFrom` が無く app の container が image の `command`・`entryPoint` を上書きしないこと、**全 13 開放単位の旧い開閉変数（`$legacySwitchKeys`）と署名・設定へ移した承認設定（`$legacySettingPrefixes`）が `Initial`・`Steady` のどちらの phase でも `environment`・`secrets`・`SPRING_APPLICATION_JSON` に 1 つも無いこと（FE-E01）**、`FEATURE_ENABLEMENT_ALLOWED` が environment に 1 つだけ原文 `true`/`false` であり他の `FEATURE_ENABLEMENT_*`・`APP_FEATURE_ENABLEMENT_*` が無いこと、security・scheduler・upload・management・`spring.main`・転送ヘッダーの設定を `application.yml` に宣言された短い名前以外（`APP_SECURITY_BOOTSTRAP_ENABLED`・`APP_UPLOAD_BASE_PATH`・`MANAGEMENT_*` 等の正規名）で渡さず、`spring.flyway.*` を設定していないこと、`JAVA_TOOL_OPTIONS` 等に `-Duser.timezone` 以外の `-D`（引用符付きを含む）・`@argfile`・`-XX:VMOptionsFile`・`-XX:Flags` が無いこと、`SPRING_APPLICATION_JSON` を secret store からも別の綴り（`spring_application_json` 等）でも渡さず、environment の `SPRING_APPLICATION_JSON` が検査対象の設定（`management`・`compliance`・`app.upload`・`app.feature-enablement`・`app.features`・`app.import-center` 等を含む）を上書きしないこと、placeholder が残っていないこと、2つの image が registry digest で固定されていること、task の形（app と ClamAV の sidecar だけ、task と container の大きさ、sidecar は essential でなく、root filesystem が読取り専用で、自分の bind volume だけを mount し、secret・app の設定・port を持たない）、`FILE_SCANNER_HOST=127.0.0.1`・`FILE_SCANNER_PORT=3310`、`UPLOAD_BASE_PATH=/app/uploads/current`（雛形の mode でも見る。§1.6 の11 (f)）、`METRICS_SCRAPER_ENABLED=true`、EFS の ID と2つの volume（uploads は読書き、`privacy-trust` は読取り専用で別の access point かつ `containerPath` が `/`・`/app`・`/app/config*`・`/app/uploads*` でないこと。どちらも IAM 認可 `iam=ENABLED`）、`TZ`、scraper 口座が `admin`・break-glass 名でないこと。
    - `-Phase Initial` は加えて `APP_SCHEDULING_ENABLED=false`・`SECURITY_BOOTSTRAP_ENABLED=true`・期限（offset 付き ISO-8601 で、現在から24時間以内の未来）・空の `BREAK_GLASS_USERNAMES`・空の `PRIVACY_TRUST_KMS_ANCHOR_FILE` であること。
    - `-Phase Steady` は bootstrap の設定が外れ、`BREAK_GLASS_USERNAMES` がちょうど2名、`APP_SCHEDULING_ENABLED` が `-Scheduler` の期待どおり（Disabled は `false` だけ、Enabled は `true` か未設定）、`PRIVACY_TRUST_KMS_ANCHOR_FILE` が空か読取り専用の mount の下の file であること。
  - 見ないもの: secret の値（起動時の validator が見る）、ALB・security group、EFS の file system policy と task role の権限（§1.6 の11）、service の desired count と deployment の設定（SRE-5）。§5.7 の bootstrap 再開は対象外。
  - `-D` は `-Duser.timezone` 以外を全て FAIL にする（設定は環境変数で渡す）。`-Dfile.encoding` 等の正当な JVM の設定が必要になったら、validator の許可を変える（変更は復審を経る）。
  - validator を変えたときは、`infra/ecs/offline/test-validate-task-definition.ps1` で規則ごとの正例・負例を pwsh と Windows PowerShell 5.1 の両方で流し、`0 mismatched` を確かめる（AWS・Docker 不要。CI では動かない）。
- 起動時schema guard: DBの `flyway_schema_history` とartifactのmigrationを照合し、一致しなければTomcatのport・schedulerを開始する前に起動を中止する。
  - 成功: INFO log `[schema guard] DBのschemaはartifactの最新migration V203 と一致しています`
  - 失敗: ERROR log `[schema guard] DBのschemaがartifactと一致しないため起動を中止します。...`（未適用・失敗行・artifactに無いmigration・最新versionの不一致・一度も適用されていないRepeatableを列挙する）。jobの結果（§3.4）とartifactのSHA（§1.4）を確認する。
  - guardはplaceholderを置換しないため、アプリへ初期管理者hashを渡す必要は無い。placeholderを含むRepeatable（初期管理者）は「同じscriptの成功適用が履歴にある」ことだけを確認する。jobが `db/migration-prod` を落として実行された（`-Dflyway.locations` 等の上書き）場合は、prodのRepeatableが一度も適用されていないとして起動を止める。
- `/actuator/health/readiness` がUPであることを確認する。UPであれば §1.0 の18 の鍵検査も通っている（検査に失敗した起動はUPにならずに停止する）。
- **readiness が UP になった後、backup の書込み静止の instance の行を確かめ、backup を再開する**（RB-QUIESCE。§4.7 の1〜4）: instance の行（1行、`RUNNING`、heartbeat が10秒以内、`gate_state = 'OPEN'`）を確かめ、`.env.backup` の `FLYWAY_VERSION` を §3.4 の最新 version にしてから §2 の0 で止めた定期起動を再開し、再開後の最初の ONLINE の checkpoint が VALID になることを確かめて配備記録に残す（初回は §1.7 の 004b）。
- schedulerは、必要な確認の後に `APP_SCHEDULING_ENABLED=true` で再起動して有効化する。この task definition は `-Phase Steady -Scheduler Enabled` で照合し、以後の常態の revision も同じく照合する。
  - scheduler は file 清掃・契約更新・freee・消込等の中核 job に加え、**機能の開放の定期評価・日の始まりの評価・署名失効接近通知・DSAR 閉鎖中の未完了請求通知（`FeatureEnablementEvaluationScheduler`）と署名消去（`FeatureSignOffPurgeScheduler`、D17）とも共用**である（`APP_SCHEDULING_ENABLED=false` の間はこれらも止まる。`feature-switches.md` §4.1）。したがって通常運用では必ず有効にする。
  - 履歴取込の清掃（02:00）は、文書台帳の副本を廃棄候補にする。ただし処分には管理者の承認と執行が要り、初回配備は `RESUME_LEDGER_COPY_ALIGNMENT_ENABLED=false` で止めるため、V197 の署名事項は scheduler の有効化の前提にしない（署名までは `RESUME_INGESTION` の副本の廃棄申請を承認しない。§4.3）。
- **機能の開放**: task definition に機能別のスイッチ環境変数は置かない（置くと起動も照合も失敗する。配置するのは `FEATURE_ENABLEMENT_ALLOWED=true` だけ）。全 13 件の開放単位は `CLOSED` のまま起動し、§5.5 で bootstrap を終えた後に画面 `/feature-enablement` で 1 件ずつ開く（§5.5.1・`feature-switches.md` §3）。

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
- **画面「機能の開放」（`/feature-enablement`・`/api/feature-enablement/**`）は bootstrap session では使えない**（`BootstrapSessionFilter` と `FeatureEnablementActorPolicy` の両方が 403 で拒否する。break-glass session も同じ）。機能の開放は §5.5 で bootstrap を終え、OIDC＋MFA の管理者が揃った後にだけ行う。
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

### 4.4 NF07 保全holdと破壊的経路（D1〜D18。NF07-A1H-2、D14 は NF07-A2b、D15 は RB-W、D16 は RB-QUIESCE、D17・D18 は FE-A06）

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
| D15 | uploads の旧版 directory（`v<n>`）と切替えの残り（`.stage-*`・`.<pointer>.switch-*`）の削除（人手。RB-W） | 接続しない（app の経路ではない。下の「D15」の手順で守る） | — |
| D16 | backup の書込み静止の信号表の保存期間の削除（`BackupQuiesceResponder` が1日1回、有効な要求が無い時に `JdbcBackupQuiesceSignalRepository.purgeRequests`・`purgeInstances` で物理削除する。30日。U-12。RB-QUIESCE） | 接続しない（対象外。下の「D16」） | — |
| D17 | 機能の開放の終わった署名（`SUPERSEDED`・`EXPIRED`・`REVOKED`・`REJECTED`）の氏名・元の file 名・文書の消去（`FeatureSignOffPurgeScheduler` 03:40、`purge_after` は終えた日（失効は失効日）の3年後。feature-enablement-console design §6.6。FE-A06） | 設定の tenant に回の初めに1回。data element `FEATURE_SIGN_OFF_SIGNER_NAME`・`FEATURE_SIGN_OFF_DOCUMENT`（`NOT_ATTRIBUTABLE`）を element 単位で判定する | その回を消さない（HELD・UNCLASSIFIED・UNAVAILABLE。gate の Bean が無い時も消さない）。行は `purge_after` を過ぎたまま残り、次の回に判定し直す |
| D18 | 機能の開放の登録されなかった署名の文書の実体の削除（登録の transaction の rollback（`FeatureSignOffRegistrar` → `FileStorageService.discardUnregistered`）と scan の拒否（`FileStorageServiceImpl.store`）。持ち主の署名の行と metadata の行がどの tenant にも無いことを確かめ、backup の書込み静止の最中は消さない。FE-A06・review rb-fe-a1 P3-1・1a4fd48d 差分 P3-N1） | 接続しない（対象外。持ち主の行も metadata も作られなかった、受け付けていない upload の実体で、どの記録・本人・element にも結べない。消せなかった実体は D10 の孤児の清掃が消す） | — |

- **監視**: metric `ses.privacy.hold.path.skipped`（tag `path`＝D1〜D9・D17、`verdict`＝`HELD`/`UNCLASSIFIED`/`UNAVAILABLE`）、`ses.privacy.hold.file.cleanup.aborted`（tag `stage`＝`gate`/`resolve`。D10 の中止）、`ses.privacy.hold.file.unprotectable`（tag `element`。D10 で守れない外部の実体）、gate の `ses.privacy.hold.gate.unavailable`。WARN `NF07_PRIVACY_HOLD_PATH_SKIPPED`（batch）・`NF07_PRIVACY_HOLD_PATH_REFUSED`（D1・D2）・`NF07_PRIVACY_HOLD_SUBJECTS_UNRESOLVED`・`NF07_PRIVACY_HOLD_FILE_CLEANUP_ABORTED`・`NF07_PRIVACY_HOLD_FILE_UNPROTECTABLE`。`UNAVAILABLE` が続くと期限超過の保持になるため、閾値と通報先は SRE が決める（plan §3.4.3 の7、UNKNOWN）。D7・D9 は1つの tenant の停止で全 tenant の kind が止まるため、`path=D7`・`path=D9` も監視に入れる。D10 の中止が続くと孤児清掃（PII を含む取込の一時 CSV・quarantine の失敗実体を含む）が全 tenant で止まるため、`ses.privacy.hold.file.cleanup.aborted` も同じく監視する。log に tenant・subject は出ない。
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

**D15: uploads の旧版 directory と切替えの残りの削除（RB-W。人手。gate に接続しない＝手順で守る）**

| 経路 | 消すもの | 起動 |
|---|---|---|
| 旧版の削除 | `ops/backup/uploads-cutover.sh switch` で pointer（`current`）から外れた旧版の directory（`<uploads の根>/v<n>`）。restore の前に本番で使っていた版で、復旧点より後に置かれた実体（hold の対象の実体を含み得る）を持ち得る | 人手（restore 主体。uploads の access point を app と同じ uid で mount する） |
| 残りの削除 | 失敗した stage の一時 directory（`.stage-<版>.XXXXXX`）と switch の一時 symlink（`.<pointer>.switch-<pid>`）。`uploads-cutover.sh status` の `leftover=` に出る | 同上 |

- `uploads-cutover.sh` は旧版を消さない（stage・switch が失敗したときに自分が作った一時物だけを消す）。D15 は app の経路ではないため、hold の gate（`PrivacyHoldPathGuard`）を通らない。以下の手順で守る。
- **保持**: 旧版は switch の後も消さずに残す（write-enable の前の rollback（逆向きの switch。`ops/backup/runbooks/restore-cutover.md` §4.5.1）の先であり、復旧点より後の実体の唯一の写しになり得る）。保持期間（いつまで残すか）は**未決定**（DBA・Privacy Owner の確認項）。決まるまで旧版は消さない。
- **hold**: hold を使った tenant（`t_privacy_projection_state` に行がある tenant）があるときは、旧版を消す前に privacy-admin が、有効な hold の対象の実体が旧版に無いこと（または有効な hold が無いこと）を確かめる。確かめ方（照会と記録の形）は D-05 owner が決める（**未決定**）。確かめられない間は消さない（fail-closed）。
- **DSAR・保持期間の purge との関係**: 旧版は孤児清掃（D10。`current` が指す版だけを見る）・保持期間の purge（D3〜D7）・DSAR の削除の範囲の外にある。旧版に残る個人データは保持期間を過ぎても自動では消えず、DSAR の削除も届かない。保持期間を過ぎた旧版を D15 で消すこと、DSAR の削除の範囲に旧版を含めるかは、D-05・D-06 owner の受理が要る（**未取得**）。
- **`database-backup-recovery/design.md` §10「旧 version は retention 期限まで read-only で保持」**: read-only 化も retention の期限も実装していない（`uploads-cutover.sh` は mode を変えず、旧版は restore 主体と app の uid で書ける）。app は `current` が指す版だけを使うため、app が旧版へ書くことはない。運用では、保持期間の間は旧版を変更も削除もせず、削除は D15 の確認を経る。read-only 化（書込みの mode を外す等）を行うかは DBA・Privacy Owner の確認項（**未決定**）。
- **残り（`.stage-*`・`.<pointer>.switch-*`）**: 中身は restore の staging（`uploads_ready`）の写しか版を指す symlink で、唯一の写しではない（restic の repository と restore の staging に同じものがある）。`status` で確かめ、作った change の中で restore 主体が消してよい。
- **記録**: 消した版・残りの名前、日時、確かめた者（hold の確認を含む）、消す前後の `status` の出力を change ticket と配備記録に残す。
- **候補案（2026-11-22 統括の起草。決定の前）**: 上の保持期間・hold の確かめ方・DSAR と保持期間の purge との関係・read-only 化の候補（各2〜3案）、推奨、利点と欠点、決める人は `d15-uploads-old-versions-options.md` にある。NF07 保持・保全と本人請求を初回リリースで ON にする（2026-11-22 利用者決定。§1.7）ため、hold と DSAR の部分（同書の (c)・(d)。下の D-05 owner の受理事項 (9)）は go-live の前提である（**未取得**）。保持期間（同 (a)）も、(d) の受理の前提になるため go-live の前に合わせて決めることを推奨する。

**D16: backup の書込み静止の信号表の保存期間の削除（RB-QUIESCE。gate に接続しない＝対象外）**

`privacy-retention-dsar/pii-inventory.md` の DB-148〜DB-150 に登録した3表（`sys_backup_quiesce_request`・`sys_backup_quiesce_instance`・`sys_backup_quiesce_ack`）の、app による保存期間の削除である。`PrivacyHoldPathGuard` に接続しない理由は次のとおり（backup-write-quiesce design.md §3.9）。

1. **hold の対象になり得る data subject が無い。** 3表は backup の静止要求・app の instance（JVM の起動ごとの UUID）・その応答の記録で、利用者・要員・顧客の誰にも結び付かない（tenant 列も本人の ID も持たない。同 §3.4）。catalog（`m_pii_data_element`）にも data element を置かない（同 §3.9）ため、subject の hold・DATA_ELEMENT の hold のどちらも当たらない。
2. **個人データを持たない。内容も最小化してある。** 持つのは app の container の host 名・DB から見た接続元の IP・job 名・時刻・状態だけで、`requested_by` は job 名と backup host の形式（`^[A-Za-z0-9._:-]{1,64}$`。個人名を書かない）だけを受け付ける。backup の job が残す記録（restic・index の metadata）も、instance の ID と件数だけに絞った（2d review P3-4。`ops/backup/README.md`「書込み静止の記録（quiesce.json）」）。ただし最小化したのは metadata だけで、DB の full の dump と binlog には信号表の行そのもの（`host_name`・`db_client_host`・`observed_app_hosts`・`request_token` を含む。保存期間の分）が入り、backup の保存期間まで残る（個人データではない。restore-cutover.md §4.6 はこれを前提にする）。
3. **削除の範囲は保存期間の古い行だけ。** 保存期間（既定 30日。U-12。§1.0 の41 の `BACKUP_QUIESCE_RETENTION` で 1〜365日）より古い解除済み（RELEASED・ABORTED・EXPIRED）の要求（応答の行は CASCADE）と、`last_beat_at` が保存期間より古い instance だけを消し、有効な要求がある間は消さない。停止口で動いていた行（`gate_state = 'DISABLED'` かつ `STOPPED` でない）は古さによらず消さない（§4.7 の片付けまで backup を失敗させ続ける行のため）。消した件数は INFO の log に出す。
4. **手作業の変更は別。** §4.7 の停止口の行の片付けは行を `STOPPED` にする UPDATE で、行を消さない（§1.8 の RR-10 の例外）。

**D-05 owner の受理事項（A1H-2。hold 開始の開放の署名と合わせて受理する）**: (1) 原本と副本の DATA_ELEMENT hold は別 element（上の「DATA_ELEMENT hold の対」）、(2) 外部にある実体は D10 の範囲外（上の「外部にある実体」）、(3) D7・D9 は1つの tenant の停止で全 tenant の kind を止める（過剰停止）、(4) D1 の READ_COMMITTED による最初の hold との順序の窓と、D1・D2 の本人の導出が文書行の lock より前であること（plan §3.4 A1H-2 実装記録の残余）、(5) 帰属できない element の行は SUBJECT hold で守られない（plan §3.4.3 規則6）、(6) D13 の PWA の台帳と freee の従業員対応の物理削除（上の表）、(7) 参照元の行が物理削除された実体は D10 で守れない（D11、plan §9 R-15）、(8) D14 の開示 package は hold で止めない（NF07-A2b。D-06 owner と合わせて受理する）。本人請求そのものが争いになり、交付した内容が証拠になる場合でも、system は `package-max-age` を超えて内容を残さず、内容 hash と交付記録だけを残す。内容を残すなら、交付の時点で system の外の保全手続で保管する。(9) D15 の uploads の旧版（`v<n>`・`.stage-*`・`.current.switch-*`）の人による削除は gate に接続しない（上の D15）。保持期間、hold の対象が旧版に無いことの確かめ方、read-only 化は未決定で、DSAR の削除と保持期限の purge が旧版に届かないことは D-06 owner と合わせて受理する（**未取得**。候補案は `d15-uploads-old-versions-options.md`。NF07 を初回リリースで ON にするため go-live の前提）。

### 4.5 NF07 保全holdの解除（NF07-REL。開放は D-03・D-05 の署名後）

**機能の開放の署名者（FE-A06・D17）の残余（D-05 の受理事項。未取得）**: 署名者の氏名と署名の文書（`FEATURE_SIGN_OFF_SIGNER_NAME`・`FEATURE_SIGN_OFF_DOCUMENT`）は `NOT_ATTRIBUTABLE` で、署名者が SUBJECT hold の本人でも SUBJECT hold では守られない。守るには DATA_ELEMENT hold を掛ける（その間は §4.4 の D17 の消去が止まり、孤児の清掃は D10 が署名の文書の保存名を守る）。SUBJECT hold の開始のたびにこの2件を選ぶ。この残余は D-05 owner の受理事項、分類は Plan Review で Privacy が確認する項目として扱う（計画 `production-bridge-phased-plan.md` の R-18）。解除の手順はこの2件でも変わらない。

`POST /api/privacy/holds/{holdId}/release` は、D-03・D-05 の決定ID が有効で、prod の verifier があり、解除 authority の trust anchor が登録され、**失効 provider が2つ以上の anchor（異なる authority）に ACTIVE を返す**まで 404 を返す（trust 文書（§4.6）を設定するまでは registry が空、失効 provider は常に UNKNOWN。D-03 方式（AWS KMS）の adapter は RB-NF07-TRUST-KMS で実装済み）。数えるのは、ISSUER の役割の anchor と APPROVER の役割の anchor で authority・主体（`sys_user:<id>`）・鍵を共有しない1組で（どちらも判定の時刻に有効期間内で、授権 tenant（`OIDC_TENANT_ID`）を範囲に含むもの）、同じ authority の2つの鍵（rotation の重複期間）や ISSUER だけの2件では開かない。anchor と失効状態は判定のたびに読むため、anchor を消せば再起動せずに閉じる。開放の後の手順は次のとおり。署名は system の外（D-03 で決める方式。Ed25519 の鍵を扱えること）で行い、system は鍵を持たない。

**手順**

1. privacy-admin（要求者）が hold 画面で解除する hold を選び、理由コード・ticket・理由を入力する。画面は束縛の全項目（tenant・partition・hold ID・hold revision・理由の hash・command ID（画面で発行）・解除の時点の journal head の revision と event hash・要求者・開始者）と署名依頼の JSON を表示する。
2. 署名依頼の JSON を発行者と承認者（要求者とは別の、trust anchor に登録された2名）に渡す。各人は下の正規形に Ed25519 で署名し（AWS KMS の手順は §4.6。署名の対象をそのまま `MessageType=RAW` で署名する）、JSON の `issuer` / `approver` に `authorityId`・`keyId`・`issuedAt`・`expiresAt`・`signature`（base64）を記入する。
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

### 4.6 NF07 の trust 文書と署名の手順（D-03 = AWS KMS 非対称署名。NF07-TRUST）

D-03 の方式は AWS KMS 非対称署名・鍵 version 単位の失効（2026-11-18 決定。CRL / OCSP は使わない）。application は AWS を呼ばない（SDK を持たず、KMS の認証情報も要らない）。署名は D-03 owner が KMS で行い、application は trust 文書に転記した公開鍵を JDK の Ed25519 で検証する。**実装は済んでいるが開放ではない**: hold 解除と policy の登録・有効化を開くには、D-01・D-02・D-03・D-05 の署名と決定ID（§1.7）が要る。**D-03 の署名は未取得**。

**設定**（§1.0 の「ONにした時だけ必須になる設定」）

| 環境変数（property） | 内容 | 既定 |
|---|---|---|
| `PRIVACY_TRUST_KMS_ANCHOR_FILE`（`privacy.trust.kms.anchor-file`） | trust 文書の file の絶対 path。空なら trust は第1期と同じ（anchor 0件・失効状態 UNKNOWN、入口は404） | 空 |
| `PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE`（`privacy.trust.kms.max-document-age`） | 文書の `generatedAt` からの鮮度の上限（5分〜31日。`PT24H`・`24h` の形。単位の無い数値は秒）。過ぎると anchor は0件・失効状態は全て UNKNOWN（入口は閉じ、照会は `APPROVED_ACTIVE` にならない）。値は D-03 で決める（下の EFS の項の目安） | なし |
| `PRIVACY_TRUST_KMS_RELOAD_INTERVAL`（`privacy.trust.kms.reload-interval`） | file の最終更新日時と大きさが変わらない間、読んだ内容を使い続ける最長の時間（1秒〜5分）。最終更新日時と大きさは判定のたびに確かめ、変われば次の判定で読み直す | `30s` |

- 起動時の検査（`PrivacyTrustStartupValidator`、prod。FE-C04）: 機能の開閉に関係なく、`PRIVACY_TRUST_KMS_ANCHOR_FILE` を設定した起動では常に検査する（未設定なら何もしない）。構造的な不足（path が絶対でない、鮮度の上限が未設定・解釈できない・範囲外、再読込の間隔が解釈できない・範囲外、file が読めない・1MiB を超える、文書の形式の違反）は固定の違反コード（`trust.kms.<code> at <文書の中の位置>`）を ERROR `NF07_TRUST_STRUCTURE_INVALID` で出して **副入口 `REL`・`REG` だけを閉じ、prod の起動自体は止めない**（`NF07_RETENTION` と hold の効力も閉じない。値・公開鍵・ARN は出さない）。時間要因（鮮度の上限を過ぎた・作成日時が未来・有効期間内で ACTIVE の anchor が無い）だけなら WARN `NF07_TRUST_NOT_READY_AT_STARTUP` で起動を続け、入口は閉じたままにする（文書を置き直せば再起動せずに開く）。
- 時間の値（`MAX_DOCUMENT_AGE`・`RELOAD_INTERVAL`）は文字列で受け取って使うときに解釈するため、解釈できない値でも bind では失敗せず、上の構造的な不足（`trust.kms.maxDocumentAge.invalid` / `trust.kms.reloadInterval.invalid`）として副入口 `REL`・`REG` を閉じる。実行中は鮮度の上限なら「設定が無い」と同じ（anchor 0件・UNKNOWN）、再読込の間隔なら既定の30秒として扱う。
- 実行中: 文書が消えた・壊れた・巻き戻った（同じ process で読んだ文書より前の `generatedAt`）、鮮度の上限を過ぎた・作成日時が未来（5分を超える）なら、anchor 0件・失効状態 UNKNOWN として扱い（fail-closed。直前に読めた内容には戻らない）、WARN `NF07_TRUST_DOCUMENT_STATE: state=UNAVAILABLE reason=…` を状態が変わったときに1回出す。鮮度の上限を過ぎると `state=STALE`、読み直せたら INFO `state=LOADED`。`UNAVAILABLE`・`STALE` をアラート対象にする。
- **file の置き方（2026-11-18 利用者決定）: trust 文書は EFS の volume に置き、application の task は読取り専用で mount する**。mount の path（`/app/privacy-trust`）・volume・access point の正本は §1.6 の 11（RB-DEPLOY が追加する項。本節では繰り返さない）で、`PRIVACY_TRUST_KMS_ANCHOR_FILE` にはその mount の下の file の絶対 path を書く（例: `/app/privacy-trust/nf07-trust.json`）。task definition の雛形は RB-DEPLOY が扱い、本 batch は `infra/**` を変えない。ECS の secret（環境変数）は変えるのに再起動が要り、失効を再起動なしに反映できないため使わない。
  - 書込み: D-03 owner の手順だけが、application とは別の経路（読書き可能な mount を持つ管理用の host・task 等。**SRE と D-03 owner で確定する**）で書く。一時 file に書いてから同じ directory の中で rename して置き換える（書きかけの file を読んでも fail-closed になるだけで、正しい文書を置けば次の判定で戻る）。**file の内容が trust の正本**で、EFS に書き込める者は anchor を足せる。EFS の access point・IAM・mount target の security group で、書込みを D-03 owner の経路だけに限る（**SRE・Security の確認事項**）。trust 文書そのものは署名していない（文書を root 公開鍵で署名させる強化は将来の選択肢。review P3-9 の記録）。
  - 失効の反映の遅れ: EFS は NFS で、client は file の属性（最終更新日時・大きさ）を cache する（Linux の NFS client の既定は `acregmin=3`・`acregmax=60` 秒、directory は `acdirmin=30`・`acdirmax=60` 秒）。application は判定のたびに属性を見るが、cache の間は変化が見えないため、失効の反映は「属性の cache の残り」か「`RELOAD_INTERVAL`（既定30秒）が過ぎて読み直すまで」の早い方まで遅れ得る（読み直しは file を開き直すため、NFS の close-to-open の規則で新しい内容を読む）。rename で置き換えた直後は directory の lookup の cache も効くため、**最悪の遅れは `RELOAD_INTERVAL` と属性・lookup の cache（既定で約60秒）を足した程度と見込み、SRE が staging で実測する（未実施）**。遅れを縮めるなら `RELOAD_INTERVAL` を短くする（例: `10s`。判定は file の属性を見るだけで、I/O が止まっても要求を待たせないため、短くしても EFS の障害で要求が詰まることはない）。mount option（`actimeo` 等）は Fargate では選べない見込み（次の項）。
  - mount option: task definition の `efsVolumeConfiguration`（RB-DEPLOY の雛形では `transitEncryption: ENABLED` と `accessPointId`）には `soft`・`timeo`・`actimeo` 等の NFS の mount option を書く項目が無く、Fargate では ECS が mount する。そのため **`hard` mount（属性の cache は既定の値）を前提にする**。実際の option（NFS の版・`hard`・`timeo`・属性の cache）は **SRE が staging の task で確かめる（未確認）**。上の「`actimeo` を小さくする」は Fargate では選べない見込みで、遅れを縮める手段は `RELOAD_INTERVAL` だけと考える。
  - **EFS に届かないとき（delta review P2-A で修正）**: hard mount では stat・読込みが返らない。trust の判定は file を読む thread を1つの lock で直列化するが、lock は最長0.3秒しか待たず、lock を持つ thread の I/O が0.3秒を超えて続いていれば待たない。lock を取れなかった判定は I/O をせず、最後に file を確かめた時刻から `RELOAD_INTERVAL` の内は直前に読めた内容を使い、外なら閉じる（anchor 0件・UNKNOWN、WARN `NF07_TRUST_DOCUMENT_STATE: state=UNAVAILABLE reason=document.stalled` を1回）。止まるのは I/O 中の1 thread だけで、他の要求が trust の判定で待つのは1回あたり最長0.3秒（NF08 の送信・hold・policy の画面が EFS の障害で全ての thread を占めることはない）。**EFS に届かなくなってから trust が閉じるまでは、最長でおよそ `RELOAD_INTERVAL` の2倍（既定で約60秒）**の見込み（属性の cache の間は stat が返り、`RELOAD_INTERVAL` ごとの読み直しで止まり、その後 `RELOAD_INTERVAL` の間だけ直前の内容を使うため。SRE が staging で実測する。未実施）。EFS が戻れば、次の判定で読み直して元に戻る（再起動は要らない）。起動時の検査は20秒で打ち切り、超過した場合は `trust.kms.document.timeout` として副入口 `REL`・`REG` を閉じたまま起動する（FE-C04）。
  - 鮮度の上限の目安: 文書を更新する周期の2〜3倍（例: 毎日更新なら `48h`〜`72h`）。更新が1回遅れても入口が閉じず、止まったことは上限の内に `STALE` の WARN で分かる。値は D-03 で決める（**未決**）。

**trust 文書の形式**（JSON、`format` は `ses-nf07-kms-trust-v1`。未知の field・同じ名前の field の重複・欠落・型の違いは文書全体を拒否する）。下の値は全て合成値で、実在の鍵・account ではない。解除には、別の authority・別の主体・別の KMS key の承認者（`roles` に `APPROVER`）の anchor も同じ形で足す:

```json
{
  "format": "ses-nf07-kms-trust-v1",
  "generatedAt": "2026-12-01T00:00:00Z",
  "policyAnchors": [
    {
      "authorityId": "privacy-policy-issuer",
      "keyId": "kms-2026a",
      "kmsKeyArn": "arn:aws:kms:ap-northeast-1:111122223333:key/00000000-0000-0000-0000-000000000000",
      "keySpec": "ECC_NIST_EDWARDS25519",
      "signingAlgorithm": "ED25519_SHA_512",
      "publicKey": "<GetPublicKey の PublicKey（DER の SubjectPublicKeyInfo の base64）>",
      "status": "ACTIVE",
      "validFrom": "2026-12-01T00:00:00Z",
      "validUntil": "2027-12-01T00:00:00Z",
      "tenants": ["default"],
      "scopes": ["NF08_AI_SUMMARY"],
      "dataElements": ["AI_PROMPT_LOG"],
      "actions": ["EXTERNAL_DISPATCH"],
      "providers": ["gemini-adapter"]
    }
  ],
  "holdReleaseAnchors": [
    {
      "authorityId": "hold-release-issuer-a",
      "keyId": "kms-2026a",
      "kmsKeyArn": "arn:aws:kms:ap-northeast-1:111122223333:key/11111111-1111-1111-1111-111111111111",
      "keySpec": "ECC_NIST_EDWARDS25519",
      "signingAlgorithm": "ED25519_SHA_512",
      "publicKey": "<GetPublicKey の PublicKey>",
      "status": "ACTIVE",
      "validFrom": "2026-12-01T00:00:00Z",
      "validUntil": "2027-12-01T00:00:00Z",
      "tenants": ["default"],
      "principal": "sys_user:20",
      "roles": ["ISSUER"],
      "targetTypes": ["SUBJECT", "DATA_ELEMENT"]
    }
  ],
  "envelopeStatuses": {
    "nf07-policy-2026-0001": "ACTIVE"
  }
}
```

- anchor 1件 ＝ KMS key 1つ（鍵 version）。同じ ARN・同じ公開鍵の anchor を2件書かない。anchor ID（`authorityId:keyId`）は policy と解除をまたいで一意。
- `authorityId`・`keyId` は `[A-Za-z0-9._-]` の1〜64文字（`:` を含めない）。`keyId` は鍵 version の識別子（例: `kms-2026a`）で、KMS key の ARN は文字種と長さに収まらないため `kmsKeyArn` に書く。`kmsKeyArn` は key の ARN（alias の ARN は付け替えられるため不可。multi-Region key の `mrk-` は可）。
- `keySpec` は `ECC_NIST_EDWARDS25519`、`signingAlgorithm` は `ED25519_SHA_512` だけ（`ED25519_PH_SHA_512` は Ed25519ph で、純粋な Ed25519 と互換が無いため拒否する）。
- `publicKey` は `aws kms get-public-key` の `PublicKey` をそのまま書く（base64。PEM・改行・padding の欠けは不可）。application が Ed25519 の SubjectPublicKeyInfo（RFC 8410、44 bytes）であることを検査する。
- `status` は `ACTIVE` / `REVOKED`。policy の anchor に `envelopeKinds`・`audiences` は書かない（`POLICY`・`PRODUCTION` に固定）。解除の anchor の `principal` は `sys_user:<id>`、`roles` は `ISSUER` / `APPROVER`、`targetTypes` は `SUBJECT` / `DATA_ELEMENT`。範囲の項目（`tenants` 等）は空・重複・`[A-Za-z0-9_.:-]` 以外の文字を不可。
- **`tenants` には `OIDC_TENANT_ID`（未設定なら `default`）を必ず含める**。開放の gate は授権 tenant を範囲に含む anchor だけを数えるため、含めないと D-03 の決定ID を設定しても入口は404のまま（fail-closed だが原因が見えにくい）。
- `envelopeStatuses` は policy envelope の `revocationReference` ごとの状態。**書いた参照だけが ACTIVE になる**（書いていない参照は UNKNOWN で、照会は `APPROVED_ACTIVE` にならない）。policy を登録する前に、その envelope の参照を `ACTIVE` で足す。
- **envelope の `revocationReference` には `:` を含めない**（例: `nf07-policy-2026-0001`）。`:` を含む値は鍵の ID（`authorityId:keyId`）と区別できないため、文書の `envelopeStatuses` に書くと文書全体を拒否し（`envelope.reference`）、その参照を持つ envelope は本番の verifier が失効状態を UNKNOWN として拒否する（`REVOCATION_STATUS_UNKNOWN`。anchor ID と同じ参照の envelope が鍵の状態で ACTIVE にならないようにする。review P2-1）。

**鍵の作成と公開鍵の転記**（D-03 owner。AWS CLI v2。application は実行しない）

```sh
aws kms create-key --key-spec ECC_NIST_EDWARDS25519 --key-usage SIGN_VERIFY --description "NF07 policy issuer kms-2026a"
aws kms get-public-key --key-id <key の ARN> --query '{PublicKey:PublicKey,KeySpec:KeySpec,SigningAlgorithms:SigningAlgorithms}'
```

- `KeySpec` が `ECC_NIST_EDWARDS25519`、`SigningAlgorithms` に `ED25519_SHA_512` があることを確かめ、`PublicKey` を文書の `publicKey` に転記する。key policy で `kms:Sign` を署名者だけに許す（**未決。Security・D-03 owner**）。

**policy envelope の署名**（KMS の `MessageType=RAW` の message は 4096 bytes まで。envelope の canonical bytes は保持規則等の文字列の長さで 4096 bytes を超え得る（登録の上限は 64KB）ため、canonical bytes の SHA-256 を持つ固定長 100 bytes の digest 声明に署名する。声明への署名も純粋な Ed25519 で、Ed25519ph は使わない）

```sh
# envelope.bin = canonical bytes（登録の要求の envelope を base64 から戻したもの）
d=$(sha256sum envelope.bin | cut -d' ' -f1)          # macOS は shasum -a 256 envelope.bin
echo "$d"   # 署名の前に、D-02 の決定書に記載された envelope の digest と一致することを確かめる
printf '30:NF07-POLICY-ENVELOPE-DIGEST-V164:%s' "$d" > statement.bin   # 100 bytes、改行なし
wc -c < statement.bin                                # 100 であること
aws kms sign --key-id <key の ARN> --message fileb://statement.bin --message-type RAW \
  --signing-algorithm ED25519_SHA_512 --query Signature --output text > signature.b64
```

- `$d` は登録の一覧・詳細に出る `envelopeDigest`（`ApprovalEnvelopeCanonicalizer.digest`）と同じ値。署名者は envelope の中身ではなく digest に署名するため、**署名の前に D-02 の決定書の digest と照合する**（違えば署名しない）。
- 登録の要求の `signature` は `signature.b64` の内容（Ed25519 署名の base64）。**本番の verifier は声明への署名だけを受け付ける**（canonical bytes そのものへの署名は 4096 bytes 以下でも拒否する。手順を1つにし、短い envelope だけ直接署名できて長いと KMS で失敗する罠を避けるため。review P3-2）。digest だけ・Ed25519ph の署名も受け付けない。canonical bytes への署名を検証するのは本番ではない合成層（`ApprovalEnvelopeVerifier`）だけで、その形と声明は先頭の bytes で区別できる（`PolicyEnvelopeSigningStatementTest` が固定）。
- 声明の正規形: `<UTF-8 の byte 長>:<値>` の field を区切り無しで連結（§4.5 と同じ）。field は domain `NF07-POLICY-ENVELOPE-DIGEST-V1` と、canonical bytes の SHA-256（小文字 hex 64桁）。
- test vector（`PolicyEnvelopeSigningStatementTest` が Java の実装と照合する。値は Node.js で独立に計算した）: canonical bytes が ASCII の `abc` なら、声明は `30:NF07-POLICY-ENVELOPE-DIGEST-V164:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`、声明の SHA-256 は `9d79de0b996eff91f78260cbc8c886e2be239e01a1487ac6ad03a1683bfa7e66`。

**hold 解除の claim の署名**（§4.5 の「署名の対象」をそのまま RAW で署名する。被署名 bytes は authority・key の ID が64文字、日時が `Instant` の最長の表記でも 352 bytes で 4096 bytes を下回るため、digest 声明は使わない）

```sh
# claim.bin = §4.5 の正規形の「署名の対象」（hash を取らない）。printf '%s' で書き、末尾に改行を付けない（echo は改行を足す）
printf '%s' "$CLAIM" > claim.bin                    # $CLAIM は「署名の対象」の文字列
sha256sum claim.bin                                  # 手順の確認: §4.5 の test vector の入力で作ると 549567a9…（§4.5 の表）になること
aws kms sign --key-id <key の ARN> --message fileb://claim.bin --message-type RAW \
  --signing-algorithm ED25519_SHA_512 --query Signature --output text
```

- 送る前に、KMS を使わず公開鍵だけで署名を確かめる手順（OpenSSL 3 の `pkeyutl -verify -rawin` 等）を D-03 owner が staging で確かめて本節に足す（**未実施**）。

**鍵の rotation**（新しい KMS key ＝ 新しい鍵 version）

1. 新しい KMS key を作り、新しい `keyId`（例: `kms-2027a`）の anchor を足す。`validFrom` は旧鍵の `validUntil` より前にする（重複期間の間は新旧どちらの鍵の署名も通り、gate も開いたまま）。
2. 以後の署名は新しい鍵で行う。旧鍵で署名した current の policy は、旧鍵の `validUntil` より前に、新しい鍵で署名し直した新しい revision に置き換える（`validUntil` を過ぎると旧鍵の署名は期限切れとして拒否される）。
3. 旧鍵の `validUntil` の後は、旧 anchor を文書から外してよい（外した鍵の署名は次の検証から `UNKNOWN_TRUST_ANCHOR`）。退役した鍵の公開鍵と ARN は、監査の claim や保存した envelope を事後に検証するため、文書の版と一緒に保管する。KMS key の削除は保管の方針を D-03 で決めてから（**未決**）。

**失効**（鍵 version 単位）

- 鍵の失効: その anchor の `status` を `REVOKED` にし、`generatedAt` を更新した文書を置く。次の判定から、その鍵で署名した全ての policy の照会が `POLICY_REVOKED`、登録・有効化・解除は拒否、gate はその鍵を数えない。再起動は要らない。KMS 側でも key を無効にする（application は KMS の状態を見ないため、文書の更新が失効の正本）。
- envelope だけの失効: `envelopeStatuses` のその参照を `REVOKED` にする。
- `REVOKED` は戻さない。同じ process では、`ACTIVE` に戻した文書を置いても `REVOKED` のまま扱う（再起動の後は文書の値に従う）。誤って失効した鍵は新しい KMS key で作り直す。`REVOKED` の行は消さない（消すと照会は `POLICY_REVOKED` ではなく trust anchor 不明・失効状態不明になる）。
- 古い版の文書を置かない。同じ process では前に読んだ文書より前の `generatedAt` の文書を受け付けず fail-closed にするが、再起動の後は防げない。

**鮮度**: 内容が変わらなくても、鮮度の上限の内に `generatedAt` を更新した文書を置き直す。更新が止まると失効状態は UNKNOWN になり、入口は閉じる（失効を確かめられない状態として扱う）。更新の担当と周期は D-03 で決める（**未決**）。

### 4.7 backup の書込み静止の確認・backup の再開・障害の切分け（RB-QUIESCE）

設計は `.kiro/specs/backup-write-quiesce/design.md`。app は起動ごとに信号表 `sys_backup_quiesce_instance` に1行を登録し（instance ID は起動ごとの UUID）、5秒ごとに heartbeat を書き、静止要求（`sys_backup_quiesce_request`）を1秒ごとに読む。backup の provider は要求を登録し、全ての instance の応答（`sys_backup_quiesce_ack`）を確かめてから整合点を取る。本節の SELECT は参照だけで、アプリ account・backup account の schema 単位の `SELECT` でも読める（processlist を見る SQL だけは PROCESS を持つ管理用の account で行う）。信号表の時刻の列は全て UTC で、比べるときは `UTC_TIMESTAMP(6)` を使う（`NOW()` は server の `+09:00` の時刻で、9時間ずれる）。

**利用者への影響**（伝え方は §1.7 の 004a で決める）: 静止の間（15分ごとの checkpoint は通常数秒・最長300秒。02:00 の full は排水が最長600秒、静止が最長60分）、更新の要求（画面・API の保存、login の送信、download、bootstrap・break-glass の session の要求）は 503 になる（画面は統一エラーページ、API は `X-SES-Error-Code: BACKUP_QUIESCED` と `Retry-After`）。参照（一覧・詳細の GET）と logout は続き、customer portal の参照も続く（portal の logout と更新は 503）。PWA の送信が 503 の後に queue に残り解除の後に送られることは、backup-write-quiesce tasks の BWQ-005 の Demo で確かめる（**未取得**）。

**起動の後の確認と backup の再開**（初回・以後の release とも。§4 の readiness が UP になった後）

1. instance の行を確かめる。
   ```sql
   SELECT instance_id, status, gate_state, scheduling_enabled,
          TIMESTAMPDIFF(SECOND, last_beat_at, UTC_TIMESTAMP(6)) AS beat_age_seconds
     FROM sys_backup_quiesce_instance WHERE status <> 'STOPPED';
   -- beat_age_seconds が10以下の行が1行（SRE-5）。その行は status = 'RUNNING'、gate_state = 'OPEN'、
   -- scheduling_enabled が task definition の APP_SCHEDULING_ENABLED と同じ（初回は 0）
   SELECT COUNT(*) FROM sys_backup_quiesce_request WHERE status = 'REQUESTED';   -- 0
   ```
   `gate_state` が `DISABLED` なら停止口で起動している（§1.0 の「停止口の使い方」。backup は全て失敗する）。heartbeat の新しい行が2行以上なら task が重なっている（SRE-5 の (b) を確かめる）。heartbeat の古い前の task の行（§2 の4）は、300秒を過ぎれば backup は無視する。
2. `.env.backup` の `FLYWAY_VERSION` を §3.4 の最新 version にする（§1.7 の「Backup 開始の前提」。初回は §2 の5 の前に済んでいる）。
3. §2 の0 で止めた定期起動（15分ごとの checkpoint と 02:00 の full）を再開し、§2 の0 で抑止した RPO の alert を戻す。再開した時刻を配備記録に残す。
4. 再開後の最初の checkpoint（`quiesce-db`。ONLINE）が VALID になることを確かめる。定期起動を待つか、`ops/backup/image/tool-image.sh compose run --rm checkpoint` を1回流す。
   - job の結果の JSON（stdout）: `status: "VALID"`、`quiesce.provider: "quiesce-db"`、`quiesce.mode: "ONLINE"`、`quiesce.verified_at_utc` が null でない、`quiesce.window_seconds`（静止区間の秒数。`QUIESCE_WINDOW_WARN_SECONDS` の既定60秒を超えると job が WARN を出す）。
   - app の log: 同じ要求の番号で `errorCode=BACKUP_QUIESCE_REQUESTED`・`BACKUP_QUIESCE_ACKED`・`BACKUP_QUIESCE_RESUMED` が1行ずつ出る。
   - 配備記録に checkpoint の `consistency_time_utc`・`restic_snapshot_id`・`quiesce.request_id`・`quiesce.window_seconds` を残す（`quiesce.json` の全体は貼らない。`ops/backup/README.md`「書込み静止の記録（quiesce.json）」）。初回はこれが §1.7 の 004b の記録で、揃うまで本番の RPO 15分の達成を宣言しない。
   - VALID にならなければ下の障害の切分けと `ops/backup/runbooks/backup-health-monitoring.md` に従う。定期起動は止めなくてよい（次の checkpoint が VALID になれば RPO の判定は戻る）。

**障害の切分け**（log は固定の `errorCode=`。alert の条件と backup の側の対応は `ops/backup/runbooks/backup-health-monitoring.md`）

- **起動が `errorCode=BACKUP_QUIESCE_STARTUP_ABORT`（ERROR）で止まる**（port を開く前。ECS が task を起動し直す）。message で分ける。
  - 「app の停止を確かめる backup（APP_STOPPED、要求の番号 …）の保持中です」: §2 の5 の `--app-stopped` の job がまだ動いている。backup の host で job の終わり（`tool-image.sh compose ps`）を待つ。job が落ちても保持は lease（60秒）で切れ、次の起動は通る。
  - 「最長 … 秒待っても backup の静止が解除されません」: ONLINE の静止の最中に起動し、`BACKUP_QUIESCE_STARTUP_WAIT_MAX`（既定60秒）の内に解除されなかった（先に WARN `errorCode=BACKUP_QUIESCE_STARTUP_WAIT` が出る）。checkpoint の静止は通常数秒で解ける。ONLINE の full の最中に起動した instance では provider がその full を中止して静止を解く（U-13。WARN `BACKUP_QUIESCE_FULL_ABORTED`。その日の full は欠け、`ops/backup/runbooks/backup-health-monitoring.md` の「WARN: full の中止（取り直し）」で取り直す）ため、通常は待ちの内に解ける。解けない場合は下の SQL で要求を確かめる。
  - 「最初の heartbeat（instance の登録）を書けません: 信号表（V202 の sys_backup_quiesce_instance 等）がありません」: V202 が適用されていない。§3.4 の結果と、起動時 schema guard の log を確かめる。
  - 「有効な静止要求を読めません」と、信号表がある場合の「最初の heartbeat（instance の登録）を書けません」: 制御用・heartbeat 用の接続（アプリ account・`DB_URL`・DB への到達）を確かめる。
  - 静止要求の確認:
    ```sql
    SELECT id, mode, job_kind, status, requested_by, requested_at, lease_expires_at, UTC_TIMESTAMP(6) AS now_utc
      FROM sys_backup_quiesce_request WHERE status = 'REQUESTED';
    -- 有効な要求は同時に1件だけ。lease_expires_at が now_utc より前なら期限切れ（app は無効として扱い、次の provider が EXPIRED にする）。
    -- requested_by は job 名と backup host（個人名を含まない）
    ```
- **起動が `[backup quiesce] 起動を中止します:` の例外で止まる**（prod の起動時検査 `BackupQuiesceProductionValidator`。instance の登録の前）: message の項目（停止口の ticket が無い・形式が違う、`readOnlyPropagatesToServer` が有効でない）を §1.0 の1・31・32 に従って直す。
- **`errorCode=BACKUP_QUIESCE_FENCED`（ERROR）**: app が heartbeat を `BACKUP_QUIESCE_FENCE_AFTER`（既定20秒）の間書けず、自分で更新を止めた（更新の要求は 503、参照は続く）。制御用・heartbeat 用の接続 pool（`ses-backup-quiesce-control`・`ses-backup-quiesce-heartbeat`。アプリ account）、DB への到達、`max_connections`、アプリ account の lock を確かめる。heartbeat を書けるようになれば WARN `errorCode=BACKUP_QUIESCE_UNFENCED` を出して自動で戻る。戻らなければ DB 側を直してから task を起動し直す。
- **`errorCode=BACKUP_QUIESCE_DISABLED`（ERROR）・`gate_state = 'DISABLED'`**: 停止口（§1.0 の「停止口の使い方」）で起動している。承認済みの ticket が配備記録にあることを確かめる。停止口の間は backup が全て失敗する。
- **`errorCode=BACKUP_QUIESCE_STOPPED_HOLD_SEEN`（ERROR）**: 動いている instance が `--app-stopped` の保持を読み、更新を止めた（参照は続く）。app を止めずに `--app-stopped` の backup を流した手順の誤りで、その backup は VALID にならない。backup の host のその job を止める。保持が解かれると（job の終わり、または lease の60秒）app は自動で更新を再開する。
- **`errorCode=BACKUP_QUIESCE_AUTO_RESUMED`（ERROR）・`BACKUP_QUIESCE_REFUSED`（WARN）・`BACKUP_QUIESCE_FULL_ABORTED`（WARN）**: その回の backup は VALID にならない。**`BACKUP_QUIESCE_ACK_UNKNOWN`（WARN）**: 応答の書込みの結果が不明で、app は閉じたまま確かめている（その回の成否は backup の job の結果で決まる）。どれも `backup-health-monitoring.md` の対応に従う。
- **停止口を戻した後も、backup が「停止口（BACKUP_QUIESCE_ENABLED=false）で動いている、または異常に止まった停止口の instance の行があります」で失敗し続ける**: 異常に止まった停止口の行が残っている（下の片付け）。

**本書が手順として書いた手作業の変更**（§1.8 の RR-10 の例外。DBA が管理用の account で、general log を無効にした接続で行い、実行した文と変わった行数を配備記録に残す）

- **保持（静止要求）を手で終わらせる**（design §9.4）: backup の host や job が落ちても保持は lease（60秒）で切れるため、通常は要らない。要求の lease の延長が続いているのに backup の host にその job が見当たらない等、要求が解かれずに app の起動・更新が止まり続ける場合だけ、上の SQL で番号を確かめてから行う。
  ```sql
  UPDATE sys_backup_quiesce_request
     SET status = 'ABORTED', released_at = UTC_TIMESTAMP(6), release_reason = 'ABORTED'
   WHERE id = <番号> AND status = 'REQUESTED';
  -- 1行だけが変わること（0行なら既に解かれている）
  ```
  app は要求の消失を読んで再開する（ERROR `errorCode=BACKUP_QUIESCE_AUTO_RESUMED`、理由 `REQUEST_GONE`）。その回の backup は verify で失敗し、VALID にならない。
- **停止口の行の片付け**（design §9.4・L-18）: 停止口で動いていた app が SIGKILL・task の喪失等で異常に止まり、行が `STOPPED` にならずに残ると、heartbeat の古さによらず全ての backup が失敗し続ける（停止口を外して app を起動し直した後も同じ）。次の2つを確かめてから、その行だけを `STOPPED` にする。
  1. ECS で、その行の task が止まっていること（クラウドの管理画面／CLI。task の接続元の IP は行の `db_client_host`）。
  2. PROCESS を持つ管理用の account で、その接続元からのアプリ account の接続が0件であること。
  ```sql
  SELECT instance_id, db_client_host, status, gate_state, last_beat_at
    FROM sys_backup_quiesce_instance WHERE gate_state = 'DISABLED' AND status <> 'STOPPED';
  SELECT COUNT(*) FROM information_schema.processlist
   WHERE user = '<アプリaccount>' AND SUBSTRING_INDEX(host, ':', 1) = '<その行の db_client_host>';   -- 0
  -- （IPv4 の接続元の場合。IPv6 では SUBSTRING_INDEX が値を途中で切るため、host の末尾の :<port> だけを外した値で比べる）
  UPDATE sys_backup_quiesce_instance
     SET status = 'STOPPED', stopped_at = UTC_TIMESTAMP(6)
   WHERE instance_id = '<instance_id>' AND gate_state = 'DISABLED' AND status <> 'STOPPED';
  -- 1行だけが変わること
  ```
  その後 `ops/backup/image/tool-image.sh compose run --rm preflight` の `checks.quiesce.status` が `ok` になり、次の checkpoint が VALID になることを確かめる。片付けるまで backup は失敗し続ける（安全側）。

## 5. 初回bootstrap（期限付きbootstrap mode）

§4.1・§4.2の設定で起動した後、次の順に行う。bootstrap中にlocal password loginできるのは初期管理者 `admin` だけで、他のユーザーのlocal loginは拒否される。OIDC loginは通常どおり使える。

- **backup の静止の間の 503（RB-QUIESCE。§4.7）**: bootstrap の間も backup の checkpoint は動く（§4.7 の3 で定期起動を再開した後）。checkpoint の静止の間（通常数秒、最長300秒）は、bootstrap session の要求が参照を含めて全て 503 になる（bootstrap・break-glass の session の要求は監査を必ず書く特権の要求として扱うため。break-glass の session も同じ）。`POST /logout` だけは静止中も終わり、session の失効と監査は解除の後に書かれる。503 になったら少し待ってから操作し直す（画面は統一エラーページ、API は `Retry-After`）。02:00 の full の間（最長60分）は bootstrap の作業をしない。

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
- **`admin`（A）は削除・改名・無効化・role変更しない**（break-glass管理者Aとして使い続ける。D1 review N-9）。bootstrapのlocal login（`ProductionBootstrapMode.isBootstrapLoginAllowed`）と起動時検査（`validateBootstrap`）は、username `admin`・role `管理者`・有効（`status = 1`）の口座だけを初期管理者として認めるため、これを崩すとbootstrapの再開（§5.7）でも誰もlocal loginできず、break-glassも使えない状態では復旧がDBAによる口座の復元だけになる。ユーザー管理は、本人による自分自身の削除・無効化・role変更を止める（既存のguard。そのまま）ほか、本番では他の管理者による操作でも、起動時検査の条件（OIDCでログインできる管理者・break-glass管理者・privacy-admin）を「変更の前は満たし、変更の後は満たさない」変更を拒否する（§5.6の「アプリでの拒否」）。bootstrap完了後の `admin` はbreak-glass username（§5.5）のため、break-glass管理者の条件を満たしている間は、削除・改名・無効化・role変更はその条件を割る変更として拒否される。ただしbootstrap中（再開を含む）は、起動時検査と同じくOIDC管理者・break-glassの条件を判定せず、bootstrap用の初期管理者の条件（`validateBootstrap`）もguardの対象外のため、他の管理者による `admin` の操作は止めない。定期確認（§5.6の集計と同じ頻度）:
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
   - 最初のloginで、IdPへのredirectの `redirect_uri` が `https://<公開FQDN>/login/oauth2/code/<registrationId>` であることも確かめる（`forwarded-header-sanitize-notes.md` §3.4 の2）。`http://` なら `TRUSTED_PROXIES` がALBの実IPを含んでいない（同 §3.3）。hostが公開FQDNと違えばALBのlistener ruleとHostの転送を見直す（同 §3.2）。client registration は起動設定から作られるため、redirect の URL 自体は deploy 直後でも見られる。ただし login を最後まで通すには1のIdP登録と4の外部identity linkが要るため、初回はここでまとめて確かめる。

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
2. **bootstrapを無効にして再起動する**。`SECURITY_BOOTSTRAP_ENABLED`・`SECURITY_BOOTSTRAP_EXPIRES_AT`（設定していれば `SECURITY_BOOTSTRAP_REOPEN_TICKET` も）を外し、`BREAK_GLASS_USERNAMES=admin,bg-second` を設定して再起動する。**`OIDC_TENANT_ID` は外さない**（§4.1）。再起動の前に、新しい task definition を `-Mode Actual -Phase Steady` で照合する（§4。scheduler の署名（§4）の前なら `-Scheduler Disabled`、後なら `-Scheduler Enabled`）。LBのアクセス元制限は、この起動を確認してから通常の設定へ戻す。
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

### 5.5.1 画面「機能の開放」（`/feature-enablement`）の運用・NF05 の差替え・障害時の注意（FE-E03）

§5.5 の完了後、各 S-NF 機能の開閉は OIDC＋MFA でログインした有効な `管理者`（NF07 は `privacy-admin`）が画面 `/feature-enablement` で行う（詳細手順と各開放単位の署名一覧は `feature-switches.md` §3・§4、前提一覧は本書 §1.7 の B）。

- **基本の流れ**: 必要な基盤設定（§1.0・§1.7 B）を整える → 管理者 A が署名 PDF（または組 `NF07_DECISION_BINDING`）を登録し、別の管理者 B が digest・原本を照合して確認する → A が開放（または設定変更 `nf02-customer-portal.visible-since`）を申請し、B（直近に管理者の role を得た者でないこと。§5.6）が承認する。NF05 以外の 11 単位は承認と同時に `OPEN`、手動閉鎖は 1 名で即時に `MANUALLY_CLOSED` になる。
- **NF05（`NF05_INBOUND`・`NF05_OUTBOUND`）の開放と、truststore・digest 対象設定の差替え（再起動 2 回。design §9.4）**:
  - 通常の初回開放は、承認（`OPEN_PENDING_RESTART`）の後に全停止で 1 回再起動すると `OPEN` になる（`NF05_INBOUND` は `OPEN` 確認後に LB/WAF の送信元 rule を切り替える）。起動時に `OPEN_PENDING_RESTART → OPEN`（`RESTART_APPLIED`）の DB 書込みだけが失敗した場合は、その起動は開いたまま WARN を出すため、DB 復旧後に画面で状態を確認し、必要なら再承認しておく。
  - **既に開いている（または署名済みの）NF05 で truststore や digest 対象の配備設定（CIDR・送信先・`batch-size`・`fixed-delay-ms`・`unauthenticated-audit-max-per-minute`）を変えるとき**は、承認評価が「現在の process が読んでいる設定・truststore」と「署名の `approvalDigest`」を比べるため、**全停止での再起動が 2 回要る**:
    1. 新しい DG-05 決定書（新決定 ID と新設定・新 truststore の `approvalDigest`）を用意する。
    2. 画面 `/feature-enablement` で対象方向を閉じ（inbound なら先に LB/WAF の遮断 D-1 を戻す）、新しい設定・truststore を入れた task definition で **1 回目の再起動**を行う（新設定・新 truststore が process に読まれ、NF05 は閉で立ち上がる）。
    3. 画面 `/feature-enablement` で新しい署名（`NF05_DG05_IN` / `NF05_DG05_OUT`）を登録・二人確認し、開放を申請・二人承認する（`OPEN_PENDING_RESTART`）。
    4. 同じ task definition で **2 回目の再起動**を行い、`OPEN` になったことを確認する（inbound なら LB/WAF の送信元 rule を戻す）。
- **自動閉鎖の DB 書込み待ち（`WARN errorCode=FEATURE_AUTO_CLOSE_PENDING`）の間の注意（design §7.4、review P3-N1）**:
  - 定期評価が条件不成立や署名失効を検出したのに DB 障害等で `AUTO_CLOSED` を書けなかった場合、その node はメモリ上の overlay（`overlay.<reason>`）で即時に実効閉にし、`FEATURE_AUTO_CLOSE_PENDING` を出して次周期に書込みを再試行する。
  - **DB への書込みが失敗している間（同じ開放単位に `FEATURE_AUTO_CLOSED` が出る前）は app を再起動しない**（再起動するとメモリ overlay が失われ、起動時の評価も同じ DB 書込み失敗に当たると一時的に不整合になり得る）。
  - やむを得ず再起動した場合は、起動直後の log と画面 `/feature-enablement` を確かめ、直前に `FEATURE_AUTO_CLOSE_PENDING` が出ていたのに `AUTO_CLOSED` になっていない開放単位があれば直ちに画面で手動閉鎖する。
- **DSAR（`NF07_DSAR`）が閉じている間の期限管理**: `NF07_DSAR` を閉じても受付済みの未完了請求の `due_date` は進み続ける。日の始まりの評価が `privacy-admin` へ未完了件数と最短期限日を毎日通知するため、画面で開き直すか手作業で期限を管理する（`APP_SCHEDULING_ENABLED=true` が前提）。
- **PITR（backup からの復元）の後**: 最初の起動は必ず `FEATURE_ENABLEMENT_ALLOWED=false` で全閉にし、CloudWatch Logs の `FEATURE_ENABLEMENT_EVENT` と照合して取消し済み署名を再取消ししてから `true` に戻して二人で開き直す（手順の正本は `ops/backup/runbooks/restore-cutover.md` §4.7）。

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
  - **アプリでの拒否（RB-ADMIN-GUARD。実装済み）**: 本番（`prod` profile。`Environment.acceptsProfiles` で判定し、prod以外では判定しない）では、管理者の人数・状態に関する起動時検査を**変更の前は満たし、変更の後は満たさなくなる**操作を、APIが実行せずに拒否する（HTTP 400。どの条件か・変更後の人数・必要な人数と、先に後任を作成してlinkを承認する案内を返す。transactionごとrollbackし、何も変わらない）。対象の条件は (1) OIDCでログインできる有効な管理者（break-glass username以外）3名以上、(2) break-glass管理者（ちょうど2件・有効な管理者・MFA登録済み）、(3) `privacy-admin` に所属しOIDCでログインできる有効な管理者2名以上。判定は `ProductionSecurityConfigurationValidator`・`PrivacyProductionConfigurationValidator` の同じ照会を変更の前後に同じtransactionで呼ぶ（`AdministratorContinuityGuard`。条件の写しを持たない）。起動時検査と同じく、bootstrap中は (1)(2) を、OIDCのtenant・issuerが未設定なら (1) を判定しない。**(3) は `NF07_RETENTION` または `NF07_DSAR` が開いている（`OPEN` / `OPEN_PENDING_RESTART`）か未決の開放申請（`PENDING`）がある間だけ判定する**（両方とも閉じて未決申請も無い間は `privacy-admin` の人数変更を拒否しない。ただし **開放状態表 `t_feature_enablement_state` や申請表 `t_feature_enablement_request` を照会できない場合は、安全側として全ての利用者管理・権限 group 割当変更を HTTP 409 `error.user.adminContinuity.unverifiable` で拒否する**。`feature-enablement-console` design §14・J-17）。変更の前から満たしていない条件（bootstrapで人数を揃えている途中など）では拒否しない。変更の前・後のどちらかで条件を照会できない場合も、人数を比べられないため拒否する（HTTP 409。`error.user.adminContinuity.unverifiable`。fail-closed）。
    - 対象の入口: ユーザーの更新（role変更・username変更。`PUT /api/users/{id}`）・有効/無効の切替（`PUT /api/users/{id}/status`）・削除（`DELETE /api/users/{id}`）、権限groupの割当替え（`PUT /api/permission-groups/users/{userId}`。privacy-adminの解除）、要員の削除（`DELETE /api/engineers/{id}`）と退社案件の完了（`POST /api/lifecycle/cases/{id}/complete`）による連携ユーザーの無効化（連携ユーザーが後から管理者へrole変更されていた場合に人数が減る）。ユーザーの作成は人数を減らさないが、同じlockを取る。自分自身の削除・無効化・role変更の拒否（既存）は変わらない。
    - 並行: ユーザー管理の各操作と権限groupの割当替えは、全tenantの未削除の管理者と対象ユーザーの `sys_user` 行を `(tenant_id, id)` の昇順でX lockしてから数える（READ_COMMITTED。NF01 lock rank 3 の昇順。実際のtransactionが READ_COMMITTED でない呼出しは、lock後の照会が古い値を読み得るため、確認できないとして409で拒否する）。2名の管理者が同時に別々の管理者を無効化しても、後の操作は先の操作のcommitを待ってから数え直すため、両方が通って人数を割ることはない。lockを待つ間に別の操作が管理者を増やして（role変更・作成）commitした場合は、lockしていない管理者が居るため409（`error.common.optimisticLock`）で止める。何も変わらないため、再度実行すればよい。lockはcommitまで保持するため、その間（本番だけ、通常は1回のユーザー管理の処理の間）、ほかの管理者のloginの記録（失敗回数・lockの更新、sessionの登録）、取込の書込み（`ImportMetadataAuthorizationService` が操作者である管理者の行をlockする）、管理者の行を外部キーで参照する書込みは、その行のlockの解放を待つ。要員の削除・退社案件の完了は、NF01のlock計画を変えないため、既にX lockしている連携ユーザーの行だけで数える（ユーザー管理の操作とは連携ユーザーの行で直列化されるが、この2つの経路どうしが同時に別々の管理者を無効化する競合は直列化しない。管理者が要員に連携されているのは誤設定の場合だけ）。
    - 拒否の監査: `t_audit_log.application_code = 'ADMIN_CONTINUITY_DENIED'`（`method = 'SECURITY'`、`uri` に操作名・対象のid・条件ごとの変更前後の人数と必要な人数）。rollbackの後に独立transactionで書く（best-effort。書けない場合はapp logのWARN `[admin-continuity]` だけが残る）。
    - 対象外（アプリからは止めない・止められない）: IdP側のaccountの停止、DBの直接変更、`m_permission_group`・`t_permission_group_action` の変更（アプリに書込みの入口が無い）。これらによる減少はアプリが拒否できないため、上の定期集計は続ける。
    - **break-glass管理者のMFAのresetと再登録はguardの対象外**（`POST /api/security/mfa/{userId}/reset`・`/mfa/setup`）。§5.8の「TOTPの喪失」（recovery codeで検証して `/mfa/setup` で登録し直す、OIDC管理者がresetして本人がbreak-glass loginで登録し直す）と「break-glass保持者の交代」の案B（OIDC管理者がresetし、後任がbreak-glass loginで登録する）の手順が、resetと登録し直しを前提にするため。resetの後、または `/mfa/setup` で新しいsecretを発行した後（6桁コードで有効化するまでは登録済みとして数えない）、本人の再登録が終わるまでに再起動すると、起動時検査（`break-glass usernameはMFA enrollment完了済みでなければなりません`）で止まる。§5.8の手順の中では再起動せず、resetから再登録までを同じ作業枠で行う（止まった場合は§5.7の再開で登録する）。
- **機能の開放における二人の承認の既知の限界と運用規則（Security が確認する。`feature-enablement-console` design §11.1、review P3-N10）**:
  - アプリと DB（CHECK・複合 FK）は「申請者と承認者（署名は登録者と確認者）が同じ `sys_user.id` であること」を拒否するが、次の 2 点は DB の制約だけでは防げない既知の残余リスクである: (1) 同じ自然人が 2 つの OIDC 口座を持つ場合（口座管理と IdP 側の統制に委ねる）、(2) 管理者の作成・role 付与・再有効化は管理者 1 名で行えるため、侵害された管理者口座が別の OIDC 口座に `管理者` role を与えて自分で承認する場合。
  - (2) の緩和として、利用者の作成・role 変更で新たに `管理者` になった時と、無効だった `管理者` を有効に戻した時は、有効な管理者全員へ通知を送る（`SysUserServiceImpl`）。運用では必ず **「承認（および署名・組の確認）の前に、直近に管理者の role を得た利用者が承認者・確認者でないことを確かめる（全ての管理者への通知と監査ログで確認する）」** を守る。
  - 「申請者が付与した管理者、または付与から N 日以内の管理者による承認」をコードで拒否するには `sys_user` に role 付与者と付与時刻の列が要るため、その DDL 追加は **go-live 後の backlog 項目**として記録する。
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
  - 戻した DB には backup の時点の信号表（RB-QUIESCE）が残り、backup account の表単位の権限は DB の復元では戻らない。扱いと付け直しは `ops/backup/runbooks/restore-cutover.md` §4.6。
  - **戻した DB の機能の開放の状態と署名も復旧点の時点に戻る**ため、復元後の最初の app の起動は必ず `FEATURE_ENABLEMENT_ALLOWED=false` で全閉にし、CloudWatch Logs の `FEATURE_ENABLEMENT_EVENT` と照合して取消し済み署名を再取消ししてから `true` に戻して二人で開き直す（`ops/backup/runbooks/restore-cutover.md` §4.7）。
  - 新旧artifactを同時に稼働させない（rolling禁止。`scheduler-claim-fence-plan.md` §10.1）。
  - uploads（RB-W）: DB を PITR で戻すとき、uploads の pointer をどうするかは次のどちらかで、**未決定**（DBA・SRE の確認項）。(1) DB を戻した plan と同じ checkpoint から `ops/backup/uploads-cutover.sh stage` で版を作り、`ops/backup/runbooks/restore-cutover.md` §4.5.1 の順序で switch する。DB と pointer が一対になる。(2) uploads は戻さず今の版のまま起動する。この場合、戻した DB が参照しない file（戻した点より後に置かれた file）は孤児清掃が safety-hours の後に消し、戻した点より後に app が消した file を戻した DB が参照していれば、その file は無いままになる。決まるまでは、DB を戻す change の中で DBA・SRE がどちらにするかを決めて記録する。

## 7. 参照

- `.kiro/specs/production-launch/feature-switches.md`（機能スイッチ）
- `.kiro/specs/scheduled-management-reporting/scheduler-claim-fence-plan.md` §10（drain・検出SQL）
- `ops/backup/runbooks/`（backup・restore）
- W1-B3 独立review（`FlywayProdFreshInstallMySqlTest` の前版と、本書の前提となった事実確認）
