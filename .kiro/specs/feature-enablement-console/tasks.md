# 機能の開放を system 内で行う 実装タスク

## 実行規約

- 本書は第 1 段階（spec）で書いた。実装は spec の独立 review の後、統括の指示で段階ごとに始める。
- 利用者の決定（`requirements.md` §2 の D-1〜D-9。D-7〜D-9 は `design.md` §18 の J-01・J-09・J-16 の決定済みの値）と、§18 の残りの判断点（J-02〜J-20 の J-09・J-16 以外）の決まった値に従う。判断点が推奨と違う値に決まったら、先に `design.md` とこの task を直してから進める。
- checkbox は、実装・自動試験・Demo が全て終わった時だけ `- [x]` にする（CLAUDE.md の spec の手順）。
- 試験は各 task の対象だけを流す（`mvn test -Dtest=…`。MySQL は `-Pmysql-tests -Dtest=…`、browser は `-Pbrowser-tests`）。全 gate の実行の時期と mysql の shard は統括が決める。migration の file を足した後は `mvn clean test` にする。
- 版番号は V203（統括の指定）。着手時に `db/migration` と `db/migration-dev` の merged の状態で空いていることを確かめる。DDL が他に要れば V204 以降を統括に申請する。
- repository に書く文字は日本語（UI・log・comment・commit）。message bundle は 4 つとも翻訳する。
- **go-live は段階 E の完了の後に限る**（D-1。段階 A〜D の間は、未移行の開放単位が property の源のまま残る。`design.md` §3.1）。
- 人が行う署名・受理・実測は、どの段階でも完了にしない（登録の器を作るだけ）。

## 段階と規模の見積り

| 段階 | 内容 | 依存 | 並行 | file の数の目安（新規＋変更） | 難所 |
|---|---|---|---|---|---|
| A | 判定層・データモデル（V203）・署名の登録・申請と承認・継続評価・総閘・画面・権限・容易な 7 件 | なし | A 内は §各 task の依存に従う | 新規 約 45（main 約 30、test 約 15）、変更 約 25 | snapshot の反映と fail-closed、lock の順、session の種別の判定、file の 2 箇所の登録、H2 の生成列 |
| B | NF01 二者確認・NF06・NF02 の公開範囲 | A | B01・B02・B03 は互いに並行。C・D とも並行 | 新規 約 8、変更 約 14 | 証跡種別の BINARY 比較の H2 互換、admission の源の差替え |
| C | NF07（承認の値の源・直接の読取り・Journal・trust・評価器・PR #130 の guard） | A | C02〜C04 は C01 の後に並行。B・D と並行 | 新規 約 10、変更 約 22 | Monitor の再帰の回避、Journal の再検査、guard の意味の変更 |
| D | NF05（policy の純粋化・起動前の読取り・runtime switch・閉鎖と barrier） | A | D03・D04 は D02 の後に並行。B・C と並行 | 新規 約 8、変更 約 18 | `EnvironmentPostProcessor` での DB の読取り、barrier の起動時と実行時の分離 |
| E | 運用・文書・照合 script・既定値の試験の総仕上げ | A〜D | E01〜E03 は並行 | 変更 約 12 | RB §1.7 の書直し（go-live の前提の範囲を変える） |

- 段階 B・C・D が同時に触る file は、目録（`FeatureEnablementCatalog`）の各行と `FeatureEnablementProductionValidator` の旧い変数の一覧だけ。合入の時の衝突は行の単位で解ける。
- 1 段階を 1 PR にする想定（A は大きいため、A1: A01〜A07、A2: A08〜A11 に分けてもよい）。

## Task 一覧

### 段階 A

- [ ] **FE-A01 — V203・H2 の schema・版番号の連動**
  - **依存:** なし
  - **対応要求:** FE-RQ-002・003・004・012・013・017
  - **Objective:** `design.md` §2 の 6 表・2 trigger・menu の seed・data element の 2 件を、本番の MySQL 8.4 と試験の H2 の両方に同じ形で作る。
  - **対象 file:** `src/main/resources/db/migration/V203__feature_enablement_console.sql`（新規）、`src/test/resources/sql/schema-feature-enablement-h2.sql`（新規）、`src/test/resources/application-test.yml`（`schema-locations`）、`src/test/resources/sql/engineer-schema-h2.sql`、`src/test/java/com/ses/migration/LatestMigrationVersion.java`、`ProductionSchemaVersionGuardTest`、`src/test/java/com/ses/migration/FeatureEnablementSchemaContractTest.java`（新規）、`FlywayProdFreshInstallMySqlTest`、`.kiro/specs/production-launch/deploy-runbook.md`（版・件数・trigger 数・§3.4 の確認）、`.kiro/specs/privacy-retention-dsar/pii-inventory.md`。
  - **Implementation:**
    1. `design.md` §2.2〜§2.8 の列・CHECK・生成列・一意索引・FK（参照動作なし）で V203 を書く。trigger は event 表の 2 件だけ。時刻の DEFAULT に `CURRENT_TIMESTAMP` を使わない。
    2. menu `feature-enablement` と `t_role_menu`（`管理者` だけ）を足す。`t_permission_group_action` には足さない。
    3. `m_pii_data_element` に 2 件（V198 と同じ列。分類等は UNKNOWN）。
    4. H2 の変種（trigger なし・同じ生成式）。`engineer-schema-h2.sql` は event → sign_off → setting → state → request → generation の順に DROP して作る。
    5. 版と件数の連動（grep `202`）。
  - **Automated test:** `MigrationScriptIntegrityTest`、`DeployRunbookMigrationCountTest`、`ProductionSchemaVersionGuardTest`、`FeatureEnablementSchemaContractTest`（V203 と H2 の一致）。MySQL（`FeatureEnablementSchemaMySqlTest`、shard は統括）: 申請者＝承認者の `APPROVED`・`REJECTED`、申請者以外の `WITHDRAWN`、未決 2 件、確認済み 2 件、`confirmed_by = registered_by`、`OPEN` で `opened_by = approved_by`、NF05 以外の `OPEN_PENDING_RESTART`、`expires_on <= signed_on` が全て拒否され（3819 は `MySqlConstraintRejections.assertRejectedBy`）、event の更新・削除が trigger で拒否される。`FlywayProdFreshInstallMySqlTest` の trigger 数が 2 増える。
  - **Demo:** local の MySQL 8.4 の空の DB で `mvn spring-boot:run`（dev）を起動し、`SHOW CREATE TABLE t_feature_enablement_request\G` で生成列と CHECK を、`flyway_schema_history` の最大が 203 であることを見る。
  - **失敗/rollback:** 版番号が衝突したら上の空き番号へ振り直す（空き番号を埋めない）。

