# NF08 C2-B2 実usage provenance・費用証拠の技術計画

**状態: PLAN REVIEW PENDING。実装承認・provider送信承認・本番料金承認ではない。**

## 目的と現在地

B1（commit `db25d2b7`）は、Spring service proxyを通るattempt完了、tenant/CAS、`STALE`、transaction rollback、usage metadata保存の境界を実DBで検証する。B1のMySQL証拠はmetadata永続化と状態遷移を示すもので、provider由来usageの真正性や料金計算結果の本番有効性を示さない。

既存の `CopilotGeminiResponseParser.parseEnvelope` はouter envelopeとusageを検証し、`validateInnerSummary` の失敗にも `ValidatedEnvelopeFailure.usage()` を保持する。`GeminiTextServiceImpl.generateCopilotResponse` はusageを `CopilotProviderResponse` または `CopilotExecutionFailure` へ運び、`AiExecutionGatewayImpl.execute` は `AiGatewayResult` へ渡す。現状の値は型付き整数であり、実際のHTTP応答から得たことを示すprovenance capabilityではない。`CopilotSummaryService.costEvidence` はこの値を `SummaryCostEvidence.fromPreflight` に渡すが、production用 `ProductionDenyAllCopilotCostPolicy` と `GeminiTextServiceImpl.allowsDispatch` は本番dispatchを引き続き拒否する。

`TrustedRateCardVerifier` は現在、production constructorで空のkey/card registryを持ち、`evaluate` も `syntheticTestVerifier` 以外を拒否する。test sourceの `SignedUsageReceipt` とsynthetic署名鍵はverifier境界の算術・binding試験専用であり、Geminiまたは他providerのusage真正性を証明しない。`CopilotRunService.completeSummaryAttempt` は `SummaryResponse.costEvidence` をmetadataと `cost_jpy` へ保存するが、verifier発行の能力型を要求する接続はまだない。

## 設計原則

1. HTTP応答のusage provenance、署名済みrate card、attempt binding、費用評価を別々に検証する。どの一つも、他の証明の代替にしない。
2. providerがusageへ署名するという前提を置かない。HTTPS/host検証を通じてアプリケーションが受領した応答であることと、provider自身の署名による非否認証明は異なる。公式仕様・契約でprovider usageの意味と受領経路が確認できなければ、productionは `UNKNOWN` のままとする。
3. `ACTUAL` は確認済みprovider usage × 有効な署名済みrate cardで計算した金額を意味する。provider請求書照合済みの実請求額を意味しない。送信開始後、usageを認証できない場合は `ACTUAL` を作らず、独立に検証されたpreflight上限がある場合だけ `UPPER_BOUND`、それ以外は金額nullの `UNKNOWN` とする。`NOT_SENT` は送信開始前である証拠がある場合に限る。
4. 検証不能、証拠欠落、期限外、未知dimension、binding不一致、registry/key/card不一致、timeout、例外はfail-closedする。失敗を0円や `NOT_SENT` へ置き換えない。
5. production card registry、verification public-key ring、dispatch policyは固定deny-allのままにする。設定値、DB row、test artifact、環境変数で解除できる本番経路を作らない。

## 目標データ経路

