# 機能の開放を system 内で行う（条件＋二人の承認で画面から開ける）要件

- 状態: **草案（第 1 段階: spec のみ。第 3 版: 独立 review `rb-feature-enablement-spec-96be3af4-review.md`（P1 2・P2 7・P3 21）と、`85840936` の delta review（P2 3・P3 10）と統括の決定を反映）**。delta review の後に、統括の指示で実装する。本書の段階では code・migration・runbook を変えない。
- branch: `codex/snf-feature-enablement-20261122`（起点 `88b31d45`＝PR #130 の head。RB-ADMIN-GUARD の `AdministratorConditionCheck` を含む）。
- 設計は `design.md`、実装の手順は `tasks.md`。利用者・統括の判断が要る点は `design.md` §18 に一覧する。J-01・J-09・J-16 は利用者が決めた（2026-11-22。§2 の D-7〜D-9）。副入口の自動の再開（D-4 の例外。J-03 の結果）は利用者が確認した（2026-11-23。§2 の D-10）。
- 本書の file の行番号は `88b31d45` の行番号である。実装の着手時に再確認する。
- 関連する正本: `.kiro/specs/production-launch/feature-switches.md`（以下「FS」）、`.kiro/specs/production-launch/deploy-runbook.md`（以下「RB」）、`.kiro/specs/privacy-retention-dsar/production-bridge-phased-plan.md`、`.kiro/specs/integration-hub-public-api/dg-05-enablement-decision-draft.md`、`.kiro/specs/data-migration-import-center/a1a-ingress-operations.md`。

## 1. 背景（確認した事実）

- 機能スイッチは配備の設定（環境変数）で決まる。`Feature` enum（`config/feature/Feature.java:28-166`）の 11 件は `FeatureSwitchProperties`（`@ConfigurationProperties("app.features")`、`toggle(Feature)` :79-93。NF07 は `PrivacyFeatureProperties` に委ねる）の値を `FeatureSwitches`（`isEnabled` :61-69、`isConfiguredOn` :90-97）が毎回読む。NF05 の 2 件（`integration.hub.public-api.enabled`・`integration.hub.external-transport.enabled`）と NF08（`ai.external-send-enabled`）は enum に無い。
- 値は起動時に bind され、切替えには再起動が要る（FS 1・4.1）。子スイッチの実効値は「親 AND 子」（`FeatureSwitches.java:61-69`）。`logStates`（:40）は起動時に 1 回だけ状態を log に出す。
- 実行時の判定点は `FeatureSwitchFilter`（:64-95。Security の前で OFF を 404）、`GlobalControllerAdvice`（:173-230。menu と `disabledFeatures`）、scheduler の入口、service 層の子スイッチの gate（`LifecycleDualConfirmationCoordinator:660`、`LifecycleTemplateServiceImpl:316`、`EngineerCertificationServiceImpl`、`PrivacyAccessGuard:83-95` 等）である。これらは全て `FeatureSwitches` を経由する。
- `FeatureSwitches` を経由せず property を直接読む箇所がある。NF07 の 5 箇所（`PrivacyProductionConfigurationValidator.enabledCapabilities` :303-315、`PrivacyDecisionWindowMonitor.enabledCapabilities` :211-223、`PrivacyTrustStartupValidator.validate` :60-61、`PrivacyJournalStartupVerifier.holdStartRequired` :142-146、`PrivacyProductionConfiguration.retentionSourceEnabled` :77-83）と、NF05 の全ての読取り（`ExternalApiDisabledFilter:29-30`、`ExternalApiAuthenticationFilter:61-62`、`ExternalApiAuditBoundary:158-162`、`BackupWriteGateFilter:87-91`、`IntegrationHubResourceChangedProducer:37-39`、`IntegrationHubWebhookDeliveryWorker:79-85,111-114,193-194`、`IntegrationHubOutboundStartupBarrier:137`）である。
- prod の起動時検査が、ON の機能の条件を満たさないと起動を止める: `LifecycleDualConfirmationReleaseGate`（`@PostConstruct` :68-80）、`ImportAdmissionStartupValidator`（:52-77）、`ImportRowReferenceKeyStartupValidator`（:38-55）、`PrivacyProductionConfigurationValidator`（`validate` :130-173）、`PrivacyTrustStartupValidator`（:55-79）、`PrivacyJournalStartupVerifier`（`enforce` :171-192）、`IntegrationHubExternalApiProperties.validateBoundaries`（`@PostConstruct` :35-125）とそこから呼ぶ `IntegrationHubProductionEnablementPolicy`（:38-207）、`LegalEntityReadinessStartupValidator`（`ApplicationReadyEvent` :21-27）。
- 時間の経過だけで生じる不足（署名日前・失効）は、NF07 と NF06 では起動を止めず機能を閉じる（2026-11-14 決定。FS 4.7.2.1、`ImportAdmissionStartupValidator:75`）。NF07 は `PrivacyDecisionWindowMonitor` が `FeatureRuntimeCondition`（`config/feature/FeatureRuntimeCondition.java:11-18`）として JST の業務日ごとに判定する。その cache の key は業務日だけで、flag と承認の値を含まない（`PrivacyDecisionWindowMonitor.java:156-165`）。
- NF05 は bean の構成が flag で決まる（`IntegrationHubInboundCondition`・`IntegrationHubOutboundCondition`・`IntegrationHubDeliverySchedulingCondition`・`IntegrationHubTransportModeCondition`・`IntegrationHubRetentionPurgeCondition`、`IntegrationHubProducerConfiguration:15-66`）。実行中に OFF にする道（kill switch）はあるが、実行中に ON にする道は無い。outbound の OFF→ON は、OFF の起動のたびに `m_system_config` の `integration.hub.outbound.runtime.v1` を `v1|OFF|0|<bound-tenant-id の SHA-256>` に書く barrier（`IntegrationHubOutboundStartupBarrier:72-101`）を前提にする。
- `m_system_config` は 3 列の KV（V1:506-512）で、tenant・version・更新者・履歴を持たない。`SystemConfigServiceImpl.getString`（:177-183）は `synchronized` で、filter の hot path では使えない。
- 二人の承認の先例: 承認 workflow の自己承認の拒否（`ApprovalEngineServiceImpl:465-466`）、NF07 policy の DB CHECK `activated_by <> registered_by`（`V190__nf07_privacy_f1_core.sql:85-86`）、DSAR の開示 package の CHECK `approved_by <> created_by`（同 :253）、break-glass の承認者 2 名（`BreakGlassServiceImpl:121-124`）。
- 起動時検査の判定を他の部品が再利用する先例: PR #130 の `AdministratorStartupConditions`（`config/AdministratorStartupConditions.java`）。起動時検査そのもの（`ProductionSecurityConfigurationValidator`・`PrivacyProductionConfigurationValidator:184-194`）が実装し、`validate` と同じ私有の検査 method を呼ぶ。条件の写しを持たない。
- 現状の調査の全体: `C:\work\ses-claude-control-20261112-r1\reports\feature-switch-inventory-20261122.md`（統括の資料。repository の外）。

## 2. 利用者の決定（2026-11-22。本書はこれに従う）

- **D-1 開放を system の中に移す**: 開閉は配備の設定から system の中へ移す。各機能は system の中で条件を満たし、二人の承認を経て、個別に開く。go-live（初回リリース）は機能を開いていることを前提にしない（2026-11-22 の「初回リリースは NF08 以外を全て ON」の前提を置き換える）。**go-live の前に実装を終える**。
- **D-2 反映**: 即時に反映できる機能は即時に反映する。**NF05 の inbound・outbound を開ける変更は次の再起動で反映し、閉じる変更は即時に反映する**（既存の kill switch）。
- **D-3 承認**: **申請者と別の管理者 1 名**が承認する（申請者≠承認者。DB でも防ぐ）。各機能が要する署名は system の中に登録する: 署名者の役割・署名日・有効期限・文書の本体と SHA-256。
- **D-4 条件が後で満たされなくなったとき**: **その機能だけを閉じて警告する**。他の機能と app は動き続ける。条件が戻った後は、管理者が再び開く（同じく二人の承認）。
- **D-5 配備の設定**: **鍵（secret）と緊急の総スイッチだけを残す**。総スイッチは既定で許可し、閉じると system の中でも開けず、開いている機能は直ちに閉じる。署名の決定 ID・文書の hash（現在の `*_APPROVAL_*`・`*_DIGEST`・`*_SIGN_OFF_REF`・`*_DECISION_ID` の類）は system の登録へ移し、署名の差替えに再起動を要しない。
- **D-6 NF08 は対象外**: code の定数と prod の起動拒否で閉じたままにする。

2026-11-22 の追加の決定（`design.md` §18 の判断点への回答）:

- **D-7（J-01）**: system へ移すのは機能スイッチと署名の値だけとし、基盤の設定（NF05 の topology・承認の内容の一覧、NF07 の Journal の接続・trust 文書の path 等）は配備の設定に残す。その内容は署名の digest で決定に束縛する。
- **D-8（J-09）**: 時間要因は新しい規則に置き換える。署名の失効でその機能を自動閉鎖して警告し、NF06 の期間外は NF06 全体を閉じる（404）。署名日前の署名では開かず、管理者の申請と承認で開く。これは 2026-11-14 の時間要因の規則（失効・署名日前は起動を止めず OFF 扱い、署名日に自動で開く）を置き換える。
- **D-9（J-16）**: NF02 の全社共通カレンダーは開放の時だけの条件とする。申請・承認の時に当年度の暦を必須にし、その後は継続の条件にせず NF02 を自動閉鎖しない。暦の期限の 30 日前から警告（監査と管理者への通知）し、暦が無い間は現行の WARN `missing_calendar` と土日だけを休日とする計算を続ける。

2026-11-23 の利用者の確認（段階 C の review rb-fe-c の判断点 7）:

- **D-10（D-4 の例外の確認）**: 副入口（NF07 保持・保全の hold 開始・REL・REG）は D-4 の対象外とし、技術の条件（trust 文書の鮮度・anchor・verifier・Journal の appender）が戻れば自動で開く。署名に依る回復（D-05・D-03・D-02 の更新、証拠の類の再登録）は新しい署名の二人の確認を要する（一人で副入口を開き直す経路は無い）。開放単位（保持・保全）の再開は D-4 のとおり二人の承認を要する。FE-RQ-005 の例外と §3 の副入口はこの確認に従う。

## 3. 用語