- [ ] **FE-A02 — entity・mapper・署名の文書の file 種別と 2 箇所の登録**
  - **依存:** FE-A01
  - **対応要求:** FE-RQ-004・013・017
  - **Objective:** 6 表の entity と mapper（event は `AppendOnlyMapper`）、`FileKind.FEATURE_SIGN_OFF`、汎用の upload・download の拒否、`FileReferenceProvider` を用意する。
  - **対象 file:** `src/main/java/com/ses/entity/FeatureEnablement*.java`・`FeatureSignOff.java`（新規）、`src/main/java/com/ses/mapper/FeatureEnablement*Mapper.java`・`FeatureSignOffMapper.java`（新規）、`src/main/java/com/ses/common/mybatis/` の規約に合わせた `FeatureEnablementEventMapper`（`@MapperScan` の外）、`common/enums/FileKind.java`（`FEATURE_SIGN_OFF`・`isDedicatedUploadOnly()`）、`controller/api/FileApiController.java`（:36-44）、`service/security/impl/FileScopeValidationService.java`（:255 の前置きの拒否）、`service/impl/FeatureSignOffFileReferenceProvider.java`（新規）。
  - **Implementation:** entity に `deletedFlag` を置かない。`selectByIdForUpdate`・状態ごとの CAS 更新（`UpdateWrapper` で列を名指し。CLAUDE.md の `FieldStrategy.ALWAYS` の注意）。`FileScopeValidationService` に `assertFeatureSignOffNotAccessibleThroughGenericFilePath`（先例 :519）。
  - **Automated test:** `FeatureEnablementEventMapperImmutabilityTest`（全ての更新・削除が例外）、`FileApiControllerTest` に `FEATURE_SIGN_OFF` の upload が 400 の case、`FileScopeValidationServiceTest` に署名の保存名が全 role で 403 の case、`FeatureSignOffFileReferenceProviderTest`（`PURGED` 以外を claim）、`FileKindTest`（pdf だけ・20 MB）。
  - **Demo:** なし（試験の結果を確かめる）。

- [ ] **FE-A03 — 目録・評価器の interface・共通の評価器**
  - **依存:** FE-A01
  - **対応要求:** FE-RQ-001・005
  - **Objective:** `FeatureEnablementCatalog`（13 件、署名の種別、鍵、親、副入口、DB 管理の印）と、`FeatureConditionEvaluator`・`FeatureConditionCheck`・`FeatureEvaluationContext`・`FeatureConditionRegistry`、共通の評価器（総閘・親・署名・鍵）、指紋を作る。
  - **対象 file:** `src/main/java/com/ses/featureenablement/catalog/*`・`condition/*`（新規）、`config/feature/Feature.java`（`NF05_INBOUND`・`NF05_OUTBOUND` を足す。`propertyName`・`environmentVariable`）、`config/feature/FeatureSwitchProperties.java`（`toggle` の NF05 の case は NF05 の property を返す）、`FeatureSwitchRouteCoverageTest`・`FeatureSwitchDefaultsTest`（一覧の追加だけ）。
  - **Implementation:** `design.md` §4。check は `Scope`（`CONTINUOUS` / `OPEN_ONLY`）を持ち、`OPEN_ONLY` は申請・承認の時だけ開閉を左右する（D-9）。違反 code は固定の文字列だけ。評価器の例外は registry が `UNVERIFIABLE` にする。固有の評価器を要する開放単位に評価器が無ければ `evaluator.missing`。指紋の正規形は §4.5。
  - **Automated test:** `FeatureEnablementCatalogCoverageTest`（13 件・NF08 なし・種別の一意・親の循環なし・`OPEN_ONLY` は NF02 の暦だけ）、`FeatureConditionRegistryTest`（`OPEN_ONLY` の不成立は申請・承認では不成立、起動時・定期では閉鎖の根拠にならない）、`SignOffConditionEvaluatorTest`（JST の境・署名日前・条件付きの種別）、`FeatureConditionFingerprintTest`（golden vector）。
  - **Demo:** なし。

- [ ] **FE-A04 — snapshot・源の切替え・`FeatureSwitches` の変更**
  - **依存:** FE-A02、FE-A03
  - **対応要求:** FE-RQ-006・016
  - **Objective:** `FeatureEnablementStateSource`（property・database・段階の間の合成）、snapshot の loader・holder・refresher（専用 thread）、overlay、`FeatureSwitches.isConfiguredOn` の差替え、`logStates` の変化ごとの出力、metric を作る。
  - **対象 file:** `featureenablement/state/*`（新規）、`config/feature/FeatureSwitches.java`（:40・:90-97）、`config/feature/FeatureRuntimeCondition.java`（`unsatisfiedReason`）、`application.yml`・`application-prod.yml`・`application-dev.yml`（`app.feature-enablement.*`）、`SchedulingDisabledDrainTest`（refresher の thread を理由つきで既知の一覧へ）。
  - **Implementation:** `design.md` §3。hot path は `AtomicReference` と日付の比較だけ。古さで全て閉。commit の後の差替えは `TransactionSynchronization.afterCommit`。`new FeatureSwitches(properties)` を残す。
  - **Automated test:** `FeatureEnablementSnapshotTest`、`DatabaseFeatureEnablementStateSourceTest`（照会 0 回を mapper の mock の呼出し回数で確かめる。JST 0:00 の境。古さ。総閘）、`FeatureSwitchRuntimeConditionTest`（既存の case が通る）、`FeatureSwitchFilterTest`・`FeatureSwitchMenuAndViewTest`・`FeatureSwitchSchedulerGateTest` に DB の源の変種（閉じた機能の応答が property の源と同じ）。
  - **Demo:** dev で `APP_FEATURE_ENABLEMENT_SOURCE=database` を付けて起動し、`/api/lifecycle/cases` が 404 で、起動の log に `[機能スイッチ] 状態: NF01=OFF, …` が出ることを見る。

