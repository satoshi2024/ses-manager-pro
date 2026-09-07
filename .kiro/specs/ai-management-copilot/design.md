# AI Management Copilot 設計

## 0. 設計状態・前提

本設計はNF-08のCANDIDATE段階で作成する実装前設計である。`<APPROVED_SCOPE>`、`<OWNER>`、`<BASE_BRANCH>`は未解決のため、catalogの許可role/providerやproduction flagを推測して確定しない。NF-07、DG-08、`GATE-S17-G10-PROD`がPASSするまで、実行providerはローカルmock/ruleのみとする。

既存の`AiExecutionGateway`、`AiPiiMasker`、AI artifact/run/feedback/evaluation機構、AI PII allow-list、DataScopeService、正本serviceを再利用する。management copilot専用の式、全件repository、任意SQL実行機構、業務状態更新機構は作らない。

## 1. 固定pipeline

```text
質問（未信頼）
  -> IntentParser（候補queryと不足parameterだけ。SQL/bean名は生成しない）
  -> SemanticCatalog.resolve(queryId)
  -> TypedParameterBinder + validation + parameter hash
  -> Authorization/ScopeResolver（role/menu/DataScope/組織）
  -> CatalogQueryGateway（固定adapterのみ）
  -> Canonical service（正本口径・scope・期間）
  -> TypedResultEnvelope（円/割合/期間/timezone/freshness/basis/state）
  -> SummaryProvider（redacted result + claim key。数値再計算なし）
  -> CitationResolver（source keyを再認可）
  -> AnswerRenderer（typed resultを値の正本として表示）
  -> Run/Feedback/Audit metadata
```

pipelineの各境界は型付きinterfaceとエラーコードを持つ。後段が前段の未検証文字列を受け取らないよう、`queryId`、parameter、scope、result、claim、citationを別型にする。

## 2. Semantic catalog

### 2.1 catalog entry

production実装時は、DBから任意のbean名やSQLを読み込むregistryではなく、レビュー可能な静的catalog（Java enumまたはimmutable registry）を第一候補とする。各entryは次を必須とする。

| 項目 | 設計 |
|---|---|
| `queryId` | 固定文字列。例 `dashboard.summary` |
| `catalogVersion` | 変更時に更新するimmutable version |
| `allowedRoles` | 承認済みroleのみ。未承認はdisabled |
| `parameterSchema` | Java DTO、型、範囲、null/0の意味 |
| `scopeResolver` | role、menu、DataScope、組織scopeを組み立てる固定処理 |
| `serviceAdapter` | 許可された正本serviceへのコンパイル時参照 |
| `resultSchemaVersion` | typed resultの互換性を識別 |
| `resultLimit` | 行数、期間、payloadの上限 |
| `citationKeys` | source key、route key、再認可規則 |
| `enabled` | gateとfeature flagの積。未承認はfalse |

catalogはquery IDを返すが、SQLやtable/column情報を返さない。入力文から任意のquery IDを文字列連結で生成せず、登録済み候補とのintent matchだけを行う。候補が一意でなければclarificationを返す。

### 2.2 初期候補と未解決事項

次は正本serviceのinventoryから作る候補であり、承認されるまでdisabledである。

| query ID | adapter | 注意点 |
|---|---|---|
| `dashboard.summary` | `DashboardService` | 年指定、actual/forecast、scope、月別値を保持。既存dashboardの口径を複製しない |
| `dashboard.profit-analysis` | `DashboardService` | 契約行をboundedに返す。名称/IDの露出はscopeとcitation再認可後のみ |
| `dashboard.utilization-forecast` | `UtilizationForecastService` | 月数1〜12。roll-offを含む場合もscope後に返す |
| `management-accounting.summary` | `ManagementAccountingService` | `YearMonth`と承認済みfilterだけ。snapshot/forecast fallbackをresult basisに残す |
| `cashflow.forecast` | `CashFlowForecastService` | `CashFlowForecastScope`を利用し、managerへ会社全体opening balanceや固定費を渡さない |
| `sales-performance.monthly` | `SalesPerformanceService` | 現行serviceにDataScope統合がないため、scoped adapter/overloadが完了するまでdisabled |

