# AI Management Copilot タスク

## 0. 開始gate

**状態: Plan CONDITIONAL PASS（2026-09-01）。F1着手可。実装コードは未着手。**

中央traceabilityのNF-08は`CANDIDATE`である。Owner、approved catalog/roles/provider（`<APPROVED_SCOPE>`）、NF-07、DG-08、`GATE-S17-G10-PROD`は未確定だが、**provisional catalog** と **モデル非依存pipeline** の構築（F1〜M）は開始できる。本番外部AI・`management-copilot-enabled=true`（本番）は禁止。

**対話の正本**

| 用途 | ファイル |
|---|---|
| 開工（S0 / F1〜M） | [start-conversations.md](start-conversations.md) |
| Review（Plan / Task / R-NF08 / SNF横断） | [review-conversations.md](review-conversations.md) |
| 入口・横断Review時の読み方 | [README.md](README.md) |

共通条件:

- 専用worktree `C:\work\ses-manager-pro-ai-management-copilot`、branch `codex/ai-management-copilot`だけを変更する。
- 1 taskの実装・テスト・Demo確認後に、そのtaskだけをcommit/pushする。
- 外部provider、本番send、業務状態更新、LLM SQL、schema送信は禁止。
- `mvn test`、MySQL gate、performance gateは承認後、変更範囲に応じて実行する。Docker不可をgreen扱いしない。
- 各taskのDemoは日本語UI・API、scope、ログ/retention、feature flag OFFを確認する。
- 各task開始時は `start-conversations.md` の該当節を実装対話へコピーする。完了後は必要に応じ `review-conversations.md` の増分Review（§3）を依頼できる。

## 1. Task一覧

### T000: Gate・正本・PII・metricのDiscovery固定（完了）

- [x] **Objective**: 開始時状態、未解決gate、既存AI、PII allow-list、canonical service、DataScope、受入後文書を読み、実装境界を固定する。
- **対象要件**: AI-MC-R1、R5、R6、R8、R9、R10
- **実施内容**:
  - `AGENTS.md`、NF-08/DG-08を含む受入後roadmap/traceability/start/review文書、`platform-invariants`を全文確認する。
  - `ai-feedback-learning`のrequirements/design/tasks/review-ledger、`g10-pii-allowlist.md`、`g10-allowlist.json`を全文確認する。
  - `AiExecutionGateway`、PII masker、AI run/feedback/evaluation、`DashboardService`、`UtilizationForecastService`、`ManagementAccountingService`、`CashFlowForecastService`、`SalesPerformanceService`、`DataScopeService`を確認する。
  - normal checkoutのdirty変更を保全し、専用worktreeのroot/branch/status/remote/baseを検証する。
- **Test requirements**: 実装なし。`git status --short --branch`がclean、`HEAD == merge-base(HEAD, origin/main)`であることを確認。
- **Demo**: `review-ledger.md`のgate表とcompletion matrixで、production codeを変更していないこと、mock/rule限定、flag OFFを示す。
- **Rollback**: spec文書だけをrevert可能。normal checkoutの既存変更には触れない。

### F1: Semantic catalog / run / feedback基盤

- [ ] **Objective**: provisional catalogを固定し、catalog外実行を型とruntimeで拒否し、management answer run/feedbackを既存AI ledgerへ安全に記録する。
- **Blocked until**: Plan Review CONDITIONAL PASS（済）。`<APPROVED_SCOPE>`正式値は未決のため catalog は **provisional / 既定disabled** とする。本番外部送信・flag ONは不可。
- **開工対話**: [start-conversations.md §F1](start-conversations.md)
- **Review対話**: [review-conversations.md §R-F1](review-conversations.md)（任意）
- **対象要件**: AI-MC-R1、R3、R7、R8、R9
- **Implementation guidance**:
  - static immutable catalogを第一候補にし、各queryにparameter/schema/scope/adapter/result/citation/limit/versionを登録する。
  - `MANAGEMENT_COPILOT` use caseを既存`AiExecutionGateway`へ追加する場合も、allowlist、canary、untrusted separation、mock/rule defaultを維持する。
  - runへcatalog/parameter/scope/data/result schema version、provider/model/prompt、cost/latencyをredactedで保存し、raw promptは保存しない。
  - feedbackは既存推薦decisionとmanagement answer feedbackを型分離し、actor/scopeを再認可する。
  - 新規DDLが必要なら公開済みmigrationを編集せず、latest+1を別taskで設計する。