- [ ] **FE-A05 — 申請・承認・却下・取下げ・閉鎖・cascade（`FeatureEnablementService`）**
  - **依存:** FE-A03、FE-A04
  - **対応要求:** FE-RQ-002・003・013
  - **Objective:** `design.md` §5 の遷移を、lock の順・指紋・評価し直し・監査・履歴・通知とともに実装する。行為者の判定（§11.2）を部品 `FeatureEnablementActorPolicy` にまとめる。
  - **対象 file:** `featureenablement/service/FeatureEnablementService(Impl).java`・`FeatureEnablementActorPolicy.java`（新規）、`WriterIsolationPolicyTest`（`READ_COMMITTED_WRITERS` に足す）、`messages*.properties`（誤りの文言）。
  - **Implementation:** 申請の時も条件の全成立を要する。承認は `expectedVersion` と指紋。却下は申請者以外、取下げは申請者だけ。閉鎖は管理者 1 名で理由が必須、子を `AUTO_CLOSED(PARENT_CLOSED)`、未決を `CANCELLED`。NF07 の行為者は privacy-admin（段階 C で有効にする印を置く）。監査は `recordDomainEventRequired`（method `FEAT_EN`）。通知は commit の後。
  - **Automated test:** `FeatureEnablementServiceH2Test`（全遷移と許さない遷移の 409、申請者による承認・却下の 403、未決 2 件の 409、指紋の違いで `STALE`、条件の不成立で承認 409 のまま未決、cascade、監査と履歴が 1 件ずつ、監査の失敗で rollback）、`FeatureEnablementActorPolicyTest`（OIDC＋MFA は可、break-glass の属性・bootstrap の session・break-glass の username・lock 中・無効・削除済み・管理者以外は不可、prod 以外の form login は可）、`AuditLogMethodLengthGuardTest`。MySQL（`FeatureEnablementConcurrencyMySqlTest`）: 同じ申請の並行承認で 1 件だけ成功、承認と閉鎖の並行、世代の lock で deadlock しない。
  - **Demo:** dev（DB の源）で管理者 2 名（seed）を使い、NF04 の申請 → 別の管理者が承認 → `/manifest.webmanifest` が 200 になり、閉鎖で 404 に戻ることを見る（署名は FE-A06 の後に登録する）。

- [ ] **FE-A06 — 署名の登録・確認・却下・取消し・差替え・失効・取得・消去（`FeatureSignOffService`）**
  - **依存:** FE-A02、FE-A03、FE-A05
  - **対応要求:** FE-RQ-004・017
  - **Objective:** `design.md` §6 を実装する。
  - **対象 file:** `featureenablement/service/FeatureSignOffService(Impl).java`、`featureenablement/signoff/*`（種別ごとの検査。placeholder の規則の共通部品化）、`FeatureSignOffPurgeScheduler.java`（新規）、`PrivacyHoldPathGuard` の呼出し、`.kiro/specs/production-launch/deploy-runbook.md` §4.4（D17）。
  - **Implementation:** upload は `FileStorageService.store(byte[], …, FEATURE_SIGN_OFF)`、SHA-256 は bytes から計算、scan が `CLEAN` でなければ拒否。確認は登録者以外（NF07 の種別は privacy-admin。段階 C で有効）。差替えは旧い行を先に `SUPERSEDED`。取消し・差替えで開いている開放単位を同じ transaction で評価し直し、成り立たなければ `AUTO_CLOSED`。取得は hash を照合し監査。消去の scheduler は `@SchedulerLock`・`void`・`PrivacyHoldPathGuard` を通す。一覧の応答に氏名を出さない。
  - **Automated test:** `FeatureSignOffServiceH2Test`（scan の拒否、hash の照合と不一致の 409、登録者による確認の 403、差替えで開いたまま・成り立たなければ自動閉鎖、取消しで自動閉鎖、`active_slot`、一覧に氏名なし）、`FeatureSignOffKindValidationTest`（種別ごとの参照・構造化された値・上限 366 日・役割）、`FeatureSignOffPurgeSchedulerTest`（3 年・hold の対象は消さない・`void`）、`PrivacyHoldPathGuardTest` の経路の一覧。
  - **Demo:** dev（DB の源）で合成の PDF を `NF04_BUSINESS_OWNER` として登録 → 別の管理者が確認 → 専用の経路で取得でき、`/api/files/…` では 403 になることを見る。