| 用語 | 定義 |
|---|---|
| 開放単位 | 申請・承認・閉鎖の単位になる機能。§6 の 13 件（現在の `Feature` の 11 件と NF05 の 2 件）。 |
| 副入口 | 開放単位の下で、別の条件で要求ごとに開閉する入口。NF07 保持・保全の hold 開始・hold 解除（REL）・policy の登録と有効化（REG）。独立の申請・承認の単位にはしない（§6.11。`design.md` §18 の判断点 J-03）。副入口の条件が戻れば副入口は自動で開く（D-4 の例外。2026-11-23 に利用者が確認した D-10。FE-RQ-005）。副入口の目録の署名（決定の類と証拠の類）の取消し・失効は副入口だけを閉じ、各入口の gate も要求ごとに判定する。 |
| 開放状態 | 開放単位の DB 上の状態（FE-RQ-002）。 |
| 実効値 | 要求の時点で機能を公開してよいか。開放状態・総スイッチ・親・有効期限・一時の閉鎖（overlay）から決まる。既存の `FeatureSwitches#isEnabled` の値。 |
| 条件 | 開放単位を開くために満たす事項。自動検査（system が判定する）・署名（登録と確認が要る）・鍵（配備の設定にあること）・親（親の開放単位が開いていること）・総スイッチの 5 種。既定は継続の条件（開いている間も評価し、外れたら自動閉鎖）。 |
| 開放の時だけの条件 | 申請・承認の時だけ開閉を左右し、開いた後に外れても閉じず警告だけをする条件。NF02 の全社共通カレンダーだけ（D-9）。 |
| 開放の遷移の前提 | 開く遷移（申請・承認と、NF05 の `OPEN_PENDING_RESTART` を最初に反映する起動）の時だけ判定する技術上の前提。開いた後と、既に `OPEN` の再起動では判定しない。NF05 outbound の「barrier の行が OFF で `NF05_OUTBOUND_OFF` の監査がある」だけ。 |
| 署名の組 | 互いに束縛し合う署名の集合（例: NF07 の D-01・D-06・D-09。D-09 の digest が他の決定の ID・日付を含む）。登録は組で行い、確認は組ごとに 1 回（二人）、差替えは組ごとに原子的に行う。 |
| 自動検査 | 現在の起動時検査と実行時の gate が判定している事項を、いつでも判定できる形にしたもの。 |
| 署名 | 機能の開放の前提になる、人が行う署名・決定・受理・確認の記録。種別・署名者の役割・署名日・有効期限・文書の本体と SHA-256・登録者・確認者を持つ。 |
| 確認（署名の） | 登録者と別の管理者が署名の内容を確かめ、有効にすること。 |
| 総スイッチ | 配備の設定の `FEATURE_ENABLEMENT_ALLOWED`（FE-RQ-007）。 |
| 自動閉鎖 | 条件の不成立・総スイッチで system が開放状態を閉じること。再び開くには申請と承認が要る。 |
| 一時の閉鎖（overlay） | DB の状態を変えずに実効値だけを閉じること。評価器の例外・snapshot の古さ等で使い、原因が消えれば戻る。 |
| 再起動待ち | NF05 の開放を承認した後、次の起動までの状態。 |
| snapshot | 開放状態・署名・設定を memory に読み込んだもの。filter の hot path はこれだけを読む。 |
| 世代 | tenant ごとの変更の通し番号。開放状態・署名・設定を変える transaction が 1 つ上げ、他の instance は世代の変化で snapshot を読み直す。 |

## 4. スコープ

対象:

- 13 の開放単位の開閉を DB の状態で行う判定層、申請・承認・却下・取下げ・閉鎖・自動閉鎖、署名の登録と確認、NF02 の公開範囲（`visible-since`）の変更。
- 条件の評価器（起動時検査の判定を再利用する）と、起動時・定期・変更時の評価。
- 管理者専用の画面「機能の開放」と API（`/feature-enablement`・`/api/feature-enablement/**`）。
- migration（V203。1 件。必要が出たら V204 以降を統括に申請する）と H2 の試験 schema。
- 起動時検査のうち、機能の条件で起動を止めていたものを「その機能を閉じる」へ改める変更。
- NF05 の開放を次の起動で反映する仕組み。NF07 保持・保全を実行時に開閉できるようにする前提の改修。
- 配備の設定（総スイッチ・旧変数の扱い）、task definition の雛形と照合 script、FS・RB の改訂。

対象外:

- NF08（D-6）。`AiProductionSafetyValidator` と `PERMIT_ONLY_DISPATCH_WIRED=false` は変えない。
- 機能の開放と関係しない prod の起動時検査（§7 の「残す」）。
- 基盤の設定（DB の接続、OIDC、`TRUSTED_PROXIES`、NF05 の topology・承認の内容の一覧、NF07 の Journal の接続・trust 文書の path 等）を DB へ移すこと（D-7。`design.md` §18 J-01、決定済み）。
- 多 tenant での公開（SRE-1 のまま単一 tenant）。複数 instance での運用（SRE-5 のまま単一 instance。仕組みは複数 instance でも安全側に倒れるようにする）。
- 人が行う署名・受理・実測そのもの（本 spec は登録の器を作るだけで、どれも**未取得**のまま）。

## 5. 要件と受入基準

### FE-RQ-001: 開放単位と条件の目録

- 開放単位は §6 の 13 件とする。NF08 は含めない。
- 開放単位ごとに、自動検査・要る署名（種別・署名者の役割・有効期限の上限）・配備の設定に要る鍵・親を目録として code に持つ（`design.md` §4.1。目録の正本は code、文書は §6）。
- 目録の各項目は出処（現在の起動時検査・gate の class と method、FS・RB の節）を持つ。
- 各条件は、継続の条件・開放の時だけの条件・開放の遷移の前提のどれかを持つ。開放の時だけの条件は NF02 の全社共通カレンダーだけ（D-9。業務の条件）、開放の遷移の前提は NF05 outbound の barrier の OFF の記録だけ（技術上の前提で、D-9 の決定の対象ではない）。他は全て継続の条件。
- 条件の code ごとに「評価できない類」（照会の失敗・接続の失敗。例: `*.unverifiable`、`journal.unreachable`、`evaluator.*`）かどうかを目録に持つ。既存の検査が照会の失敗を違反 code に変えて返す場合も、この分類で `UNVERIFIABLE` として扱う。
- 子の開放単位（NF01 二者確認・NF02 顧客ポータルの問い合わせ・NF03 資格番号）は親が開いていることを条件に持つ。NF05 の inbound と outbound、NF07 保持・保全と DSAR は親子にしない。
- 目録に評価器が欠けた開放単位は申請も承認もできない（fail-closed）。

受入基準:

- **FE-AC-001-01** WHEN `Feature` の全ての値と NF05 の 2 件を走査する THEN 各開放単位に目録の行と評価器が 1 つ以上あり、NF08 の行は無い（`FeatureEnablementCatalogCoverageTest`）。
- **FE-AC-001-02** WHEN 評価器を登録しない開放単位を申請する THEN 409 で拒否され、DB の行は増えない。
- **FE-AC-001-03** WHEN 画面の一覧を開く THEN 13 件が表示され、各件で条件の種類ごとの成否と理由の文言が出る。

### FE-RQ-002: 開放状態と遷移

- 開放状態は `CLOSED`（未開放）・`OPEN`（開放中）・`OPEN_PENDING_RESTART`（再起動待ち。NF05 だけ）・`AUTO_CLOSED`（自動閉鎖。理由の code を持つ）・`MANUALLY_CLOSED`（手動閉鎖）とする。「申請中」は未決の申請があることを表す表示上の状態で、開放状態の値ではない。
- 遷移は次だけを許す（他は 409）:
  - 申請: `CLOSED`・`AUTO_CLOSED`・`MANUALLY_CLOSED` の時だけ。未決の申請を作る。
  - 承認: 未決の申請を承認すると `OPEN`（NF05 は `OPEN_PENDING_RESTART`）。
  - 却下・取下げ・期限切れ・陳腐化: 申請だけが終わり、開放状態は変わらない。
  - 閉鎖: `OPEN`・`OPEN_PENDING_RESTART` を `MANUALLY_CLOSED` にする。
  - 自動閉鎖: `OPEN`・`OPEN_PENDING_RESTART` を `AUTO_CLOSED` にする（条件の不成立・署名の失効・総スイッチ・親の閉鎖・評価できない状態の継続）。
  - 再起動での反映: NF05 の `OPEN_PENDING_RESTART` を、起動時の評価で `OPEN` か `AUTO_CLOSED` にする。
- 親の開放単位が閉じたら、開いている子は同じ transaction で `AUTO_CLOSED`（理由 `PARENT_CLOSED`）にする（D-4。子を再び開くには申請と承認が要る）。
- 申請から承認までの間に条件が変わり得るため、承認の時に同じ transaction の中で条件を評価し直す（FE-RQ-003）。
- 未決の申請は既定 7 日で期限切れ（`EXPIRED`）にする（`design.md` §18 J-07）。期限切れは日の始まりの処理に加えて、新しい申請の transaction の中でも、同じ対象の期限を過ぎた未決を先に `EXPIRED` にしてから行う（scheduler が止まっていても未決の枠が塞がらない）。

受入基準:

- **FE-AC-002-01** WHEN 許さない遷移（例: `OPEN` の申請、`CLOSED` の閉鎖）を要求する THEN 409 になり、状態・申請・履歴の行は変わらない。
- **FE-AC-002-02** WHEN 親を閉じる THEN 開いていた子は同じ transaction で `AUTO_CLOSED`（`PARENT_CLOSED`）になり、親を開き直しても子は閉じたままである。
- **FE-AC-002-03** WHEN 申請から 7 日を過ぎる THEN 申請は `EXPIRED` になり、承認は 409 になる。

### FE-RQ-003: 二人の承認

- 申請者と承認者は別の利用者とする。service で拒否し、DB の CHECK でも拒否する（申請の行の `decided_by <> requested_by`、開放状態の行の `approved_by <> opened_by`）。開放状態の行と設定の行は、承認した申請の行を複合 FK（申請の ID・申請者・決定者）で参照し、申請の行と食い違う申請者・承認者を DB で拒否する。却下も申請者以外が行う。取下げは申請者だけが行う。
- 二人の承認の残る穴（同じ人が 2 つの口座を持つ、管理者の role を 1 名で付与できるため、盗まれた管理者が自分の別の口座を管理者にして自分で承認する）を RB §5.6 に書く。利用者が管理者の role を得た時に、有効な管理者全員へ通知する。「承認者の管理者の role を申請者が付与した、または N 日以内に付与された」場合の拒否は、付与の記録が `sys_user` に無いため本 batch では行わない（`design.md` §11.1）。代わりに RB §5.6 に運用の規則「承認の前に、直近に管理者の role を得た利用者が承認者でないことを確かめる」を書き、付与の記録の DDL を go-live の後の backlog の 1 項目として記録する。
- 申請・承認の時の条件の評価と指紋は、世代の行を lock した後に同じ transaction の中で DB から読み直した状態で行う（memory の snapshot を使わない）。
- 申請・承認・却下を行う利用者は、その時点で有効な管理者とする: role `管理者`、`status=1`、未削除、lock 中でない、設定の tenant に属する（`SecurityContextCallerContextProvider.isCurrentActiveAccount` :130-139 と同じ判定）。
- 申請・承認・却下に使う session は、prod では OIDC の session で MFA を済ませたものに限る。**break-glass の session と bootstrap の session は使えない**（推奨。理由は `design.md` §11.2。判断点 J-04）。break-glass の incident の許可 action に `feature-enablement.*` を入れることは拒否する。
- NF07 の開放単位（保持・保全、DSAR）は、申請者と承認者の両方が privacy-admin に所属する有効な管理者であることを加える（`design.md` §11.3。判断点 J-05）。
- 同じ開放単位に同時に置ける未決の申請は 1 件だけとする（DB の一意索引で強制する）。
- 承認の時に、条件を評価し直す。全ての条件が成り立たなければ承認を 409 で拒否し、理由の code を返す（申請は未決のまま）。申請の時に記録した条件の指紋（評価に使った署名の ID と版、設定の値）と承認の時の指紋が違えば、申請を `STALE`（陳腐化）として終え、申請し直させる（承認者が申請者と違う内容を承認しないため）。
- 申請も、その時点で全ての条件が成り立つ時だけ受け付ける。
- 総スイッチが閉じている間は、申請・承認を 409 で拒否する。

受入基準:

