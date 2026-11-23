# 機能の開放を system 内で行う 実装タスク

## 実行規約

- 本書は第 1 段階（spec）で書いた（第 3 版: 独立 review `rb-feature-enablement-spec-96be3af4-review.md` の P1 2・P2 7・P3 21、`85840936` の delta review の P2 3・P3 10 と統括の決定 J-08・J-10・J-20 を反映。指摘と設計の対応は `design.md` §19）。実装は delta review の後、統括の指示で段階ごとに始める。
- 利用者の決定（`requirements.md` §2 の D-1〜D-9。D-7〜D-9 は `design.md` §18 の J-01・J-09・J-16 の決定済みの値）、統括の決定（J-08・J-10）と、§18 の残りの判断点（独立 review で同意した推奨）に従う。判断点が推奨と違う値に決まったら、先に `design.md` とこの task を直してから進める。
- checkbox は、実装・自動試験・Demo が全て終わった時だけ `- [x]` にする（CLAUDE.md の spec の手順）。
- 試験は各 task の対象だけを流す（`mvn test -Dtest=…`。MySQL は `-Pmysql-tests -Dtest=…`、browser は `-Pbrowser-tests`）。全 gate の実行の時期は統括が決める。migration の file を足した後は `mvn clean test` にする。
- **新しい `@Tag("mysql")` の試験は、足す commit と同じ commit で `scripts/test-suites/mysql-shard-{1,2,3}.txt` のどれかに登録する**（`MySqlTestShardInventoryTest`。shard は着手の時に統括の指示を受ける。後回しにしない）。
- 版番号は V203（統括の指定）。着手時に `db/migration` と `db/migration-dev` の merged の状態で空いていることを確かめる。DDL が他に要れば V204 以降を統括に申請する。
- repository に書く文字は日本語（UI・log・comment・commit）。message bundle は 4 つとも翻訳する。
- **go-live は段階 E の完了の後に限る**（D-1。段階 A〜D の間は、未移行の開放単位が property の源のまま残る。`design.md` §3.1）。
- 人が行う署名・受理・実測は、どの段階でも完了にしない（登録の器を作るだけ）。

## 段階と規模の見積り

| 段階 | 内容 | 依存 | 並行 | file の数の目安（新規＋変更） | 難所 |
|---|---|---|---|---|---|
| A | 判定層・データモデル（V203）・署名の登録（組を含む）・申請と承認・継続評価・総スイッチ・画面・権限・容易な 7 件 | なし | A 内は各 task の依存に従う | 新規 約 52（main 約 34、test 約 18）、変更 約 30 | snapshot の反映と fail-closed、transaction の中の読直し、`unverifiable_since` の共有、書込み待ちの sticky、lock の順、session の種別の判定、file の 2 箇所の登録、H2 の生成列と複合 FK |
| B | NF01 二者確認・NF06・NF02 の公開範囲 | A | B01・B02・B03 は互いに並行。C・D とも並行 | 新規 約 8、変更 約 15 | 証跡種別の BINARY 比較の H2 互換、admission の源の差替え、NF06 の権限の実効の判定 |
| C | NF07（承認の値の源・組・期間の範囲・直接の読取り・Journal の 2 つの結果・trust・評価器・PR #130 の guard） | A | C02〜C04 は C01 の後に並行。B・D と並行 | 新規 約 12、変更 約 26 | 組の digest の整合、capability ごとの期間、Monitor の再帰の回避、hold gate を下げない Journal の分離、guard の意味の変更 |
| D | NF05（policy と digest の純粋化・起動前の読取り・purge の property・runtime switch・閉鎖と barrier・遷移の前提） | A | D03・D04 は D02 の後に並行。B・C と並行 | 新規 約 10、変更 約 22 | `EnvironmentPostProcessor` の実装の条件、`OPEN_TRANSITION` と再起動、barrier の起動時と実行時の分離 |
| E | 運用・文書・照合 script・既定値の試験・PITR の手順の総仕上げ | A〜D | E01〜E04 は並行 | 変更 約 18 | RB §1.7 の書直し（go-live の前提の範囲を変える）、restore-cutover と `CONTENT_HASH_TABLES` |

- 段階 B・C・D が同時に触る file: 目録（`FeatureEnablementCatalog`）の各行、`FeatureEnablementProductionValidator` の旧い変数の一覧、4 つの message bundle（どの段階も文言を足す）、`FeatureEnablementService`（B03 の `SETTING_CHANGE`、D04 の NF05 outbound の閉鎖の呼出し）、`FeatureEnablementActorPolicy`（C05 の privacy-admin）。衝突は行と method の単位で解けるが、B03 と D04 を同時に進める場合は `FeatureEnablementService` の拡張点（閉鎖の hook・申請の種類）を A05 で先に用意しておく（review P3-21）。
- 1 段階を 1 PR にする想定（A は大きいため、A1: A01〜A07・A12、A2: A08〜A11 に分けてもよい）。
- task の数: 段階 A 12・B 3・C 6・D 5・E 5 の計 31（第 1 版は 29。A12 と E04 を足した）。

## Task 一覧

### 段階 A

- [ ] **FE-A01 — V203・H2 の schema・版番号の連動**
  - **依存:** なし
  - **対応要求:** FE-RQ-002・003・004・012・013・015・017
  - **Objective:** `design.md` §2 の 9 表・6 trigger・menu の seed・data element の 2 件を、本番の MySQL 8.4 と試験の H2 の両方に同じ形で作る。
  - **対象 file:** `src/main/resources/db/migration/V203__feature_enablement_console.sql`（新規）、`src/test/resources/sql/schema-feature-enablement-h2.sql`（新規）、`src/test/resources/application-test.yml`（`schema-locations`）、`src/test/resources/sql/engineer-schema-h2.sql`、`src/test/java/com/ses/migration/LatestMigrationVersion.java`、`ProductionSchemaVersionGuardTest`、`src/test/java/com/ses/migration/FeatureEnablementSchemaContractTest.java`（新規）、`FlywayProdFreshInstallMySqlTest`、`.kiro/specs/production-launch/deploy-runbook.md`（版・件数・trigger 数・表の件数が出る全ての箇所: 冒頭、§1.2、§1.7 の DBA-1・W-1 の行、§3.4 等）、`.kiro/specs/production-launch/dba-prelaunch-draft.md`（件数）、`.kiro/specs/privacy-retention-dsar/pii-inventory.md`。
  - **Implementation:**
    1. `design.md` §2.2〜§2.8 の列・CHECK・生成列・一意索引・FK（参照動作なし。開放状態・設定から申請への複合 FK を含む）で V203 を書く。作成の順は §2.1。trigger は event・組の member・延長の記録の各 2 件。時刻の DEFAULT に `CURRENT_TIMESTAMP` を使わない。
    2. menu `feature-enablement` と `t_role_menu`（`管理者` だけ）を足す。`t_permission_group_action` には足さない。
    3. `m_pii_data_element` に 2 件（V198 と同じ列。分類等は UNKNOWN）。
    4. H2 の変種（trigger なし・同じ生成式（`STORED` なし）・複合 FK・menu と data element の seed）。`engineer-schema-h2.sql` は §2.10 の順に DROP して作る。
    5. 版と件数の連動（grep `202`、trigger と表の件数は grep `67`・`285` 等で全ての箇所を探す）。
  - **Automated test:** `MigrationScriptIntegrityTest`、`DeployRunbookMigrationCountTest`、`ProductionSchemaVersionGuardTest`、`FeatureEnablementSchemaContractTest`（V203 と H2 の一致）、`AllMappersSchemaSweepTest`。MySQL（`FeatureEnablementSchemaMySqlTest`。shard の登録を同じ commit で）: 申請者＝承認者の `APPROVED`・`REJECTED`、申請者以外の `WITHDRAWN`、未決 2 件、確認済み 2 件（署名・組）、`confirmed_by = registered_by`（署名・組）、`OPEN` で `opened_by = approved_by`、開放状態・設定の申請者・承認者が参照する申請と違う行（複合 FK）、設定の `requested_by = approved_by`、NF05 以外の `OPEN_PENDING_RESTART`、`expires_on <= signed_on`、`PURGED` で `original_name` が残る行が全て拒否され（3819 は `MySqlConstraintRejections.assertRejectedBy`）、event・member の更新・削除が trigger で拒否される。延長の記録の `confirmed_by = requested_by`・`new_expires_on <= old_expires_on`、組の member の種別・tenant が署名の行と違う行（複合 FK）も拒否される。延長の記録の更新・削除も trigger で拒否される（micro review 88630af1 の P3-M3）。`FlywayProdFreshInstallMySqlTest` の trigger 数が 6 増える。
  - **Demo:** local の MySQL 8.4 の空の DB で `mvn spring-boot:run`（dev）を起動し、`SHOW CREATE TABLE t_feature_enablement_state\G` で複合 FK を、`flyway_schema_history` の最大が 203 であることを見る。
  - **失敗/rollback:** 版番号が衝突したら上の空き番号へ振り直す（空き番号を埋めない）。

- [x] **FE-A02 — entity・mapper・署名の文書の file 種別と 2 箇所の登録**
  - **依存:** FE-A01
  - **対応要求:** FE-RQ-004・013・017
  - **Objective:** 9 表の entity と mapper（event・組の member・延長の記録は `AppendOnlyMapper`）、`FileKind.FEATURE_SIGN_OFF`、汎用の upload・取得・再 scan の拒否、`FileReferenceProvider` を用意する。
  - **対象 file:** `src/main/java/com/ses/entity/FeatureEnablement*.java`・`FeatureSignOff*.java`（新規）、`src/main/java/com/ses/mapper/FeatureEnablement*Mapper.java`・`FeatureSignOff*Mapper.java`（新規。追記専用の 2 つも `com.ses.mapper` に置き、`AppendOnlyMapper` を継承して `appendOnlyTable()` を実装する。先例 `LifecycleDualEventMapper`）、`common/enums/FileKind.java`（`FEATURE_SIGN_OFF`（pdf・10 MB）・`isDedicatedPathOnly()`）、`controller/api/FileApiController.java`（:36-44）、`service/impl/FileStorageServiceImpl.java`（汎用の `load`・`rescan` の種別の拒否、`loadDedicated`）、`service/security/impl/FileScopeValidationService.java`（:255 と `assertRescanAllowed` :504-506 の前置きの拒否）、`service/impl/FeatureSignOffFileReferenceProvider.java`（新規）。
  - **Implementation:** entity に `deletedFlag` を置かない。`selectByIdForUpdate`・状態ごとの CAS 更新（`UpdateWrapper` で列を名指し。NULL に戻す列は `set(col, null)`。CLAUDE.md の `FieldStrategy.ALWAYS` の注意）。`FileScopeValidationService` に `assertFeatureSignOffNotAccessibleThroughGenericFilePath`（先例 :519）。
  - **Automated test:** `FeatureEnablementEventMapperImmutabilityTest`・`FeatureSignOffBundleMemberMapperImmutabilityTest`・`FeatureSignOffExtensionMapperImmutabilityTest`（全ての更新・削除が例外。延長の記録は P3-M3）、`FileApiControllerTest` に `FEATURE_SIGN_OFF` の upload が 400 の case（server 生成の種別の拒否の試験が無い穴も同時に埋める）、`FileStorageServiceImplTest` に汎用の `load`・`rescan` が種別で 403・`loadDedicated` は種別の一致を要する case、`FileScopeValidationServiceTest` に署名の保存名が全 role で取得・再 scan とも 403 の case、`FeatureSignOffFileReferenceProviderTest`（`PURGED` 以外を claim）、`FileKindTest`（pdf だけ・10 MB）、`AllMappersSchemaSweepTest`。
  - **Demo:** なし（試験の結果を確かめる）。