- [ ] **FE-A07 — 継続評価（起動時・定期・日の始まり）と自動閉鎖**
  - **依存:** FE-A05、FE-A06
  - **対応要求:** FE-RQ-005
  - **Objective:** `FeatureEnablementStartupEvaluator`（`SmartLifecycle`、web server の前）、`FeatureEnablementConditionScheduler`（`evaluateAll`・`evaluateAtBusinessDayStart`。`@SchedulerLock`・`void`）、overlay と `UNVERIFIABLE` の 3 回、申請の期限切れ、失効の接近の通知、副入口の変化の履歴。
  - **対象 file:** `featureenablement/evaluation/*`（新規）、`FeatureSwitchRouteCoverageTest`（`UNGATED_SCHEDULERS` に理由つき）、`SystemConfigServiceImpl`（`SCHEMAS` に `feature-enablement.expiry-warning-days`）、`messages*.properties`（通知の文言）。
  - **Implementation:** `design.md` §7。DB に書けない時は overlay のまま次の周期。WARN `errorCode=FEATURE_AUTO_CLOSED`。
  - **Automated test:** `FeatureEnablementConditionSchedulerH2Test`（不成立で自動閉鎖・他の開放単位は不変・通知、例外で overlay・3 回で自動閉鎖、失効日の 0:00 の後の最初の評価、期限切れの申請、`OPEN_ONLY` の check が外れても閉じず監査 `FEATURE_OPEN_CONDITION_EXPIRING`・`FEATURE_OPEN_CONDITION_LOST` と通知だけ）、`FeatureEnablementStartupEvaluatorTest`（起動時の自動閉鎖・総閘）、`ScheduledMethodsHaveSchedulerLockTest`、`SchedulerLockProxyInvocationTest`。
  - **Demo:** dev（DB の源）で開いた NF04 の署名の失効日を翌日にした合成の署名へ差し替え、clock を進めた試験で自動閉鎖と通知を確かめる（試験の結果を見る）。

- [ ] **FE-A08 — 総閘・旧い変数の拒否・prod の源の固定（段階 A の範囲）**
  - **依存:** FE-A04、FE-A07
  - **対応要求:** FE-RQ-007・016
  - **Objective:** `FEATURE_ENABLEMENT_ALLOWED` と `FeatureEnablementProductionValidator`（prod の判定は `Environment.acceptsProfiles`）を作り、段階 A で DB 管理にした 7 件の旧い変数を拒否する。CLAUDE.md の規則どおり、RB §1.0 の行と雛形の名前を同じ change で足す。
  - **対象 file:** `featureenablement/config/FeatureEnablementProperties.java`・`FeatureEnablementProductionValidator.java`（新規）、`application.yml`・`application-prod.yml`、`infra/ecs/task-definition.prod.json`（`FEATURE_ENABLEMENT_ALLOWED=true`）、`infra/ecs/offline/validate-task-definition.ps1`（`mustBeVisible` に足す・値の検査）、`infra/ecs/offline/test-validate-task-definition.ps1`、`ProductionTaskDefinitionTemplateTest`、`deploy-runbook.md` §1.0。
  - **Implementation:** `design.md` §10。旧い変数の名前の正規化は ps1 の `NameKey` と同じ。message は変数名だけ。総閘が閉なら起動時に `AUTO_CLOSED(MASTER_SWITCH_CLOSED)`・未決を `CANCELLED`・ERROR・gauge。
  - **Automated test:** `FeatureEnablementProductionValidatorTest`（`@ActiveProfiles("prod")` 相当で、旧い変数・`SPRING_APPLICATION_JSON` の各綴りで起動が止まる、値が message に出ない、源が `database` でなければ止まる）、`FeatureEnablementMasterSwitchH2Test`（閉で全て閉・申請 409・署名の登録は可・開に戻しても閉のまま）、ps1 の試験（雛形 PASS、`FEATURE_ENABLEMENT_ALLOWED` を secrets に置くと FAIL）。
  - **Demo:** dev（DB の源）で `FEATURE_ENABLEMENT_ALLOWED=false` で起動し、開いていた NF04 が閉じ、画面の申請が 409 になることを見る。

- [ ] **FE-A09 — API・権限の配線**
  - **依存:** FE-A05、FE-A06
  - **対応要求:** FE-RQ-011・012
  - **Objective:** `FeatureEnablementApiController`（`design.md` §15.1）と page controller、権限の配線を足す。
  - **対象 file:** `controller/api/FeatureEnablementApiController.java`・`controller/page/FeatureEnablementPageController.java`（新規）、`service/security/ActionPermissionResolver.java`（`RESOURCE_NAMES` :13 と `pageResource` :450）、`service/security/impl/AuthorizationServiceImpl.java`（`isAdminOnlyAction` :204-208 に `feature-enablement.`）、`config/SecurityConfig.java`（両 path を `hasRole("管理者")`）、`service/security/impl/BreakGlassServiceImpl.java`（`validateAndSerializeActions` :316-331 で拒否）、`FeatureSwitchRouteCoverageTest`（`UNGATED_ROUTE_PREFIXES` に理由つき）。
  - **Implementation:** 一覧・履歴は `PageUtils.safePage`。`/units/{key}/evaluate` は開放単位ごとに 1 分に 1 回。応答は `ApiResult`。
  - **Automated test:** `FeatureEnablementApiControllerTest`（管理者 200、営業・HR・マネージャー（group あり・なし）403、未認証 401/302、CSRF、氏名を一覧に返さない）、`ActionPermissionMatrixTest`（allow-list を足さずに通る）、`BreakGlassServiceImplTest` に `feature-enablement.view` の拒否、`MenuPermissionFilter` の試験に admin の素通しと resolver の null にならないこと。
  - **Demo:** dev で管理者と営業で `/api/feature-enablement/units` を叩き、200 と 403 を見る。