- **FE-AC-003-01** WHEN 申請者本人が承認・却下する THEN 403 で拒否され、DB へ直接 `decided_by = requested_by` の `APPROVED` 行を書く SQL も CHECK で失敗する（MySQL は `MySqlConstraintRejections.assertRejectedBy`）。
- **FE-AC-003-02** WHEN 同じ開放単位に 2 件目の未決の申請を作る THEN 一意索引で失敗し、API は 409 を返す。
- **FE-AC-003-03** WHEN break-glass の session、bootstrap の session、lock 中・無効・削除済みの管理者、管理者以外の role が申請・承認する THEN 403 になる。
- **FE-AC-003-04** WHEN 申請の後に必要な署名が失効・取消しされてから承認する THEN 承認は 409 で、開放状態は変わらない。WHEN 申請の後に署名が差し替えられてから承認する THEN 申請は `STALE` になる。
- **FE-AC-003-05** WHEN privacy-admin に所属しない管理者が NF07 の開放単位を申請・承認する THEN 403 になる。
- **FE-AC-003-06** WHEN 別の instance で（または同じ instance の commit の後の読直しの前に）必要な署名が取り消された直後に承認する THEN 承認は transaction の中で読み直した状態で評価され、409 になる（MySQL の並行の試験）。
- **FE-AC-003-07** WHEN 承認で申請が `STALE` になる THEN `STALE` と監査は commit され、その後に 409 が返る。

### FE-RQ-004: 署名の登録と確認

- 署名は、種別（§6 の各表）・参照（決定 ID・文書 ID・ticket 等）・署名者の役割・署名者の氏名（任意）・署名日・有効期限・文書の本体・SHA-256・大きさ・種別ごとの構造化された値（例: NF07 D-09 の承認 tenant・承認 capability・approval digest、NF06 の admission の source code・digest、NF07 D-06 の `due-days`・`package-max-age`）・登録者・確認者を持つ。
- 文書は既存の upload の経路（`FileStorageServiceImpl.storeBytes` :122-187。quarantine → scan → `CLEAN` で published）を通す。scan が `CLEAN` でなければ登録を拒否する。SHA-256 は upload の経路が計算しないため、本機能が bytes から計算して署名の行に保存し、download の時に計算し直して照合する（先例: `PrivacyExportPackageService.readVerified` :880-901）。
- 保存した文書は CLAUDE.md の規則どおり 2 箇所に登録する: `FileReferenceProvider`（生きている署名の保存名を claim する）と `FileScopeValidationService`（汎用の `/api/files/**` からは拒否し、専用の経路だけが読む）。新しい `FileKind` を汎用の upload（`FileApiController:36-44`）で受け付けず、汎用の取得（`load`）と再 scan（`FileScopeValidationService.assertRescanAllowed` :504-506）でも metadata の `file_kind` で拒否する。scan が `CLEAN` でない文書は quarantine に残さずその場で消す（文書は upload し直せる）。
- 署名は登録しただけでは効かない。登録者と別の有効な管理者が確認して初めて有効になる（二人。推奨。理由は `design.md` §6.4。判断点 J-06）。確認者の条件は FE-RQ-003 の承認者と同じ。NF07 の種別は privacy-admin が確認する。
- **署名の組**（目録が定める。現在は NF07 の決定の束縛 D-01・D-06・D-09）は、組として登録し、組ごとに 1 回確認する。確認の transaction で、旧い組の差し替わる署名を `SUPERSEDED`、新しい組の署名を `CONFIRMED` にしてから評価する。組の一部だけの確認は受け付けず、組の中で digest が一致しない（例: D-09 の digest が同じ組の D-01・D-06 の ID・日付と合わない）組は確認を 400 で拒否する。変わらない署名は新しい組へ引き継げる（文書の再 upload は要らない）。
- 組の種類の決定の束縛（digest と構造）の入力は、**現在 `CONFIRMED` の組の全ての member** の参照・日付・構造化された値とし、member の行の状態（`EXPIRED`・`REVOKED`、`PURGED` の後も残る参照と日付）に依らない。member の有効性（期間・取消し）は、その決定を要する開放単位の署名の条件だけが判定する（P1-2 の「開放単位が要る決定だけ」のまま）。組の member を 1 件だけ取り消しても組は `CONFIRMED` のままで、その種別を要する開放単位だけを閉じる（例: D-06 の取消しで DSAR だけが閉じ、保持・保全は開いたまま）。
- **署名日が今日より後の署名は確認できない**（登録はできる）。確認は署名日の当日から行え、その時に旧い署名を差し替える（D-8 により署名日に自動で開くことはない）。
- 署名の参照・構造化された値は、種別ごとに現在の起動時検査と同じ規則で登録の時に検査する（例: NF01 二者確認の参照は `LifecycleDualConfirmationReleaseGate.acceptable` :86-97、NF07 の決定は `PrivacyDecisionBinding` の規則、NF06 の admission は `ImportAdmissionApproval.evaluate` :78、NF05 の決定 ID は `DG-05-IN-[0-9]{8}-[0-9]{2}` 等）。digest の内容が署名の中で閉じている種別（NF07 の組、NF06 の admission）は、system が正規形から計算した値と文書に記載された値（登録者が入力する）の一致を登録・確認の時に要する。NF05 の DG-05 の digest は配備の設定の topology を含むため、登録の時は形式だけを検査し、現在の配備の設定から計算した値との一致・不一致を画面に出す（一致の判定は評価の時）。
- 有効期限は全ての種別で必須とする。上限は種別の類で分ける（J-08。統括の決定で (b)。**暫定で、最終は各 owner と Privacy が確認する**）: 決定の類（NF07 の D-xx、NF06 の admission、NF05 の DG-05 等、署名者の決定そのもの）は 366 日。証拠の類（受理・実測・review の PASS・手動の確認）は 3 年（1,095 日）。証拠の類は、二人の再確認で延長できる: **同じ行の有効期限を更新し**（新しい行を作らない。文書・保存名・署名日は変えない）、申請者・確認者・旧い期限・新しい期限を追記専用の延長の記録に残す。延長は申請（`SIGN_OFF_EXTENSION`）と、申請者と別の管理者の確認で行い、新しい期限の上限は確認の日から 1,095 日。消去の判定はその行の最後の状態と期限で行う。
- 有効期限の当日（JST 0:00）から署名は無効になり、それを要する開いている開放単位は自動閉鎖する（FE-RQ-005）。署名日前の署名は条件を満たさない（署名日に自動で開くことはせず、署名日以降に管理者の申請と承認で開く。2026-11-14 の時間要因の規則を D-8 が置き換える。`design.md` §18 J-09、決定済み）。
- 失効の既定 30 日前から、毎日 1 回、管理者へ通知する（日数は `/system-config` の設定。`privacy.approval.expiry-warning-days` の役割を引き継ぐ）。
- 署名の差替え（同じ種別の新しい署名、または新しい組の確認）は、旧い署名を `SUPERSEDED` にし、開いている開放単位を閉じずに続ける（新しい署名で条件が成り立つ場合）。再起動は要らない（D-5）。ただし旧い署名が確認の時点で既に失効していた（`今日 ≥ 失効日`）場合は、同じ transaction で先に、それを要する開いている開放単位を `AUTO_CLOSED`（`SIGN_OFF_EXPIRED`）にしてから確認する（scheduler が止まっていた間の失効を、差替えで承認なしに戻さないため）。
- 確認済みの署名の取消し（`REVOKED`）は管理者 1 名で行える（閉じる方向）。取り消すと、それを要する開いている開放単位を同じ transaction で自動閉鎖する。
- 署名者は system の利用者とは限らない。氏名は任意の個人データとし、最小化と保存期間を FE-RQ-017 に従う。

受入基準:

- **FE-AC-004-01** WHEN scan が `CLEAN` でない文書で登録する THEN 登録は拒否され、署名の行は作られない。
- **FE-AC-004-02** WHEN 登録者本人が確認する THEN 403 になり、DB の CHECK（`confirmed_by <> registered_by`）でも失敗する。
- **FE-AC-004-03** WHEN 確認済みの署名の文書を専用の経路で取得する THEN 保存した SHA-256 と一致した時だけ返し、一致しなければ 409 になる。WHEN 同じ保存名を `/api/files/**` で取得する THEN 管理者でも 403 になる。
- **FE-AC-004-04** WHEN 失効日を迎える THEN その署名を要する開いている開放単位は JST 0:00 以降の最初の要求から実効値が閉じ、定期の評価で `AUTO_CLOSED`（`SIGN_OFF_EXPIRED`）になり、管理者に通知が届く。
- **FE-AC-004-05** WHEN 開いている開放単位の署名を新しい署名に差し替えて確認する THEN 開放単位は開いたままで、再起動なしに新しい署名の有効期限で判定される。WHEN 開いている NF07 保持・保全・DSAR の D-01 を更新した組（D-01′・D-09′、D-06 は引き継ぐ）を確認する THEN 両方とも開いたままである。WHEN D-01′ だけを確認しようとする THEN 400 になる。
- **FE-AC-004-08** WHEN 署名日が明日の署名を確認する THEN 400 になり、旧い署名は `CONFIRMED` のままである。
- **FE-AC-004-09** WHEN scheduler を止めている間に失効した署名を差し替えて確認する THEN それを要する開放単位は `AUTO_CLOSED`（`SIGN_OFF_EXPIRED`）になり、開き直すには申請と承認が要る。
- **FE-AC-004-06** WHEN digest の内容が署名の中で閉じている種別（NF07 の組・NF06 の admission）で、正規形から計算した値と入力した値が違う THEN 登録・確認は 400 で拒否される。NF05 の DG-05 は登録の時に不一致を画面に出し、評価の時に不成立とする。
- **FE-AC-004-07** WHEN 新しい `FileKind` を `/api/files` で upload する THEN 400 で拒否される。

### FE-RQ-005: 条件の継続評価と自動閉鎖