- [x] **FE-A03 — 目録・評価器の interface・共通の評価器**
  - **依存:** FE-A01
  - **対応要求:** FE-RQ-001・004・005
  - **Objective:** `FeatureEnablementCatalog`（13 件、署名の種別と類（決定・証拠）、組、鍵、親、副入口、DB 管理の印、評価できない類の code）と、`FeatureConditionEvaluator`・`FeatureConditionCheck`・`FeatureEvaluationContext`・`FeatureConditionRegistry`、共通の評価器（総スイッチ・親・署名・鍵）、指紋を作る。
  - **対象 file:** `src/main/java/com/ses/featureenablement/catalog/*`・`condition/*`（新規）、`config/feature/Feature.java`（`NF05_INBOUND`・`NF05_OUTBOUND` を足す。`propertyName`・`environmentVariable`）、`config/feature/FeatureSwitchProperties.java`（`toggle` の NF05 の case は NF05 の property を返す）、`FeatureSwitchRouteCoverageTest`・`FeatureSwitchDefaultsTest`（一覧の追加だけ）。
  - **Implementation:** `design.md` §4。check は `Scope`（`CONTINUOUS` / `OPEN_ONLY` / `OPEN_TRANSITION`）を持つ。`OPEN_ONLY` は申請・承認の時だけ開閉を左右する（D-9）。`OPEN_TRANSITION` は申請・承認・`BOOT_PENDING` でだけ判定する（review P1-1）。違反 code は固定の文字列だけ。評価器の例外と「評価できない類」の code は registry が `UNVERIFIABLE` にする（review P2-1）。固有の評価器を要する開放単位に評価器が無ければ `evaluator.missing`。`remoteChecksAllowed=false` では外部への検査をしない。指紋の正規形は §4.5（組を含む）。
  - **Automated test:** `FeatureEnablementCatalogCoverageTest`（13 件・NF08 なし・種別の一意・類の付与・親の循環なし・`OPEN_ONLY` は NF02 の暦だけ・`OPEN_TRANSITION` は NF05 outbound の barrier の OFF の記録だけ）、`FeatureConditionRegistryTest`（`OPEN_ONLY` の不成立は申請・承認では不成立、起動時・定期では閉鎖の根拠にならない。`OPEN_TRANSITION` は定期で評価されない。評価できない類の code が `UNVERIFIABLE` になる）、`FeatureConditionCodeClassificationTest`、`SignOffConditionEvaluatorTest`（JST の境・署名日前・条件付きの種別・組の member）、`FeatureConditionFingerprintTest`（golden vector）。
  - **Demo:** なし。

- [ ] **FE-A04 — snapshot・源の切替え・`FeatureSwitches` の変更**
  - **依存:** FE-A02、FE-A03
  - **対応要求:** FE-RQ-006・015・016
  - **Objective:** `FeatureEnablementStateSource`（property・database・段階の間の合成）、snapshot の loader・holder（最初の読込みは初期化の時）・refresher（専用 thread。`verifiedAt` の更新）、overlay（書込み待ちの sticky を含む）、`FeatureSwitches.isConfiguredOn` の差替え、`logStates` の変化ごとの出力、実効値と閉じている理由の照会、metric を作る。
  - **対象 file:** `featureenablement/state/*`（新規）、`config/feature/FeatureSwitches.java`（:40・:90-97）、`config/feature/FeatureRuntimeCondition.java`（`unsatisfiedReason`）、`application.yml`・`application-prod.yml`・`application-dev.yml`（`app.feature-enablement.*`。範囲は `@Validated`）、`SchedulingDisabledDrainTest`（refresher の thread を理由つきで既知の一覧へ）。
  - **Implementation:** `design.md` §3。hot path は `AtomicReference` と日付の比較だけ。要る署名の欠落で `openUntil` が閉。`unverifiable_since` の行は閉。古さは `verifiedAt` で判定し、世代の行が無いのは世代 0。commit の後の差替えは `TransactionSynchronization.afterCommit`。`new FeatureSwitches(properties)` を残す。
  - **Automated test:** `FeatureEnablementSnapshotTest`、`DatabaseFeatureEnablementStateSourceTest`（照会 0 回を mapper の mock の呼出し回数で確かめる。JST 0:00 の境。要る署名の欠落。古さと `verifiedAt`。世代の行なし。総スイッチ。`unverifiable_since`）、`FeatureEnablementSnapshotHolderStartupTest`（`FeatureSwitches.logStates` と `SmartInitializingSingleton` が読込みの後の状態を見る）、`FeatureEnablementAfterCommitRefreshTest`（非 transaction。専用 DB で後始末）、`FeatureSwitchRuntimeConditionTest`（既存の case が通る）、`FeatureSwitchFilterTest`・`FeatureSwitchMenuAndViewTest`・`FeatureSwitchSchedulerGateTest` に DB の源の変種（閉じた機能の応答が property の源と同じ）。
  - **Demo:** dev で `APP_FEATURE_ENABLEMENT_SOURCE=database` を付けて起動し、`/api/lifecycle/cases` が 404 で、起動の log に `[機能スイッチ] 状態: NF01=OFF, …` が出ることを見る。

- [ ] **FE-A05 — 申請・承認・却下・取下げ・閉鎖・cascade（`FeatureEnablementService`）**
  - **依存:** FE-A03、FE-A04
  - **対応要求:** FE-RQ-002・003・013
  - **Objective:** `design.md` §5 の遷移を、lock の順・transaction の中の読直し・失効の先行の閉鎖・指紋・評価し直し・監査・履歴・DB の外への log・通知とともに実装する。行為者の判定（§11.2）を部品 `FeatureEnablementActorPolicy` にまとめる。後の段階の拡張点（申請の種類・閉鎖の hook）を用意する。管理者の role の付与の通知（§11.1）を足す。
  - **対象 file:** `featureenablement/service/FeatureEnablementService(Impl).java`・`FeatureEnablementActorPolicy.java`（新規）、`service/impl/SysUserServiceImpl.java`（管理者の role を得た時の通知）、`WriterIsolationPolicyTest`（`READ_COMMITTED_WRITERS` に足す）、`messages*.properties`（誤りと通知の文言）。
  - **Implementation:** 申請の時も条件の全成立を要し、同じ対象の期限切れの未決を先に `EXPIRED` にする（review P3-7）。承認は `expectedVersion` と、transaction の中で読み直した状態の指紋（review P2-3）。承認は申請を先に `APPROVED` にしてから開放状態・設定の行を書く（複合 FK。review P3-N7）。`STALE` は commit してから 409（service は結果の値を返す。review P3-8）。却下は申請者以外、取下げは申請者だけ。閉鎖は管理者 1 名で理由が必須、子を `AUTO_CLOSED(PARENT_CLOSED)`、未決を `CANCELLED`、全ての管理者へ通知。NF07 の行為者は privacy-admin（段階 C で有効にする印を置く）。監査は `recordDomainEventRequired`（method `FEAT_EN`）と INFO log `FEATURE_ENABLEMENT_EVENT`。通知は commit の後。
  - **Automated test:** `FeatureEnablementServiceH2Test`（全遷移と許さない遷移の 409、申請者による承認・却下の 403、未決 2 件の 409、申請の時の遅延の期限切れ、指紋の違いで `STALE` が commit されて 409、条件の不成立で承認 409 のまま未決、要る署名の欠落で承認 409、変更の transaction の最初の失効の閉鎖、cascade、監査と履歴と log が 1 件ずつ、監査の失敗で rollback）、`FeatureEnablementActorPolicyTest`（OIDC＋MFA は可、break-glass の属性・bootstrap の session・break-glass の username・lock 中・無効・削除済み・管理者以外は不可、prod 以外の form login は可）、`SysUserServiceImplTest` に管理者の role の付与で全ての管理者へ通知の case、`AuditLogMethodLengthGuardTest`。MySQL（`FeatureEnablementConcurrencyMySqlTest`。shard の登録を同じ commit で）: 同じ申請の並行承認で 1 件だけ成功、承認と閉鎖の並行、世代の lock で deadlock しない。
  - **Demo:** なし（試験の結果）。画面の流れの Demo は署名を登録できる FE-A06・FE-A10 の後に行う（review P3-21）。

- [ ] **FE-A06 — 署名の登録・確認・却下・取消し・差替え・延長・失効・取得・消去（`FeatureSignOffService`）**
  - **依存:** FE-A02、FE-A03、FE-A05
  - **対応要求:** FE-RQ-004・017
  - **Objective:** `design.md` §6 の 1 件の署名の流れを実装する（組は FE-A12）。
  - **対象 file:** `featureenablement/service/FeatureSignOffService(Impl).java`、`featureenablement/signoff/*`（種別ごとの検査。placeholder の規則の共通部品化）、`FeatureSignOffPurgeScheduler.java`（新規）、`PrivacyHoldPathGuard` の呼出し、`.kiro/specs/production-launch/deploy-runbook.md` §4.4（D17）。
  - **Implementation:** upload は `FileStorageService.store(byte[], …, FEATURE_SIGN_OFF)`、SHA-256 は bytes から計算、scan が `CLEAN` でなければ実体を消して拒否。確認は登録者以外（NF07 の種別は privacy-admin。段階 C で有効）、署名日が今日以前、類の上限（決定 366 日・証拠 1,095 日）。差替えは §5.1 の失効の先行の閉鎖の後に旧い行を先に `SUPERSEDED`。延長は証拠の類だけで、新しい行を作らず、延長の申請（`SIGN_OFF_EXTENSION`）の承認で同じ行の `expires_on` を更新し `t_feature_sign_off_extension` に 1 行を足す（申請を先に `APPROVED`。上限は承認の日から 1,095 日。review P2-N2）。承認の時の指紋に対象の署名の `id:version` を含め、まだ `CONFIRMED`・`EVIDENCE` であることを確かめ（違えば `STALE`）、NF07 の種別の延長は申請者・承認者とも privacy-admin に限る（J-05。spec の micro review 88630af1 の P3-M1）。消去はその行の最後の状態と失効日で判定する。取消し・差替えで開いている開放単位を同じ transaction で評価し直し（`remoteChecksAllowed=false`）、成り立たなければ `AUTO_CLOSED`。取消しは全ての管理者へ通知。取得は hash を照合し監査。DG-05 は登録の時に形式だけ、現在の配備の設定との一致・不一致を返す。消去の scheduler は `@SchedulerLock`・`void`・`PrivacyHoldPathGuard` を通し、氏名と元の file 名を NULL にする。一覧の応答に氏名を出さない。
  - **Automated test:** `FeatureSignOffServiceH2Test`（scan の拒否と実体の削除、hash の照合と不一致の 409、登録者による確認の 403、署名日が未来の確認の 400 と旧い署名の維持、差替えで開いたまま・成り立たなければ自動閉鎖、scheduler が止まっていた間に失効した旧い署名の差替えで先に自動閉鎖、取消しで自動閉鎖と通知、`active_slot`、一覧に氏名なし）、`FeatureSignOffExtensionH2Test`（延長で同じ行の期限が延びて延長の記録が 1 行、申請者本人の承認 403、決定の類は延長できない、上限、延長の後に旧い期限から 3 年を過ぎても文書を取得できる）、`FeatureSignOffKindValidationTest`（種別ごとの参照・構造化された値・類の上限・役割・DG-05 は形式だけ）、`FeatureSignOffPurgeSchedulerTest`（3 年・氏名と元の file 名・hold の対象は消さない・`void`）、`PrivacyHoldPathGuardTest` の経路の一覧。MySQL（`FeatureEnablementConcurrencyMySqlTest` に追加）: 署名の取消し・差替え × 承認。
  - **Demo:** dev（DB の源）で合成の PDF を `NF04_BUSINESS_OWNER` として登録 → 別の管理者が確認 → 専用の経路で取得でき、`/api/files/…` では 403 になることを見る。