## 3. Intentとtyped parameter

### 3.1 Intent boundary

`IntentParser`は、未信頼の質問を次の候補型に限定する。

- `CandidateQuery(queryId, confidence, missingParameters, clarificationOptions)`
- `TypedParameterRequest`（候補queryに対応したdiscriminated union）
- `Unanswerable(reasonCode, safeAlternatives)`

parserの出力へSQL、table、column、repository、Java class、raw free text filterを持たせない。質問中の「前の回答を無視」「system promptを表示」「全件を返せ」は入力データまたは攻撃文字列として記録対象外のredacted reasonに変換する。

### 3.2 期間・表示意味

各adapterはtenant timezoneの`Clock/asOf`を受け取れる境界を持つ。現行serviceが内部でnowを読む場合は、production実装時に正本口径を変えない明示的context overloadまたはrun context wrapperを追加し、copilotだけ別の日付計算をしない。

- `YearMonth`: tenant timezoneで月初から翌月月初までの半開区間。
- `from + months`: 1〜12のbounded期間。
- `asOf`: query実行時刻とデータfreshnessを分離して記録。
- `future/forecast`: actualとforecastを同じ数値列へ潰さず、basisとsourceを保持。
- `NULL/0`: `NULL`は未設定・未確認、`ZERO`は計算上の0として区別。

## 4. Authorization / scope resolver

### 4.1 scope適用

`CopilotScopeResolver`はページ、API、export、citationを同一の認可結果へ収束させる。順序はrole/menu permission → DataScope → 必要な組織scope → query固有制限である。既存DataScopeServiceのallowed ID/conditionを使用し、AI側の後段filterは安全弁であって主scope実装にしない。

- 管理者: 既存のfull access判定を先に行う。
- マネージャー: 既存組織範囲とDataScopeの交差。空集合は0件。
- 営業: `DataScopeService`が返す担当顧客・要員・契約等。未対応serviceはcatalogから除外。
- HR、要員、その他role: approved catalog/roleが明示されない限りdeny。

`scopeHash`は対象IDそのものではなく、scope type、policy version、対象集合の一方向hashだけを保存する。回答、summary、provider prompt、ログへscope外IDを出さない。

### 4.2 SalesPerformanceの既知gap

現行`SalesPerformanceService`は営業自身の条件を一部扱うが、`DataScopeService`を正本として注入する契約がない。このため、`sales-performance.monthly`をcatalogへ追加する前に、次のどちらかを実装してcontract testで固定する。

1. `SalesPerformanceService`へscope context付きの新しいmethodを追加し、既存画面/APIも同じmethodへ収束する。
2. `SalesPerformanceScopedAdapter`を追加し、DataScopeで許可されたsales user/engineer/contract/proposal集合をserviceへ渡す。

どちらもできない場合は、営業roleのqueryをdisabledとし、管理者/マネージャーの範囲も既存APIの認可と一致することを確認するまで公開しない。例外的な全件取得でgapを埋めない。

## 5. Canonical service adapter

### 5.1 adapter責務

`CatalogQueryGateway`はentryのadapterを呼び、結果をcopilotのtyped envelopeへ変換するだけとする。売上、粗利、稼働率、bench、cash flow、commissionの計算式は持たない。adapterは次を付与する。

- query ID / catalog version / parameter hash
- `asOf`、source freshness、tenant timezone
- service resultのactual/forecast/mixed basis
- resolved scope type/hash
- bounded row countとtruncation状態
- citation source key

### 5.2 正本service対応