- 条件は次の時に評価する: 起動時（port を開く前に、開いている開放単位の全てを）、定期（既定 5 分ごと。加えて JST 0:00 の直後）、変更時（署名の確認・取消し・失効、親の閉鎖、設定の変更、申請・承認）。
- 定期の評価は `@Scheduled` と `@SchedulerLock` で行い、method は `void` を返す（CLAUDE.md。`ScheduledMethodsHaveSchedulerLockTest`）。scheduler が無効（`app.scheduling.enabled=false`）の間も、有効期限による閉鎖は snapshot の期限で実効値に反映する（hot path で日付を比べる）。
- 条件が成り立たない（`UNSATISFIED`）と分かったら、その開放単位だけを直ちに実効値で閉じ、`AUTO_CLOSED`（理由の code）に遷移し、`recordDomainEventRequired` で監査し、管理者へ通知し、WARN log（`errorCode=FEATURE_AUTO_CLOSED`、機能と理由の code だけ）と metric を出す。他の開放単位と app は動き続ける（D-4）。
- 評価できない（`UNVERIFIABLE`。評価器の例外と、目録が「評価できない類」とする code。FE-RQ-001）時は、直ちに一時の閉鎖で実効値を閉じる。一時の閉鎖は開放状態の行の `unverifiable_since` に書き、snapshot で全ての instance が同じに閉じる（DB に書けない時はその instance の memory でだけ閉じる）。評価できない状態が既定 3 回の定期の評価の間（`unverifiable_since` から評価の間隔の 3 倍）続けば `AUTO_CLOSED`（`EVALUATION_UNVERIFIABLE`）にする。評価が成り立てば `unverifiable_since` を消す（判断点 J-10。統括の決定で (a) に 3 項を加えた）。
- 自動閉鎖を決めたが DB に書けない（backup の静止・監査の失敗等）時は、その開放単位を「自動閉鎖の書込み待ち」の一時の閉鎖にし、次の周期では条件の成否に関係なく書込みを再試行し、書けるまで閉じたままにする（条件が戻っても承認なしに開かない）。書込み待ちは instance の memory にあり、再起動で失われる。そのため書込み待ちの間は WARN `errorCode=FEATURE_AUTO_CLOSE_PENDING` を毎周期出し、RB に「DB に書けない間は再起動しない。再起動した場合は、起動の後に WARN が出ていて `AUTO_CLOSED` が書かれていない開放単位を手動で閉じる」を書く。
- **機能の条件では app の起動を止めない**。§7 の「機能を閉じる」に挙げた起動時検査は、prod でも例外にせず、該当の開放単位を閉じる。機能の開放と関係しない検査と鍵の fail-fast は残す（§7 の「残す」）。
- 自動閉鎖の後に条件が戻っても自動では開かない（D-4）。**例外（2026-11-23 に利用者が確認した。§2 の D-10）**: 副入口（hold 開始・REL・REG）は DB の開放状態を持たず、条件が戻れば自動で開く（J-03 の結果）。理由: 副入口の条件の署名は既に二人の確認を経ており、外れる原因の多くは trust 文書の鮮度のような一時の状態である。自動で戻るのは技術の条件だけで、署名に依る回復は新しい署名の二人の確認を要する。副入口の閉鎖と再開は履歴に残し、管理者へ通知する。
- 起動時・定期の評価は、開放の遷移の前提（NF05 outbound の barrier の OFF の記録）を判定しない。
- 開放の時だけの条件（NF02 の全社共通カレンダー。D-9）は、起動時・定期・変更時の評価で外れていても自動閉鎖も一時の閉鎖もしない。暦の有効期間の終わりの既定 30 日前から毎日 1 回、監査（`recordDomainEventRequired`）・管理者への通知・WARN で警告し、暦が切れた後も毎日警告する。暦が無い間の SLA の計算は現行どおり（WARN `missing_calendar`、土日だけを休日とする。`ServiceSlaCalculator.isNonWorkingDay` :314-329）。

受入基準:

- **FE-AC-005-01** WHEN 開いている開放単位の自動検査が成り立たなくなる（例: privacy-admin の有効な管理者が 1 名になる、単一法人でなくなる、Journal の appender 権限が無くなる）THEN 次の定期の評価で、その開放単位だけが `AUTO_CLOSED` になり、他の開放単位の実効値は変わらない。
- **FE-AC-005-02** WHEN prod の起動で、開いている開放単位の条件が成り立たない THEN app は起動し、その開放単位は `AUTO_CLOSED` になり、起動の log と監査に理由の code が残る。
- **FE-AC-005-03** WHEN 評価器が例外を投げる、または Journal に一時的に届かない（`journal.unreachable`）THEN その開放単位の実効値は直ちに全ての instance で閉じ、開放状態は 3 回の評価の間続くまで `OPEN` のままで、届けば戻る。
- **FE-AC-005-06** WHEN 自動閉鎖の書込みが backup の静止で失敗した後に条件が戻る THEN 開放単位は閉じたままで、静止の解除の後に `AUTO_CLOSED` が書かれる。
- **FE-AC-005-04** WHEN `@SchedulerLock` の method を走査する THEN 本機能の scheduler は `void` を返し、`ScheduledMethodsHaveSchedulerLockTest`・`SchedulerLockProxyInvocationTest` が通る。
- **FE-AC-005-05** WHEN 開いている NF02 の全社共通カレンダーの有効期間が切れる THEN NF02 と子は開いたままで、期限の 30 日前から毎日の監査と管理者への通知があり、SLA は WARN `missing_calendar` を出して土日だけを休日として計算される。WHEN 当年度の暦が無い時に NF02 を申請・承認する THEN 409 になる。

### FE-RQ-006: 実効値の判定と反映

- `FeatureSwitches#isEnabled` の値（実効値）は、prod では DB の開放状態から作る snapshot で決める: 総スイッチが許可、開放状態が `OPEN`、親の実効値が開、有効期限（その開放単位が要する署名の最も早い失効日。要る署名が 1 つでも無ければ閉）の前、一時の閉鎖が無い、の全てが成り立つ時だけ開。既存の `FeatureRuntimeCondition` も引き続き掛ける。
- filter・ControllerAdvice・scheduler・service 層の gate は今のまま `FeatureSwitches` を呼ぶ。hot path は memory の snapshot を読むだけで、DB に往復しない。`SystemConfigServiceImpl.getString`（`synchronized`）を使わない。
- 反映の速さ: 変更した instance では commit の後（同じ要求の応答の前）に snapshot を作り直す。他の instance は世代を既定 5 秒ごとに読み、変わっていれば作り直す。世代が変わらなくても、読めた時刻を更新する。最後に読めた時刻から既定 60 秒を超えたら、全ての開放単位を一時の閉鎖にする（fail-closed）。現在の配備は単一 instance（SRE-5）。
- 起動時の最初の snapshot は、`FeatureSwitches` と起動時検査（`@PostConstruct`・`SmartInitializingSingleton`）が読む前に読み込む（読めなければ全て閉で、WARN を出す）。
- 画面と API は DB の状態に加えて、実効値と閉じている理由（一時の閉鎖・snapshot の古さ・期限の経過で自動閉鎖の書込みの前・この起動では閉）を返す。
- 既存の OFF の時の挙動（filter の 404 の本文・header、menu の非表示、scheduler の no-op、子の gate の 400）は変えない。
- 実効値が変わるたびに、`[機能スイッチ] 状態: …` の INFO log を出す（`logStates` は起動時の 1 回から、変化のたびへ）。

受入基準:

- **FE-AC-006-01** WHEN 承認の応答が返る THEN 同じ instance の次の要求は新しい実効値で判定される。
- **FE-AC-006-02** WHEN filter の hot path を 10,000 回呼ぶ THEN DB の照会は 0 回である（照会の数を数える試験）。
- **FE-AC-006-03** WHEN snapshot の再読込が 60 秒を超えて失敗する THEN 全ての開放単位の実効値が閉じ、metric `ses_feature_enablement_snapshot_age_seconds` が閾値を超え、再読込が成功すると DB の状態どおりに戻る（DB の状態は変わらない）。
- **FE-AC-006-04** WHEN 開放単位が閉じている THEN `FeatureSwitchFilterTest`・`FeatureSwitchMenuAndViewTest`・`FeatureSwitchSchedulerGateTest` の既存の期待どおりの応答になる。

### FE-RQ-007: 緊急の総スイッチと旧い設定

- 配備の設定に総スイッチ `FEATURE_ENABLEMENT_ALLOWED`（property `app.feature-enablement.allowed`、既定 `true`）を置く。値は文字列の `true` / `false` だけで、それ以外（`yes`・`on`・`1` 等の Spring が真偽値として受け付ける値を含む）は起動を止める（設定の誤り。原文の文字列で検査する）。secret ではなく、task definition の `environment` に置く。総スイッチ以外の `app.feature-enablement.*` の値（反映・評価の間隔、古さの上限、源）は既定値と範囲の検査を持ち、prod の配備の設定（正規名の環境変数・`SPRING_APPLICATION_JSON`・system property）では設定させない（D-5）。
- `false` で起動すると:
  - 最初の要求から全ての開放単位の実効値を閉じる（NF05 は起動時の値も閉じる）。
  - 起動時の処理で、開いている開放単位と再起動待ちを `AUTO_CLOSED`（`MASTER_SWITCH_CLOSED`）にし、未決の申請を `CANCELLED` で終え、監査し、ERROR log（`errorCode=FEATURE_ENABLEMENT_MASTER_CLOSED`）と gauge を出す。
  - 申請・承認を 409 で拒否する。署名の登録・確認・閲覧はできる（再開の準備のため）。
  - `true` に戻して再起動しても自動では開かない。各開放単位を申請と承認で開き直す（D-4 と同じ扱い。判断点 J-11）。
- 配備の設定から次を廃止する（D-5）: 機能スイッチの変数（`FEATURE_NF*_ENABLED`、`PRIVACY_RETENTION_ENABLED`、`PRIVACY_DSAR_ENABLED`、`INTEGRATION_HUB_PUBLIC_API_ENABLED`・`INTEGRATION_HUB_EXTERNAL_TRANSPORT_ENABLED` とその綴りの変種）、署名の値（`FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF`、`PRIVACY_APPROVAL_*`、`IMPORT_ADMISSION_*`、`INTEGRATION_HUB_INBOUND_DECISION_ID`・`INTEGRATION_HUB_OUTBOUND_DECISION_ID`・`INTEGRATION_HUB_INBOUND_APPROVAL_DIGEST`・`INTEGRATION_HUB_OUTBOUND_APPROVAL_DIGEST`、`PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE`）、公開範囲（`FEATURE_NF02_CUSTOMER_PORTAL_VISIBLE_SINCE`）。Spring の正規名（`APP_FEATURES_*` 等）と `SPRING_APPLICATION_JSON` での指定も含む。
- prod で旧い変数が 1 つでもあれば起動を止める（変数名だけを出し、値は出さない。環境変数・`SPRING_APPLICATION_JSON`・system property を見る）。黙って無視すると、運用者が機能の開閉を誤解するためである（推奨。判断点 J-12）。prod は新規構築で、旧い値を移す処理は作らない。
- dev・test の profile は、従来の property を開放状態の源として残す（FE-RQ-016）。

受入基準:

- **FE-AC-007-01** WHEN prod で `FEATURE_ENABLEMENT_ALLOWED=false` で起動する THEN 開いていた開放単位は全て実効値が閉じ、`AUTO_CLOSED`（`MASTER_SWITCH_CLOSED`）になり、申請と承認は 409、署名の登録はできる。
- **FE-AC-007-02** WHEN prod で旧い変数（例: `FEATURE_NF02_ENABLED`、`PRIVACY_APPROVAL_D01_DECISION_ID`、`INTEGRATION_HUB_PUBLICAPI_ENABLED`）が 1 つでもある THEN 起動が止まり、message に変数名だけが出る。
- **FE-AC-007-03** WHEN `validate-task-definition.ps1` で旧い変数を含む task definition を照合する THEN `-Phase Initial`・`-Phase Steady` のどちらでも FAIL になり、`FEATURE_ENABLEMENT_ALLOWED` を secret store に置いても FAIL になる。

### FE-RQ-008: NF05 は次の起動で開き、閉じるのは即時