- [x] **FE-A12 — 署名の組（`t_feature_sign_off_bundle`）の登録・確認・差替え・取消し**
  - **依存:** FE-A06
  - **対応要求:** FE-RQ-004
  - **Objective:** `design.md` §2.6.1・§6.5 の組の流れを、組の種類に依らない形で実装する（NF07 の組の種類の定義と digest の整合の検査は FE-C01 で足す）。
  - **対象 file:** `featureenablement/service/FeatureSignOffBundleService(Impl).java`（新規）、目録（組の種類の定義の器）。
  - **Implementation:** 組の登録（新しい署名と引き継ぐ確認済みの署名）、組の確認（1 回、登録者と別の確認者、全ての member の署名日が今日以前、組の種類ごとの整合の検査の hook）、旧い組と置き換わる署名の `SUPERSEDED` と新しい組・署名の `CONFIRMED` を同じ transaction で、組に属する種別の 1 件ずつの確認の拒否、組の取消し。
  - **Automated test:** `FeatureSignOffBundleH2Test`（試験用の組の種類で: 1 回の確認、引継ぎ、組の一部だけの確認の拒否、整合の hook の拒否で何も変わらない、組の取消しで依存する開放単位が自動閉鎖、member の 1 件の取消しで組は `CONFIRMED` のままその種別を要する開放単位だけが閉じる、member の種別・tenant の複合 FK）。
  - **Demo:** なし（試験の結果。NF07 の組の Demo は FE-C05）。

- [ ] **FE-A07 — 継続評価（起動時・定期・日の始まり）と自動閉鎖**
  - **依存:** FE-A05、FE-A06、FE-A12
  - **対応要求:** FE-RQ-005
  - **Objective:** `FeatureEnablementStartupEvaluator`（`SmartLifecycle`、web server の前）、`FeatureEnablementConditionScheduler`（`evaluateAll`・`evaluateAtBusinessDayStart`。`@SchedulerLock`・`void`）、`unverifiable_since` の共有と時間での 3 回、書込み待ちの sticky と再試行、申請の期限切れ、失効の接近の通知、副入口の変化の履歴、DSAR が閉じている間の未完了の請求の通知の器。
  - **対象 file:** `featureenablement/evaluation/*`（新規）、`FeatureSwitchRouteCoverageTest`（`UNGATED_SCHEDULERS` に理由つき）、`SystemConfigServiceImpl`（`SCHEMAS` に `feature-enablement.expiry-warning-days`）、`messages*.properties`（通知の文言）。
  - **Implementation:** `design.md` §7（§7.4 の擬似 code）。`OPEN_ONLY`・`OPEN_TRANSITION` は閉鎖の根拠にしない。評価できない時は `unverifiable_since` を DB に書き（書けなければ memory）、評価の間隔の 3 倍で自動閉鎖。自動閉鎖を書けなければ `AUTO_CLOSE_PENDING` を残し、条件の成否に関係なく次の周期で再試行し、毎周期 WARN `errorCode=FEATURE_AUTO_CLOSE_PENDING` を出す（再起動で失われるため。review P3-N1）。WARN `errorCode=FEATURE_AUTO_CLOSED`。
  - **Automated test:** `FeatureEnablementConditionSchedulerH2Test`（不成立で自動閉鎖・他の開放単位は不変・通知、評価できない類の code（`journal.unreachable` 等）で `unverifiable_since` だけ・評価の間隔の 3 倍で自動閉鎖・回復で消える、自動閉鎖の書込みの失敗の後に条件が戻っても閉じたまま・次の周期で書かれる、失効日の 0:00 の後の最初の評価、期限切れの申請、`OPEN_ONLY` の check が外れても閉じず監査 `FEATURE_OPEN_CONDITION_EXPIRING`・`FEATURE_OPEN_CONDITION_LOST` と通知だけ、`OPEN_TRANSITION` を評価しない）、`FeatureEnablementStartupEvaluatorTest`（起動時の自動閉鎖・総スイッチ・書けない時の書込み待ち）、`ScheduledMethodsHaveSchedulerLockTest`、`SchedulerLockProxyInvocationTest`。MySQL（`FeatureEnablementConcurrencyMySqlTest` に追加）: 定期の自動閉鎖 × 承認、2 つの instance 相当の snapshot が `unverifiable_since` で同じに閉じる。
  - **Demo:** dev（DB の源）で開いた NF04 の署名の失効日を翌日にした合成の署名へ差し替え、clock を進めた試験で自動閉鎖と通知を確かめる（試験の結果を見る）。

- [ ] **FE-A08 — 総スイッチ・旧い変数と運用値の上書きの拒否・prod の源の固定（段階 A の範囲）**
  - **依存:** FE-A04、FE-A07
  - **対応要求:** FE-RQ-007・016
  - **Objective:** `FEATURE_ENABLEMENT_ALLOWED` と `FeatureEnablementProductionValidator`（prod の判定は `Environment.acceptsProfiles`）を作り、段階 A で DB 管理にした 7 件の旧い変数と、`app.feature-enablement.*` の運用値の上書きを拒否する。CLAUDE.md の規則どおり、RB §1.0 の行と雛形の名前を同じ change で足す。
  - **対象 file:** `featureenablement/config/FeatureEnablementProperties.java`・`FeatureEnablementProductionValidator.java`（新規）、`application.yml`・`application-prod.yml`、`infra/ecs/task-definition.prod.json`（`FEATURE_ENABLEMENT_ALLOWED=true`）、`infra/ecs/offline/validate-task-definition.ps1`（`mustBeVisible` に足す・原文の値の検査・運用値の上書きの FAIL）、`infra/ecs/offline/test-validate-task-definition.ps1`、`ProductionTaskDefinitionTemplateTest`、`deploy-runbook.md` §1.0。
  - **Implementation:** `design.md` §10。総スイッチは原文の文字列が `true`/`false` のどちらかだけ。旧い変数の名前の正規化は ps1 の `NameKey` と同じで、環境変数・system property・`SPRING_APPLICATION_JSON` を見る。message は変数名だけ。総スイッチが閉なら起動時に `AUTO_CLOSED(MASTER_SWITCH_CLOSED)`・未決を `CANCELLED`・ERROR・gauge。 A1 で足した運用値の `app.feature-enablement.evaluation-initial-delay`・`sign-off-purge-cron`（`FeatureEnablementProperties` の範囲・式の検査は A1 で済み。実装 review rb-fe-a1 P3-5）も prod の上書きの拒否の対象に含める。
  - **Automated test:** `FeatureEnablementProductionValidatorTest`（`@ActiveProfiles("prod")` 相当で、旧い変数・system property・`SPRING_APPLICATION_JSON` の各綴りで起動が止まる、`yes`・`1` の総スイッチで止まる、`APP_FEATURE_ENABLEMENT_MAX_STALENESS` 等の上書きで止まる、値が message に出ない、源が `database` でなければ止まる）、`FeatureEnablementPropertiesTest`（範囲と相互の関係）、`FeatureEnablementMasterSwitchH2Test`（閉で全て閉・申請 409・署名の登録は可・開に戻しても閉のまま）、ps1 の試験（雛形 PASS、`FEATURE_ENABLEMENT_ALLOWED` を secrets に置くと FAIL、`yes` で FAIL）。
  - **Demo:** dev（DB の源）で `FEATURE_ENABLEMENT_ALLOWED=false` で起動し、開いていた NF04 が閉じ、画面の申請が 409 になることを見る。
    - 手順（段階 A2 で補った。統括が dev の MySQL と browser で一括して行う）: (1) `APP_FEATURE_ENABLEMENT_SOURCE=database` で `mvn spring-boot:run` し、FE-A10 の Demo の手順で NF04 を開く。(2) 止めて `FEATURE_ENABLEMENT_ALLOWED=false` を足して起動し、起動の log に ERROR `errorCode=FEATURE_ENABLEMENT_MASTER_CLOSED` と `[機能の開放] 起動時の評価: … NF04=AUTO_CLOSED(MASTER_SWITCH_CLOSED)` が出ること、`/actuator/prometheus` の `ses_feature_enablement_master_allowed` が 0 であること、画面の上部に総スイッチの警告が出て開放単位の「申請」の button が出ず、API で申請すると 409 `masterClosed` になること、署名の登録・確認はできることを見る。(3) `FEATURE_ENABLEMENT_ALLOWED=true` に戻して起動し、NF04 が `AUTO_CLOSED` のままであることを見る。(4) `FEATURE_ENABLEMENT_ALLOWED=yes` では起動が止まることを見る。照合は `pwsh infra/ecs/offline/test-validate-task-definition.ps1 -Filter enablement` で確かめる（A2 の worker が両 host で流した）。