- [ ] **FE-A10 — 画面「機能の開放」と 4 言語の文言**
  - **依存:** FE-A09
  - **対応要求:** FE-RQ-011
  - **Objective:** `design.md` §15.2 の画面を作る。
  - **対象 file:** `templates/feature-enablement/list.html`、`static/js/modules/feature-enablement.js`、`templates/layout/sidebar.html`（menu `feature-enablement`）、`messages.properties`・`messages_en.properties`・`messages_ko.properties`・`messages_zh_CN.properties`。
  - **Implementation:** 既存の規約（`SES.csrf.header()`、`res.code === 200`、`Toast`、`Swal.fire`、`#featureEnablementModal`、`/lib/...` だけ）。履歴の絞込みは `.card > .card-body > form#searchForm`。自分の申請に承認の button を出さない。digest は先頭 16 文字だけ。
  - **Automated test:** `MessageBundleConsistencyTest`（`testTemplateMessageKeysExist` を含む）、`StaticAssetLocalityTest`、`FeatureEnablementTemplateRenderingTest`（Thymeleaf で描画し、管理者以外に menu が出ない）。browser（`FeatureEnablementScreenBrowserTest`、統括が実行）は段階 E でまとめて流す。
  - **Demo:** dev（DB の源）で画面を開き、署名の登録 → 確認 → 申請 → 別の管理者で承認 → 閉鎖を画面だけで行い、phone 幅で履歴の絞込みが畳まれることを見る。

- [ ] **FE-A11 — 容易な 7 件を DB 管理にする（NF01・NF02・NF02 顧客ポータル・NF03・NF03 資格番号・NF04・NF10）**
  - **依存:** FE-A07、FE-A08、FE-A10
  - **対応要求:** FE-RQ-001・006（要件 §6.1・6.3〜6.7・6.13）
  - **Objective:** 7 件の目録の行（署名の種別・親）に DB 管理の印を付け、NF02 の暦（開放の時だけの条件。D-9）と NF10 の scheduler の評価器を足す。
  - **対象 file:** `featureenablement/catalog/FeatureEnablementCatalog.java`、`featureenablement/condition/WorkCalendarConditionEvaluator.java`・`SchedulingConditionEvaluator.java`（新規）、`mapper/WorkCalendarMapper.java`（件数の method）、`FeatureEnablementProductionValidator`（7 件の旧い変数）。
  - **Implementation:** 暦は `ServiceSlaCalculator.findCalendarForScope`（:366-400）の 4 段目と同じ条件（本日を含む当年度の全社共通の暦）で 1 回照会し、check を `Scope.OPEN_ONLY` にする: 申請・承認の時だけ必須、開いた後は NF02 を自動閉鎖しない。暦の有効期間の終わりの 30 日前からと切れた後は、日の始まりの評価で毎日 1 回、監査・管理者への通知・WARN（`design.md` §7.4）。暦が無い間の SLA の計算（WARN `missing_calendar`、土日だけを休日）は変えない。scheduler 無効は `ADVISORY`。各機能の code は変えない。
  - **Automated test:** `WorkCalendarConditionEvaluatorTest`（暦なし・期間外・要員／組織／法人つきの暦だけ・無効の暦で不成立、`Scope.OPEN_ONLY`）、`FeatureEnablementNf02CalendarH2Test`（暦なしで NF02 の申請・承認が 409、開いた後に暦が切れても NF02 と子は `OPEN` のまま、30 日前からの毎日の監査と通知、`ServiceSlaCalculator` の既存の `missing_calendar` の試験が変わらず通る）、`SchedulingConditionEvaluatorTest`、`FeatureEnablementEasyUnitsH2Test`（7 件を DB の源で開閉し、filter・menu・scheduler・子の gate が property の源と同じ応答になる）、`FeatureSwitchChildTemplateRenderingTest`（DB の源の変種）。
  - **Demo:** dev（DB の源）で、暦が無い状態では NF02 の申請が条件の不成立で拒否されることを見る。暦の seed を入れて NF02 を開き、子の顧客ポータルを開き、`/portal/customer` に問い合わせの tab が出ること、NF02 を閉じると子も `AUTO_CLOSED` になることを見る。暦の有効期間の終わりを 30 日以内にした seed で日の始まりの評価を手で呼び、NF02 が開いたままで通知と監査が出ることを見る。

### 段階 B

- [ ] **FE-B01 — NF01 二者確認**
  - **依存:** 段階 A
  - **対応要求:** FE-RQ-001・005（要件 §6.2）
  - **Objective:** `LifecycleDualConfirmationReleaseGate` の起動の停止をやめ、同じ判定を評価器から使う。証跡種別の検査と閉鎖の警告を足す。
  - **対象 file:** `service/lifecycle/dual/LifecycleDualConfirmationReleaseGate.java`（:68-80 の例外を削除、`acceptable` は残す）、`service/lifecycle/dual/LifecycleDualConfirmationEnablementConditions.java`（新規）、`mapper/LifecycleTemplateTaskMapper`・`LifecycleTaskMapper`（件数の method）、目録、`FeatureEnablementProductionValidator`（`FEATURE_NF01_DUAL_CONFIRMATION_*`）、`FeatureEnablementQueryService`（有効な DUAL template の件数）。
  - **Implementation:** `design.md` §8.2。BINARY 比較の H2 での書き方を着手時に確かめる。
  - **Automated test:** `LifecycleDualConfirmationEnablementConditionsTest`（`acceptable` と同じ入力で同じ成否、許可値の外の行で不成立）、`LifecycleDualConfirmationReleaseGateTest`（prod でも起動を止めない）、`LifecycleTemplateDualPolicyTest`・`LifecycleTemplateDualSwitchUnavailableTest`（既存のまま通る）、MySQL で照合順序の揺れ（全角英字・NBSP）を検出する case。
  - **Demo:** dev（DB の源）で合成の Privacy の署名を登録・確認し、NF01 → 二者確認の順に開き、二者確認の提出が通ること、閉鎖の dialog に DUAL template の件数が出ることを見る。