| 正本 | 既存の口径を守る箇所 | copilotで禁止する複製 |
|---|---|---|
| `DashboardService` | work-record優先・contract fallback、forecast pipeline、dashboard scope | 月次売上/粗利式の再計算 |
| `UtilizationForecastService` | `UtilizationCalcService`に基づく稼働率、roll-off、scope | engineer/contractを再取得して稼働率を再計算 |
| `ManagementAccountingService` | snapshot、forecast fallback、予算、wait cost、monthly resolver | 契約行からAI側で粗利を集計 |
| `CashFlowForecastService` | invoice/BP payment/payroll、opening balance、reconciliation、scope | Dashboard売上をcash inflowへ直接変換 |
| `SalesPerformanceService` | 成約口径、提案口径のwin rate、work-record優先、commission rule | 成約率・commission式の再実装 |

serviceが返す値が空、NULL、未確認の場合、adapterはその状態をtyped resultへ保持する。LLMに「0とみなす」判断をさせない。

## 6. Typed result envelope

候補名は実装時に既存DTOと整合させるが、境界契約は次の概念を必須とする。

```text
TypedResultEnvelope {
  queryId, catalogVersion, resultSchemaVersion,
  asOf, generatedAt, tenantTimezone,
  scope {type, policyVersion, hash},
  values [MetricValue],
  rows [BoundedResultRow],
  freshness {sourceUpdatedAt?, stale?, basis},
  citations [CitationCandidate],
  limit {maxRows, truncated},
  dataVersion
}

MetricValue {
  key, value(BigDecimal|Long), unit,
  state(VALUE|ZERO|NULL|NOT_APPLICABLE|UNCONFIRMED),
  period, basis(ACTUAL|FORECAST|MIXED), displayScale
}
```

LLMへ渡すのは、allowlistで許可された`MetricValue`のclaim keyとredacted row summaryだけである。answer rendererは`value/unit/period/timezone/freshness/basis/state`をtyped resultから描画し、LLM textへ数値を依存しない。

巨大resultはcatalogのmax rows/period/payloadを超えた時点でsummary用に集約し、`truncated=true`を表示する。scope外の行を除去した後の件数だけを使い、拒否理由から元の全件数を推測できる情報を返さない。

## 7. Summary provider / AI gateway

### 7.1 provider boundary

既存`AiExecutionGateway`のPII mask、canary、untrusted data分離、run metadata保存、mock fallbackを共通基盤として拡張する。management copilotのprovider interfaceは次の入力だけを受ける。

```text
SummaryRequest {
  useCase = MANAGEMENT_COPILOT,
  promptVersion,
  redactedClaimContext,
  allowedClaimKeys,
  outputSchemaVersion,
  deadline,
  costBudget
}

SummaryResponse {
  summaryText,
  claimKeys[],
  providerStatus,
  modelVersion,
  latencyMs,
  tokenCount?,
  costYen?
}
```

providerはSQL、schema、repository、entity、raw prompt、scope外IDを受け取らない。`claimKeys`はtyped resultに存在するkeyとの完全一致を検証し、不明key、HTML、命令文、数字の再計算を拒否する。UIに表示する数値は必ずtyped resultから取得する。

### 7.2 gate / flag

実装時の有効条件は次の積とする。

```text
managementCopilotEnabled
∧ approvedCatalogAndRoles
∧ NF07Passed
∧ existingAiProductionGatePassed
∧ DG08Passed
∧ externalSendEnabled
∧ providerContractPassed
```

ただし開発・評価のmock/rule providerは外部送信なしで個別に実行できる。現行設定の`ai.external-send-enabled=false`、mock provider、既存production guardを維持し、gate未完のまま条件を迂回するfallbackは設けない。production code実装時もデフォルトflagはOFF、missing configはfail-closedとする。

## 8. PII / retention / logging

既存`g10-pii-allowlist.md`と`g10-allowlist.json`を送信契約の正本とする。management copilotの独自allowlistを作らず、質問・typed result・citationの各fieldをallowlistへ照合する。