- **Test requirements**:
  - catalog外query、SQL/table/column文字列、unknown query IDを拒否。
  - duplicate trace、0/NULL、巨大result、PII canary、feedback scope外を拒否。
  - mock/ruleでrun metadataのmodel/prompt/data/cost/latencyを検証。
- **Demo**: 承認済みcatalogの一つをmockで実行し、run/feedbackのredacted証跡とcatalog外拒否を画面/APIで確認する。外部egressがないことを確認する。
- **Rollback**: catalog flagをOFFへ戻し、追加artifact/versionをretire。migrationはrollback手順とpurge手順を用意してから適用する。

### F2: Intent / typed parameter / scope / service gateway

- [ ] **Objective**: 質問をtyped parameterへ変換し、DataScope/role/menu認可後にcanonical serviceだけを実行してtyped resultを返す。
- **Blocked until**: F1 PASS、Scope A/Bの受入fixture、canonical service adapter契約（provisional catalogで可）。
- **開工対話**: [start-conversations.md §F2](start-conversations.md)
- **Review対話**: [review-conversations.md §R-F2](review-conversations.md)（任意）
- **対象要件**: AI-MC-R2、R4、R5、R6、R10
- **Implementation guidance**:
  - parserはcandidate queryと不足parameterだけを返し、SQL、repository、table、column、raw filterを型へ入れない。
  - `YearMonth`、期間、月数、asOf、timezone、0/NULL/forecastを明示する。
  - `DashboardService`、`UtilizationForecastService`、`ManagementAccountingService`、`CashFlowForecastService`の既存口径をadapterで再利用する。
  - `SalesPerformanceService`はDataScopeを統合したscoped adapter/overloadができるまでcatalog disabledとする。
  - result envelopeにvalue/unit/period/timezone/freshness/state/basis/scope/source/data versionを付与する。
- **Test requirements**:
  - Scope A（管理者）とScope B（マネージャー）を同一質問で比較し、Bの結果がAの範囲外を露出しないこと。
  - 営業DataScope、empty scope、detail/citation再認可、期間境界、timezone、0/NULL/forecastを検証。
  - 画面・export・AIの同一指標contract testを追加。
- **Demo**: 管理者・マネージャー・営業のtest userで同じ質問を実行し、typed resultと正本API/exportの値・単位・期間が一致することを確認する。
- **Rollback**: query単位のenabledをOFFにしてgatewayから除外。正本serviceの既存画面/exportの口径を変更しない。

### A1: Chat / answer / citation UI

- [ ] **Objective**: typed resultを正本として表示するchat画面を追加し、summaryとcitationを安全に表示する。
- **Blocked until**: F2 PASS、citation route/menu/scope再認可契約（provisionalで可。human escalationはDG-08まで文言のみ）。
- **開工対話**: [start-conversations.md §A1](start-conversations.md)
- **Review対話**: [review-conversations.md §R-A1](review-conversations.md)（任意）
- **対象要件**: AI-MC-R2、R6、R7、R10
- **Implementation guidance**:
  - 既存Thymeleaf + jQuery/Bootstrapのmodule conventionを守る。
  - summary textから数値をparseせず、value/unit/period/timezone/freshness/basis/stateをtyped resultからrenderする。
  - 0/NULL/未確認/forecast/truncatedを専用表示し、citationはsource keyからserverで解決する。
  - 更新操作、自由なdownload、scope外detailをchat responseに実装しない。
- **Test requirements**: 390px desktop responsive、CSRF、session expiry、catalog外、citation再認可失敗、partial citation、巨大result、0/NULLを検証。
- **Demo**: 管理者/マネージャーの画面で同じresultの表示差異を確認し、営業のscope外citationが404相当の安全表示になることを確認する。
- **Rollback**: page menuとflagをOFFにし、既存dashboard/accounting UIを変更前へ戻す。

### B1: Provider / redaction / timeout / cost

- [ ] **Objective**: mock/rule providerで評価可能なsummary gatewayと、PII redaction・canary・timeout・429・invalid JSON・cost上限を固定する。**具体モデル未決定でも`AiTextService`差し替え点を実装する。**
- **Blocked until**: A1またはAPI contractがPASS。外部provider有効化は別承認（本taskでは不可）。
- **開工対話**: [start-conversations.md §B1](start-conversations.md)
- **Review対話**: [review-conversations.md §R-B1](review-conversations.md)（任意）
- **対象要件**: AI-MC-R1、R7、R8、R9
- **Implementation guidance**:
  - `AiExecutionGateway`の既存mask/canary/untrusted data boundaryを使う。
  - mock/ruleは外部egressなし。実provider adapterはfeature flag/gateの二重fail-closed配下に置く。
  - provider responseはschema validationし、unknown claim、HTML、数値再計算、PIIを拒否する。
  - retryはprovider error別にboundedにし、latency/cost/attemptをrunへ記録する。