- [ ] **FE-A09 — API・権限の配線**
  - **依存:** FE-A05、FE-A06、FE-A12
  - **対応要求:** FE-RQ-011・012
  - **Objective:** `FeatureEnablementApiController`（`design.md` §15.1。組の API を含む）と page controller、権限の配線を足す。
  - **対象 file:** `controller/api/FeatureEnablementApiController.java`・`controller/page/FeatureEnablementPageController.java`（新規）、`service/security/ActionPermissionResolver.java`（`RESOURCE_NAMES` :13 と `pageResource` :450）、`service/security/impl/AuthorizationServiceImpl.java`（`isAdminOnlyAction` :204-208 に `feature-enablement.`）、`config/SecurityConfig.java`（両 path を `hasRole("管理者")`）、`service/security/impl/BreakGlassServiceImpl.java`（`validateAndSerializeActions` :316-331 で拒否）。`FeatureSwitchRouteCoverageTest` の `UNGATED_ROUTE_PREFIXES` には登録しない（NF の入口として検出されず、対象外の表は NF 配下の入口のための表のため。実装 review rb-fe-a2 P3-4。当初の「理由つきで登録」を改めた）。
  - **Implementation:** 一覧・履歴は `PageUtils.safePage`。`/units` は実効値と閉じている理由を返す。`/units/{key}/evaluate` は開放単位ごとに 1 分に 1 回で、状態を遷移させない（`Purpose.DISPLAY`）。応答は `ApiResult`。段階 A2 の実装の細部（読取りの `FeatureEnablementQueryService`、最後の評価の memory、申請の種類、`UNGATED_ROUTE_PREFIXES` に登録しない理由）は `design.md` §15.1 の「実装で決めた細部」。
  - **Automated test:** `FeatureEnablementApiControllerTest`（管理者 200、営業・HR・マネージャー（group あり・なし）403、未認証 401/302、CSRF、氏名を一覧に返さない、実効値の項目、evaluate で状態が変わらない）、`ActionPermissionMatrixTest`（allow-list を足さずに通る）、`BreakGlassServiceImplTest` に `feature-enablement.view` の拒否、`MenuPermissionFilter` の試験に admin の素通しと resolver の null にならないこと。
  - **Demo:** dev で管理者と営業で `/api/feature-enablement/units` を叩き、200 と 403 を見る。
    - 手順（段階 A2 で補った）: dev で `admin` / `admin123` で login し、`/user/list` で営業の利用者を作る。管理者の browser で `/api/feature-enablement/units` を開くと 200 の JSON（`effectiveOpen`・`closedReasons` を含み、署名者の氏名を含まない）、営業で login した browser では 403 で、sidebar に「機能の開放」が出ないことを見る。break-glass の incident の申請に `feature-enablement.view` を入れると 400 になることは `BreakGlassServiceImplTest` の結果で見る。

- [ ] **FE-A10 — 画面「機能の開放」と 4 言語の文言**
  - **依存:** FE-A09
  - **対応要求:** FE-RQ-011
  - **Objective:** `design.md` §15.2 の画面を作る（実効値の badge、組の登録の modal、延長を含む）。
  - **対象 file:** `templates/feature-enablement/list.html`、`static/js/modules/feature-enablement.js`、`templates/layout/sidebar.html`（menu `feature-enablement`）、`messages.properties`・`messages_en.properties`・`messages_ko.properties`・`messages_zh_CN.properties`。
  - **Implementation:** 既存の規約（`SES.csrf.header()`、`res.code === 200`、`Toast`、`Swal.fire`、`#featureEnablementModal`、`/lib/...` だけ）。履歴の絞込みは `.card > .card-body > form#searchForm`。自分の申請に承認の button を出さない。署名日が未来の署名に確認の button を出さない。digest は先頭 16 文字だけ。
  - **Automated test:** `MessageBundleConsistencyTest`（`testTemplateMessageKeysExist` を含む）、`StaticAssetLocalityTest`、`FeatureEnablementTemplateRenderingTest`（Thymeleaf で描画し、管理者以外に menu が出ない）。browser（`FeatureEnablementScreenBrowserTest`、統括が実行）は段階 E でまとめて流す。
  - **Demo:** dev（DB の源）で画面を開き、署名の登録 → 確認 → 申請 → 別の管理者で承認 → 閉鎖を画面だけで行い（NF04 の 5 種の署名を合成の PDF で登録する）、DB の状態と実効値の badge が分かれて出ること、phone 幅で履歴の絞込みが畳まれることを見る。
    - 手順（段階 A2 で補った）: (1) `APP_FEATURE_ENABLEMENT_SOURCE=database` で起動し、`/user/list` で2人目の管理者を作る（dev は local の form login で行為者になれる。prod は OIDC＋MFA だけ）。(2) 管理者 A が「署名・受理」tab で `NF04_BUSINESS_OWNER`・`NF04_SRE`・`NF04_PRIVACY`・`NF04_SECURITY`・`NF04_DEVICE_ACCEPTANCE` を、署名日を今日以前・失効日を類の上限以内にして、合成の PDF（`%PDF-1.7` で始まり `%%EOF` で終わる file）で登録する（一覧に氏名が出ず、詳細に出ることを見る）。署名日を明日にした署名には確認の button が出ないことも見る。(3) 管理者 B が5件を「確認」する（A の画面では確認の button が出ない）。(4) A が「開放単位」tab の NF04 の「申請」を押す（B の「申請」tab にだけ承認の button が出る）。(5) B が承認し、NF04 の DB の状態が「開放中」、実効値が「開」になり、`/manifest.webmanifest` が 404 でなくなることを見る。(6) 「再評価」を2回続けて押すと2回目が 429 の toast になり、状態は変わらないことを見る。(7) A が「閉鎖」を理由の区分を選んで行い、全ての管理者へ通知が届き、実効値が「閉」になることを見る。(8) 「履歴」tab で絞り込み、browser を phone 幅（375px）にして絞込みが畳まれることを見る。(9) `NF04_SRE` の文書を「文書」で取得し、`/api/files/<保存名>` では 403 になることを見る（FE-A06 の Demo を兼ねる）。

- [ ] **FE-A11 — 容易な 7 件を DB 管理にする（NF01・NF02・NF02 顧客ポータル・NF03・NF03 資格番号・NF04・NF10）**
  - **依存:** FE-A07、FE-A08、FE-A10
  - **対応要求:** FE-RQ-001・006（要件 §6.1・6.3〜6.7・6.13）
  - **Objective:** 7 件の目録の行（署名の種別と類・親）に DB 管理の印を付け、NF02 の暦（開放の時だけの条件。D-9）と NF10 の scheduler の評価器を足す。
  - **対象 file:** `featureenablement/catalog/FeatureEnablementCatalog.java`、`featureenablement/condition/WorkCalendarConditionEvaluator.java`・`SchedulingConditionEvaluator.java`（新規）、`mapper/WorkCalendarMapper.java`（件数の method）、`FeatureEnablementProductionValidator`（7 件の旧い変数）、`application-prod.yml`（7 件の placeholder の削除）。
  - **Implementation:** 暦は `ServiceSlaCalculator.findCalendarForScope`（:366-400）の 4 段目と同じ条件（本日を含む当年度の全社共通の暦）で 1 回照会し、check を `Scope.OPEN_ONLY` にする: 申請・承認の時だけ必須、開いた後は NF02 を自動閉鎖しない。暦の有効期間の終わりの 30 日前からと切れた後は、日の始まりの評価で毎日 1 回、監査・管理者への通知・WARN（`design.md` §7.4）。暦が無い間の SLA の計算（WARN `missing_calendar`、土日だけを休日）は変えない。scheduler 無効は `ADVISORY`。各機能の code は変えない。
  - **Automated test:** `WorkCalendarConditionEvaluatorTest`（暦なし・期間外・要員／組織／法人つきの暦だけ・無効の暦で不成立、`Scope.OPEN_ONLY`）、`FeatureEnablementNf02CalendarH2Test`（暦なしで NF02 の申請・承認が 409、開いた後に暦が切れても NF02 と子は `OPEN` のまま、30 日前からの毎日の監査と通知、`ServiceSlaCalculator` の既存の `missing_calendar` の試験が変わらず通る）、`SchedulingConditionEvaluatorTest`、`FeatureEnablementEasyUnitsH2Test`（7 件を DB の源で開閉し、filter・menu・scheduler・子の gate が property の源と同じ応答になる）、`FeatureSwitchChildTemplateRenderingTest`（DB の源の変種）。
  - **Demo:** dev（DB の源）で、暦が無い状態では NF02 の申請が条件の不成立で拒否されることを見る。暦の seed を入れて NF02 を開き、子の顧客ポータルを開き、`/portal/customer` に問い合わせの tab が出ること、NF02 を閉じると子も `AUTO_CLOSED` になることを見る。暦の有効期間の終わりを 30 日以内にした seed で日の始まりの評価を手で呼び、NF02 が開いたままで通知と監査が出ることを見る。
    - 手順（段階 A2 で補った）: 暦の seed は dev の DB に `INSERT INTO m_work_calendar (name, valid_from, valid_to, status, version, deleted_flag) VALUES ('全社共通', '<当年度の初日>', '<当年度の末日>', '有効', 0, 0)`（要員・組織・法人の列は NULL）で入れる。NF02 と顧客ポータルの署名は FE-A10 の Demo と同じく画面で登録・確認する。日の始まりの評価を手で呼ぶ入口は無い（定期の scheduler `evaluateAtBusinessDayStart` は JST 0:00:05 に動く）ため、`app.scheduling.enabled=true` の dev で暦の `valid_to` を今日から 30 日以内にして JST 0:00 の後の log の WARN `errorCode=FEATURE_OPEN_CONDITION_EXPIRING` と監査を見るか、`FeatureEnablementNf02CalendarH2Test` の結果で代える（統括の判断）。起動時と定期の評価で NF02 が閉じないことは、暦を消した後に `[機能の開放] 起動時の評価` の log が `NF02=OPEN` であることで見る。

### 段階 B