- [ ] **FE-B02 — NF06**
  - **依存:** 段階 A
  - **対応要求:** FE-RQ-001・005（要件 §6.10）
  - **Objective:** admission の承認の値の源を差し替え、2 つの起動時検査の停止をやめて評価器にする。V193 の権限と単一法人の検査を足す。
  - **対象 file:** `importcenter/metadata/ImportAdmissionApprovalSource.java`（新規。property と DB の実装）、`ApprovedSourceImportAdmissionPort.java`（:121）、`ImportAdmissionStartupValidator.java`（:52-77）、`ImportRowReferenceKeyStartupValidator.java`（:38-55）、`importcenter/metadata/ImportCenterEnablementConditions.java`（新規）、`mapper/PermissionGroupActionMapper`（V193 の件数）、`LegalEntityContextService`（件数の method）、目録、`FeatureEnablementProductionValidator`（`IMPORT_ADMISSION_*`）。
  - **Implementation:** `design.md` §8.3。`NF06_ADMISSION` の構造化された値を `ImportAdmissionApprovalProperties` に写して `ImportAdmissionApproval.evaluate`。
  - **Automated test:** `ImportCenterEnablementConditionsTest`（`ImportAdmissionPortTest` の golden vector `c5dc5f03…` の値の署名で成立、改竄・期間外・鍵の状態・権限の行・法人 0 件と 2 件で不成立）、`ImportAdmissionStartupValidatorTest`・`ImportSourceRowReferenceTest`（prod でも起動を止めない）、`ImportCenterPageControllerTest`・`ImportCenterPageFeatureOffTest`（DB の源の変種）。
  - **Demo:** prod の profile の試験の構成で、合成の admission の署名を登録・確認し NF06 を開き、upload の前の検査が通ることを確かめる（dev では評価器が prod 専用のため申請できないことも見る）。

- [ ] **FE-B03 — NF02 の公開範囲（`visible-since`）の変更**
  - **依存:** 段階 A（FE-A11）
  - **対応要求:** FE-RQ-010
  - **Objective:** 設定の変更の申請と承認、`PortalServiceRequestVisibilityPolicy` の毎回の読取りを実装する。
  - **対象 file:** `service/servicedesk/PortalServiceRequestVisibilityPolicy.java`（:45-52）、`FeatureEnablementService`（`SETTING_CHANGE`）、目録（`NF02_PORTAL_VISIBLE_SINCE_DECISION`・`NF02_PORTAL_INVENTORY`）、画面の「設定」tab、`FeatureEnablementProductionValidator`（`FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`）。
  - **Implementation:** `design.md` §5.6・§8.4。DB の値が壊れていれば portal 起票だけ（fail-closed）。
  - **Automated test:** `PortalServiceRequestVisibilityPolicyTest`（property の源の既存の case と、DB の源で値の変更が次の判定から効く case）、`PortalServiceRequestVisibilityIntegrationTest`（DB の源の変種）、`FeatureEnablementSettingChangeH2Test`（申請者本人の承認 403、署名の値と違う値 409、offset なし 400、未設定へ戻す）。
  - **Demo:** dev（DB の源）で公開範囲を設定・承認し、社内で登録した問い合わせが portal に出ることと、未設定へ戻すと消えることを見る。

### 段階 C

- [ ] **FE-C01 — NF07 の承認の値の源（`PrivacyApprovalSource`）**
  - **依存:** 段階 A
  - **対応要求:** FE-RQ-009（要件 §6.11・6.12）
  - **Objective:** `privacy.approval.*` と DSAR の `due-days`・`package-max-age` を、property か確認済みの署名から読む源に置き換える。
  - **対象 file:** `privacy/production/PrivacyApprovalSource.java`（新規。property・DB の実装）、`PrivacyDecisionBinding.java`（:145・:202 の overload）、`retention/hold/PrivacyHoldDecisions.java`（:47-83）、`dsar/PrivacyDsarDecisions.java`（:49-88）、`retention/policy/PrivacyPolicyRegistrationService.java`（:134-150）、目録（`NF07_D01`〜`NF07_D09`、構造化された値の検査）。
  - **Implementation:** `design.md` §8.5 の 1・7。digest の正規形 v1 は変えない。
  - **Automated test:** `PrivacyDecisionBindingTest`（既存の golden vector `a4ce05f1…` が源の経由でも同じ）、`PrivacyApprovalSourceTest`、`PrivacyHoldDecisionsTest`・`PrivacyDsarDecisionsTest`（署名の差替えが次の呼出しから効く）。
  - **Demo:** なし（試験の結果）。

- [ ] **FE-C02 — 5 箇所の直接の読取りと Monitor の cache**
  - **依存:** FE-C01
  - **対応要求:** FE-RQ-009
  - **Objective:** `design.md` §8.5 の 2・5。
  - **対象 file:** `PrivacyProductionConfigurationValidator.java`（:303-315）、`PrivacyDecisionWindowMonitor.java`（:156-165・:211-223）、`PrivacyProductionConfiguration.java`（:77-83）、`src/test/java/com/ses/architecture/PrivacyFeatureFlagReadGuardTest.java`（新規）。
  - **Automated test:** `PrivacyDecisionWindowMonitorTest`（同じ日に開く・DSAR を足す・署名を差し替えると直ちに判定が変わる、再帰しない）、`PrivacyProductionConfigurationTest`、`PrivacyFeatureFlagReadGuardTest`。
  - **Demo:** なし。

- [ ] **FE-C03 — Journal の検査を flag から切り離す**
  - **依存:** FE-C01
  - **対応要求:** FE-RQ-009
  - **Objective:** `design.md` §8.5 の 3。
  - **対象 file:** `retention/hold/PrivacyJournalStartupVerifier.java`（:115・:130-192）、`retention/hold/PrivacyHoldJournal.java`（:108・:180-200）。
  - **Automated test:** `PrivacyJournalStartupVerifierTest`（prod で設定があれば flag に関係なく appender を要求、reader だけなら不成立、`enforce` が例外にしない、`reverify` の間隔の下限）、`PrivacyHoldJournalTest`（現在値を読む）。MySQL の Journal の試験（既存の shard）に reader の principal で保持・保全が開けない case。
  - **Demo:** なし。

