# NF08 C2 費用証拠・実DB永続化計画

## 目的と前提

- 対象HEAD: `3d95d3f57dd9a9a5da6e458b3fd07f8bf145f070` (`codex/snf-prod-nf08-summary-boundary`)。C1は18クラス・91件PASSとして受入済み。本書は次のC2実装のPLANであり、実装前にAstraの独立レビューを受ける。
- 既存のbounded adapter/transport、deny-all本番cost policy、feature flags OFF、synthetic loopbackを維持する。Gemini実送信、実credential/provider、実料金の登録、artifact publish、migration/DDL、production bean/flag有効化、push/PR/deploy/production承認は対象外。
- 現状 `CopilotGeminiResponseParser.parse` はouter usage検証後にinner summary JSONも検証し、全て成功したときだけusageを返す。したがってinner JSON/schema/claim/numeric検証失敗時に、受領済みtoken usageとdispatch状態をattemptへ渡す型経路がない。
- `SummaryCostEvidence` / `PreflightCostEvidence` と `CopilotRunService` のtenant+trace+usecase+PENDING+statusVersion CASは存在する。ただし既存testはservice/mock capture中心で、実DBに対する全状態行列、duplicate completion、stale/wrong-tenant/wrong-trace拒否と行不変性を証明していない。

## C2-A: usage保持と費用計算境界

対象候補: `CopilotGeminiResponseParser`、Copilot専用provider result/exception、`CopilotProviderUsage`、`SummaryCostEvidence`、`CopilotSummaryService`、cost policy/evaluatorとそのtest。