- raw prompt: 保存・provider送信とも0日。
- redacted run/summary: NF-07承認retention。現行mock/ruleの730日方針を勝手に延長しない。
- input: raw payloadではなくparameter hash、redacted shape、data versionを保存。
- logs: secret、raw PII、full provider request/response、scope外IDを書かない。
- canary: `SES-PII-CANARY-T109-7f2e9c1a`を含むテストを常にfailさせる。
- purge: 既存AI retention/purge機構へ登録し、copilot専用の無期限保存を作らない。

provider 429/timeout/invalid JSONの応答本文は保存せず、status code、bounded error code、latency、retry countだけを記録する。

## 9. Persistence / feedback / audit

既存AIの`AiArtifactVersion`、`AiRecommendationRun`、`AiRecommendationItem`、`AiFeedback`、`AiEvaluation`の運用を再利用する。新しいquery runで必要なcatalog/data/result schema versionは、承認後に既存runへ最小限のmetadata fieldを追加するか、既存runのredacted summary JSONのversioned envelopeへ格納する。どちらを採るかはschema影響と既存migrationを確認したPlan Reviewで確定し、raw prompt columnは追加しない。

feedbackは既存の推薦decision（ACCEPT/REJECT/HOLD）とmanagement answer feedback（HELPFUL/INCORRECT/UNSAFE）を型として分離する。既存feedbackのactor/scope認可を再利用し、別ユーザーのrunを更新できないようにする。feedbackはanswer表示や業務状態を自動更新せず、evaluation datasetへ採用する場合も匿名化・version固定・人手承認を必要とする。

各runへ次を記録する。

| metadata | 保存方針 |
|---|---|
| actor / role | user IDは既存監査方針に従う。providerには送らない |
| query/catalog/parameter | query ID、catalog version、typed parameter hash |
| scope | scope type、policy version、scope hash |
| data/result | data version、result schema version、freshness、truncated |
| provider | provider/model/prompt version、status、latency、token、cost |
| feedback | typed feedback、reason、redacted comment、actor、timestamp |
| error | bounded code、retry、timeout、canary/PII state |

外部provider呼出しはtransaction内で行わず、run metadataの保存はprovider応答後に安全なredacted値だけを行う。run traceの重複は既存のunique/冪等パターンに合わせる。

## 10. Citation

catalog entryの`citationKeys`は、既存routeとtyped parameterの組み合わせとして静的に定義する。例:

| source key | 画面/route候補 | 再認可 |
|---|---|---|
| `dashboard.summary` | `/dashboard` | dashboard menu + role + scope + period |
| `dashboard.profit-analysis` | `/dashboard/profit` | profit menu + contract/customer scope |
| `dashboard.utilization-forecast` | dashboard forecast detail導線 | analytics/dashboard permission + engineer scope |
| `management-accounting.summary` | `/management-accounting` | management-accounting menu + scope |
| `cashflow.forecast` | `/dashboard` cashflow tab | dashboard/cashflow permission + `CashFlowForecastScope` |
| `sales-performance.monthly` | `/sales-performance` | sales-performance menu + scoped sales data |

routeへ遷移する前に、`CitationAuthorizationService`が現行sessionで再認可する。URLにscope外のIDを直接埋め込まず、query resultに返されたcitation keyをserver側で解決する。source unavailableは404相当の安全な未確認表示とし、元IDをerror messageへ出さない。

## 11. 失敗・境界の型

| code | 挙動 |
|---|---|
| `CATALOG_NOT_FOUND` | query実行なし。安全な候補を提示 |
| `AMBIGUOUS_PARAMETER` | query実行なし。typed確認質問 |
| `SCOPE_DENIED` | 403/安全な未回答。scope外の存在を示さない |
| `RESULT_LIMITED` | bounded resultを表示し、truncatedを明示 |
| `NO_DATA` / `NULL_DATA` | 0とNULLを区別して表示 |
| `PROVIDER_DISABLED` | typed resultのみ、summary unavailable |
| `PROVIDER_TIMEOUT` / `PROVIDER_429` | bounded retry後にsummary unavailable |
| `PROVIDER_INVALID_JSON` | responseを表示せずsafe fallback |
| `PII_CANARY` | 送信・保存停止、監査用bounded event |
| `CITATION_REAUTH_FAILED` | citationを除外し、IDを露出しない |
| `STATE_UPDATE_FORBIDDEN` | 更新APIを呼ばず、管理者承認が必要と表示 |