- [ ] **FE-B01 — NF01 二者確認**
  - **依存:** 段階 A
  - **対応要求:** FE-RQ-001・005（要件 §6.2）
  - **Objective:** `LifecycleDualConfirmationReleaseGate` の起動の停止をやめ、同じ判定を評価器から使う。証跡種別の検査と閉鎖の警告を足す。
  - **対象 file:** `service/lifecycle/dual/LifecycleDualConfirmationReleaseGate.java`（:68-80 の例外を削除、`acceptable` は残す）、`service/lifecycle/dual/LifecycleDualConfirmationEnablementConditions.java`（新規）、`mapper/LifecycleTemplateTaskMapper`・`LifecycleTaskMapper`（件数の method）、目録、`FeatureEnablementProductionValidator`（`FEATURE_NF01_DUAL_CONFIRMATION_*`）、`FeatureEnablementQueryService`（有効な DUAL template の件数）、`application-prod.yml`（placeholder の削除）、`messages*.properties`。
  - **Implementation:** `design.md` §8.2。BINARY 比較の H2 での書き方を着手時に確かめる。
    - **過渡期の拒否を外し正式の扱いに替える**（実装 review rb-fe-a2 P2-1）: 段階 A2 は、二者確認を DB 管理にするまでの暫定として、prod の `FEATURE_NF01_DUAL_CONFIRMATION_ENABLED=true`（`app.features.nf01-dual-confirmation.enabled` の全ての綴り）を拒否している（`featureenablement/config/FeatureEnablementTransitionalRejections` の NF01 の規則、`validate-task-definition.ps1` の暫定の block の NF01 の行と self-test の case、雛形の `FEATURE_ENABLEMENT` の注記、RB §1.0「段階の間の暫定の拒否」の (1) と §1.7 の注記、`FeatureEnablementProductionValidatorTest`・`ProductionTaskDefinitionTemplateTest` の暫定の試験）。本 task で二者確認に DB 管理の印を付けて旧い変数として正式に拒否する時に、これらを全て外す（両方が残ると同じ変数を2回拒否する）。

  - **Automated test:** `LifecycleDualConfirmationEnablementConditionsTest`（`acceptable` と同じ入力で同じ成否、許可値の外の行で不成立）、`LifecycleDualConfirmationReleaseGateTest`（prod でも起動を止めない）、`LifecycleTemplateDualPolicyTest`・`LifecycleTemplateDualSwitchUnavailableTest`（既存のまま通る）、MySQL で照合順序の揺れ（全角英字・NBSP）を検出する case（shard の登録を同じ commit で）。
  - **Demo:** dev（DB の源）で合成の Privacy の署名を登録・確認し、NF01 → 二者確認の順に開き、二者確認の提出が通ること、閉鎖の dialog に DUAL template の件数が出ることを見る。
    - 手順（段階 B で補った。統括が dev の MySQL と browser で一括して行う）: (1) `APP_FEATURE_ENABLEMENT_SOURCE=database` で起動し、FE-A10 の Demo の手順で NF01 を開く。(2) 管理者 A が「署名・受理」tab で `NF01_DUAL_PRIVACY`（参照は英字と数字を含む合成の値。例 `PRIV-DEMO-2026-01`）・`NF01_DUAL_MANUAL_CHECK`・`NF01_DUAL_BREAK_GLASS_DECISION`・`NF01_DUAL_RUNNER_EVIDENCE`・`NF01_DUAL_REVIEW_PASS` を合成の PDF で登録し、管理者 B が確認する（参照を `TODO-1` にした `NF01_DUAL_PRIVACY` は登録の時に 400 になる）。(3) 「開放単位」tab の NF01_DUAL_CONFIRMATION の「再評価」で `dualConfirmation.privacySignOff.valid` と `dualConfirmation.evidenceTypes.allowed` が ✓ であることを見て、A が申請・B が承認する。(4) `/lifecycle/templates` で二者確認を含むテンプレートを作って有効にし、案件を起票して DUAL タスクの提出が通ることを見る。(5) A が NF01_DUAL_CONFIRMATION の「閉鎖」を押すと、dialog に「二者確認を含む有効なテンプレートが 1 件あります。…」が出ることを見る。(6) 起動の log に `[二者確認] Privacy署名の参照を確認して公開します` が出ない（起動時検査を撤去した）ことを見る。揺れた証跡種別の検出は `LifecycleDualConfirmationEnablementConditionsTest`（H2）と `LifecycleDualConfirmationEvidenceTypeMySqlTest`（MySQL shard 2）の結果で見る（DB を手作業で書き換えない。FS 4.9.5）。

- [ ] **FE-B02 — NF06**
  - **依存:** 段階 A
  - **対応要求:** FE-RQ-001・005（要件 §6.10）
  - **Objective:** admission の承認の値の源を差し替え、2 つの起動時検査の停止をやめて評価器にする。`import.metadata.manage` の実効の許可と単一法人の検査を足す。
  - **対象 file:** `importcenter/metadata/ImportAdmissionApprovalSource.java`（新規。property と DB の実装）、`ApprovedSourceImportAdmissionPort.java`（:121）、`ImportAdmissionStartupValidator.java`（:52-77）、`ImportRowReferenceKeyStartupValidator.java`（:38-55）、`importcenter/metadata/ImportCenterEnablementConditions.java`（新規）、`AuthorizationServiceImpl`（group ごとの実効の判定を評価器から呼べる形にする）、`LegalEntityContextService`（件数の method）、目録、`FeatureEnablementProductionValidator`（`IMPORT_ADMISSION_*`）、`application-prod.yml`（placeholder の削除）。
  - **Implementation:** `design.md` §8.3。`NF06_ADMISSION` の構造化された値を `ImportAdmissionApprovalProperties` に写して `ImportAdmissionApproval.evaluate`。権限は拒否行の有無ではなく、group ごとの許可・拒否の評価の結果で判定する（review P3-19）。
  - **Automated test:** `ImportCenterEnablementConditionsTest`（`ImportAdmissionPortTest` の golden vector `c5dc5f03…` の値の署名で成立、改竄・期間外・鍵の状態・法人 0 件と 2 件で不成立、`import.*` の許可を持つ別の group で不成立、拒否行の無い新しい group でも許可が無ければ成立）、`ImportAdmissionStartupValidatorTest`・`ImportSourceRowReferenceTest`（prod でも起動を止めない）、`ImportCenterPageControllerTest`・`ImportCenterPageFeatureOffTest`（DB の源の変種）。
  - **Demo:** prod の profile の試験の構成で、合成の admission の署名を登録・確認し NF06 を開き、upload の前の検査が通ることを確かめる（新規の dev の DB では単一法人の条件（法人 ID を持つ組織が無い）で申請できないことも見る。段階 B で「評価器が prod 専用」から直した: design §8.3 のとおり評価器は profile に依らない）。
    - 手順（段階 B で補った。統括が一括して行う）: (1) dev（`APP_FEATURE_ENABLEMENT_SOURCE=database`）で、法人 ID を持つ組織が無いまま「開放単位」の NF06 を「再評価」し、`importCenter.legalEntity.notSingle`（`count=0`）で申請が 409 になることを見る。(2) 組織に法人 ID を1つ登録し、`NF06_ADMISSION` を合成の値で登録する。digest は署名日・失効日を含むため、Demo の日を署名日にして計算し直す（復審 rb-fe-b-656d71b8 の P3-3。golden vector の署名日 `2026-12-01` のままでは、その日まで確認できない）: `printf 'nf06-import-admission-approval-v1\ndecision-id=DO-NF06-2026-001\ntenant-id=default\nsigned-on=<署名日>\nexpires-on=<失効日>\nentity=CUSTOMER\nschema-version=ses-import-v1\nsource-codes=crm,crm-2,crm2,crm_2\n' | sha256sum`（失効日は署名日から 366 日以内。FS 4.8.2 #1 と同じ正規形で、golden vector の日付にすると `c5dc5f03…3550` になることを先に確かめる）。例: 署名日 `2026-11-23`・失効日 `2027-11-22` なら `adc02a85e9dfe55bbda91b57a2676f4f22315fc213a220aa830b9617113a680a`。構造化された値は決定 ID `DO-NF06-2026-001`・tenant `default`・source code `CRM_2,crm-2,Crm,crm2,crm`・計算した digest にし、`NF06_PRIVACY_RAW_CSV`・`NF06_SRE_LB`・`NF06_DBA_ACCOUNT`・`NF06_STAGING_CHECK` を合成の PDF で登録・別の管理者が確認する（digest を1文字変えた admission は登録の時に 400 `admissionInvalid`）。(3) 再評価で `importCenter.*` の4件が ✓ になることを見て申請・承認し、`/import-center` が開き、承認済みの source code の upload の前の検査が通る（未承認の source code は 403）ことを見る。(4) prod の profile の試験の構成は `ImportCenterEnablementConditionsTest`・`ImportAdmissionPortTest`（DB の源）・`ImportCenterPageDatabaseSourceTest` の結果で見る（admission の失効日の当日から NF06 全体が 404）。

- [ ] **FE-B03 — NF02 の公開範囲（`visible-since`）の変更**
  - **依存:** 段階 A（FE-A11）
  - **対応要求:** FE-RQ-010
  - **Objective:** 設定の変更の申請と承認、`PortalServiceRequestVisibilityPolicy` の毎回の読取りを実装する。
  - **対象 file:** `service/servicedesk/PortalServiceRequestVisibilityPolicy.java`（:45-52）、`FeatureEnablementService`（`SETTING_CHANGE`。A05 の拡張点を使う）、目録（`NF02_PORTAL_VISIBLE_SINCE_DECISION`・`NF02_PORTAL_INVENTORY`）、画面の「設定」tab、`FeatureEnablementProductionValidator`（`FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`）、`application-prod.yml`（placeholder の削除）。
  - **Implementation:** `design.md` §5.6・§8.4。設定の行は申請者・承認者を複合 FK で申請と一致させる。DB の値が壊れていれば portal 起票だけ（fail-closed）。復審 rb-fe-b-656d71b8 の P3-1（統括の決定。design §18 J-21・requirements FE-RQ-010）で、NF02 顧客ポータルに継続の条件「今の公開範囲の設定が確認済みの決定の範囲の内」（`PortalVisibleSinceConditionEvaluator`）を足した: より狭い決定への差替え・決定の失効で顧客ポータルだけを自動閉鎖して警告し、開き直しも範囲の内を要する。
    - **過渡期の拒否を外し正式の扱いに替える**（実装 review rb-fe-a2 P2-2）: 段階 A2 は、公開範囲を二人の承認の設定へ移すまでの暫定として、prod の空でない `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`（`app.features.nf02-customer-portal.visible-since` の全ての綴り）を拒否している（`FeatureEnablementTransitionalRejections` の NF02 の規則、`validate-task-definition.ps1` の暫定の block の NF02 の行（secret の綴りを含む）と self-test の case、雛形の注記、RB §1.0「段階の間の暫定の拒否」の (2) と §1.7 の注記、暫定の試験）。本 task で公開範囲を DB の設定にし旧い変数として正式に拒否する時に、これらを全て外す（FE-B01 と合わせて全ての規則を外せば `FeatureEnablementTransitionalRejections` と validator の呼出し1行を削除する）。
  - **Automated test:** `PortalServiceRequestVisibilityPolicyTest`（property の源の既存の case と、DB の源で値の変更が次の判定から効く case）、`PortalServiceRequestVisibilityIntegrationTest`（DB の源の変種）、`FeatureEnablementSettingChangeH2Test`（申請者本人の承認 403、署名の値と違う値 409、offset なし 400、未設定へ戻す。P3-1: より狭い決定へ差し替えると顧客ポータルが自動閉鎖し、設定を変える承認の前は開き直しが 409）、`PortalVisibleSinceConditionEvaluatorTest`（範囲の内・外の各 case）。
  - **Demo:** dev（DB の源）で公開範囲を設定・承認し、社内で登録した問い合わせが portal に出ることと、未設定へ戻すと消えることを見る。
    - 手順（段階 B で補った。統括が dev の MySQL と browser で一括して行う）: (1) `APP_FEATURE_ENABLEMENT_SOURCE=database` で起動し、FE-A11 の Demo の手順で NF02 と顧客ポータルを開き、社内で問い合わせを1件登録して portal の一覧に出ないことを見る（設定の行が無い＝portal 起票だけ）。(2) 管理者 A が `NF02_PORTAL_VISIBLE_SINCE_DECISION`（構造化された値 `visibleSince` に今より前の日時。例 `2026-11-01T00:00:00+09:00`）と `NF02_PORTAL_INVENTORY` を合成の PDF で登録し、管理者 B が確認する。(3) A が「設定」tab の「変更を申請」で同じ値を申請する（違う値は 409 `conditionsUnsatisfied`（`decision.valueMismatch`）、`2026-11-01T00:00:00` のような offset の無い値は 400 の toast）。(4) B の「申請」tab にだけ承認の button が出ることを見て承認し、再起動せずに portal の一覧へ社内で登録した問い合わせが出ることを見る。(5) 決定の署名を `visibleSince` が空の新しい決定に差し替えて確認し、A が値を空にして申請、B が承認すると、portal の一覧から社内で登録した問い合わせが消えることを見る。(6) `FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE` を付けた prod の起動が止まることは `FeatureEnablementProductionValidatorTest` の結果で見る。