1. `CopilotRunService.beginSummaryAttempt` が作成したsummary attemptを実行のrootにする。bindingには `runId`（attempt id）、`tenantId`（`AccountingTenantContextHolder.requireTenantContext()` と照合）、`traceId`、`useCase=MANAGEMENT_COPILOT`、開始時 `status=PENDING` / `statusVersion=0`、artifact version、provider、model revision、config hash、preflight snapshot hashを含める。query attemptのtraceとsummary attemptのtraceを取り違えない。ここでCSPRNG由来の少なくとも128-bit `dispatchNonce` と別の `idempotencyKey` を発行し、初期PENDING metadataと `CopilotAttemptRecord` に保存する。preflightでdispatchが拒否されたnonceは未使用のまま残る。
2. `CopilotQueryService.query` はsummary呼出し時に現在渡している `CopilotAttemptRecord` を `CopilotSummaryService.summarize` へ渡す。現状のtrace文字列だけの引数を置き換え、recordのtenant/run idと `CopilotExecutionContext` tenantが一致しない場合はdispatch前に拒否する。`CopilotInvocation` と `AiGatewayRequest` にこのimmutable attempt bindingを渡し、`AiExecutionGatewayImpl.assertCopilotBoundedRequest` が再確認する。同一attemptは1回だけdispatchする。物理retryを将来導入する場合はその前に設計を再レビューし、retryごとに独立attempt/trace/nonce/idempotencyKeyを発行する。
3. nonce発行時刻・送信開始時刻・応答受領時刻・費用評価時刻をUTC `Instant` で結び、HTTP deadlineは既存monotonic `deadlineNanos` でも検査する。UTC時刻はcardのeffective window照合に使い、期限時計異常や順序逆転をfail-closedする。nonceは秘密credentialではないがraw requestやAPI keyと同様にログへ出さない。
4. `CopilotBoundedHttpTransport.postGemini` は実際にsocket送信を開始した地点とresponse受領地点を一貫して記録する。許可hostのHTTPS、HTTP status、JSON content type、request/response digest、provider endpoint identity、model/config identityとdispatch状態を保持する。raw prompt、authorization header、API key、生response bodyはreceipt・例外・監査logへ保存しない。HTTP送信開始後のtimeout/reset/response parse failureを `NOT_SENT` としない。
5. `CopilotGeminiResponseParser.parseEnvelope` は全billable usage dimensionをstrict integer・範囲・overflow・total/subset制約で検証し、検証済みenvelopeに対する短命な `CopilotProviderUsageProvenance` を発行する。提案するissuerは `CopilotUsageProvenanceIssuer.issueFromBoundResponse(...)` とし、呼出し可能範囲をbounded adapter/parser内部へ限定する。provenanceには上記attempt/nonce/time/context、HTTP/endpoint identity、validated usage全dimension、response digest、config hash、preflight digestを結合したcanonical digestを持たせ、raw bodyは持たせない。request DTO、`AiGatewayResult` の単純usage setter、DB JSON再読込からprovenanceを再構成できない型にする。
6. `CopilotGeminiResponseParser.validateInnerSummary` または後段claim/numeric/deadline検証が失敗しても、envelopeで検証済みのprovenanceを `ValidatedEnvelopeFailure` → `CopilotExecutionFailure` → `CopilotSummaryService.summarize` へ保持する。outer envelope/usage自体が不正ならprovenanceとusageをnullにし、dispatch outcomeと独立preflight証拠だけを使って `UPPER_BOUND`/`UNKNOWN` を判定する。成功したsummaryの本文statusと費用stateは独立させ、summaryが `FAILED` でもusage provenance/cardが検証済みなら費用結果を保持できる。
7. 新しい `CopilotProviderUsageEvidenceService.verifyAndEvaluate(...)` が、provenance capability、署名済みcard、現在のattempt binding、`TokenUsageBounds`、`PreflightCostEvidence` を検証する。ここで `CopilotRunService.loadCurrentSummaryBinding(attempt)` 相当のtenant/id/trace/use_case限定queryを追加し、DB行が `PENDING/v0` で、PENDING metadataのnonceがattempt nonceと一致することを確認して `CurrentAttemptBinding` を作る。DB行がない、tenant不一致、terminal、version不一致、nonce不一致は費用評価せずSTALE/UNKNOWNにする。カード計算後からcompletionまでに別workerが勝つraceは最終CASが遮断する。card検証は既存 `TrustedRateCardVerifier.evaluate` / `validateCardSignature` / `validateCardDomain` を拡張し、署名、immutable digest registry、provider/model revision/region/tier/currency/price unit/effective windowを全て照合する。usage bindingは `RateCardExecutionInput` と `CurrentAttemptBinding` の全項目を比較し、`attemptId + tenantId + traceId + useCase + status + statusVersion + dispatchNonce + idempotencyKey + provider/model/region/tier + configHash + preflightSnapshotHash + evaluatedAt + usage dimensions + response digest` を含む `VerifiedUsageBinding` を作る。rate cardとprovenance capabilityをDB値から復元しない。
8. `CopilotProviderUsageEvidenceService` は検証に成功した場合だけ `VerifiedCostEvaluation` をsummary completion層へ渡す。`CopilotSummaryService` はsummary outcome（SUCCEEDED/FAILED）と費用証拠を組み合わせ、現在の `SummaryResponse` / `SummaryCostEvidence` 経路へ接続する。`CopilotRunService.completeSummaryAttempt` は既存CAS `id + tenant + trace + use_case + PENDING + status_version=0` を維持し、更新1件だけを `COMPLETED`、0件だけを `STALE` とする。`SQL`、JSON serialization、commit例外は伝播させてrollbackする。検証済みcard ID/version/digest、provenance class/source、execution binding digest、dispatch outcome、usage各dimension、計算basisをredacted metadataへ保存し、raw signed receipt/body/key materialは保存しない。`cost_jpy` は `ACTUAL` のみ実額整数、`UPPER_BOUND`/`UNKNOWN` はnull、`NOT_SENT` は0とする。