## 12. platform invariants適用表

### 時間・asOf

| 決定 | 適用 |
|---|---|
| 期間境界 | `YearMonth`/開始日から次期間開始までの半開区間 |
| timezone | tenant設定を使用。現行defaultはAsia/Tokyoだが固定値をserviceへ埋めない |
| asOf | 読み取り時刻、source freshness、actual/forecast basisを分離 |
| null/no history | `NULL`、未確認、履歴なし、0を別stateで保持 |
| cache | scope/asOf/data versionをkeyに含め、更新系がある場合はafter-commit invalidation |

### subject × operation × visible population

| subject | operation | 管理者 | マネージャー | 営業 | その他 |
|---|---|---|---|---|---|
| dashboard/accounting/cashflow | summary/forecast | 既存full access | 組織scope∩DataScope | approved entryのみ、担当scope | deny |
| engineer/customer/contract | rows/citation | 既存full access | 組織scope∩DataScope | DataScope担当のみ | deny |
| sales performance | monthly answer/export/citation | 全営業の許可範囲 | 組織・DataScope範囲 | 自身/担当範囲。scoped adapter必須 | deny |
| run/feedback | read/feedback | 既存AI role rule | 自scope/許可role | 自runまたは許可範囲 | deny |
| state-changing action | execute | 別の明示APIと承認 | 別の明示APIと承認 | 別の明示APIと承認 | deny |

全操作はlist、page、detail、count、option、summary、dashboard、export、download、notification、scheduler、async、cache、citationの全consumerへ同じscopeを適用する。

### state / concurrency

| 対象 | invariant |
|---|---|
| copilot run | read-only。業務tableの状態を変更しない |
| duplicate request | trace/parameter hashと既存unique/冪等規則で重複をboundedにする |
| provider call | transaction外、deadline/cost/retry上限、結果保存はredactedのみ |
| feedback | actor/scopeを再確認し、同じanswerへの競合は既存version/監査規則に従う |
| catalog/artifact | version、status、human approval、rollbackを管理。自動promotion禁止 |
| citation | response直前に認可を再評価。失敗時はURL/IDを出さない |

## 13. テスト設計

### contract

- dashboard、management accounting、cashflow、sales performance、utilizationの同一期間/同一scopeについて、画面APIまたはexportの値とAI typed resultを比較する。
- 円、%、件数、期間、timezone、actual/forecast、freshnessを比較対象にし、summary textの数値は比較対象にしない。
- managerの会社全体cashflow入力、salesの担当外contract、empty scopeを明示する。

### security/adversarial

- catalog外SQL、table/column、schema説明、repository、prompt injection、scope昇格、URL直接改変を投入する。
- allowlist外PIIとG10 canaryの送信前・保存前遮断を確認する。
- 0、NULL、未確認、forecast、巨大行、truncated、partial citationを確認する。

### provider/evaluation

- mock/rule providerで429、timeout、invalid JSON、HTML/unknown claim、cost超過を再現する。
- anonymized fixtureのdataset/model/prompt/rule/data versionを固定し、min segment未満はPASSにしない。
- adoption、precision@5/@10、latency p95、PII leak、scope leak、citation integrityを検証し、regression時はpromotion不可とする。

## 14. migration・設定方針

本Discoveryではmigration、設定、template、Java、JSを変更しない。承認後にpersist metadataが既存AI runで表現できないと確定した場合のみ、公開済みmigrationを編集せず、現行latest+1の新migrationとして最小変更を設計する。`V1`、公開済みmigration、seed済みgateを直接書き換えない。

設定の目標値は、management copilot flag OFF、external send OFF、provider mock/rule、raw prompt retention 0、redacted retentionはNF-07承認値である。production providerのURL/API keyはこのspecの実装対話で追加しない。