1. parseをouter envelope段階とinner summary段階へ分ける。outer parse完了時点で、secret/raw bodyを含まないimmutable `EnvelopeEvidence`（strictly validated usage、HTTP/dispatch outcome、model/config identity）を返す。inner parse/schema/claim/numeric/deadlineが失敗してもこのevidenceを捨てず、typed failureへ添付してsummary serviceからattempt完了へ渡す。HTTP failureやouter envelope自体の破損でusageを信頼できない場合はusage=nullとし、実際にdispatchを開始した可能性に従ってUNKNOWN/UPPER_BOUNDへ進む。生応答や例外cause本文を記録しない。
2. usageは縮小・丸め前の整数token countを保持し、prompt、candidate/output、thinking、cached等、料金表でbillableとなる全dimensionを明示する。strict JSON integer、非負、個別最大値、加算overflow、total整合を検証する。synthetic contractの包含意味を次で固定する。`promptTokenCount` はcachedを含むprompt総数、`candidateTokenCount` はthinkingを含むcandidate総数、`cachedContentTokenCount` はpromptの部分集合、`thoughtsTokenCount` はcandidateの部分集合であり、`totalTokenCount = promptTokenCount + candidateTokenCount`、`0 <= cached <= prompt`、`0 <= thinking <= candidate` とする。provider仕様がこの包含関係を保証しない場合は未対応としてUNKNOWNにし、推測で再分類しない。欠落/未知billable dimension/overflow/不整合はACTUAL計算不可。数値上限は実装契約として固定し、型変換前に検査する。
3. cost evaluatorはversion付きtrusted rate-card snapshotを受け取る純粋関数とする。card identityにはprovider/model revision、region/tier、通貨JPY、effective window、全token dimension単価、FX provenance（外貨ならsource/time/rate）、料金単位、唯一の総額丸め規則を含める。金額はBigDecimal等でoverflowを検知しながら計算し、ACTUALは正確なexclusive明細を合算して最後に一度だけ円へ丸める。上限はsubset上限を母上限から減算してはならない。prompt上限 `Pmax`、candidate上限 `Omax`、各非負rateがtrustedである場合のみ、`upperRaw = Pmax * max(promptRate, cachedRate) / 1000 + Omax * max(candidateRate, thinkingRate) / 1000` とする。これは各母集合全体に母子の高い方の単価を適用するため、subset量の相関・可行値を仮定せず安全側となる。総額を最後に一度だけ `CEILING` してbudgetと比較する。負rate、単位不明、token semantics不明、Pmax/Omaxいずれか欠落、usage/rateのoverflowは上限なしとしてUNKNOWNにする。`maxOutputTokens`や文字数推定を入力上限として使わない。
4. trusted cardの検証方式を次のように限定する。rate-card入力DTO（JSON/DB/API）は常にuntrustedであり、DTO、ID、version文字列だけでは計算器へ渡せない。実装時は `TrustedRateCardVerifier` を唯一の入口とし、`VerifiedRateCard` と `VerifiedUsageBinding` をそのverifierのprivate nested classとして定義し、private constructorもverifier内に置く。verifierは固定registryのcard canonical bytesに対するEd25519署名を、productionで固定された公開鍵ringから検証する。署名対象にはcard ID/version/provider/model revision/region/tier/currency/effective window/rates/price unit/rounding ruleを全て含め、利用者指定のID引き当て結果だけで信用しない。署名秘密鍵はアプリ・CI・DB・DTOへ置かず、将来の別途承認済みoffline release手続きに限定する。この作業ではregistryは固定deny-all、検証可能なproduction cardも署名鍵も実装/投入しない。

   verifierは評価直前にexecution-scoped canonical bindingを作り、card署名に加えて `attemptId + tenantId + traceId + useCase + statusVersion + dispatchNonce + provider/model revision + configHash + effectiveWindow + validated usageの全dimension + preflight snapshot hash` を結合する。binding capabilityもverifierのprivate nested class/private constructorとし、capabilityはverifierのprivate評価メソッド内でのみpackage-privateの純粋算術関数へ渡す。testはconstructorへ直接アクセスせず、test sourceのsynthetic signing keyで署名したuntrusted DTOをreal verifierのtest key ringへ渡して発行させる。`attemptId`/`dispatchNonce` は1回の送信に対して新規発行し、attempt完了後はDBの既存CAS（PENDINGとstatusVersion）により同一attempt再完了を拒否する。検証時に現在のattempt/contextとcanonical digestを全項目比較するため、public DTOからの詐造、別attemptへの署名済みcard/usage replay、usage差し替え、preflight/config/model差し替えは検証失敗となる。保存JSONを再読込してもcapabilityへ復元しない。productionで署名検証鍵やregistryが未設定・deny-allなら検証器は常に失敗し、ACTUAL/UPPER_BOUNDへの昇格経路を持たない。

   test sourceだけに、test packageに閉じたsynthetic署名鍵、synthetic issuer、card fixture factoryを置く（production sourceのAPIからは参照不能）。負例行列として、署名1byte改変、別tenant、同じtenantの別attempt、trace/usecase/statusVersion/dispatchNonce違い、usageの各count1違い、preflight/config/model/effective-window差し替え、期限外、card ID同一でversion/rate違い、署名なし/未知issuerを拒否し、拒否時はproduction相当のACTUAL/UPPER_BOUNDを作らないことを確認する。実providerのbillable dimension包含関係、rounding、token semanticsが未確認なら、実usageをACTUALへ昇格させずUNKNOWNとする。
5. `ACTUAL`は「検証済み実usage × 承認済みversioned cardから計算した確定額」を意味する。provider請求書照合済みの実請求額とは呼ばず、メタデータにも計算basis/card versionを残す。必要usage dimensionが欠ける/不正ならACTUAL禁止。送信有無不明または送信済みでACTUALを確定できず、独立に証明された有効な上限があればUPPER_BOUND、なければUNKNOWN。dispatch前に未開始を証明した場合のみNOT_SENT。inner validation失敗でもvalid usageがあれば費用状態を維持し、summary outcomeだけFAILEDにする。
6. このC2実装の本番card/policyは引き続き固定deny-allとし、アプリ設定、DB値、artifact、承認booleanで解除不能にする。数値テスト用rate cardはtest sourceのsynthetic fixtureでのみ生成し、それは費用算術の正しさを示すだけで、実料金の正確性・dispatch許可・production evidenceを示さない。将来の本番cardには外部公式価格/契約・region・FX資料、effective date、承認者/有効化手続きが別途必要。

### C2-A 受入証拠