- [ ] **FE-C04 — trust の構造の検査を flag から切り離し、副入口の変化を告げる**
  - **依存:** FE-C01
  - **対応要求:** FE-RQ-009
  - **Objective:** `design.md` §8.5 の 4 と §7.4 の副入口。
  - **対象 file:** `retention/trust/PrivacyTrustStartupValidator.java`（:55-79）、`retention/hold/PrivacyHoldReleaseService.java`（:131-137）、`retention/policy/PrivacyPolicyRegistrationService.java`（:125-150）、`featureenablement/evaluation/SubgateMonitor`（新規）。
  - **Automated test:** `PrivacyTrustStartupValidatorTest`（構造の不足で起動を止めない・副入口が閉じる）、`PrivacyHoldReleaseServiceTest`・`PrivacyPolicyRegistrationServiceTest`（構造の不足で 404）、副入口の変化で履歴と通知。
  - **Demo:** なし。

- [ ] **FE-C05 — NF07 の評価器・行為者の privacy-admin・PR #130 の guard**
  - **依存:** FE-C02、FE-C03、FE-C04
  - **対応要求:** FE-RQ-003・005・009（要件 §6.11・6.12）
  - **Objective:** `PrivacyProductionConfigurationValidator` に評価器を実装させ、`validate` の起動の停止をやめる。NF07 の行為者と署名の確認者を privacy-admin に限る。guard の privacy-admin の条件を NF07 が開いている等の時だけにする。
  - **対象 file:** `PrivacyProductionConfigurationValidator.java`（:130-194）、`FeatureEnablementActorPolicy`、`service/security/AdministratorContinuityGuard.java`（:234-254 の呼出し側）、`AdministratorConditionCheck` の javadoc、目録（NF07 の 2 件を DB 管理に）、`FeatureEnablementProductionValidator`（`PRIVACY_*` の旧い変数）。
  - **Implementation:** `design.md` §8.5 の 6・8 と §14。
  - **Automated test:** `PrivacyProductionConfigurationValidatorTest`（既存の全ての違反 code が評価器でも同じ code になる、prod でも起動を止めない）、`AdministratorContinuityGuardTest`・`AdministratorContinuityGuardH2Test`（NF07 が閉じていれば privacy-admin の減少を拒否しない、開いている・未決・再起動待ちなら拒否）、`FeatureEnablementActorPolicyTest`（NF07 で privacy-admin 以外は 403）。MySQL の `AdministratorContinuityGuardMySqlTest` に申請との並行の case。
  - **Demo:** prod の profile の試験の構成で、合成の D-01・D-09 を登録・確認し、privacy-admin 2 名で保持・保全を開き、1 名を外す変更が拒否されることを確かめる。

- [ ] **FE-C06 — NF07 の起動の停止の撤去の確認と文書**
  - **依存:** FE-C05
  - **対応要求:** FE-RQ-005・014
  - **Objective:** NF07 の 3 つの起動時検査（validator・Journal・trust）が prod で機能の条件では起動を止めないことを、prod の完全な context で確かめ、FS 4.7 の起動時の記述を直す。
  - **対象 file:** `ProductionSecurityConfigurationTest`（prod の完全な context）、FS 4.7.2・4.7.4・4.7.5・4.7.7。
  - **Automated test:** prod の完全な context が、NF07 の条件を満たさない DB でも起動する case。
  - **Demo:** なし。

### 段階 D

- [ ] **FE-D01 — `IntegrationHubProductionEnablementPolicy` の純粋化と mode の規則**
  - **依存:** 段階 A
  - **対応要求:** FE-RQ-008
  - **Objective:** `design.md` §9.5。
  - **対象 file:** `config/integrationhub/IntegrationHubProductionEnablementPolicy.java`（:38-207）、`IntegrationHubExternalApiProperties.java`（:35-125）、`IntegrationHubApprovals.java`（新規）。
  - **Automated test:** `IntegrationHubProductionEnablementPolicyTest`（既存の case が引数の経由で同じ違反、方向の引数、outbound 閉で HTTPS を許す、truststore の検査に副作用が無い）、`IntegrationHubApprovalDigestTest`（golden vector のまま）。
  - **Demo:** なし。

- [ ] **FE-D02 — 起動の前の読取り（`IntegrationHubEnablementBootResolver`）と起動の後の反映**
  - **依存:** FE-D01
  - **対応要求:** FE-RQ-008
  - **Objective:** `design.md` §9.1・§9.2。
  - **対象 file:** `featureenablement/nf05/IntegrationHubEnablementBootResolver.java`（新規、`META-INF/spring.factories`）、`featureenablement/nf05/IntegrationHubBootDecisionRecorder.java`（新規）、`config/LegalEntityReadinessStartupValidator.java`（:21-27）、目録（NF05 の 2 件を DB 管理に）、`deploy-runbook.md` §1.2（起動前の読取りの受理）。
  - **Automated test:** `IntegrationHubEnablementBootResolverTest`（状態・署名・総閘・違反の組合せで property の値、DB に届かなければ閉と `READ_FAILED`、値を log に出さない）、`IntegrationHubBootDecisionRecorderTest`、`LegalEntityReadinessStartupValidatorTest`（NF05 は自動閉鎖、copilot は例外のまま）。MySQL（`IntegrationHubEnablementRestartMySqlTest`）: 承認 → 再起動 → bean の作成と `OPEN`。
  - **Demo:** prod の profile の試験の構成で、outbound を承認 → 再起動 → 起動の log に結果、画面で `OPEN` を確かめる。

