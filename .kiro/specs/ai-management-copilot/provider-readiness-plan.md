# NF-08 外部AI Provider 接続準備・sandbox受入計画

## 1. 目的・判定境界

本書は NF-08 management copilot の実provider有効化前の接続・sandbox・stop設計である。文書追加のみを行い、adapter接続、コード/config、credential、外部送信、production flagを変更しない。固定Control `9bac40c9624e7d964bcf6e18f480b3457662206e` が基準。NF08候補 `647b35c09e6828f03b7f0ae9b3d3bec0939d517a` は read-only参考であり、その差分や検証結果をControlの事実・承認へ転記しない。

Mock/rule/loopback/HTTP fakeの成功はローカル契約の合成確認であり、実provider、実credential、実ネットワーク、DPA、費用、data handling、production acceptanceではない。

## 2. 現行能力と未接線点

| 境界 | Controlで確認できる状態 | 未接線/制約 |
|---|---|---|
| 既存AI | `ai.provider=mock` を基準とし、旧Gemini関連service/registryと通常用途の呼出し境界がある。API keyはserver-side設定を前提とし、ブラウザへ入力させない | `GeminiTextServiceImpl` は `CopilotBoundedAiTextService` を実装し、bounded transport/parserとsnapshot identity確認を持つ。ただし本番dispatchは `allowsDispatch` が固定deny-allで常に拒否し、synthetic loopback fixtureだけが合成cost proofでdispatch可能 |
| Copilot pipeline | `CopilotSummaryService` → `AiExecutionGateway` → `AiTextService` の差し替え境界。mock/rule summaryのoffline/synthetic検証。typed result/citationをsummaryから分離 | 本番transport dispatchは未許可であり、production cost policyはdeny-all。実運用向けcost/usage proof、deadline/cancelの最終保証、run attempt結果の永続化整合、provider contract、独立Reviewが未完 |
| Transport | Gemini用bounded HTTP transport・response parserを `GeminiTextServiceImpl` が呼ぶ実装経路と、合成loopback専用constructor seamがある | production dispatchは明示的deny-all。合成loopbackは実providerの応答・費用・data handling証拠ではない。API/model契約とcost authorizationは未決であり、live接続受入は未実施 |
| Privacy gate | current specはraw prompt保存0日、allow-list/redaction/canary、mock/rule限定、external send OFF | NF-07 inventory/retention/purge/PII承認、DG-08 provider/owner/role/region/cost/escalation、DPA/越境/G10 production gate未完 |
| Flags | `ai.management-copilot-enabled=false`; `ai.external-send-enabled=false`; provider mockを維持 | production flagの有効化は禁止。provider-transport/canary等の細分flagを将来実装する場合は別CODE/PLAN/Reviewが要る |

確認箇所: `AiTextService`, `AiProviderRegistry`, `AiExecutionGatewayImpl`, `CopilotSummaryService`, `CopilotBoundedAiTextService`, `CopilotConfigSnapshot`, `CopilotBoundedHttpTransport`, `CopilotBoundedJsonParser`, `GeminiTextServiceImpl` と `ai-management-copilot/{design,requirements,review-ledger}.md`。静的ソース確認であり、当該依存テストを本作業では実行していない。

## 3. Provider選択・公式文書・適用版

未決選択肢: Gemini Developer API（現行Gemini `generateContent`）、Vertex AI Gemini、OpenAI API、その他。選択はData processing条件、region/residency、DPA、subprocessor、retention/logging/training opt-out、network control/private connectivity、model lifecycle/SLO、structured output、input/output usage/cost証明、日英性能、abuse policy、契約/請求主体で比較し、ownerとPrivacy/Legal/Securityが決裁する。安価なfree tierや既存API keyは企業data送信承認を代替しない。