- loopbackでouter usage valid + inner JSON malformed/schema mismatch/claim rejection/numeric rejection/deadline expiryを各実行し、FAILED attemptに元token count、ACTUALまたは証明可能なUPPER_BOUND/UNKNOWN、dispatchOutcomeが一致して残る。raw body/key/promptは例外・ログ・DBに出ない。
- 各dimensionの0、最大境界、最大+1、欠落、負値、decimal/string、合計overflow/不整合、rate multiplication overflow、円丸め境界、budgetちょうど/超過を評価する。synthetic cardの期待計算をfixture外の独立した期待値で検査する。
- usageが無いHTTP error、outer parser failure、timeout/connection reset、dispatch前denyを区別し、送信後をNOT_SENT/0円へ誤分類しない。どの構成値でもproduction policy request count=0を維持する。
- 算術は重複課金を避けるexclusive quantityで行う。全rate unitを「JPY / 1,000 tokens」とし、`promptExclusive = prompt - cached`、`candidateExclusive = candidate - thinking`、未丸め額を `promptExclusive*promptRate/1000 + cached*cachedRate/1000 + candidateExclusive*candidateRate/1000 + thinking*thinkingRate/1000` と定義する。synthetic例 `prompt=1,000, candidate=500, cached=200, thinking=100`、rates `prompt=3, cached=1, candidate=12, thinking=20 JPY/1,000 tokens` のexclusive量は `800/200/400/100`、明細は `2.4/0.2/4.8/2.0 JPY`、合計 `9.4 JPY`、全明細を正確に加算した後に一度だけ `HALF_UP` で丸めて `9 JPY` とする。独立した期待値は定数9とし、production計算helperから期待値を作らない。このrateはtest専用synthetic値で実provider価格を表さない。
- token count上限、subset/total関係、乗算・合算・JPY変換をBigInteger/BigDecimal等でoverflow前に検査し、範囲外はACTUAL不可。各明細を個別に円丸めして合算してはならない。upper boundは母上限式 `Pmax * max(promptRate,cachedRate)/1000 + Omax * max(candidateRate,thinkingRate)/1000` を使い、subsetの上限値を差し引かず、総額を最後に一度だけ `CEILING`、budget比較はそのJPY整数で行う。synthetic負例として `Pmax=10,000, cachedMax=2,000, promptRate=3, cachedRate=1, Omax=0` では、子上限を差し引いた誤式が `8,000*3/1,000 + 2,000*1/1,000 = 26円` を出す一方、合法な `cached=0` は `10,000*3/1,000 = 30円` となることを示し、母上限のmax-rate式が30円を返すことを独立定数で確認する。cached単価が母単価より高い例 `promptRate=3, cachedRate=5, Pmax=10,000, cachedMax=2,000` では、上限を `50円` とし、cached全量の実例 `34円` とcachedゼロの実例 `30円` の両方以上になることを独立計算で検査する。上限30円・cachedゼロの実例30円はupper boundとactualが等しくなる境界として検査する。preflightに母上限のPmax/Omaxどちらかが欠ければ上限なしとしてUNKNOWNとする。0境界、subset等号、端数合算、HALF_UPのx.5境界、CEILING境界、count/rate乗算/総額overflowを検証する。

## C2-B: 実DB状態行列とCAS証明

### B1（今回承認・最小範囲）

対象は `CopilotRunService.completeSummaryAttempt`、必要最小限の `CopilotQueryService` stale応答処理、`AiRecommendationRunMapper` の実MySQL経路、および `CopilotRunServiceMySqlIntegrationTest` とする。Spring管理の実service proxyを呼び、test method自体には `@Transactional` を付けない。commit後は別の新規DataSource connectionで行を再読込する。rollback試験ではservice proxy呼出しを含むtest側transactionをrollbackし、完了後の独立connectionから元行を検査する。テスト用fixture attemptは既存artifact versionを参照する独立行として作り、各ケースで固有fixtureを使う。

| 入力/遷移 | 期待DB状態 |
|---|---|
| `PENDING/v0` + dispatch未開始を示す `NOT_SENT` | `FAILED/v1`、`cost_jpy=0`、redacted JSONに `NOT_SENT` とbounded reason/outcome |
| `PENDING/v0` + response受領後の未認証usage構造 | `FAILED/v1`、`cost_jpy=NULL`、redacted JSONに `UNKNOWN`、dispatch状態と全usage dimension。金額は生成しない |
| 同一attemptを二度complete | 初回は `COMPLETED`、2回目は例外でなく明示的 `STALE`。terminal行の対象列すべて不変 |
| active tenant未設定、またはactive tenantとattempt tenant不一致 | completion前に拒否し、DB全列不変 |
| 行とattemptのtenant/trace/usecase不一致、または行status/versionがPENDING/v0以外 | CAS更新0件は `STALE`。対象行および別tenant行の全列不変 |
| 二threadをbarrierで同時complete | 1件だけ `COMPLETED`、他方は `STALE`（通常例外なし）。最後に独立connectionからterminal行1件を確認 |
| transaction内でservice proxy更新後にtest側rollback | rollback後に元の `PENDING/v0` と全対象列が不変 |