- NF05 の inbound・outbound の承認は `OPEN_PENDING_RESTART` にし、画面に「再起動待ち」と承認の日時を出す。
- 起動の時、Spring の bean を作る前に、DB の開放状態・確認済みの DG-05 の署名・総スイッチを読み、NF05 の条件を評価し、成り立つ時だけ NF05 の property を開の値にする（`design.md` §9.1）。条件の判定は、評価器と同じ「普通の値を入力にする純粋な関数」を boot resolver と評価器が共用する（写しを持たない）。既に `OPEN` の outbound は barrier の行の tenant の指紋だけを見る（OFF を要求しない）。`OPEN_PENDING_RESTART` の outbound は、加えて行が OFF で `NF05_OUTBOUND_OFF` の監査があることを要する。
- 起動の後、`OPEN_PENDING_RESTART` を、評価が成り立てば `OPEN`（`RESTART_APPLIED`）、成り立たなければ `AUTO_CLOSED`（理由の code）にして監査する。DB を読めなければ NF05 は閉じたまま起動し、状態は変えず、画面に「この起動では閉」と出す。
- 閉じる変更（手動・自動・総スイッチ）は即時に反映する: 実行時の NF05 の判定（§1 の NF05 の全ての読取り）を「起動時の値 AND 実効値」に改める。outbound を閉じる transaction は、barrier の行を `v1|OFF|0|<指紋>` に書き直し `NF05_OUTBOUND_OFF` を監査する（次に開く起動で再び隔離するため。現在の kill switch は行を書き直さない）。
- NF05 outbound の閉鎖を 2 種類に分ける:
  - **(i) 一時の閉鎖**（開放状態は `OPEN` のまま、評価できない状態（`unverifiable_since`・この instance の memory）か snapshot の古さだけで実効値が閉じている）: producer は outbox への書込みを続け（起動時の値・最後に読めた開放状態が `OPEN`・総スイッチだけを見る）、worker は claim を止めるだけで、処理中の配信は DLQ にせず PENDING へ戻す。解ければ同じ process で送信を再開し、閉じていた間の変更も送る（at-least-once を保つ）。barrier の行は `ON` のままで、隔離は要らない（OFF の期間の配信を作らないため）。
  - **(ii) 確定の閉鎖**（`AUTO_CLOSED`・手動の閉鎖・総スイッチ。DB に書けない間の自動閉鎖の書込み待ちを含む）: 現在の kill switch と同じ（producer は積まない、未送信は PENDING のまま、処理中は DLQ `KILL_SWITCH`）で、barrier の行を OFF に書き直す。同じ process では開き直さず、開き直しは `OPEN_PENDING_RESTART` → 再起動 → 隔離を経る。
- inbound は (i)・(ii) のどちらでも実効値が閉じている間は 404（client が再試行する。SES 側に失われるものは無い）。
- NF05 の保存期間の purge（retention・nonce の purge、read snapshot の purge）は、NF05 を閉じた後の起動でも動かす。NF05 を一度でも開いたか、対象の表に行があれば、boot resolver が purge 用の property を開にする（個人データを無期限に残さない）。
- outbound の barrier の条件を 2 つに分ける: (a) 継続の条件: barrier の行の tenant の指紋が `sha256(現在の bound-tenant-id)`（phase は問わない）。(b) 開放の遷移の前提: 行の phase が `OFF` で `NF05_OUTBOUND_OFF` の監査がある（現在 RB §1.7 で DBA が SQL で確かめていること）。(b) は申請・承認と、`OPEN_PENDING_RESTART` を反映する起動でだけ判定する。開いた後に barrier が `QUARANTINING`・`ON` へ進むのは正常で、(b) で閉じない。全ての起動で同じ `INTEGRATION_HUB_BOUND_TENANT_ID` を設定する規則は変えない。
- outbound が閉じている間の prod の `provider.mode` の規則（現在は MOCK だけ。`IntegrationHubProductionEnablementPolicy:58-60`）を、MOCK か HTTPS に改める（承認の前に HTTPS と承認の内容を設定できるようにするため。transport の bean は outbound が閉じていれば作られない）。
- `LegalEntityReadinessStartupValidator` の NF05 の部分は、起動を止めず NF05 の両方向を自動閉鎖する。判定は port を開く前（`FeatureEnablementStartupEvaluator`）に行う（`ApplicationReadyEvent` では port を開いた後になるため）。management copilot の部分は変えない。

受入基準:

- **FE-AC-008-01** WHEN NF05 outbound を承認する THEN 状態は `OPEN_PENDING_RESTART` で、再起動の前は worker・scheduler・producer が作られず、画面に「再起動待ち」と出る。
- **FE-AC-008-02** WHEN 再起動する THEN 条件が成り立てば outbound の bean が作られ、barrier が隔離から READY へ進み、状態が `OPEN`（`RESTART_APPLIED` の監査）になる。続く定期の評価の後も `OPEN` のままで、もう一度再起動しても `OPEN` のままで、隔離は走らない。条件が成り立たなければ app は起動し、outbound の bean は作られず、状態は `AUTO_CLOSED` になる。
- **FE-AC-008-06** WHEN 開いていた NF05 が `AUTO_CLOSED` になった後に再起動する THEN retention・nonce・read snapshot の purge の scheduler は作られ、保存期間を過ぎた行を消す。
- **FE-AC-008-03** WHEN 開いている outbound を確定の閉鎖で閉じる THEN 次の worker の tick から送信しない（未送信は保留、claim 済みは DLQ `KILL_SWITCH`）。barrier の行は `v1|OFF|0|<指紋>` になり、開き直しは再起動と隔離を経る。
- **FE-AC-008-07** WHEN outbound が一時の閉鎖（評価できない状態）になり、その間に業務の変更が起き、その後に評価が戻る THEN 閉じていた間の変更の配信が戻った後に送られ、閉じた時に処理中だった配信は DLQ にならず PENDING へ戻って送られる。
- **FE-AC-008-04** WHEN 開いている inbound を閉じる THEN 次の要求から `/external-api/**` は 404（`DISABLED_DENY`）になる。
- **FE-AC-008-05** WHEN 起動時に DB を読めない THEN NF05 の property は閉の値で、app は起動する。

### FE-RQ-009: NF07 保持・保全を実行時に開閉できるようにする前提

- NF07 の 5 箇所の直接の読取り（§1）を、開放状態（`FeatureSwitches` か開放状態の snapshot）経由にする。
- 決定 ID の束縛（`privacy.approval.*`）と DSAR の `due-days`・`package-max-age` を、確認済みの署名から読む部品（`PrivacyApprovalSource`）に置き換える。読む側（`PrivacyDecisionBinding.evaluate` の呼出し元、`PrivacyHoldDecisions`、`PrivacyDsarDecisions`、`PrivacyPolicyRegistrationService.readiness`、`PrivacyProductionConfigurationValidator`、`PrivacyDecisionWindowMonitor`、`ThirdPartyRedactor`（D-06 の決定 ID を読む）。NF05 の `IntegrationHubApprovalDigest` も同じく決定 ID を properties から読まない）は毎回この部品を読む。承認の値の properties の getter を property の源だけが呼ぶことを静的な試験で固定する。
- `PrivacyDecisionWindowMonitor` の cache の key に、snapshot の世代と開いている capability を加える（業務日だけの key では、開いた日・署名を差し替えた日に旧い判定が残る）。
- `PrivacyJournalStartupVerifier` の結果を 2 つに分ける: (a) hold の効力を確かめる結果（hold gate が使う）: reader・appender のどちらの principal でも可で、現在と同じく起動時に検査し、再検査は成功した時だけ更新する（開閉・principal の種類・再検査の一時の失敗で UNAVAILABLE にならない）。(b) 開放の条件の結果: appender の権限を要する。再検査の method を持ち、承認の時と定期の評価で使う（間隔の下限を置く）。hold の開始（append）は (b) で判定する。Journal の設定（接続）は起動時に要るため、設定の追加は再起動を要する（基盤の準備）。
- `PrivacyTrustStartupValidator` は flag に関係なく trust 文書の構造を検査し、結果を副入口（REL・REG）の条件に使う。起動は止めない。
- 決定の期間による閉鎖は、その開放単位が要する決定だけで判定する。保持・保全は D-01・D-09、DSAR は D-01・D-06・D-09。D-06 の内容は D-09 の digest に入るが、D-06 の失効は DSAR だけを閉じる（D-4）。
- privacy-admin の group・人数・他 group の許可行の検査（`PrivacyProductionConfigurationValidator` の `validatePrivacyAdminGroup` :221-233、`validatePrivacyAdministrators` :240-282、`validateNoPrivacyAllowOutsidePrivacyAdmin` :284-301）は、評価器が同じ method を呼んで再利用する（`AdministratorStartupConditions` と同じ形）。

受入基準:

- **FE-AC-009-01** WHEN 同じ JST の日の中で NF07 保持・保全を開く THEN 承認の直後から入口が開く（翌日まで待たない）。WHEN 保持・保全が開いている日に DSAR を開く THEN D-06 の検査を通った時だけ DSAR が開く。
- **FE-AC-009-02** WHEN Journal の principal が reader の権限しか持たない THEN 保持・保全は申請・承認できず、開いていれば自動閉鎖する。hold gate は AVAILABLE のままで、文書の処分・retention の purge・孤児 file の掃除は既存の hold を守ったまま動く。
- **FE-AC-009-04** WHEN 保持・保全と DSAR が開いている時に D-06 が失効する THEN DSAR だけが `AUTO_CLOSED` になり、保持・保全は開いたままである。D-06 の行が `EXPIRED` になった後の次の定期の評価でも、D-06 を 1 件だけ `REVOKED` にした後でも、保持・保全は開いたままで DSAR は閉じている。
- **FE-AC-009-03** WHEN `src/main` を走査する THEN `getRetention().isEnabled()`・`getDsar().isEnabled()` を呼ぶのは開放状態の源（property の源）だけである（`PrivacyFeatureFlagReadGuardTest`）。

### FE-RQ-010: NF02 の公開範囲（`visible-since`）の変更も二人の承認で行う

- 顧客ポータルの問い合わせの公開範囲（`app.features.nf02-customer-portal.visible-since`）は顧客に見せる範囲を決めるため、変更は設定の変更の申請（`SETTING_CHANGE`）と、申請者と別の管理者の承認で行う。未設定へ戻す変更も同じ。
- 値の形式は現在と同じ（offset 付き ISO-8601。`PortalServiceRequestVisibilityPolicy`）。不正な値は申請の時に 400 で拒否する（起動は止めない）。
- 申請する値は、確認済みの署名「公開範囲の決定」（CS Owner）の構造化された値と一致することを条件にする。値を設定する（未設定でない）場合は、署名「棚卸し」（CS Owner）も要る。
- `PortalServiceRequestVisibilityPolicy` は値を構築時に固定せず（現在は `final`。:45-52）、snapshot から毎回読む。反映は即時。

受入基準:

- **FE-AC-010-01** WHEN 公開範囲を承認する THEN 次の portal の一覧・詳細・返信・CSAT・添付から新しい範囲で判定される。
- **FE-AC-010-02** WHEN 申請者本人が承認する THEN 403、署名の値と違う値の申請は 409、offset の無い値は 400 になる。

### FE-RQ-011: 画面「機能の開放」

- 管理者専用の画面 `/feature-enablement` を置く。内容: 開放単位の一覧（状態・申請中・再起動待ち・有効期限・最終の評価時刻）、各開放単位の条件（種類ごとの ✓/✗ と理由の文言。鍵は有無だけで値を出さない）、署名の登録・確認・却下・取消し・文書の取得、申請・承認・却下・取下げ・閉鎖、公開範囲の変更、履歴。
- 一覧の条件は、定期の評価の結果（memory。評価の時刻を出す）を表示し、利用者が「再評価」を押した時だけ 1 件をその場で評価する（画面を開くたびに全件を評価しない。Capacity の注意）。その場の評価は表示だけで、状態を遷移させない（閉じるのは定期の評価）。
- 一覧と詳細は DB の状態に加えて実効値と閉じている理由を出す（FE-RQ-006）。
- 閲覧も管理者だけとする（推奨。条件・署名の参照は安全上の構成を表すため。判断点 J-13）。
- 閉鎖と署名の取消しは、有効な管理者全員へ通知する（1 名でできる操作のため、気づけるようにする）。
- 閉鎖は確認の dialog（SweetAlert2）と理由の入力を要する。NF01 二者確認を閉じる時は、二者確認を含む有効な template の件数を警告に出す（FS 4.9.4）。
- 既存の frontend の規約に従う（`templates/feature-enablement/list.html`、`static/js/modules/feature-enablement.js`、`layout:fragment` の `content`・`page-js`、`SES.csrf.header()`、`res.code === 200`、`Toast`、外部の CDN を使わない）。