### 段階 C

- [x] **FE-C01 — NF07 の承認の値の源（`PrivacyApprovalSource`）・組・期間の範囲**
  - **依存:** 段階 A
  - **対応要求:** FE-RQ-004・009（要件 §6.11・6.12）
  - **Objective:** `privacy.approval.*` と DSAR の `due-days`・`package-max-age` を、property か確認済みの組・署名から読む源に置き換える。組の種類 `NF07_DECISION_BINDING` と digest の整合の検査を足す。期間による閉鎖を開放単位が要る決定だけにする。
  - **対象 file:** `privacy/production/PrivacyApprovalSource.java`（新規。property・DB の実装）、`PrivacyDecisionBinding.java`（:145・:202 の overload と、期間を判定する capability の引数）、`retention/hold/PrivacyHoldDecisions.java`（:47-83）、`dsar/PrivacyDsarDecisions.java`（:49-88）、`retention/policy/PrivacyPolicyRegistrationService.java`（:134-150）、`redaction/ThirdPartyRedactor.java`（:48-53）、目録（`NF07_D01`〜`NF07_D09`、組、構造化された値の検査）、`src/test/java/com/ses/architecture/ApprovalValueReadGuardTest.java`（新規）。
  - **Implementation:** `design.md` §2.6.1・§8.5 の 1・7。digest の正規形 v1 は変えない。構造（digest を含む）は承認 capability の決定の全てで、署名日前・失効は評価する開放単位の capability の決定だけで判定する。
  - **Automated test:** `PrivacyDecisionBindingTest`（既存の golden vector `a4ce05f1…` が源の経由でも同じ、D-06 の失効で retention の評価は成立・dsar は不成立）、`PrivacyApprovalSourceTest`、`PrivacyHoldDecisionsTest`・`PrivacyDsarDecisionsTest`・`ThirdPartyRedactorTest`（組の差替えが次の呼出しから効く、決定の束縛の入力は `CONFIRMED` の組の全 member で member の状態に依らない）、`FeatureSignOffBundleH2Test` に NF07 の組（D-01 の更新で D-06 を引き継ぐ、D-09 の digest の不一致で 400）、`ApprovalValueReadGuardTest`。
  - **実装の記録（段階 C）:** golden vector・期間の範囲・member の状態・差替え（hold・DSAR・redaction の3つの読む側）・組の整合の検査は `PrivacyApprovalSourceTest` にまとめた（`PrivacyHoldDecisionsTest`・`PrivacyDsarDecisionsTest` は存在しないため作らなかった）。`ApprovalValueReadGuardTest` は `PrivacyApprovalProperties` と DSAR の期限規則・保存期間の getter を固定し、NF06・NF05 の行は段階 B・D が同じ表に足す。review rb-fe-c P3-5 で provider からの連鎖・`var`・決定の getter の名前・`@Value`・`getProperty` の読取りも検出するようにした（`PrivacyFeatureFlagReadGuardTest` も変数に受けた読取りと設定名の読取りを検出する）。
  - **Demo:** なし（試験の結果）。

- [x] **FE-C02 — 5 箇所の直接の読取りと Monitor の cache**
  - **依存:** FE-C01
  - **対応要求:** FE-RQ-009
  - **Objective:** `design.md` §8.5 の 2・5（Monitor の状態を capability ごとに持つ）。
  - **対象 file:** `PrivacyProductionConfigurationValidator.java`（:303-315）、`PrivacyDecisionWindowMonitor.java`（:156-165・:211-223）、`PrivacyDsarDecisionWindowCondition.java`、`PrivacyProductionConfiguration.java`（:77-83）、`src/test/java/com/ses/architecture/PrivacyFeatureFlagReadGuardTest.java`（新規）。
  - **Automated test:** `PrivacyDecisionWindowMonitorTest`（同じ日に開く・DSAR を足す・署名を差し替えると直ちに判定が変わる、D-06 の失効で `NF07_RETENTION` の条件は成立のまま、再帰しない）、`PrivacyProductionConfigurationTest`、`PrivacyFeatureFlagReadGuardTest`。
  - **実装の記録（段階 C）:** 5箇所のうち trust・Journal は FE-C04・C03 で flag を読まないようにした（`PrivacyFeatureFlagReadGuardTest` が全てを固定）。`PrivacyProductionConfiguration` の supplier は `FeatureSwitches` を `ObjectProvider` で読む（`PrivacyPolicyEnforcementConfiguration` も同じ）。
  - **Demo:** なし。

- [x] **FE-C03 — Journal の結果を 2 つに分ける（hold の効力と開放の条件）**
  - **依存:** FE-C01
  - **対応要求:** FE-RQ-009
  - **Objective:** `design.md` §8.5 の 3。hold gate は開閉・principal の種類・再検査の失敗で UNAVAILABLE にならない。
  - **対象 file:** `retention/hold/PrivacyJournalStartupVerifier.java`（:115・:130-192）、`retention/hold/PrivacyHoldJournal.java`（:108・:116-127・:180-200）、`retention/hold/PrivacyHoldService.java`（hold の開始は開放の条件の結果で判定）、`journal-witness-grant-design.md`（注記）。
  - **Automated test:** `PrivacyJournalProtectionResultTest`（reader の principal で NF07 が閉の時に hold gate が AVAILABLE、appender の再検査の失敗で hold gate が下がらない、開放の条件は appender を要する）、`PrivacyJournalStartupVerifierTest`（`enforce` が例外にしない、`reverify` の間隔の下限、`journal.unreachable` は評価できない類）、`PrivacyHoldJournalTest`。MySQL の Journal の試験（既存の shard）に reader の principal で保持・保全が開けず、文書の処分・purge の hold の確認は動く case。
  - **実装の記録（段階 C）:** `PrivacyHoldJournalTest` は存在しないため、`appendAvailability` は `PrivacyJournalProtectionResultTest` で確かめた。MySQL は `PrivacyJournalStartupVerifierMySqlTest`（shard 既存）に1回の検査の2つの結果（reader は hold の効力で適合・開放の条件で権限不足、appender は両方適合）を足した（gate の動作の MySQL の確認は `PrivacyHoldServiceMySqlTest` 等の既存の試験に任せ、新しい case は足していない。理由: gate・purge の経路は hold の効力の結果だけを読み、その結果が reader で適合することは同じ MySQL の試験が確かめている）。review rb-fe-c の修正: reader の principal での hold の開始の 503 と、Journal の権限の拒否を確定の拒否にして pending を戻すこと（P3-1。`JournalAccessDeniedExceptionTest`）は `PrivacyHoldServiceTest`、Journal の照会の失敗の分類（P2-2）は `PrivacyJournalStartupVerifierTest`・`FeatureConditionCodeClassificationTest`。hold の効力の結果は reconciler の実行ごとにも再検査する（P3-2）。申請・承認の外部への検査は世代の lock の前の `refreshRemote` で行う（P2-6。段階 D の評価器も同じ規則に従う）。
  - **Demo:** なし。

- [x] **FE-C04 — trust の構造の検査を flag から切り離し、副入口の変化を告げる**
  - **依存:** FE-C01
  - **対応要求:** FE-RQ-005・009
  - **Objective:** `design.md` §8.5 の 4・10 と §7.4 の副入口（自動の再開は D-4 の例外）。
  - **対象 file:** `retention/trust/PrivacyTrustStartupValidator.java`（:55-79）、`retention/hold/PrivacyHoldReleaseService.java`（:131-137）、`retention/policy/PrivacyPolicyRegistrationService.java`（:125-150）、`featureenablement/evaluation/SubgateMonitor`（新規）。
  - **Automated test:** `PrivacyTrustStartupValidatorTest`（構造の不足で起動を止めない・副入口が閉じる）、`PrivacyHoldReleaseServiceTest`・`PrivacyPolicyRegistrationServiceTest`（構造の不足で 404）、副入口の閉鎖と再開で履歴と通知、D-05 の失効で保持・保全は開いたまま副入口だけが閉じる。
  - **実装の記録（段階 C）:** REL の構造の不足は `PrivacyTrustSourceTest`（構成の bean method で作った解除の service）、副入口の履歴・通知・自動の再開と D-05 の失効は `SubgateMonitorTest`。副入口の目録の署名（証拠の類を含む）を入口の gate も要求ごとに読む（`PrivacySubgateSignOffs`。hold 開始は `PrivacyHoldServiceTest`、REL は `PrivacyTrustSourceTest`、REG は `PrivacyPolicyRegistrationServiceTest`、判定は `PrivacySubgateSignOffsTest`。review rb-fe-c P2-1）。自動の再開は D-4 の例外で、REL・REG は利用者が確認した（2026-11-23。要件 §2 の D-10）。hold 開始は J-03 の推奨（独立 review で同意）で、利用者の確認の範囲外。
  - **Demo:** なし。