既存CAS where条件の `id + tenant + trace + usecase + PENDING + version=0` を保つ。summary completionは更新件数1なら `COMPLETED`、0件だけ `STALE` を返す。SQL、serialization、commit等の例外は握りつぶさずtransaction rollbackさせる。query completionは従来通りstrict failureを維持し、失敗時に `FAILED` で二度目の更新を試みても最初の例外を置き換えない。`CopilotQueryService` はsummary completionが明示的 `STALE` の場合に生成済みだが未永続化のsummaryを返さず、bounded unavailable responseにする。Mockitoの未stub戻り値nullは完了扱いにしない。各workerは終了時に `AccountingTenantContextHolder.clear()` を `finally` で必ず実行する。

B1の検証範囲はmetadata永続化、transaction rollback、tenant/CAS/stale境界に限る。A2 verifierはsynthetic算術試験専用でproduction usage認証経路へ接続されていないため、testがmock金額を注入してACTUAL/UPPER_BOUNDの信頼性を主張してはならない。`NOT_SENT` および金額nullの `UNKNOWN` に対しdispatch情報・usage dimensionがredacted JSONへ保存されることだけを検証する。schemaはV108/V161/V162/V173の既存列を使い、migration/DDLを追加しない。`CopilotRunServiceMySqlIntegrationTest` を `scripts/test-suites/mysql-shard-1.txt` に一度だけ登録する。既存 `recordQueryRun` / `recordCatalogRun` のcontext拒否を変えない。

### B2（未承認・後続計画）

実provider応答由来usageの真正性、production card署名/registry、usage receiptとcardのruntime結合、ACTUAL/UPPER_BOUND計算結果から `SummaryCostEvidence`・attempt metadata・DB `cost_jpy` への端から端までの経路、およびinner validation失敗後にACTUAL保持するDB試験はB1の範囲外である。production usage costは引き続きUNKNOWNとし、B2は実usage provenance設計と明示的な別PLAN review後に扱う。synthetic signing keyやfixture金額をB2の代替証拠にしない。

## バッチ順序とレビュー

1. **C2-A1 usage carrier/parser境界** — usageをinner validation失敗と独立して運ぶ。parser・summary serviceのunit/loopback証拠を先に揃える。本番policy deny-allを同時に再確認。
2. **C2-A2 rate-card evaluator** — versioned trusted-card契約、token dimension/overflow/roundingを実装。ただしproduction registryはdeny-all固定、synthetic cardはtest限定。Astraが料金意味と上限根拠を承認するまでproduction値を作らない。
   本実装の署名済みsynthetic usage receiptはtest source内で署名・検証する算術境界試験専用であり、runtime usage署名サービス、provider応答の真正性証明、実usageのACTUAL昇格経路ではない。productionの実usage costは引き続きUNKNOWNとし、provider usageの取得・認証・attemptへの接続には別途承認済みplanが必要である。
3. **C2-B DB persistence matrix** — Testcontainersの `@Tag("mysql")` 実DB試験で全表の状態遷移、CAS、tenant isolation、duplicate/stale、rollbackを証明。スキーマ変更なし。

各バッチを実装前Astra PLAN review、実装後に対象専用gateと独立implementation reviewへ分ける。C1（18クラス/91件PASS）のcontrolは変更しない。今回のPLAN作成ではMaven、Docker、Chrome等のheavy testは実行しない。

## 明示的な非承認範囲

この計画のPASSやsynthetic試験結果は、本番価格の承認、provider contract/DPA/region承認、token上限の実運用証明、Gemini送信承認、AI flags有効化、Gemini artifact作成/ACTIVE化、production rolloutの承認を意味しない。実credential/provider、外部ネットワーク、実データは使わない。production gateway/adapterはdeny-all、`ai.provider`/Copilot/external-send flagsは現状OFFのままとする。