受入基準:

- **FE-AC-011-01** WHEN 管理者が画面を開く THEN 13 件と各件の条件が出る。WHEN 管理者以外の role が開く THEN 403 になり、menu に出ない。
- **FE-AC-011-02** WHEN 画面の HTML・JS を走査する THEN 鍵の値・digest の全体・署名者の氏名が一覧の応答に含まれない（氏名は署名の詳細だけ）。
- **FE-AC-011-03** WHEN `MessageBundleConsistencyTest`・`StaticAssetLocalityTest` を流す THEN 通り、新しい key は 4 つの bundle で翻訳されている。

### FE-RQ-012: 権限・menu・action

- API root `/api/feature-enablement` を `ActionPermissionResolver.RESOURCE_NAMES`（:13）に resource `feature-enablement` として登録し、画面の path を静的な page の対応（`pageResource` :450）に登録する。
- migration で menu `feature-enablement`（`path_prefix` `/feature-enablement`、`api_prefix` `/api/feature-enablement`）と `t_role_menu` の `管理者` だけの付与を足す。**管理者以外の group に `feature-enablement.*` を seed しない**（CLAUDE.md の resource wildcard＋deny。管理者は `role-admin` の `*` で通る）。
- `AuthorizationServiceImpl.isAdminOnlyAction`（:204-208）に接頭辞 `feature-enablement.` を加え、group の無い利用者の `legacyRoleAllows` でも管理者以外を拒否する。`SecurityConfig` でも両 path を `hasRole("管理者")` に固定する。
- `BootstrapSessionFilter` の許可一覧（:64・:73）には加えない。break-glass の incident の許可 action（`BreakGlassServiceImpl.validateAndSerializeActions` :316-331）では `feature-enablement.*` を拒否する。

受入基準:

- **FE-AC-012-01** WHEN 営業・HR・マネージャー（group あり・なし）が `/api/feature-enablement/units` を呼ぶ THEN 403 になる。WHEN 管理者が呼ぶ THEN 200 になる（`ActionPermissionMatrixTest` に allow-list を足さずに通る）。
- **FE-AC-012-02** WHEN break-glass の incident に `feature-enablement.view` を入れて申請する THEN 400 で拒否される。

### FE-RQ-013: 監査と履歴

- 開放状態・申請・署名・設定の全ての遷移を、同じ transaction の `AuditLogService.recordDomainEventRequired`（:41）で監査する。`method` は 10 文字以下（`FEAT_EN`。`AuditLogMethodLengthGuardTest`）。監査の本文は ID・開放単位・状態・理由の code だけで、鍵・digest の全体・氏名・文書の内容を含めない。
- 同じ内容（ID・開放単位・遷移・理由の code・世代・時刻。氏名と自由記述は含めない）を INFO log `FEATURE_ENABLEMENT_EVENT` にも出し、DB の外（CloudWatch Logs）に残す。PITR の後に、復旧の時点より後の取消し・閉鎖を照合するために使う（FE-RQ-015）。
- 履歴は追記専用の表に残し、mapper は `AppendOnlyMapper`、MySQL では BEFORE UPDATE / DELETE の trigger で更新・削除を拒否する。
- 自動閉鎖・総スイッチの監査は actor を SYSTEM、確認の元を起動時・定期の評価とする。

受入基準:

- **FE-AC-013-01** WHEN 各遷移を行う THEN 監査と履歴の行が 1 件ずつ増え、監査の書込みが失敗すれば遷移も rollback する。
- **FE-AC-013-02** WHEN 履歴の行を更新・削除する THEN mapper は例外を投げ（H2）、MySQL では trigger が拒否する。

### FE-RQ-014: 運用と文書

- `infra/ecs/task-definition.prod.json` の雛形に `FEATURE_ENABLEMENT_ALLOWED=true` を置き、旧い変数の名前を雛形と注記から除く。
- `infra/ecs/offline/validate-task-definition.ps1` は、旧い変数を両 phase で FAIL にし（:316-321 の機能スイッチの名前の照合を置き換える）、`FEATURE_ENABLEMENT_ALLOWED` を `environment` に置くことと値が `true`/`false` であることを確かめる。`-Phase Initial` と `-Phase Steady` の違いは、機能の開閉と関係しなくなる（bootstrap の項目だけになる）。
- `FeatureSwitchDefaultsTest` を改める: prod で環境変数による ON を検査する case を、prod で旧い変数を拒否する case に置き換える。test profile は全て開のまま。
- FS を「system の中で開く手順」へ書き直す（1 の一覧の環境変数の列、3 の手順、4 の各機能の ON の条件と手順、6 の試験）。
- RB を改める: §1.0 に総スイッチと新しい起動時検査の行、`FEATURE_ENABLEMENT_*` の運用値の行を足し、「ONにした時だけ必須になる設定」から署名の値を除いて鍵だけにする。§1.7 は「go-live の前提は基盤と中核だけ。各機能の署名は system の中で開く前提」に書き直す。§1.7 の「ON の起動までの順序と準備」のうち、自動検査に置き換わるもの（NF05 の OFF の記録、privacy-admin の人数、単一法人、全社共通カレンダー）は条件の表示で確かめる手順へ改め、基盤の準備（NF07 の Journal instance と専用 Flyway job、NF05 の topology の設定）は残す。
- PITR の後の手順（FE-RQ-015）を `ops/backup/runbooks/restore-cutover.md` に書き、`dba-prelaunch-draft.md` §1 の `CONTENT_HASH_TABLES` の候補 B に `t_feature_enablement_state`・`t_feature_sign_off`・`t_feature_sign_off_extension`・`t_feature_sign_off_bundle`・`t_feature_sign_off_bundle_member` を足す（`.kiro/specs/production-launch/dba-prelaunch-draft.md`）。
- DSAR が閉じた（自動閉鎖を含む）後も受付済みの請求の法定の期限は進むため、閉じている間に未完了の請求があれば privacy-admin へ毎日通知し（件数と最も近い期限日だけ）、RB に対処を書く。
- 文書の更新の範囲は `design.md` §17 に一覧する（RB の trigger・表の件数が出る全ての箇所、`infra/ecs/README.md`、各 spec の ON の手順、`application-prod.yml` の機能スイッチの placeholder の削除を含む）。
- CLAUDE.md の NF01・NF07 の記述（起動を止める gate の説明）は本 batch の後に統括が改める（worker は CLAUDE.md を変えない）。

受入基準:

- **FE-AC-014-01** WHEN 雛形を `-Mode Template` で照合する THEN PASS する。WHEN 旧い変数を 1 つ足す THEN FAIL する。
- **FE-AC-014-02** WHEN `DeployRunbookMigrationCountTest` と `ProductionSchemaVersionGuardTest` を流す THEN V203 の件数で通る。

### FE-RQ-015: 非機能

- 性能: filter・ControllerAdvice の判定は memory だけで行う（FE-AC-006-02）。世代の poll は 1 行の照会を既定 5 秒ごと。定期の評価は既定 5 分ごとで、評価器ごとの照会の数を設計に書く（`design.md` §7.3）。
- 既存の OFF の挙動を変えない（FE-RQ-006）。
- 単一 instance を前提にするが、複数 instance でも安全側（閉）へ倒れる（snapshot の古さで閉じる。評価できない状態は `unverifiable_since` で共有する）。自動閉鎖の書込み待ちの一時の閉鎖だけは instance の memory にあり、DB に書けない間は他の instance へ伝わらない（同じ DB に書けないため、他の instance でも開く遷移はできない）。
- **PITR（backup からの復旧）の後**: 開放状態・署名・barrier の行が復旧の時点へ戻るため、復旧の後の最初の起動は総スイッチを閉（`FEATURE_ENABLEMENT_ALLOWED=false`）で行い（restore の必須の checklist の項目にする）、全ての開放単位を `AUTO_CLOSED`（`MASTER_SWITCH_CLOSED`）にし、NF05 outbound の barrier を OFF の起動で OFF にする。復旧の時点より後の署名の取消しと、手動・自動の閉鎖（開放単位と理由）を、DB の外に出した履歴の log（`FEATURE_ENABLEMENT_EVENT`）から一覧にして確かめ、取消しを取り消し直してから、総スイッチを開に戻し、各開放単位を二人で開き直す（安全のための閉鎖を承認なしに戻さない）。この log の保存期間は PITR で戻れる期間以上にする。
- 時刻: JST の業務日で有効期限を判定する（`Clock` を注入する）。DB の時刻の列は JVM の時計で書く（`AutoFillMetaObjectHandler.rowTimestamp(clock)`）。

### FE-RQ-016: dev・test の profile と既存の試験

- dev・test の profile は、開放状態の源を property のまま（`app.features.*`・`privacy.*.enabled`・`integration.hub.*.enabled`）にする。test profile の「全て開」は変えず、既存の試験の意味を保つ。
- `app.feature-enablement.source=database` で、dev・test でも DB の源を使える（本機能の H2 の試験と local の Demo のため）。prod では `database` 以外を起動の時に拒否する。

受入基準:

- **FE-AC-016-01** WHEN 既定の高速 suite を流す THEN 既存の機能スイッチの試験は変更なしで通る（prod の環境変数の case を除く）。
- **FE-AC-016-02** WHEN prod で `app.feature-enablement.source=properties` を指定する THEN 起動が止まる。

### FE-RQ-017: 個人データ（署名者の氏名・文書）

- 署名者の氏名は任意とし、役割（例: Privacy Owner・CS Owner・SRE）を必須にする。一覧の応答に氏名を出さない。
- 氏名・文書（氏名・署名を含み得る）・元の file 名は、署名が失効・取消し・差替えになってから既定 3 年で消す（氏名と元の file 名は NULL、文書の実体と metadata を削除し、SHA-256・参照・日付・役割は残す）（判断点 J-14。開放の根拠の証拠に 3 年を超える法定の保存が要るかを、確認の時に合わせて確かめる）。消す処理は CLAUDE.md の規則どおり `PrivacyHoldPathGuard` を通す（保全 hold の対象なら消さない）。
- 新しい data element（署名者の氏名・署名の文書）を NF07 の PII inventory と data element catalog に登録する（`m_pii_data_element`。分類・保持期間は D-02 の署名で決まるまで UNKNOWN。V198 と同じ扱い）。

受入基準:

- **FE-AC-017-01** WHEN 失効から 3 年を過ぎた署名がある THEN 定期の処理が氏名を消し文書を削除し、hold の対象なら何もしない。
- **FE-AC-017-02** WHEN 一覧の API を呼ぶ THEN 応答に氏名が含まれない。

## 6. 開放単位の目録

列の意味:

- **自動検査**: system が判定する条件。出処は現在の起動時検査・gate の class と method。「現在」は `88b31d45` の挙動。
- **署名**: 登録と確認が要る署名（種別 code・署名者の役割・有効期限の上限）。出処は RB §1.7 の「機能スイッチをONにする前に要る署名・受理」の表（以下「署名表」）と FS。どれも**未取得**。
- **鍵**: 配備の設定に要る secret。無い・形式が違えば、その開放単位の条件が成り立たない（app は起動する）。「常時」は機能の開閉と関係なく起動時に必須（§7 の「残す」）。
- 有効期限の上限は、決定の類（参照が決定 ID の種別）は 366 日、証拠の類（受理・実測・review の PASS・手動の確認）は 3 年（J-08 は (b)。暫定で、最終は各 owner と Privacy が確認する）。各表の種別の後の【決】【証】で類を示す。