- **Test requirements**: PII canary、allowlist外、429、timeout、invalid JSON、partial claims、cost上限、provider disabledをmockで検証。実外部送信テストは行わない。
- **Demo**: mock/ruleの正常・異常fixtureを実行し、外部URLへ接続せず、UIがtyped resultを保持したままsummary unavailableになることを確認する。
- **Rollback**: provider flagをOFF、mock/ruleへ戻し、未承認のcredential/configを配置しない。

### B2: Evaluation / adversarial suite

- [ ] **Objective**: 固定匿名datasetとadversarial suiteでmetric、PII、scope、citation、provider failureを評価する。
- **Blocked until**: B1 PASS、評価dataset/version/segment policy（provisional fixture可）。
- **開工対話**: [start-conversations.md §B2](start-conversations.md)
- **Review対話**: [review-conversations.md §R-B2](review-conversations.md)（任意）
- **対象要件**: AI-MC-R8、R9、R10
- **Implementation guidance**:
  - 既存`AiOfflineEvaluationService`、`AiEvaluationMetrics`、artifact version/status/CASを再利用する。
  - adoption、precision@5/@10、latency p95、PII leak、scope leak、citation integrityをdataset/model/prompt/data version付きで記録する。
  - min segment未満はPASSにせず、regression時のpromotion/rollbackを人手承認へ送る。
  - prompt injection、catalog外SQL/schema、0/NULL/forecast、巨大result、429/timeout/invalid JSON/partial citation/PIIをfixture化する。
- **Test requirements**: fast H2、必要なMySQL gate、performance gateをプロジェクトの明示的profileで実行。Docker不足をskip green扱いしない。
- **Demo**: baseline/candidateのmetric、failure reason、dataset/model/prompt/data version、cost/latencyの評価画面またはAPI証跡を確認する。
- **Rollback**: candidate artifactをshadow/retiredへ戻し、activeは既知のmock/rule baselineに固定する。

### M: 統合・production gate・Review handoff

- [ ] **Objective**: 画面/API/export/AIの同一指標とscopeを統合検証し、Reviewへremote Head、plan/spec/tasks、completion matrixを渡す。
- **Blocked until**: F1、F2、A1、B1、B2の全PASS。NF-07、DG-08、`GATE-S17-G10-PROD`、approved owner/catalogは本番有効化gate（M完了でも未完ならCONDITIONAL PASS）。
- **開工対話**: [start-conversations.md §M](start-conversations.md)
- **最終Review対話**: [review-conversations.md §R-NF08](review-conversations.md)（必須。PR gate）
- **対象要件**: AI-MC-R1〜R10
- **Implementation guidance**:
  - production flagはgateの全条件が揃うまでOFF。gate未完なら`CONDITIONAL PASS` handoffとする。
  - taskごとにcommit/pushし、Review用にremote branch/headを確定する。
  - implementation conversationではPRを作らない。独立ReviewがPLAN PASS→IMPLEMENTATION PASSした後だけ別のPR作成主体へ渡す。
  - `review-ledger.md`のcompletion matrix、未解決risk、test evidence、rollbackを更新する。
- **Test requirements**: `verify-like-ci.ps1`相当のfast/mysql/performance/backup gate、metric contract、security/adversarial、retention/purge、flag OFF/ON gate test、手動Demo。
- **Demo**: independent Reviewへ渡すbundleに、final remote Head、base、branch、spec一式、task checkbox、commit一覧、test結果、gate statusを含める。
- **Rollback**: flag OFF、provider mock/rule、active artifact rollback、追加metadata purge、既存AI gatewayの旧routeへ復帰。

## 2. 実装対話の停止条件

次のいずれかに該当したら、推測で前進せず、statusを維持してowner/Reviewへ返す。

- `<APPROVED_SCOPE>`、`<OWNER>`、`<BASE_BRANCH>`の実値がない。
- NF-07、DG-08、既存AI production gateのPASS証跡がない。
- catalog query、role、scope、source citation、retention、cost limit、human escalationが未承認。
- SalesPerformanceのscope契約、canonical metric contract、巨大result上限、provider error contractが未定義。
- mock/ruleだけで再現できないtestを外部providerで代替しようとする要求が出た。
- PR作成、normal checkout変更、業務状態自動更新、LLM SQL生成、schema/PII外部送信が求められた。