- [ ] **FE-D03 — 実行時の NF05 の判定を runtime switch に置き換える**
  - **依存:** FE-D02
  - **対応要求:** FE-RQ-008
  - **Objective:** `design.md` §9.3 の `IntegrationHubRuntimeSwitch` と静的な試験。
  - **対象 file:** `IntegrationHubRuntimeSwitch.java`（新規）、`ExternalApiDisabledFilter.java`、`ExternalApiAuthenticationFilter.java`、`ExternalApiAuditBoundary.java`、`backup/quiesce/BackupWriteGateFilter.java`、`IntegrationHubResourceChangedProducer.java`、`IntegrationHubWebhookDeliveryWorker.java`、`src/test/java/com/ses/architecture/IntegrationHubFlagReadGuardTest.java`（新規）。
  - **Automated test:** 各 class の既存の試験（kill switch の挙動のまま）、`IntegrationHubFlagReadGuardTest`、`BackupWriteGateMockMvcTest`（外部 API の静止の扱いが変わらない）。
  - **Demo:** なし。

- [ ] **FE-D04 — 閉鎖での barrier の書き直しと起動時・実行時の分離**
  - **依存:** FE-D02
  - **対応要求:** FE-RQ-008
  - **Objective:** `design.md` §9.3 の barrier の部分。
  - **対象 file:** `service/integrationhub/IntegrationHubOutboundStartupBarrier.java`（:55-150。`bootEnabled`・`recordRuntimeOff`）、`FeatureEnablementService`（NF05 outbound の閉鎖で呼ぶ）。
  - **Automated test:** `IntegrationHubOutboundStartupBarrierTest`（閉鎖で行が `v1|OFF|0|<指紋>` と `NF05_OUTBOUND_OFF`、memory の `ready` が false、起動時の経路は起動時の値で分岐）。MySQL: 閉鎖 → ON の再起動で隔離が走る、`continueRecovery` と閉鎖の並行で deadlock しない。
  - **Demo:** なし（MySQL の試験の結果）。

- [ ] **FE-D05 — NF05 の評価器（承認の時の barrier の行を含む）**
  - **依存:** FE-D03、FE-D04
  - **対応要求:** FE-RQ-008（要件 §6.8・6.9）
  - **Objective:** 承認の時と定期の評価で、policy の検査・署名・barrier の行・法人の未解決を判定する評価器を作り、旧い NF05 の変数を拒否する。
  - **対象 file:** `featureenablement/nf05/IntegrationHubEnablementConditions.java`（新規）、目録、`FeatureEnablementProductionValidator`（`INTEGRATION_HUB_*_ENABLED`・`*_DECISION_ID`・`*_APPROVAL_DIGEST`）。
  - **Automated test:** `IntegrationHubEnablementConditionsTest`（barrier の行の指紋の不一致・行なし・監査なしで不成立、digest の不一致、鍵の欠落）。
  - **Demo:** prod の profile の試験の構成で、bound-tenant-id を変えた起動の後に outbound を申請すると条件が不成立になることを確かめる。

### 段階 E

- [ ] **FE-E01 — 照合 script と雛形の総仕上げ**
  - **依存:** 段階 A〜D
  - **対応要求:** FE-RQ-007・014
  - **Objective:** 全ての旧い変数を両 phase で FAIL にし、Initial phase の機能スイッチの検査を削除する。
  - **対象 file:** `infra/ecs/offline/validate-task-definition.ps1`（:316-321・:576・:589 以降）、`test-validate-task-definition.ps1`、`infra/ecs/task-definition.prod.json`（注記）、`ProductionTaskDefinitionTemplateTest`、`FeatureEnablementLegacySettingTest`（Java の一覧と ps1 の一覧の一致）。
  - **Demo:** `validate-task-definition.ps1 -Mode Template` が PASS し、旧い変数を 1 つ足すと両 phase で FAIL することを見る。

- [ ] **FE-E02 — 段階の印の撤去と既定値の試験**
  - **依存:** 段階 A〜D
  - **対応要求:** FE-RQ-006・016
  - **Objective:** 目録の DB 管理の印と合成の源を削除し（全て DB）、`FeatureSwitchDefaultsTest` の prod の case を旧い変数の拒否に書き直す。
  - **対象 file:** `FeatureEnablementCatalog`、`FeatureEnablementStateSource` の合成の実装、`FeatureSwitchDefaultsTest`（:136-176 の prod の case）。
  - **Automated test:** `FeatureSwitchDefaultsTest`、`FeatureEnablementCatalogCoverageTest`（全て DB 管理）、既存の機能スイッチの全試験。
  - **Demo:** なし。

- [ ] **FE-E03 — FS と RB の書直し**
  - **依存:** 段階 A〜D
  - **対応要求:** FE-RQ-014
  - **Objective:** `design.md` §17 の文書の更新を行う。RB §1.7 は「go-live の前提は基盤と中核だけ。各機能の署名は system の中で開く前提」に書き直し、署名表の各行の署名を目録の種別 code と対応させる。
  - **対象 file:** `feature-switches.md`、`deploy-runbook.md`（冒頭・§1.0・§1.2・§1.7・§1.7.1・§3.4・§4.2・§4.4・§5.5 の後・§5.6）、`engineer-lifecycle-workflow/runbook.md`、`privacy-retention-dsar/production-bridge-phased-plan.md`、`integration-hub-public-api/dg-05-enablement-decision-draft.md`、`ops/security/runbooks/integration-hub-public-api.md`、`data-migration-import-center/a1a-ingress-operations.md`。
  - **Automated test:** `DeployRunbookMigrationCountTest`、runbook を読む既存の試験。
  - **Demo:** なし（文書の review）。CLAUDE.md の更新は統括へ依頼する。

- [ ] **FE-E04 — 全 gate と browser の確認（統括の Runner）**
  - **依存:** FE-E01〜FE-E03
  - **対応要求:** FE-RQ-015・016
  - **Objective:** 既定の高速 suite・`-Pmysql-tests`・`-Pperformance-tests`・backup integration・`-Pbrowser-tests`（`FeatureEnablementScreenBrowserTest`）を skip 0 で通す。staging で prod の profile の起動と、1 件の開放単位の申請・承認・閉鎖を人が確かめる（未取得）。
  - **Demo:** staging（人が行う。未取得）。