### 6.1 NF01 本体（`NF01`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | なし（現在も起動時検査は無い） | RB §1.7「初回リリースのスイッチ」の NF01 本体の行 |
| 署名 | `NF01_PLAN_SECURITY`【決】（Identity/Security）、`NF01_PLAN_SRE`【決】（SRE）、`NF01_PLAN_DOCUMENT_OWNER`【決】（Document Owner）、`NF01_PLAN_HR`【決】（HR）、`NF01_PLAN_DBA`【決】（DBA）: NF01 細粒度 epoch 計画 §10 の署名。`NF01_STAGING_VERIFICATION`【証】（production owner）: lifecycle runbook §6.4 の staging の実測と §6.6 の R-1〜R-3。`NF01_RUNNER_EVIDENCE`【証】（技術責任者）: M6・M10・`AssetMySqlIntegrationTest` の 5 回以上の Runner の実測 | 署名表の NF01 本体の行 (1)〜(4) |
| 鍵 | なし | ― |
| 親・子 | 子: NF01 二者確認 | `Feature.java:47` |

### 6.2 NF01 二者確認（`NF01_DUAL_CONFIRMATION`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | (a) 署名 `NF01_DUAL_PRIVACY` の参照が `LifecycleDualConfirmationReleaseGate.acceptable`（:86-97）を満たす（現在は prod の起動時に `FEATURE_NF01_DUAL_CONFIRMATION_PRIVACY_SIGN_OFF_REF` を検査し、違えば起動を止める :68-80）。(b) 証跡種別の許可値の外の行が 0 件（FS 4.9.5 の (1)(2)。現在は人が SQL で確かめる。4.9.3 の1） | `LifecycleDualConfirmationReleaseGate`、FS 4.9.2・4.9.5 |
| 署名 | `NF01_DUAL_PRIVACY`【決】（Privacy Owner。comment 2 列の保存期間・削除と匿名化の経路・表示範囲が role 級・代理提出の差戻し理由の運用）、`NF01_DUAL_MANUAL_CHECK`【証】（production owner。RB §1.7.1 の 9 項目）、`NF01_DUAL_BREAK_GLASS_DECISION`【決】（Security。break-glass の口座で提出・確認できることを許すか）、`NF01_DUAL_RUNNER_EVIDENCE`【証】（技術責任者。M1・M8 の Runner の実測）、`NF01_DUAL_REVIEW_PASS`【証】（技術責任者。W3-21a・W3-21b の PASS） | 署名表の NF01 二者確認の行 (1)〜(7) |
| 鍵 | なし | ― |
| 親 | NF01 | `Feature.java:47` |

### 6.3 NF02 本体（`NF02`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査（**開放の時だけ**。D-9） | 本日を含む当年度の全社共通カレンダー（要員・組織・法人が NULL、状態が `有効`/`ACTIVE`、有効期間が本日を含む）がある（`ServiceSlaCalculator.findCalendarForScope` :366 の 4 段目と同じ条件）。申請・承認の時だけ必須で、開いた後は継続の条件にせず NF02 を自動閉鎖しない。有効期間の終わりの既定 30 日前から毎日、監査と管理者への通知で警告する。暦が無い間は現行の WARN `missing_calendar`（:322）と土日だけを休日とする計算を続ける（現在は起動時検査が無く、実行時の WARN だけ） | FS 4.3、RB §1.7「ON の起動までの順序と準備」 |
| 署名 | `NF02_DG02`【決】（CS Owner。DG-02。B3 の休日の運用事項 H-1・H-2 を含む） | 署名表の NF02 の行 |
| 鍵 | なし | ― |
| 親・子 | 子: NF02 顧客ポータルの問い合わせ | `Feature.java:70` |

### 6.4 NF02 顧客ポータルの問い合わせ（`NF02_CUSTOMER_PORTAL`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | なし（親だけ） | ― |
| 署名 | `NF02_PORTAL_PUBLICATION`【決】（公開の Owner 承認）、`NF02_PORTAL_DG02_AC`【決】（CS Owner。DG-02-A/C）、`NF02_PORTAL_VISIBLE_SINCE_DECISION`【決】（CS Owner。公開範囲を設定するか・その値。構造化された値 `visibleSince`、未設定も値）、`NF02_PORTAL_INVENTORY`【証】（CS Owner。公開範囲を設定する場合の棚卸し。設定しない場合は不要）、`NF02_PORTAL_BROWSER_CHECK`【証】（production owner。起票・CSAT・詳細・返信の modal の実ブラウザの確認） | FS 3.4・4.3.1、署名表の NF02 の行 |
| 設定 | 公開範囲 `visible-since`（FE-RQ-010） | FS 1・4.3.1 |
| 鍵 | なし | ― |
| 親 | NF02 | `Feature.java:70` |

### 6.5 NF03 本体（`NF03`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | なし | RB §1.7 の NF03 の行 |
| 署名 | `NF03_BUSINESS_OWNER`【決】（業務 Owner の承認） | 署名表の NF03 の行、FS 3.2 |
| 鍵 | 資格番号の暗号鍵（RB §1.0 の 19・20。**常時**: `CertificationNumberKeyProviderImpl` が開閉に関係なく起動時に必須） | FS 4.1 |
| 親・子 | 子: NF03 資格番号 | `Feature.java:91` |

### 6.6 NF03 資格番号（`NF03_CERTIFICATION_PII`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | なし（親だけ。鍵は常時の起動時検査） | ― |
| 署名 | `NF03_DG07`【決】（Privacy・Owner。DG-07 の `CERTIFICATION_PII` の保持年数と破棄）、`NF03_EVIDENCE_PRIVACY`【証】（Privacy。証憑文書の確認と、`first-release-scope-confirmation.md` の「0. 前提の変更」の各担当の再確認） | 署名表の NF03 の行、FS 3.4・4.6.1 |
| 鍵 | NF03 と同じ（常時） | ― |
| 親 | NF03 | `Feature.java:91` |

### 6.7 NF04（`NF04`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | なし | RB §1.7 の NF04 の行 |
| 署名 | `NF04_BUSINESS_OWNER`【決】（業務 Owner。利用者向けの文言）、`NF04_SRE`【証】（SRE/Ops。単一 instance・sticky session）、`NF04_PRIVACY`【証】（Privacy。端末内 30 日の保存）、`NF04_SECURITY`【決】（Owner/Security。B-NF04-4、P1-N4-1 の案 A、P1-W309-1 (a)）、`NF04_DEVICE_ACCEPTANCE`【証】（業務の受入担当。実機の受入 S-01〜S-15） | 署名表の NF04 の行 |
| 鍵 | `PWA_CONTEXT_SECRET`（**常時**。RB §1.0 の 17） | FS 4.1 |
| 親・子 | なし | ― |

### 6.8 NF05 inbound（`NF05_INBOUND`。開くのは次の起動）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | `IntegrationHubProductionEnablementPolicy` の credential（:181-207）と inbound（:74-116）の検査。署名 `NF05_DG05_IN` の決定 ID（`DG-05-IN-[0-9]{8}-[0-9]{2}`）と approval digest を、現在の `*.approval.decision-id`・`*.approval.approval-digest` の代わりに使う。digest は `IntegrationHubApprovalDigest.canonicalInbound`（:71-89）と一致。`IntegrationHubExternalApiProperties.validateBoundaries` の public-id-key（:42-46）と DEDICATED_DATABASE・bound-tenant-id（:47-50）。法人の未解決の行が無い（`LegalEntityReadinessStartupValidator` :21-27 の NF05 の部分。起動の後に判定） | RB §1.0「ONにした時だけ必須になる設定」の NF05、DG-05 草案 §1.1 |
| 署名 | `NF05_DG05_IN`【決】（NF05 owner。DG-05-IN の決定。構造化された値: 決定 ID・approval digest）、`NF05_IN_SRE_WAF`【証】（SRE。SRE-2 の遮断を承認済みの送信元だけを通す rule へ置き換えた確認）、`NF05_SRE_LOAD`【証】（SRE。負荷の実測 (d)。outbound と共用）、`NF05_STAGING_WIRE`【証】（SRE。実 wire の TLS・XFF の staging の証跡 (c)。共用）、`NF05_QUIESCE_ACCEPTANCE`【証】（NF05 owner。backup の静止中の 503 の受理 U-6 (g)）、`NF05_CLIENT_NOTICE`【証】（NF05 owner。外部の client への 503 の周知 (h)）、`NF05_DIGEST_RULE_DECISION`【決】（Security/Owner。digest が鍵と期間を持たないことの判断 (e)。共用） | 署名表の NF05 の行 (a)〜(h) |
| 鍵 | `INTEGRATION_HUB_PUBLIC_ID_KEY`、`INTEGRATION_HUB_CRYPTO_CURRENT_KEY_VERSION` と `INTEGRATION_HUB_CRYPTO_KEYS_<VERSION>`、`INTEGRATION_HUB_CRYPTO_CREDENTIAL_HASH_PEPPER` | RB §1.0 |
| 基盤の設定（配備の設定に残す） | `INTEGRATION_HUB_BOUND_TENANT_ID`、許可する client の CIDR、trusted proxies、forwarded-header、承認済み provider、未認証の監査の上限（どれも DG-05-IN の digest の内容） | `IntegrationHubApprovalDigest:71-89`、J-01 |
| 親・子 | なし（outbound と独立） | ― |

### 6.9 NF05 outbound（`NF05_OUTBOUND`。開くのは次の起動）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | credential（:181-207）と outbound（:119-157）の検査。署名 `NF05_DG05_OUT` の決定 ID（`DG-05-OUT-[0-9]{8}-[0-9]{2}`）と digest（`canonicalOutbound` :96-115。truststore の SHA-256 を含む）。`provider.mode=HTTPS`、truststore が読めて 1 MiB 以下。barrier の行の tenant の指紋が `sha256(現在の bound-tenant-id)`（継続の条件。phase は問わない）。**開放の遷移の前提**: 行の phase が `OFF` で `NF05_OUTBOUND_OFF` の監査がある（申請・承認と `OPEN_PENDING_RESTART` を反映する起動だけで判定。現在は RB §1.7 で DBA が SQL で確かめる）。法人の未解決の行が無い | RB §1.7「ON の起動までの順序と準備」、`IntegrationHubOutboundStartupBarrier:72-101` |
| 署名 | `NF05_DG05_OUT`【決】（NF05 owner。構造化された値: 決定 ID・digest）、`NF05_OUT_G3R`【証】（NF05 owner。OUT-7・OUT-9 の正式な証拠）、`NF05_OUT_CHANGE_ACCEPTANCE`【証】（Owner/SRE。truststore・batch-size 等の変更に新しい決定が要る運用の受理 (f)）、共用の `NF05_SRE_LOAD`・`NF05_STAGING_WIRE`・`NF05_DIGEST_RULE_DECISION` | 署名表の NF05 の行 |
| 鍵 | inbound と同じ 3 つと、truststore（`INTEGRATION_HUB_TRUSTSTORE_PATH`・`INTEGRATION_HUB_TRUSTSTORE_PASSWORD`） | RB §1.0 |
| 基盤の設定 | `INTEGRATION_HUB_BOUND_TENANT_ID`（全ての起動で同じ値）、`provider.mode`、送信先の一覧、truststore の種類、batch-size、fixed-delay-ms（どれも DG-05-OUT の digest の内容） | `IntegrationHubApprovalDigest:96-115` |
| 親・子 | なし | ― |