`COMPLETED` はCAS更新がtransaction内で1件通った結果であり、外側transactionが後でrollbackする前でもdurable commitを意味しない。呼出し側はSpring proxyから戻った後の結果として扱い、例外時は最初のprovider/query/serialization/SQL/commit exceptionを保つ。既存のquery completion strict failureと、失敗時二回目の `FAILED` completion exceptionを元exceptionのsuppressedへ加える挙動を維持する。DB CASは最終的なduplicate/replay防止であり、process内 `TrustedRateCardVerifier.completedAttempts` cacheだけに重複防止を依存させない。

## Usage意味・不確実性

`CopilotGeminiResponseParser.validateUsage` は現状 `promptTokenCount` / `candidatesTokenCount` / `totalTokenCount` を受け取り、`thoughtsTokenCount` と `cachedContentTokenCount` は欠落または0だけを許す。B2実装前に、採用するprovider・API version・model revisionについて公式仕様と契約資料でfieldの意味、subset関係、billable分類、最大値、cached/thinking処理、請求時の集計/丸めを確認する。資料がA2契約の包含仮定（cachedはpromptの部分集合、thinkingはcandidateの部分集合）を保証しないなら、そのdimensionを料金計算へ入れず、production費用は `UNKNOWN` にする。

未認証または不正usageでは、preflightのtrusted上限をusageから作り直さない。独立した上限が存在し、cardとpreflight snapshotが認証済みで、dispatchが開始された可能性がある時だけ `UPPER_BOUND` を許す。`promptMax` または `candidateMax` が欠落、rateが未確認/負/単位不明、card期限外、preflight binding不一致、算術overflowなら `UNKNOWN` / cost nullへ落とす。response受領後のinner JSON不正はusage認証と費用計算の可否を単独では変えず、summary statusだけをFAILEDにする。

## Key/card発行・rotation・停止

- cardは `SignedRateCard` のcanonical bytes、card ID/version、provider/model revision、region/tier、JPY単位、effective window、全rate dimension、rounding、issuer key IDを含めて署名する。不変cardを更新せず価格変更ごとに新version/digestを発行する。公開keyとapproved digest registryは将来の承認済みreleaseへ固定し、DB、runtime property、admin UI、test artifactをtrust rootにしない。
- 署名秘密鍵はoffline release環境だけに置き、アプリ、CI、DB、test fixtureへコピーしない。key rotationは旧/新public keyの承認済みoverlap、new cardの発効時刻、旧key停止時刻、compromise時の即時revoke・再評価停止手順をrelease manifestに記録する。歴史行の再検証には保存済みcard version/digest/key IDが必要だが、過去に作られた料金結果を新rateで再計算しない。
- unknown/revoked key、未登録digest、expired/not-yet-effective card、署名不一致は `ACTUAL`/`UPPER_BOUND` にせず、利用可能な独立上限がなければUNKNOWNにする。registryが空、verifier設定欠落、key ring読込失敗、時刻信頼異常、緊急停止中は拒否する。
- panic stopはproduction provider dispatch gateとcost evaluator gateを同時にdenyする独立運用スイッチとして将来設計する。ただし本PLANと実装は既存の固定deny-allを解除する設定や停止解除経路を追加しない。停止後は未完了attemptの自動再送を行わず、送信開始状態に応じてUNKNOWNまたは証明済みUPPER_BOUNDにする。