- [ ] **FE-C05 — NF07 の評価器・行為者の privacy-admin・PR #130 の guard**
  - **依存:** FE-C02、FE-C03、FE-C04
  - **対応要求:** FE-RQ-003・005・009（要件 §6.11・6.12）
  - **Objective:** `PrivacyProductionConfigurationValidator` に評価器を実装させ、`validate` の起動の停止をやめる。NF07 の行為者と署名・組の確認者を privacy-admin に限る。guard の privacy-admin の条件を NF07 が開いている・未決の申請の時だけにする。DSAR が閉じている間の未完了の請求の通知を有効にする。
  - **対象 file:** `PrivacyProductionConfigurationValidator.java`（:130-194）、`FeatureEnablementActorPolicy`、`service/security/AdministratorContinuityGuard.java`（:234-254 の呼出し側）、`AdministratorConditionCheck` の javadoc、目録（NF07 の 2 件を DB 管理に。評価できない類の code: `privacyAdmins.unverifiable` 等）、`FeatureEnablementProductionValidator`（`PRIVACY_*` の旧い変数）、`application-prod.yml`（placeholder の削除）。
  - **Implementation:** `design.md` §8.5 の 6・8 と §14（NF07 に再起動待ちは無い。guard が表を読めない時は利用者の管理の変更を全て拒否する）。
  - **Automated test:** `PrivacyProductionConfigurationValidatorTest`（既存の全ての違反 code が評価器でも同じ code になる、`privacyAdmins.unverifiable` が `UNVERIFIABLE`、prod でも起動を止めない）、`AdministratorContinuityGuardTest`・`AdministratorContinuityGuardH2Test`（NF07 が閉じていれば privacy-admin の減少を拒否しない、開いている・未決なら拒否、表を読めなければ拒否）、`FeatureEnablementActorPolicyTest`（NF07 で privacy-admin 以外は 403）、DSAR が閉じている間の未完了の請求の通知。MySQL の `AdministratorContinuityGuardMySqlTest` に申請との並行の case。
  - **実装の記録（段階 C）:** 評価器の code の一致・`privacyAdmins.unverifiable` の分類・D-06 の失効・guard 向けの判定（閉・開・未決・読めない）は `PrivacyProductionConfigurationValidatorTest`、guard が読む開放状態は `PrivacyEnablementStateReaderH2Test`、DSAR が閉じている間の通知は `PrivacyDsarClosedNotifierTest`。guard を通した NF07 の開閉（閉じていれば privacy-admin の解除を拒否しない・開いている・未決の申請があれば拒否・開放状態を読めなければ 409 `unverifiable`）は `AdministratorContinuityGuardH2Test`（実際の guard の READ_COMMITTED の transaction と実 SQL。review rb-fe-c P2-4）。`AdministratorContinuityGuardMySqlTest` の申請との並行の case は作らない（理由: guard と申請の作成は別の行を読み書きし、競合は承認の時の評価し直し（privacy-admin の2名の条件。§5.3）が閉じ、残る窓は定期の評価が閉じる。並行を MySQL で再現しても、検証できるのは「承認が privacy-admin の不足で拒否される」ことで、それは評価器の試験が固定している）。NF07 の行為者の privacy-admin は `privacy.` の拒否行を持つ group の所属者を除く（`FeatureEnablementActorPolicyTest`。review rb-fe-c P3-3）。Demo の試験の一部（D-06 の1件の取消しで DSAR だけが閉じる・組の member の状態に依らない束縛）は `FeatureSignOffBundleH2Test`（test profile。開放は `openDirectly`）と `PrivacyApprovalSourceTest`（EXPIRED の後の評価）にある。
  - **Demo:** prod の profile の試験の構成で、合成の D-01・D-09（・D-06）を組で登録・確認し、privacy-admin 2 名で保持・保全と DSAR を開き、1 名を外す変更が拒否されること、D-01 の更新の組を確認しても両方が開いたままであること、D-06 を失効させると DSAR だけが閉じること、D-06 の行が `EXPIRED` になった後の次の定期の評価と、D-06 を 1 件だけ `REVOKED` にした後も保持・保全は `OPEN` のままで DSAR は閉じていることを確かめる（review P2-N1。試験は `FeatureSignOffBundleH2Test`）。NF07 の評価器は `@Profile("prod")` のため、**dev の DB の源では NF07 を申請できない**（評価器が無く `evaluator.missing` の評価できない類になり、申請は 409。NF06 と同じ。review rb-fe-c P3-9）。この Demo は prod の profile の試験の構成で行う。

- [x] **FE-C06 — NF07 の起動の停止の撤去の確認と文書**
  - **依存:** FE-C05
  - **対応要求:** FE-RQ-005・014
  - **Objective:** NF07 の 3 つの起動時検査（validator・Journal・trust）が prod で機能の条件では起動を止めないことを、prod の完全な context で確かめ、FS 4.7 の起動時の記述を直す。
  - **対象 file:** `ProductionSecurityConfigurationTest`（prod の完全な context）、FS 4.7.2・4.7.4・4.7.5・4.7.7。
  - **Automated test:** prod の完全な context が、NF07 の条件を満たさない DB でも起動する case。
  - **実装の記録（段階 C）:** `ProductionSecurityConfigurationTest` に `sql/production-security-nf07-open-h2.sql`（保持・保全を DB で開き、署名は有効だが束縛・privacy-admin・Journal・鍵を満たさない）を足し、起動して起動時の評価が `AUTO_CLOSED` にすることを確かめた。FS 4.7.2〜4.7.5・4.7.7 と RB §1.0 の NF07 の行を直した（RB §1.7・FS の冒頭の表は FE-E03）。
  - **Demo:** なし。

### 段階 D

- [x] **FE-D01 — `IntegrationHubProductionEnablementPolicy`・`IntegrationHubApprovalDigest` の純粋化と mode の規則**
  - **依存:** 段階 A
  - **対応要求:** FE-RQ-008
  - **Objective:** `design.md` §9.5 と §9.3 の digest の部分、§9.1 の 0 の純粋な関数 `IntegrationHubEnablementDecision.decide(BootInputs)`。
  - **対象 file:** `config/integrationhub/IntegrationHubProductionEnablementPolicy.java`（:38-207）、`IntegrationHubApprovalDigest.java`（:79・:104）、`IntegrationHubExternalApiProperties.java`（:35-125）、`IntegrationHubApprovals.java`・`IntegrationHubEnablementDecision.java`（新規）。
  - **Automated test:** `IntegrationHubProductionEnablementPolicyTest`（既存の case が引数の経由で同じ違反、方向の引数、outbound 閉で HTTPS を許す、truststore の検査に副作用が無い）、`IntegrationHubApprovalDigestTest`（golden vector のまま、決定 ID を引数で受ける）、`IntegrationHubEnablementDecisionTest`（`OPEN` は指紋だけ、`OPEN_PENDING_RESTART` は OFF の記録も要る、purge の property の判定）。
  - **Demo:** なし。
  - **実装（段階 D）:** 違反は固定の code と従来の文言の組。DB の源の prod の安全網は違反のある方向を閉じて code を残す。細部は `design.md` §9.6。

- [ ] **FE-D02 — 起動の前の読取り（`IntegrationHubEnablementBootResolver`）・purge の property・起動の後の反映**
  - **依存:** FE-D01
  - **対応要求:** FE-RQ-008
  - **Objective:** `design.md` §9.1（0 の実装の条件、5 の barrier の分岐、5a の purge の property）・§9.2（法人の未解決は起動時の評価器へ）。
  - **対象 file:** `featureenablement/nf05/IntegrationHubEnablementBootResolver.java`（新規、`META-INF/spring.factories`）、`featureenablement/nf05/IntegrationHubBootDecisionRecorder.java`（新規）、`service/integrationhub/IntegrationHubRetentionPurgeCondition.java`（:15-22）・`ExternalApiReadSnapshotPurgeScheduler` の条件・`IntegrationHubDeliveryMetrics` の条件、`config/LegalEntityReadinessStartupValidator.java`（:21-27。NF05 の部分を `FeatureEnablementStartupEvaluator` へ）、目録（NF05 の 2 件を DB 管理に）、`deploy-runbook.md` §1.2（起動前の読取りの受理）、`application-prod.yml`（placeholder の削除）。
  - **Automated test:** `IntegrationHubEnablementBootResolverTest`（順序が `ConfigDataEnvironmentPostProcessor` の後、enabled を常に書く、状態・署名・総スイッチ・違反の組合せで property の値、`OPEN` は barrier の指紋だけ・`OPEN_PENDING_RESTART` は OFF の記録も、DB に届かない・表が無いと閉と `READ_FAILED`、purge の property（`READ_FAILED` では `true`。review P3-N3）、`NF05_OUTBOUND_OFF` の照会が `reference_type='NF05'` を付けて索引を使う（review P3-N8）、値を log に出さない）、`IntegrationHubBootDecisionRecorderTest`、`LegalEntityReadinessStartupValidatorTest`（NF05 は port を開く前に自動閉鎖、copilot は例外のまま）、`IntegrationHubRetentionPurgeConditionTest`。MySQL（`IntegrationHubEnablementRestartMySqlTest`。shard の登録を同じ commit で）: 承認 → 再起動で `OPEN` → 定期の評価の後も `OPEN` → もう一度再起動しても `OPEN` で隔離が走らない、`AUTO_CLOSED` の後の再起動で purge の scheduler が作られる。
  - **Demo:** prod の profile の試験の構成で、outbound を承認 → 再起動 → 起動の log に結果、画面で `OPEN`、もう一度再起動しても `OPEN` を確かめる。
  - **実装（段階 D）:** 法人の未解決は起動時の評価でなく起動の後の反映（`IntegrationHubBootDecisionRecorder`。同じく port を開く前）で判定した。起動時の値は実行時条件にもし、画面の閉じている理由に `nf05.boot.*` が出る。MySQL の再起動の試験は boot resolver・barrier の起動時の経路・起動の後の反映を同じ順で組み立てて再現する（context の refresh は試験の context のまま）。`application-prod.yml` に NF05 の開閉の placeholder は無い（固定の `false` を boot resolver が上書きする）。Demo は prod の profile と実 MySQL が要るため統括が行う（未実施）。細部は `design.md` §9.6。

- [ ] **FE-D03 — 実行時の NF05 の判定を runtime switch に置き換える**
  - **依存:** FE-D02
  - **対応要求:** FE-RQ-008
  - **Objective:** `design.md` §9.3 の `IntegrationHubRuntimeSwitch`（閉鎖の 2 種類 `TRANSIENT` / `DEFINITIVE`）と静的な試験。一時の閉鎖では producer が outbox への書込みを続け、worker は claim を止め、処理中の配信を DLQ にせず PENDING へ戻す。確定の閉鎖は現在の kill switch のまま（review P2-N3）。
  - **注記（micro review 88630af1 の P3-M2。段階 D の着手時に詰める）:** `TRANSIENT` と `DEFINITIVE` の境の細部（`AUTO_CLOSE_PENDING` を確定の閉鎖として扱う時点、snapshot の古さが続いて自動閉鎖に変わる時の処理中の配信の扱い）と、処理中の配信を PENDING へ戻す更新は claim の持ち主（lease の token）と状態の CAS で行い `attempt_count` を増やさないこと（回数の上限で DLQ に落とさない）を、着手時に design §9.3 と合わせて確定する。
  - **対象 file:** `IntegrationHubRuntimeSwitch.java`（新規）、`mapper/ApiDeliveryMapper.java`（claim を外して PENDING に戻し試行の回数を増やさない method。`holdPending` :81-86 は確定の閉鎖だけで使う）、`ExternalApiDisabledFilter.java`、`ExternalApiAuthenticationFilter.java`、`ExternalApiAuditBoundary.java`、`backup/quiesce/BackupWriteGateFilter.java`、`IntegrationHubResourceChangedProducer.java`、`IntegrationHubWebhookDeliveryWorker.java`、`src/test/java/com/ses/architecture/IntegrationHubFlagReadGuardTest.java`（新規）。
  - **Automated test:** 各 class の既存の試験（確定の閉鎖で kill switch の挙動のまま）、`IntegrationHubRuntimeSwitchTest`（`unverifiable_since`・snapshot の古さは `TRANSIENT`、`AUTO_CLOSED`・手動・総スイッチ・書込み待ちは `DEFINITIVE`、一時の閉鎖が解ければ戻る、確定の閉鎖では戻らない）、`IntegrationHubResourceChangedProducerTest`（一時の閉鎖の間も積む、確定の閉鎖では積まない）、`IntegrationHubWebhookDeliveryWorkerTest`（一時の閉鎖で処理中は PENDING へ戻る、確定の閉鎖で DLQ `KILL_SWITCH`）、MySQL（`IntegrationHubDeliveryTransientClosureMySqlTest`。shard の登録を同じ commit で）: 一時の閉鎖の間の変更の配信が回復の後に送られ、処理中だった配信も DLQ にならず送られる、`IntegrationHubFlagReadGuardTest`、`BackupWriteGateMockMvcTest`（外部 API の静止の扱いが変わらない）。
  - **実装（段階 D）:** `TRANSIENT`・`DEFINITIVE` の境（P3-M2）は `design.md` §9.6 で確定した。PENDING への戻しは `ApiDeliveryMapper.releaseClaim`（lease の token の CAS・`attempt_count` を戻す・送信の前だけ）。静的な試験は承認の値の getter も `IntegrationHubApprovals` だけに固定した（review P3-15）。MySQL の試験は未実行（Runner）。
  - **Demo:** なし。