### 6.10 NF06（`NF06`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | (a) 署名 `NF06_ADMISSION` の構造化された値で `ImportAdmissionApproval.evaluate`（:78）が構造的な不足なし・有効期間内（現在は `ImportAdmissionStartupValidator` :52-77 が構造的な不足で起動を止め、時間要因だけなら upload を 403）。(b) 行参照の鍵の状態が使える（`ImportSourceRowReference.state()` :96。現在は `ImportRowReferenceKeyStartupValidator` :38-55 が起動を止める）。(c) `import.metadata.manage` を `role-admin`・`privacy-admin` が許可し、それ以外の有効な group には実効の許可が無い（拒否行の有無ではなく、許可・拒否を評価した結果で判定する。今後の migration で group が増えても、拒否行の付け忘れで NF06 を閉じない）。(d) 単一法人（論理削除されていない組織の法人 ID がちょうど 1 種類。`LegalEntityContextService` と同じ条件） | FS 4.8.2 の #1〜#5 |
| 署名 | `NF06_ADMISSION`【決】（Data Owner。構造化された値: 決定 ID・承認 tenant・署名日・失効日・source code・approval digest。現在の `IMPORT_ADMISSION_*` の 6 項目）、`NF06_PRIVACY_RAW_CSV`【証】（Privacy。raw CSV の保持期間と preview の mask の分類の受容）、`NF06_SRE_LB`【証】（SRE。SRE-4 の idle timeout 130 秒以上と SRE-5）、`NF06_DBA_ACCOUNT`【証】（DBA。全 instance が同じ account、alternating-users を使わない）、`NF06_STAGING_CHECK`【証】（production owner。W3-12 §6 の人手の確認） | 署名表の NF06 の行、FS 4.8.2 の #6〜#10 |
| 鍵 | `IMPORT_ROW_REFERENCE_HMAC_KEY` | RB §1.0 |
| 親・子 | なし | ― |

`ImportJdbcReconnectGuard`（FS 4.8.2 の #7）は開閉に関係なく起動時に検査しており、§7 の「残す」とする。

### 6.11 NF07 保持・保全（`NF07_RETENTION`。副入口: hold 開始・hold 解除（REL）・policy の登録と有効化（REG））

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査（開放単位） | (a) 決定 ID の束縛 D-01・D-09（`PrivacyDecisionBinding.evaluate` :145-191。承認 tenant が設定の tenant と一致、capability `retention` を承認、digest の一致、有効期間内）。(b) privacy-admin group が privacy を許可し拒否行を持たない（`validatePrivacyAdminGroup` :221-233）。(c) privacy-admin の有効な OIDC 管理者 2 名以上（`validatePrivacyAdministrators` :240-282）。(d) privacy-admin 以外の group に `privacy.` の許可行が無い（`validateNoPrivacyAllowOutsidePrivacyAdmin` :284-301）。(e) Journal が設定され、開放の条件の結果（FE-RQ-009 の (b)）が appender の権限で AVAILABLE（`PrivacyJournalStartupVerifier` :130-192 を flag から切り離したもの）。期間は D-01・D-09 だけで判定する（D-06 の失効で閉じない） | FS 4.7.2 の表の 1〜4、RB §1.0「ONにした時だけ必須になる設定」の NF07 |
| 自動検査（副入口: hold 開始） | D-05 が有効（`PrivacyHoldDecisions.evaluate` :47-63。要求ごと。現在どおり 409）、署名 `NF07_D05`・`NF07_D05_OWNER_ACCEPTANCE` が確認済み、Journal の開放の条件の結果が appender | RB §1.7 の NF07 保持・保全の行 |
| 自動検査（副入口: REL） | D-03・D-05 が有効（`PrivacyHoldDecisions.releaseReadiness` :69-83）、解除の trust anchor が 1 件以上 ACTIVE、verifier がある（`PrivacyHoldReleaseService.isOpen` :131-137）、trust 文書の構造に不足が無い（`PrivacyTrustStartupValidator` の検査を flag から切り離したもの） | FS 4.7.7、RB §4.5・§4.6 |
| 自動検査（副入口: REG） | D-01・D-02・D-03 が有効、policy の trust anchor が 1 件以上 ACTIVE、verifier がある、prod（`PrivacyPolicyRegistrationService.readiness` :134-150）、trust 文書の構造 | FS 4.7.6 |
| 署名 | 開放単位: 署名の組「NF07 の決定の束縛」の `NF07_D01`【決】（Privacy Owner。決定 ID・署名日・失効日）と `NF07_D09`【決】（D-09 の署名者。構造化された値: 承認 tenant・承認 capability・approval digest）、`NF07_BRIDGE`【証】（技術責任者。production bridge W-1〜W-12 の接続。B-NF07-1）。副入口 hold 開始・REL（開放単位の条件ではない。外れれば副入口だけが閉じる）: `NF07_D05`【決】（D-05 owner）、`NF07_D05_OWNER_ACCEPTANCE`【証】（D-05 owner。RB §4.4 末尾の受理事項。D15 の (9) を含む）。REL: `NF07_D03`【決】（D-03 owner）、`NF07_TRUST_KMS`【証】（Security・D-03 owner。KMS key と key policy、EFS の書込みの経路と権限、退役した鍵の扱い）、`NF07_TRUST_STAGING`【証】（SRE。staging での実 KMS の署名の検証、mount option と失効の反映の遅れの実測）。REG: `NF07_D02`【決】（D-02 owner。保存期間等の値） | 署名表の NF07 保持・保全の行 (a)〜(e)、FS 4.7.3 |
| 鍵 | Journal の接続 `PRIVACY_JOURNAL_DATASOURCE_URL`・`_USERNAME`・`_PASSWORD`（secret）・`PRIVACY_JOURNAL_JOURNALID`（接続は起動時に要る。追加は再起動を要する） | RB §1.0、§3.6 |
| 基盤の設定 | `PRIVACY_TRUST_KMS_ANCHOR_FILE`・`PRIVACY_TRUST_KMS_MAX_DOCUMENT_AGE`・`PRIVACY_TRUST_KMS_RELOAD_INTERVAL`（trust の source の選択は起動時。`PrivacyTrustConfiguration:29-36`） | FS 4.7.7、J-01 |
| 親・子 | なし（DSAR と独立） | `Feature.java:140` |

D-01・D-09 は保持・保全と DSAR が共用する。DSAR を足すときは capability `dsar,retention` の新しい D-09 に差し替える（FE-RQ-004 の差替え。再起動は要らない）。

### 6.12 NF07 本人請求（`NF07_DSAR`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | (a) 決定 ID の束縛 D-01・D-06・D-09（capability `dsar` を承認。組「NF07 の決定の束縛」に D-06 を含める）。(b) D-06 の構造化された値の `due-days` が 1〜366、`package-max-age` が 1 時間〜366 日、F2 provider が 1 件以上（`validateDsarPrerequisites` :201-219）。(c) 6.11 の (b)〜(d) | FS 4.7.2 の表の 5 |
| 署名 | `NF07_D06`【決】（D-06 owner。構造化された値: 決定 ID・署名日・失効日・`due-days`・`package-max-age`。現在の `PRIVACY_DSAR_DUE_DAYS`・`PRIVACY_DSAR_PACKAGE_MAX_AGE` を含む。J-15）、共用の `NF07_D01`・`NF07_D09`、`NF07_DSAR_HOLD_ACCEPTANCE`【証】（D-05 owner・D-06 owner。受理事項 (8)・(9)）、`NF07_DSAR_SELF_DELIVERY`【証】（D-06 owner。SELF 交付区分 (a)）、`NF07_DSAR_I10`【証】（Privacy。I-10 の受理 (b)）、`NF07_DSAR_REDACTION`【証】（D-06 owner。承認前の確認が使える状態にしてから redaction 規則を設定したこと (c)） | 署名表の NF07 本人請求の行、FS 4.7.2 の人手の確認 (a)〜(e) |
| 鍵 | なし | ― |
| 親・子 | なし | `Feature.java:155` |

`PRIVACY_DSAR_DEADLINE_WARNING_DAYS`（運用の値）は配備の設定に残す。

### 6.13 NF10（`NF10`）

| 種類 | 内容 | 出処 |
|---|---|---|
| 自動検査 | なし。`app.scheduling.enabled=true` でない時は警告だけを出す（定期の配布は scheduler が動くときだけ行う。RB §1.7 の NF10 の行） | RB §1.7 |
| 署名 | `NF10_REPORT_OWNER`【決】（Report Owner。ON の署名、確定版の配布方針、「12回」の数え方、生成・配布の所要時間）、`NF10_SRE`【決】（SRE。alert rule の閾値・評価窓・重要度・通知経路、NTP と許容する時刻差）、`NF10_TZ_POLICY`【決】（Report Owner・SRE。tenant の timezone を変える時の方針）、`NF10_DRAIN_APPROVAL`【決】（SRE/DBA。排水・検出 SQL・復旧の手順草案の承認） | FS 4.5.1 の #1〜#8、署名表の NF10 の行 |
| 鍵 | なし | ― |
| 親・子 | なし | ― |

## 7. 起動時検査の扱い

**残す（機能の開放と関係しない。prod で満たさなければ従来どおり起動を止める）**:

- `ProductionSecurityConfigurationValidator` の全て（OIDC・MFA・session・break-glass・OIDC 管理者 3 名以上・default group・`'*'` を持つ group の `privacy.*` の拒否行）と `ForwardedHeaderProductionValidator`。
- 常時の鍵: `CertificationNumberKeyProviderImpl`、`PwaUserContextService`、`BatchOperationServiceImpl`、`BpCompanyServiceImpl`、compliance の鍵、`AccountingTenantInventoryStartupValidator`（RB §1.0 の 1〜26）。
- `ImportJdbcReconnectGuard`、`BackupQuiesceProductionValidator`、`ProductionSchemaVersionGuard`、`AiProductionSafetyValidator`（NF08）。
- `IntegrationHubProductionEnablementPolicy` の flag に関係しない部分（`provider.mode` が STUB・LOOPBACK でない、loopback の port が空）。outbound が閉じている時の mode の規則は FE-RQ-008 のとおり MOCK か HTTPS に改める。
- `ExternalApiTomcatConfiguration`（AJP と `RemoteIpValve` の拒否。flag に関係なく作られる bean）。
- `LegalEntityReadinessStartupValidator` の management copilot の部分。
- 新設: 総スイッチの値の解釈、旧い変数の拒否、prod の源が `database` であること（FE-RQ-007・FE-RQ-016）。

**機能を閉じる（起動を止めず、該当の開放単位を自動閉鎖する）**:

| 現在の検査 | 開放単位 |
|---|---|
| `LifecycleDualConfirmationReleaseGate.validate`（:68-80） | NF01 二者確認 |
| `ImportAdmissionStartupValidator.validate`（:52-77）、`ImportRowReferenceKeyStartupValidator.validate`（:38-55） | NF06 |
| `PrivacyProductionConfigurationValidator.validate`（:130-173） | NF07 保持・保全、DSAR |
| `PrivacyJournalStartupVerifier.enforce`（:171-192） | NF07 保持・保全 |
| `PrivacyTrustStartupValidator.validate`（:55-79） | NF07 の副入口 REL・REG |
| `IntegrationHubExternalApiProperties.validateBoundaries` の flag に依る部分（:42-50・:92-100）と `IntegrationHubProductionEnablementPolicy` の credential・inbound・outbound の検査 | NF05 inbound・outbound（起動の前の評価で閉の値にする。FE-RQ-008） |
| `LegalEntityReadinessStartupValidator` の NF05 の部分 | NF05 inbound・outbound |
| `PortalServiceRequestVisibilityPolicy` の構築時の解釈の失敗（prod） | 起動時には解釈しない（値は申請の時に検査する） |