現行 `GeminiTextServiceImpl` には `generativelanguage.googleapis.com` の完全一致allow-list、`x-goog-api-key` header transport、Copilot用snapshot/parser経路がある。未指定modelのfallbackは `gemini-1.5-flash`。これは現行コード既定値にすぎず、modelのサポート状態/契約/データ条件/費用が承認された意味ではない。既存設計がGeminiを記載していてもprovider選定decisionではない。Gemini Developer APIを選ぶ場合に限り、次の公式文書を**2026-10-26時点の確認候補**として採用packetへ固定し、sandbox/本番判断直前に改訂日・対象project/tier/model/regionを再確認する。

- REST endpoint/auth/schema: [Gemini API reference](https://ai.google.dev/api) と [Generate content](https://ai.google.dev/api/generate-content)（API referenceの掲載version/pathを採用する。URL/versionは本書で固定しない）。公式referenceは `x-goog-api-key` headerと `models.generateContent` を示す。
- Key storage: [Using Gemini API keys](https://ai.google.dev/gemini-api/docs/api-key)（`GEMINI_API_KEY` / `GOOGLE_API_KEY`の環境注入案内。実secret値は扱わない）。
- Data terms: [Gemini API Additional Terms](https://ai.google.dev/gemini-api/terms)（official page reports effective date 2026-03-23。Paid/Unpaid tier distinctionsとprocessor termsを契約主体・地域に即して再確認）。
- Retention: [Zero data retention](https://ai.google.dev/gemini-api/docs/zdr)（featureごとのstate/log/cache条件を確認。zero-retentionを無条件に約束しない）。
- Pricing/usage: [Gemini pricing](https://ai.google.dev/gemini-api/docs/pricing) と [Billing](https://ai.google.dev/gemini-api/docs/billing)。価格、quota、rate limitは変わるため、正式予算化当日にmodel/region/currency/tierを保存する。

公式Web文書は動的で、利用契約や顧客の処理根拠ではない。source URL、取得日時、更新日、適用tier/region/model、document hash、承認者をprovider decision recordへ残す。Vertex AI等を選ぶ場合はDeveloper APIの文書を流用せず、選択製品固有のofficial endpoint/auth/IAM/DPA/region/pricingを新たに照合する。

## 4. Credential名・権限・注入/rotation/revoke

| 項目 | readiness要件 |
|---|---|
| 仮のsecret name | Gemini Developer API候補: `NF08_GEMINI_API_KEY`（secret valueは本書/terminal/CI/artifactへ書かない）。Vertex AIならAPI keyではなく専用runtime service identity/WIF等の公式認証を検討し、`NF08_VERTEX_AI_SERVICE_IDENTITY`はresource label案に留める。OpenAI等を選ぶ場合は`NF08_<PROVIDER>_API_KEY`へdecision後命名 |
| 権限 | 専用sandbox projectと専用runtime identity。必要なgeneration API/modelだけ。project/org admin、billing admin、key管理権限をruntimeへ付与しない。key restriction/rotation supportの公式機能を確認。sandboxとproductionでkey・project・billingを分離 |
| 注入 | secret manager/identity federationからserver-side execution identityへ取得。browser、request DTO、DB、source、profile yaml、JVM args、logs、prompt/run metadataへ入れない。blank/unknown/version drift時は送信前拒否 |
| 監査 | secret値・header・raw request/responseを記録しない。secret version/reference ID、model/provider/prompt/artifact version、actor/system identity、attempt、latency、bounded usage/cost/statusのみredacted ledgerへ |
| Rotation | 新versionを隔離sandboxで検査し、短い承認済みoverlap中にcanary、旧key使用停止・revoke、旧keyでの401/denyを確認。固定有効期間はprovider/owner決定。keyは共有しない |
| Revoke | secret manager version disable/delete + provider project側key revoke + `external-send` OFF + copilot flag OFF。egress deny/circuit open、inflight cancel、未完runをsafe unavailableへ。漏洩疑い時は旧key再利用不可、security incident processへ |

## 5. Sandbox正常系・異常系受入マトリクス

実provider sandboxを呼べるのは、別途提供された隔離project、synthetic-only入力、専用credential、明示的な人手実行許可、上限付きbilling/quota、Security/Privacy/Legal条件が揃った時のみ。揃わない場合はlocal mock/rule/loopback fixtureに留める。

| ケース | 合成fixtureで先に必須確認 | sandboxで人手許可後に追加確認 |
|---|---|---|
| 正常 | typed resultからallow-listed claim keyだけ生成し、数字/期間/scopeを再計算しない。schema厳格parse、PII canary pass | provider/model/API version、応答schema、usage metadata、latencyをsynthetic promptで確認 |
| timeout/取消 | transport・parser・gateway全体に同一deadline、late response破棄、typed result保持/unavailable、bounded cancellation | provider側timeout/connection resetと請求確定タイミング。実cost未知なら停止 |
| 429/5xx | Retry-After bounded parse、jitter付き小回数retry、max attempts後unavailable、budget枯渇で送信前拒否 | provider quota/header/codeとrate limitを確認。retry-safe条件不明ならretryしない |
| malformed/bad | invalid UTF-8/JSON、oversize/deep JSON、HTML/fence/unknown claim/extra candidateを拒否。raw bodyは表示・保存しない | 実API schema/model incompatibilityを確認。未知schema時は自動fallback禁止 |
| wrong identity | missing local secret、local上のexpired/revoked version、wrong provider/model/artifact/config identity、および承認済みsecret reference/versionとproject bindingの不一致はdispatch前に拒否（dispatch=0）。provider側だけで判明するinvalid/revoked credentialは、provider契約で定義された認証拒否code（例: 401/403）を確認したらretryゼロ・raw response不保存で停止。別project keyの所属をkey値から推定しない。localでproject bindingを証明できない場合はonboardingをBLOCKED | 失効credentialのprovider拒否確認は担当者が明示許可したsandboxだけで実施し、provider契約の拒否code、retryなし、safe codeのみ記録。別project binding mismatchはsynthetic metadata fixtureでdispatch=0を確認する。出力や認証情報を保存しない |
| tenant/cross scope | tenant Aの synthetic dataはAのclaimsのみ。tenant B ID/PII/canaryをpromptに含めず、scope空/不一致時provider dispatch count=0 | 実顧客/実tenantを使わない。provider sandboxはsynthetic tenant labelsだけ |
| retry/idempotency | 同一run/attempt IDは同一identity/config、timeout後の再試行をpolicyで明示、重複summaryを書き込まない。AI generation自体の副作用なし、run CASは一度 | APIのidempotency対応を公式仕様で確認。不対応ならtimeout後自動再送を止めるかhuman adjudicationにする |
| revoke/rotation | fake secret providerでrotation state machine、旧version拒否、header/log/exceptionにsecret値なし | sandbox key rotate/revokeを実施し旧key拒否、audit trailを確認 |
| 費用上限 | synthetic trusted rate card、token/input/output bound、per-call/per-tenant/day/month caps、予算不明/未知価格はdeny、circuit stop | 最新公式価格/contract、実usage、billing cap/alert反応。金額上限とcurrencyを人手承認、free tier不可 |
| audit/Privacy | raw prompt/provider body 0保存、redacted metadataのみ、PII canaryでdispatch阻止、revoke/stop event監査 | provider console/data controls、retention/logging/training settings、DPA/region/subprocessorを契約責任者が確認 |

Synthetic fixtureで埋められるのはadapter契約と失敗挙動まで。loopback応答をGoogle/OpenAI等のlive response・data processing・billingの証跡にしない。

## 6. Flag、緊急停止、rollback

- default fail-closed: `ai.management-copilot-enabled=false`, `ai.external-send-enabled=false`, `ai.provider=mock`。productionのmode/flagを書き換えない。missing provider/model/endpoint/secret, unknown config, failed approval gateは拒否。
- 将来の実装・別承認後もstageは環境→synthetic sandbox project→allow-listed use case/catalog→role/tenant cohort→限定canary。provider transport flag、copilot feature flag、external-send flagを独立し、どれかOFFなら送信ゼロ。各段階のownerと自動停止条件を別CODE/PLANで実装/review。
- Emergency stop runbook: management copilot OFF、external send OFF、provider transport circuit open、AI egress deny、scheduler/queue dispatch停止、未完run cancelまたはsafe unavailable、credential revoke、artifactを既知mock/ruleへ退避。再送を自動化しない。既送信attemptのusage/cost/provider logを許可済み範囲で照合する。
- stop条件: PII canary/scope/tenant leak、prompt/raw dataの未許可外送、wrong model/provider/config identity、secret露出、schema/fallback violation、cost/usage未知または上限超過、timeout/429閾値逸脱、provider/DPA/region/retention変更、audit欠落、owner/承認失効。
- rollbackはmock/ruleへ戻しactive artifactを既知検証版へ戻す。run metadataはredacted状態を維持し、NF-07の保持・purgeに従う。再開にはincident closure、credential再発行、synthetic regression、Privacy/Legal/Security/Operations/Provider owner再承認が必要。

## 7. Hard gates・人手必要範囲

**NF-07 hard gate:** PII inventory/classification、allow-list、retention/purge/legal hold、data subject handling、provider processor/subprocessor条件が承認されるまで、実prompt/PII送信は禁止。現在のNF-07未完をこの文書で補完しない。

**DPA/Privacy hard gate:** provider-specific DPA/processor terms、用途/目的制限、training/improvement opt-out、abuse monitoring/log retention、region/越境・subprocessor、incident通知、削除、監査、再委託、契約主体をPrivacy/Legalが承認するまでprovider sendは禁止。paid/free tierや「ZDR」表記だけでは承認にならない。

**Production approval hard gate:** DG-08 provider/owner/role/cost/escalation decision、`GATE-S17-G10-PROD`、Security review、approved catalog/roles, risk acceptance, budget/cost policy, model/prompt artifact review, production topology evidence, rollback drillとProduction Owner署名がすべてPASSするまでmanagement/external flag OFF。

**Syntheticで可能:** parser/schema, 429/timeout/invalid JSON, wrong identity config, scope/canary, retry/cost cap with fake rate card, run CAS/idempotency, revoke state, logs redaction, flag/emergency stopをoffline mock/loopbackで検証。

**人工の実環境と許可が必要:** model/provider/API versionの決定、sandbox accountとsecret発行・注入・rotation/revoke、実TLS/endpoint/API互換、実quota/usage/billing、region/retention/training/subprocessorの契約確認、顧客 data可否判断、Production Owner含むprod enablement。本依頼の範囲ではいずれも実施しない。

## 8. 人工決定チェックリスト

1. provider製品（Gemini Developer API / Vertex AI / other）、API/model version、region、project/account、deprecation plan。
2. Privacy Owner/LegalがNF-07 data class・用途・DPA・越境・retention・training/abuse logを承認。
3. Securityがruntime identity、secret manager名/RBAC、network egress、rotation/revoke SLAを決裁。
4. AI product ownerがcatalog/allowed roles、human escalation、model evaluation、acceptable output/errorを承認。
5. Finance/Ownerがcurrency別rate card、input/output bound、per-call/day/month hard cap、alert/stop operatorを承認。
6. Operations/Production Ownerがsandbox executor/time window、証跡保管、incident contact、emergency stop drill、rollback criteriaを承認。
7. 独立PLAN/IMPLEMENTATION review、必要なCODE実装/テスト、production gate証拠を個別に完了。

**状態:** 接続準備設計案のみ。provider未選定、NF-07/DPA/DG-08/G10未完、外部send・production approval不可。実provider受入やCODE PASSを宣言しない。