- [ ] **FE-D04 — 閉鎖での barrier の書き直しと起動時・実行時の分離**
  - **依存:** FE-D02
  - **対応要求:** FE-RQ-008
  - **Objective:** `design.md` §9.3 の barrier の部分。
  - **対象 file:** `service/integrationhub/IntegrationHubOutboundStartupBarrier.java`（:55-150。`bootEnabled`・`recordRuntimeOff`）、`FeatureEnablementService`（A05 の閉鎖の hook から呼ぶ）。
  - **Automated test:** `IntegrationHubOutboundStartupBarrierTest`（閉鎖で行が `v1|OFF|0|<指紋>` と `NF05_OUTBOUND_OFF`、memory の `ready` が false、起動時の経路は起動時の値で分岐）。MySQL: 閉鎖 → ON の再起動で隔離が走る、`continueRecovery` と閉鎖の並行で deadlock しない。`IntegrationHubOutboundStartupBarrierTest` に、起動時の初期化が失敗した後に確定の閉鎖で行が OFF になった時、`continueRecovery` が初期化を再試行せず行を `QUARANTINING` に書き換えない case（review P3-N2）。
  - **Demo:** なし（MySQL の試験の結果）。
  - **実装（段階 D）:** 閉鎖の hook `IntegrationHubOutboundClosureHook`。MySQL の試験（閉鎖 → ON の再起動で隔離、`continueRecovery` と閉鎖の並行）は `IntegrationHubEnablementRestartMySqlTest`（shard 3）に入れた。FE-D03 と同じ commit（barrier の実行時の判定が runtime switch を要し、静的な試験が同時に成り立つため）。未実行（Runner）。

- [ ] **FE-D05 — NF05 の評価器（継続の条件と開放の遷移の前提）**
  - **依存:** FE-D03、FE-D04
  - **対応要求:** FE-RQ-008（要件 §6.8・6.9）
  - **Objective:** 承認の時と定期の評価で、policy の検査・署名・barrier の行の指紋（継続）と OFF の記録（`OPEN_TRANSITION`）・法人の未解決を判定する評価器を作り（D01 の純粋な関数を使う）、旧い NF05 の変数を拒否する。
  - **対象 file:** `featureenablement/nf05/IntegrationHubEnablementConditions.java`（新規）、目録（評価できない類の code: `state=STATE_IO_ERROR` 等）、`FeatureEnablementProductionValidator`（`INTEGRATION_HUB_*_ENABLED`・`*_DECISION_ID`・`*_APPROVAL_DIGEST`）。
  - **Automated test:** `IntegrationHubEnablementConditionsTest`（指紋の不一致で継続の条件が不成立、`QUARANTINING`・`ON` の行で継続の条件は成立、申請・承認では OFF の行と監査が要る、行なしで不成立、digest の不一致、鍵の欠落）。
  - **Demo:** prod の profile の試験の構成で、bound-tenant-id を変えた起動の後に outbound を申請すると条件が不成立になることを確かめる。
  - **実装（段階 D）:** 法人の未解決（`legalEntity.unresolved`）も評価器の check にした。旧い変数は開閉と承認の値（決定 ID・digest）の全ての綴りを止め、許可の一覧（`allowed-client-cidrs`・`allowed-destinations`）は止めない。DG-05 の一致・不一致は check の code（`nf05.<方向>.approvalDigest.mismatch`）で画面の最後の評価に出る（署名の登録の応答で返す FE-A06 の残りは未実装）。Demo は統括が行う（未実施）。実装の独立 review（rb-fe-d 8aadee14）の P2-1〜P2-3・P3 の修正は `design.md` §9.6 の末尾（評価器の試験 `IntegrationHubEnablementConditionsTest` は P2-3 で足した）。

### 段階 E

- [ ] **FE-E01 — 照合 script と雛形の総仕上げ**
  - **依存:** 段階 A〜D
  - **対応要求:** FE-RQ-007・014
  - **Objective:** 全ての旧い変数を両 phase で FAIL にし、Initial phase の機能スイッチの検査を削除する。
  - **対象 file:** `infra/ecs/offline/validate-task-definition.ps1`（:316-321・:576・:589 以降）、`test-validate-task-definition.ps1`、`infra/ecs/task-definition.prod.json`（注記）、`infra/ecs/README.md`（:27）、`ProductionTaskDefinitionTemplateTest`、`FeatureEnablementLegacySettingTest`（Java の一覧と ps1 の一覧の一致）。
  - **Demo:** `validate-task-definition.ps1 -Mode Template` が PASS し、旧い変数を 1 つ足すと両 phase で FAIL することを見る。

- [ ] **FE-E02 — 段階の印の撤去と既定値の試験**
  - **依存:** 段階 A〜D
  - **対応要求:** FE-RQ-006・016
  - **Objective:** 目録の DB 管理の印と合成の源を削除し（全て DB）、`FeatureSwitchDefaultsTest` の prod の case を旧い変数の拒否に書き直す。`application-prod.yml:130-152` の機能スイッチの placeholder が残っていないことを確かめる。
  - **対象 file:** `FeatureEnablementCatalog`、`FeatureEnablementStateSource` の合成の実装、`FeatureSwitchDefaultsTest`（:136-176 の prod の case）、`application-prod.yml`。
  - **Automated test:** `FeatureSwitchDefaultsTest`、`FeatureEnablementCatalogCoverageTest`（全て DB 管理）、既存の機能スイッチの全試験。
  - **Demo:** なし。

- [ ] **FE-E03 — FS と RB と関連の文書の書直し**
  - **依存:** 段階 A〜D
  - **対応要求:** FE-RQ-014
  - **Objective:** `design.md` §17 の文書の更新を行う。RB §1.7 は「go-live の前提は基盤と中核だけ。各機能の署名は system の中で開く前提」に書き直し、署名表の各行の署名を目録の種別 code と類（決定・証拠）と対応させる。NF05 の truststore 等の差替えの流れ（再起動 2 回）、総スイッチを誤って `false` にした代償、DSAR が閉じている間の法定の期限の扱い、二人の承認の残る穴（§11.1）と RB §5.6 の運用の規則「承認の前に、直近に管理者の role を得た利用者が承認者でないことを確かめる」、付与の記録の DDL を go-live の後の backlog に記録すること（review P3-N10）、自動閉鎖の書込み待ちの間は再起動しないことと再起動した後の確認（review P3-N1）、guard が表を読めない時の挙動を書く。
  - **対象 file:** `feature-switches.md`、`deploy-runbook.md`（冒頭・§1.0・§1.2・§1.7・§1.7.1・§3.4・§4.2・§4.4・§5.5 の後・§5.6、件数が出る全ての箇所）、`engineer-lifecycle-workflow/runbook.md`、`privacy-retention-dsar/production-bridge-phased-plan.md`、`integration-hub-public-api/dg-05-enablement-decision-draft.md`、`ops/security/runbooks/integration-hub-public-api.md`、`data-migration-import-center/a1a-ingress-operations.md`、`certification-learning-skill-gap/first-release-scope-confirmation.md`、`mobile-pwa-self-service/first-release-user-and-operations-notes.md`。
  - **Automated test:** `DeployRunbookMigrationCountTest`、runbook を読む既存の試験。
  - **Demo:** なし（文書の review）。CLAUDE.md の更新は統括へ依頼する。

- [ ] **FE-E04 — PITR の後の手順と `CONTENT_HASH_TABLES`**
  - **依存:** 段階 A〜D
  - **対応要求:** FE-RQ-014・015
  - **Objective:** `design.md` §13.1 の手順を restore の runbook に書き、`CONTENT_HASH_TABLES` の候補に本機能の 5 表を足す。
  - **対象 file:** `ops/backup/runbooks/restore-cutover.md`（必須の checklist に「次の起動の task definition は `FEATURE_ENABLEMENT_ALLOWED=false`」、復旧の後の最初の起動は総スイッチを閉、復旧の時点より後に手動・自動で閉じた開放単位と理由の一覧と確認、CloudWatch Logs の log group の保存期間を PITR で戻れる期間以上にする SRE の設定（review P3-N4）、`FEATURE_ENABLEMENT_EVENT` との照合と Logs Insights の query の例、総スイッチを戻して二人で開き直す）、`.kiro/specs/production-launch/dba-prelaunch-draft.md` §1（候補 B に `t_feature_enablement_state`・`t_feature_sign_off`・`t_feature_sign_off_extension`・`t_feature_sign_off_bundle`・`t_feature_sign_off_bundle_member`）、`deploy-runbook.md`（restore の後の手順への参照）。
  - **Automated test:** backup tool の既存の shell の試験（`CONTENT_HASH_TABLES` の候補の書式を検査するものがあれば）、文書の review。
  - **Demo:** 復旧の演習（DBA・SRE。staging。人が行う。未取得）で、復旧の後の最初の起動が全ての開放単位を `AUTO_CLOSED(MASTER_SWITCH_CLOSED)` にし、barrier が OFF になることを確かめる。

- [ ] **FE-E05 — 全 gate と browser の確認（統括の Runner）**
  - **依存:** FE-E01〜FE-E04
  - **対応要求:** FE-RQ-015・016
  - **Objective:** 既定の高速 suite・`-Pmysql-tests`・`-Pperformance-tests`・backup integration・`-Pbrowser-tests`（`FeatureEnablementScreenBrowserTest`）を skip 0 で通す。staging で prod の profile の起動と、1 件の開放単位の申請・承認・閉鎖を人が確かめる（未取得）。
  - **Demo:** staging（人が行う。未取得）。