## ローカルで実装・検証できる範囲

次は実credential、実provider、外部ネットワーク、実料金なしで実装・合成検証できる。test-only loopbackとsynthetic keys/ratesの証拠には全て `SYNTHETIC_TEST_ONLY` を付け、本番rate registry、production `CopilotProviderUsageProvenance` issuer、production artifact/cost stateを作らない。

1. parser/transportのbounded loopbackで、envelope valid + inner JSON malformed/schema/claim/numeric/deadline failureを通し、test-only provenance carrierにusage、dispatch state、attempt bindingが欠けずraw body/key/promptを含まないことを検証する。outer usage malformed、unknown billable dimension、transport timeout/reset、send前denyはprovenanceを発行しないことも検証する。
2. test-source専用 `SignedUsageReceipt` / synthetic verifierで、署名改変、別attempt/tenant/trace/usecase/statusVersion/nonce/config/model/preflight/cardへのreplay、usage各dimensionの差し替え、card期間外、unknown/revoked key/card digest、duplicate idempotencyを拒否する。これはverifierの技術的binding試験であり、provider response authenticityの証拠ではない。
3. synthetic loopback結果を実service proxy・MySQLへ通すintegrationで、summary SUCCEEDED/FAILEDと費用stateを独立に記録し、5 usage dimension・provenance/card/binding metadata・`cost_jpy`の組み合わせを独立connectionから確認する。synthetic ACTUAL値を使うテストがあれば、fixture/metadata/reportにsynthetic-onlyと明示し、実料金やprovider請求を示す結果として扱わない。本番policyは引き続きdeny-all。
4. B1で未カバーのtwo-tenant sentinelを加える。tenant A attemptのtenant不一致/stale/duplicate completionを試し、別tenant B sentinel rowの全列も独立connectionから前後比較する。B1のquery failure original exception保持を維持し、service proxy付きMySQL試験でmetadata serialization failure、mapper/SQL failure、commit failureを注入し、元exception、rollback後の全列、別tenant sentinelが保たれることを確認する。commit failure fixtureはcommit前に失敗させ、成功後例外を模擬してdurabilityを誤認させない。
5. 全integration test methodは自身に `@Transactional` を付けない。rollbackケースだけtest側 `TransactionTemplate` を使い、commit済みケースはproxy呼出し後の独立 `DataSource` connectionで全列を読む。workerは `finally` で `AccountingTenantContextHolder.clear()` と `SecurityContextHolder.clearContext()` を行う。全MySQL test classは `@Tag("mysql")`、shard一覧は一度だけ登録し、CIのMySQL gateをDocker必須・zero-skipで実行する。

serialization/SQL/commit failureの注入方法は実装PLAN reviewでテスト構成を先に固定する。`@SpyBean`やtest transaction managerを使っても、対象serviceは必ずSpring transaction proxy経由で呼び、別connectionによる全列比較と実rollbackを維持する。

## 人手・外部資料による別途承認

以下はsynthetic fixture、local loopback、Astra PLAN/CODE PASSだけでは承認できない。資料・承認者・有効期間・保管先・撤回条件を別のhuman-reviewed evidence bundleへ記録する。

| 判断 | 必要な根拠/承認 | 未承認時の扱い |
|---|---|---|
| provider送信の法務・情報取扱 | provider契約/DPA、処理地域・subprocessor・保持/削除、mask対象の残存リスク、セキュリティとdata ownerの書面承認 | production dispatch固定deny、全送信後costは根拠に応じUNKNOWN/UPPER_BOUND |
| provider/model usage意味 | 対象API versionとmodel revisionの公式field仕様、cached/thinking等全billable dimension、subset/total/maximum/rounding、usageが請求計算で使われることの確認。provider署名が無い場合TLS受領応答を本番usage provenanceとして受け入れるか、product/finance/securityの明示判断 | authenticity/semanticsに疑義があればACTUAL禁止・UNKNOWN。test receiptを根拠にしない |
| card金額 | 公式price pageまたは契約rate、region/tier/model、JPY単位、effective window、FXを使う場合source/time/rate、総額roundingのfinance/legal確認 | approved digest registryは空のまま、production ACTUAL/UPPER_BOUNDを作らない |
| trust rootと運用 | offline signer責任者、key custody/rotation/revoke、dual control、release署名、監査証跡、incident stop/restore手順のsecurity/operations承認 | production verifierはdeny-all |
| 本番利用開始 | provider/Gemini実送信、AI/Copilot/external-send flag、budget、artifact ACTIVE、rolloutを個別に承認するrelease recordとhuman approver | 本PLANでは有効化不可 |

provider APIにusage応答署名がない場合、アプリ内issuerが作る署名やtest signing keyはprovider起源を証明しない。TLS認証を採用するという判断を人が承認しても、それはproviderの電子署名と同等だとは表現しない。承認記録には証拠モデルの限界を明記する。

## 実装順とレビューgate

1. **PLAN review（未完了）** — Astraがdata carrier、provenance限界、DB failure injection、key/card lifecycleを独立確認する。PASSしても実装・本番承認にはならない。
2. **P1 local provenance carrier** — `CopilotProviderResponse` / `CopilotExecutionFailure` / `AiGatewayResult` のcarrierとinner failure propagationを実装し、productionはdeny-all維持。synthetic loopbackのtyped error/usage testのみ。
3. **P2 attempt runtime binding** — `CopilotAttemptRecord` を `CopilotSummaryService` / `CopilotInvocation` / bounded adapterへ接続し、tenant/attempt/trace/status/version/nonce/model/config/preflight/time bindingを実装。replay/2-tenant testsを先に通す。
4. **P3 verifier integration behind fixed deny-all** — `CopilotProviderUsageEvidenceService` とtrusted verifierを接続し、test-only issuerでのみ署名/cardを使う。production keys/approved cardsが無い状態でUNKNOWN/deniedとなることを検証する。
5. **P4 metadata + real DB transaction matrix** — verifier resultだけを `SummaryCostEvidence` / `completeSummaryAttempt` へ渡す経路を作り、inner validation failure後のsynthetic pipeline、two-tenant sentinel、serialization/SQL/commit rollback、原exception保持を`@Tag("mysql")`で検証する。migration/DDLは別PLAN reviewなしに追加しない。
6. **P5 external evidence reviewは別タスク** — 公式provider/legal/price資料、offline signer、trust registry、human approvalを揃え、provider送信とproduction registry/flagsを有効化する作業は独立したplan、独立Astra review、明示human release approvalを必須とする。このB2作業に含めない。

各実装phaseは専用fast/MySQL gateとAstra CODE reviewを分離する。production policy、Gemini provider、external-send flags、artifact ACTIVE、DB schemaは本PLAN作成・PLAN review・synthetic testの結果では変更しない。

## 明示的な非承認範囲

この文書の存在またはPLAN review PASSは、provider contract/DPA/region、model token semantics、実料金、FX、usage authenticity acceptance、production key/card registry、実credential、Gemini実送信、Copilot/AI/external-send flag、artifact ACTIVE、database migration、production rolloutを承認しない。production gateway/adapterはdeny-all、現行flagsはOFFのままとする。実provider応答、実データ、外部network、実料金を使わない。
