# NF08 C2-B2 実usage provenance・費用証拠の技術計画

**状態: B1 Wave A PASS。B2a PLAN REVIEW PASS。実装CODE reviewとfast gateは未完了。今回承認された範囲は独立して実装可能なB2a契約に限る。B2aのPASSはB2b以降、provider送信、本番料金、DDLの承認ではない。**

## B2: 小分け実装計画（B2a PLAN PASS / 後続batchは個別review）

B1 Wave AのMySQL境界証拠はPASS済み。B2aの限定PLAN reviewはPASS済みであり、この小batchだけが実装CODE review対象となる。B2b以降はそれぞれ独立reviewを要する。B1または各B2 batchのCODE PASSはprovider契約、usage意味、production料金、provider送信、鍵配布、production flagの承認にならない。今回の計画はpublic ACTUAL保存経路をdenyするB2aを先に独立した最小実装として定義し、transport provenance、dispatch claim、私有費用能力を後続batchとして分ける。production policy / adapter / key registryは各batch後もdeny-allとする。
### B2a: 最小実装契約（PLAN PASS済）

B2aは既存のpublic ACTUAL保存入口を閉じるだけの独立batchとする。`CopilotRunService.completeAttempt` の共通DB writer境界で、private verifier capabilityがまだ存在しないB2aでは、public `SummaryCostEvidence` / `SummaryResponse` / `AiGatewayResult` 等に含まれる `ACTUAL` を例外で拒否する。実際のpublic completion入口は費用付きsummaryの `completeSummaryAttempt(...)` とcost-freeの `completeQueryAttempt(...)` である。ACTUAL拒否は両方が通るprivate共通writer `completeAttempt(...)` に置き、authoritative costState、`costEvidence.costState()`、inherited attempt metadataを検査する。拒否はmetadata構築/JSON化・UPDATEより前に起こし、attempt row全列を不変にする。B2aではprivate capability受入れ経路を作らず、ACTUALを例外的に通す互換pathも作らない。

B2aの検証はpublic `completeSummaryAttempt(...)` にsynthetic/public ACTUALを直接渡し、期待する具体的拒否例外と独立DB readbackによる全列不変を確認するservice proxy testとする。`UPPER_BOUND` / `UNKNOWN` / `NOT_SENT` の既存許可経路が意図せず変わらないことも個別確認する。production dispatch/cost policyはdeny-allのまま、provider transport、provenance issuer、rate registry/key、DDL、migration、credential、外部送信は触れない。このcontractはB2aだけで実装可能で、B2b以降の完成を前提にしない。

### 現行実装で確認した5つの阻断点

| 阻断点 | 現在のコード/DB | B2で満たす契約 |
|---|---|---|
| transport応答のauthority | `CopilotBoundedHttpTransport.postGemini` は公開 `Response(statusCode, contentType, body)` recordを返し、`CopilotGeminiResponseParser.parseEnvelope(byte[])` は任意byte列を受け取る。`GeminiTextServiceImpl` はusageを公開 `CopilotProviderUsage` へ平坦化する。request attempt、nonce、config、tenant、trace、endpoint、TLS検証との結合が型に残らない。 | parser/issuerは実送信transportだけが発行できる不変 `TransportBoundResponse` capabilityを要求する。raw body、public DTO、DB JSONからprovenanceを作らない。test用synthetic issuerは別型・test sourceに閉じ、production issuerが受け付けない。 |
| offline keyとprovider usageの混同 | `SignedUsageReceipt` は署名済みexecution inputを作るが、現状はtest-only verifier内で使われるsynthetic receiptである。Gemini自体はprovider応答usageへ署名しない。 | offline keyはimmutable `SignedRateCard` と承認済み `SignedUsagePolicy` だけを署名する。runtime usageはTLS hostname検証済みtransport受領とstrict parserに基づくアプリ内observationとし、provider署名/非否認を主張しない。synthetic receiptはproduction pathへ渡さない。 |
| 送信重複 | `completeSummaryAttempt` の `PENDING/status_version=0` CASは完了行の競合を防ぐが、CAS前に外部送信する構造なので一回限りの送信を保証しない。`TrustedRateCardVerifier.completedAttempts` はprocess-local cacheで、再起動/複数nodeのfenceではない。`t_ai_recommendation_run` にdispatch claim/nonce/expiry列はない。 | socketより前にDB上のdurable one-shot claimをcommitする。claim commit後・socket前を含むclaim後のcrashは未送信と推測せず `UNKNOWN`、同じattemptを再claim/再送しない。現schemaにはnonce・binding・claim stateのauthority列がないため、別migration proposalを要する。version番号は未割当とし、このplanではDDLを追加しない。 |
| 費用結果の偽造 | `SummaryCostEvidence` はpublic record/constructorで、`CopilotRunService.completeSummaryAttempt` は `SummaryResponse.costEvidence()` の `ACTUAL` をそのまま金額とmetadataへ保存できる。package-private `VerifiedCostEvaluation` の存在だけでは、この公開保存経路を閉じない。 | completion writerは公開DTO/`CopilotProviderUsage`をACTUAL authorityとして扱わない。検証器だけが作れるopaque capabilityを受け、同一transactionで現在attempt bindingを再読込・照合してからcost/status CASする。capabilityはDB JSONや通常DTOから復元不可。 |
| 順序と有効時間 | `CopilotQueryService` はsummary attemptを作ってから `CopilotSummaryService` / gatewayへ進み、preflightは `AiExecutionGatewayImpl.assertCopilotBoundedRequest` で行われる。nonce/attempt TTL/card dispatch時刻は未実装。transportは `System.nanoTime()` deadlineを持つがUTCのcard時間とattemptの期限を束ねない。 | 下記の固定順でpreflight、nonce/attempt発行、card時間、TTL、現在DB fields、claim、socket、応答検証、completionを行う。UTC `Instant` はcard/発行/応答時刻、monotonic deadlineは経過時間だけに使い、相互変換しない。 |

### authority型とtransaction境界

- `CopilotBoundedHttpTransport` の公開 `Response` recordをauthority境界から除く。production capabilityはpublic interface（constructorが無いinterfaceを含む）やpublic DTOにせず、通常のsource codeから構築/実装/serialization/reconstructionできないprivate final/sealed型とする。唯一issuerはbounded transport実装のprivate issuer経路に限定し、同一クラス/強い閉包内でrequest送受信とparserを連続実行する形を優先する。constructorless public interface、package内factory、public setter、reflection fallbackをissuerにしない。capabilityはendpoint/検証済host identity、attempt/trace/tenant/use-case、nonce hash、config/model/preflight hash、request/response digest、HTTP status/content type、dispatch-start/response-received UTC時刻をimmutable保持する。API key、authorization header、prompt、raw bodyをcapability/例外/logへ複製しない。raw payloadは閉じたparser内部でのみ扱い、例外、log、DTO、DB JSONへ出さない。
- `CopilotGeminiResponseParser` のproduction入口は`byte[]`ではなく `TransportBoundResponse` を要求する。outer envelopeと全usage dimensionが有効な場合だけ、private constructorの `CopilotProviderUsageProvenance` をissueする。inner summary schema/claim検証が失敗しても有効envelopeのprovenanceはtyped failure内に保持する。outer envelope、usage、HTTP status/content type、期限のいずれかが不正ならprovenanceを発行しない。
- `CopilotProviderResponse`、`CopilotExecutionFailure`、`AiGatewayResult`、`CopilotProviderUsage`、`SummaryCostEvidence` は表示/summary outcome用の通常値であり、どれもprovenanceまたは費用認証をmintしない。特にLombok `@Data` のsetterや `SummaryCostEvidence` public constructorをauthorityとして使わない。DB JSON/`input_hash`も能力issuerとして扱わない。
- test sourceの `SyntheticBoundResponse` / synthetic issuer / synthetic rate verifierをproduction capabilityと型分離し、各結果に `SYNTHETIC_TEST_ONLY` を付ける。production verifierはsynthetic typeを受け取らず、production card/key registryは空、`ProductionDenyAllCopilotCostPolicy`とGemini production dispatch denyを維持する。
- offline署名はcard価格と`SignedUsagePolicy`（採用API/model revision、許容dimension、subset/total式、最大値、billable分類、rounding policy version）に限る。実runtime usageはoffline keyで署名しない。`TransportBoundResponse`はアプリが検証TLS接続から受け取った事実を束ねるもので、Google等providerの電子署名ではない。

### 事前順序: preflight → nonce/attempt → current fields lock → fresh clock/card TTL → claim CAS → final recheck → socket

| 順 | 実行場所 | 要求と失敗時の扱い |
|---|---|---|
| 1. context / config | `CopilotQueryService`からsummary実行境界へ | authenticated tenant、query/use-case、scope、artifact snapshot、provider/model/config hash、feature flag、budget、既存monotonic deadlineを検査する。tenant/artifact/config不一致なら送信せず拒否。 |
| 2. preflight | DB claim前、network外 | immutable approved policy/card snapshotからusage boundsとbudgetを評価し、preflight digestを確定する。deny、欠損bounds、未知usage semantics、budget超過、registry emptyならnonceもsummary dispatch attemptも作らず、送信しない。public preflight recordはauthorityではない。 |
| 3. nonceとattempt | preflight許可後、`beginSummaryAttempt` transaction | CSPRNGで独立の128-bit以上nonceとidempotency keyを生成する。UTC `issuedAt` と `expiresAt=issuedAt+configured TTL` を付け、nonce/idempotency hashとimmutable binding digestを保存する。summary dispatch attemptは `PENDING/status_version=0/dispatch_state=READY` で一度だけ作る。nonceはlogへ出さず、provider idempotencyを仮定しない。 |
| 4. current fields lock/read | claim transaction開始後、row lock保持中 | tenant + attempt id + trace + use_caseで対象rowをlockし、`PENDING/v0/READY` と発行時snapshot（artifact/provider/model/region/tier/config/preflight hash、nonce/idempotency hash、binding hash、issued/expires、scope/input identity、card/policy id/version/digest）を全て照合する。どれか不一致ならclaim前に拒否し、rowを変更しない。DB/JSONからcapabilityを作らない。 |
| 5. fresh clock/card TTL | **lock取得と全current-fields照合の後、CAS直前に新たに** `Clock.instant()` を読む | `effectiveFrom <= claimAt < effectiveUntil`、`issuedAt <= claimAt < expiresAt`、monotonic deadline残量>0を検査する。以前のpreflight/attempt時刻を使い回さない。逆行、期限切れ、card境界外ならclaimせず拒否する。 |
| 6. durable claim CAS | 上記transaction内でCASし、provider I/O前にcommit完了を確認 | 全てのcurrent fieldsとstatus/version/stateをWHERE fenceに含む `READY → CLAIMED` exact one-row CASを行いclaim ID/timeとclaim binding snapshotを保存する。更新件数が1でない、commit結果が呼出側に不明、commit例外ならsocketへ進まない。claim済みの可能性がある場合は消費済み `UNKNOWN` とし、絶対に `READY` / `NOT_SENT` へ戻さず再claimしない。 |
| 7. final recheck / one-shot permit consume | claim commit後、socket直前 | transport内のprivate permitをatomic compare-and-setで一回だけconsumeし、permitのrequest/card/policy/current-binding digest、fresh card/attempt TTL、monotonic deadlineを再検査する。同一permitの並行/順次再利用は拒否する。ここで不一致・期限切れ・DB再読込不整合があればsocketは0回。durable claimは解放せず `CLAIMED` を消費済みのまま維持し、意味上 `UNKNOWN` とする。 |
| 8. socket / transport observation | permit consume成功後のみ | 同じclaim permitについてHTTP exchangeは最大1回。実request-bound responseだけがprivate transport observationを作る。claim後のtimeout/reset/crash/partial bodyではretryしない。response UTC時刻がdispatch/issued/expiryと矛盾すればACTUALを作らない。 |
| 9. verify / completion | provider I/O外の単一completion transaction | verifierがprivate cost capabilityを作成し、rowを再読込/lockしてclaim ID、nonce hash、binding hash、card/policy digest、status/version、expiry、dispatch observationを再照合する。summary/status/cost/metadataを一度に保存し、更新条件不一致はSTALE、SQL/JSON/commit errorはrollback/propagateする。同じattemptの送信は再開しない。 |

claim rowが`CLAIMED`のまま結果を確定できない状態、claim commit後socket前のcrash、socket後/response前のcrash、timeout、partial response、response/usage検証失敗はUNKNOWNである。`NOT_SENT`はclaimが一度もcommitされず、denyが送信前に確定した場合だけとする。独立に検証可能なpreflight上限がある場合だけ `UPPER_BOUND` を許す。HTTP/TLS接続はprovider署名や請求額証明ではない。
### unallocated schema migration proposal

現行 `t_ai_recommendation_run`（V108 + V161 + V173）はtenant/trace/use_case/artifact、`PENDING/status_version` CAS、scope/input metadataを持つが、durable dispatch claim、claim ID、nonce/idempotency、card/policy snapshot、transport observation列を持たない。`redacted_summary_json`は監査metadataでありauthority/current bindingの格納先にしない。status CASまたはprocess-local cacheだけでは一回送信を保証できない。B2a/B2bはDDLを変更しない。次の別migration reviewへ以下の未割当version proposalを出し、DB owner承認とversion割当後にだけB2cで実装する。

`t_ai_recommendation_run`へsummary dispatch専用のnullable/legacy-safe列を提案する。全てのhashはcanonical encodingのSHA-256、secret nonce/idempotency値そのものは保存しない。

- **one-shot状態/識別**: `dispatch_state` (`NOT_APPLICABLE`/`READY`/`CLAIMED`/`UNKNOWN`)、`dispatch_claim_id BINARY(16)`、`dispatch_nonce_hash BINARY(32)`、`dispatch_idempotency_hash BINARY(32)`、`dispatch_claimed_at DATETIME(6)`、`dispatch_started_at DATETIME(6)`。tenant-scoped unique indexをnonce hash、idempotency hash、claim IDへ置く。NULLのlegacy/query rowは複数許容されるindex仕様を実DBで検証する。
- **attempt/current binding authority**: `dispatch_binding_hash BINARY(32)`、`preflight_snapshot_hash BINARY(32)`、`dispatch_request_sha256 BINARY(32)`、`dispatch_issued_at DATETIME(6)`、`dispatch_expires_at DATETIME(6)`、および現行rowに既存でない各値を比較可能にする`dispatch_provider`、`dispatch_model_revision`、`dispatch_region`、`dispatch_tier`、`dispatch_config_hash BINARY(32)`、`dispatch_artifact_version_id`、`dispatch_scope_hash BINARY(32)`、`dispatch_input_hash BINARY(32)`。既存tenant/id/trace/use_case/status/status_versionと併せ、発行時snapshotの完全なfield mapを作り、claim SQLのWHERE条件とcompletion transactionの再照合対象にする。aggregate binding hashはfield-by-field照合の代わりにしない。
- **card/policy snapshot**: `dispatch_card_id`、`dispatch_card_version`、`dispatch_card_digest BINARY(32)`、`dispatch_policy_id`、`dispatch_policy_version`、`dispatch_policy_digest BINARY(32)`。これらは発行時固定値との照合用で、DB値から署名済card/policy capabilityを復元しない。
- **transport observation**: `dispatch_endpoint_id`または正規host identity、`dispatch_tls_peer_hash BINARY(32)`、`dispatch_observed_at DATETIME(6)`、`response_received_at DATETIME(6)`、`response_http_status SMALLINT`、`response_content_type`、`response_sha256 BINARY(32)`。raw request/responseは保存しない。response observationだけでusage authenticityを主張しない。


binding digestを計算するcanonical field mapとDB照合先を固定する。以下はcapability source snapshotとDB rowの比較表であり、DB値単独からcapabilityを再生成してはならない。

| binding要素 | claim rowの永続field | 固定・照合条件 |
|---|---|---|
| tenant / attempt / trace / use-case | 既存 `tenant_id` / `id` / `trace_id` / `use_case` | tenant contextと発行済みattemptに完全一致。tenant-scoped fenceに含める。 |
| status / version / dispatch state | 既存 `status` / `status_version` + `dispatch_state` | `PENDING/v0/READY` の完全一致。claim時のみ `READY → CLAIMED`。 |
| artifact / scope / input | `dispatch_artifact_version_id` / `dispatch_scope_hash` / `dispatch_input_hash` | 呼出し時immutable snapshotと照合。hashはcanonical encoding。 |
| provider / model / region / tier / config | `dispatch_provider` / `dispatch_model_revision` / `dispatch_region` / `dispatch_tier` / `dispatch_config_hash` | artifact/config snapshotと完全一致し、claim後変更を禁止する。 |
| preflight / nonce / idempotency / attempt TTL | `preflight_snapshot_hash` / `dispatch_nonce_hash` / `dispatch_idempotency_hash` / `dispatch_issued_at` / `dispatch_expires_at` | 発行時に固定。raw nonce/keyは永続化しない。 |
| card / policy | `dispatch_card_id` / `dispatch_card_version` / `dispatch_card_digest` / `dispatch_policy_id` / `dispatch_policy_version` / `dispatch_policy_digest` | 署名済みimmutable snapshotのidentity/digestと照合。JSONから復元しない。 |
| request / claim | `dispatch_request_sha256` / `dispatch_claim_id` / `dispatch_claimed_at` | exact request digestと一意claim identityをCAS対象へ含める。 |
| endpoint / TLS / response | `dispatch_endpoint_id` / `dispatch_tls_peer_hash` / `dispatch_started_at` / `dispatch_observed_at` / `response_received_at` / `response_http_status` / `response_content_type` / `response_sha256` | transportが観測後に記録。raw payloadは保持しない。 |
legacy/query rowsは`NOT_APPLICABLE`/NULLを維持し、preflight許可後の新summary dispatch rowだけ`READY`で作る。claim transactionはrowをlockし全current-field mapを比較した後、fresh clockでcard/TTLを確認し、tenant/id/trace/use_case/status/status_version/dispatch_stateと上記current/card/policy/binding hashesを含むexact `READY → CLAIMED` CASを行い、claim IDと時刻を保存してcommitする。commit済み`CLAIMED`は再送可能状態へ戻さない。read/reconciliation時に完了記録の無いclaimは意味上UNKNOWNとし、別プロセスからも再claimを許さない。

migration reviewではlegacy backfill/default、tenant nullability、index length/NULL semantics、column bounds/collation、H2 schema replay、MySQL concurrent claim affected-row、MyBatis entity update-strategy、legacy rowsと他use-caseへの影響を検証する。DDL本文もmigration version番号もこのplanでは確定しない。
## B2後続batchのscopeと完了条件

| batch | 実装境界 | 必須完了証拠 / 明示的な外 |
|---|---|---|
| **B2a（PLAN PASS対象）** | 共通completion writerでpublic `ACTUAL` を永続化前に拒否する。private verified capabilityのacceptance pathはまだ作らない。 | direct forged ACTUALをservice proxyへ渡し、具体的拒否とattempt全列不変を独立readbackで確認。deny-all維持。DDL、provider送信、transport変更なし。これだけで独立実装可能。 |
| **B2b** | transport-bound response observationとstrict parser境界。実requestの送受信点からだけprivate observationを作り、synthetic loopback専用型はproduction authorityから分離する。 | parser public raw-body/DTO入口がないこと、valid outer + inner failure observation保持、不正outer/timeout/resetでprovenanceなし、sensitive payloadがlog/exception/DTOに出ないこと。schema/実providerなし。 |
| **B2c** | DB ownerが別reviewでmigration proposalを承認しversion割当した後にschema/entity/mapperとdurable one-shot claimを実装する。DB claimは跨processの重複送信を防ぐ。transport内のprivate final `ClaimPermit` はprivate issuerだけが生成し、non-serializable/non-cloneableとする。`AtomicBoolean.compareAndSet(false, true)`で一度だけconsumeし、permit再利用・別process移送・DBからの再構築を拒否する。local permit CASはdurable DB claimの代替ではない。 | MySQL二connection claim raceで1/0、同じpermitの並行・順次reuseでsocket最大1回、process restart後もDB claimで再送0回。post-claim pre-socket mismatchはCLAIMED消費済み/UNKNOWNのまま。 |
| **B2d** | verifierだけが作るprivate cost capabilityを同じcompletion transactionへ渡し、DB current field/card/policy/bindingを再確認してstatus/costを原子的に保存する。B2a guardをこのcapability-only pathで置換する。 | forged DTO拒否継続、capability有効時のみACTUAL、各binding/card/policy/tenant/status drift拒否、SQL/serialization/commit rollback。production registryとdispatch gateはdeny-all。 |
| **B2e** | timeout/reset/partial response、clock/TTL/card境界、inner failure、multi-tenant、restart、response/completion crash matrixと運用観測を仕上げる。 | 各UNKNOWN/UPPER_BOUND/NOT_SENT分類と不再送をdeterministic/MySQLで立証。外部provider、契約、実価格、production rolloutは含めない。 |

今回のB2a PLAN PASS対象は「private共通writer `completeAttempt(...)` がACTUAL state/evidenceを全経路で拒否し、拒否前のDB状態を不変にする最小差分」である。B2b～B2eを含むB2全体の設計PASSや実装許可ではない。後続batchは対象batchごとのPLAN/CODE reviewを行う。
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

## Usage意味・不確実性

`CopilotGeminiResponseParser.validateUsage` は現状 `promptTokenCount` / `candidatesTokenCount` / `totalTokenCount` を受け取り、`thoughtsTokenCount` と `cachedContentTokenCount` は欠落または0だけを許す。B2実装前に、採用するprovider・API version・model revisionについて公式仕様と契約資料でfieldの意味、subset関係、billable分類、最大値、cached/thinking処理、請求時の集計/丸めを確認する。資料がA2契約の包含仮定（cachedはpromptの部分集合、thinkingはcandidateの部分集合）を保証しないなら、そのdimensionを料金計算へ入れず、production費用は `UNKNOWN` にする。

未認証または不正usageでは、preflightのtrusted上限をusageから作り直さない。独立した上限が存在し、cardとpreflight snapshotが認証済みで、dispatchが開始された可能性がある時だけ `UPPER_BOUND` を許す。`promptMax` または `candidateMax` が欠落、rateが未確認/負/単位不明、card期限外、preflight binding不一致、算術overflowなら `UNKNOWN` / cost nullへ落とす。response受領後のinner JSON不正はusage認証と費用計算の可否を単独では変えず、summary statusだけをFAILEDにする。

## Key/card発行・rotation・停止

- cardは `SignedRateCard` のcanonical bytes、card ID/version、provider/model revision、region/tier、JPY単位、effective window、全rate dimension、rounding、issuer key IDを含めて署名する。不変cardを更新せず価格変更ごとに新version/digestを発行する。公開keyとapproved digest registryは将来の承認済みreleaseへ固定し、DB、runtime property、admin UI、test artifactをtrust rootにしない。
- 署名秘密鍵はoffline release環境だけに置き、アプリ、CI、DB、test fixtureへコピーしない。key rotationは旧/新public keyの承認済みoverlap、new cardの発効時刻、旧key停止時刻、compromise時の即時revoke・再評価停止手順をrelease manifestに記録する。歴史行の再検証には保存済みcard version/digest/key IDが必要だが、過去に作られた料金結果を新rateで再計算しない。
- unknown/revoked key、未登録digest、expired/not-yet-effective card、署名不一致は `ACTUAL`/`UPPER_BOUND` にせず、利用可能な独立上限がなければUNKNOWNにする。registryが空、verifier設定欠落、key ring読込失敗、時刻信頼異常、緊急停止中は拒否する。
- panic stopはproduction provider dispatch gateとcost evaluator gateを同時にdenyする独立運用スイッチとして将来設計する。ただし本PLANと実装は既存の固定deny-allを解除する設定や停止解除経路を追加しない。停止後は未完了attemptの自動再送を行わず、送信開始状態に応じてUNKNOWNまたは証明済みUPPER_BOUNDにする。

## ローカルで実装・検証できる範囲

B2の合成検証は実credential/provider/network/実料金を使わず、synthetic fixtureには `SYNTHETIC_TEST_ONLY` を付ける。synthetic response/receiptをproduction provenance issuerやproduction ACTUALへ変換しない。次のcaseを各batchの必須証拠とする。

1. **preflight順序**: deny理由を個別に指定し、attempt/nonce insertが0件、claim SQLとtransportの呼出しが0回であることを確認する。allow caseでは保存したconfig/artifact/context/preflight digestが後段bindingと一致すること、tenant/artifact/config driftごとに具体的な拒否結果となりdispatch state/row snapshotが不変であることを確認する。
2. **runtime observationとparser boundary**: production parserはraw `byte[]` / public DTOから呼べない型/APIであることをcompile-timeまたはreflection testで確認する。実request-bound loopback応答だけがtransport observationを作る。valid outer usage + invalid inner summaryではusage observationをfailure pathでも保持し、invalid outer usage、unknown dimension、HTTP/content-type不正、timeout/reset/partial bodyではusage capabilityを発行しない。raw body、prompt、authorization値がcapability、例外、redacted metadataに入らない。
3. **one-shot dispatch claim**: MySQL二connectionで同一READY rowを競合claimし、成功は一方のみ（affected rows 1/0）とする。claim transaction commit後・socket開始前にfaultを注入するとstateはUNKNOWN相当で消費済みとなり、同attempt再実行でclaim再取得・socket再送がない。process restartを跨いでも同じDB状態となり、process-local cache無しで成立する。MySQL testは実mapper SQLと独立readbackを使う。
4. **ACTUAL authorityとcompletion**: public `SummaryCostEvidence`/`SummaryResponse`/`AiGatewayResult` を直接構築・変更してACTUALを注入してもcompletionで拒否し、row全列不変とする。検証器が発行したprivate capabilityは同一completion transactionのclaim/current binding一致時のみ保存できる。tenant/id/trace/use-case/status/version/nonce/config/preflight/card/bindingの各driftを個別に作り、期待する409/STALE相当を具体的にassertし、対象rowと別tenant sentinelの全列前後比較を行う。
5. **clock/TTL/card境界**: productionコードと同じ注入可能Clockを使い、fixed instantで `effectiveFrom` の包含・`effectiveUntil` の排他、`issuedAt <= claimAt < expiresAt`、response時刻の逆行、TTL満了、壁時計逆行を決定的に確認する。expired/clock-invalidはclaim/socket前に拒否し、response後の期限外/時刻逆行はACTUALを作らずUNKNOWNとする。monotonic deadlineはfake tickerで別途検査し、sleepや幅広assertで時刻競合を隠さない。

MySQL専用caseは`@Tag("mysql")`で一度だけshard登録し、test method自身へ`@Transactional`を付けない。commit済み状態は独立connectionで確認する。H2はpure decision/type mappingのfast feedbackに限り、SQL dialect、atomic claim、lock、affected-row、crash/restart永続性の証拠に数えない。必要なschema migration replay / legacy-row default / unique-index重複拒否もMySQL gateで検証する。Maven/Docker suiteの実行は実装taskのgateであり、このplan-only変更では実行しない。
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

1. **B2a PLAN review（PASS）** — 共通completion writerのpublic ACTUAL拒否、拒否前のDB不変、deny-all維持、DDL/provider非接触という最小contractだけをreviewする。このPASSで許可された作業範囲はB2a単独のcode changeとそのCODE review/fast gateである。B2全体、B2b以降、DDL、provider送信、本番承認は含まない。
2. **B2a CODE review / fast gate** — public forged ACTUALの拒否とrow全列不変をservice proxyで検証する。B2aにはmigration/MySQL concurrency/provider fixtureを持ち込まない。
3. **B2b～B2eは個別PLAN/CODE review** — observation/parser、migration/durable claim、同一completion transactionのcost capability、failure matrixを順に扱う。B2cはmigration proposalへのDB owner承認とversion割当の後に開始する。各batchに必要なfast/MySQL gateを通す。
4. **外部資料・human releaseは別タスク** — provider API/DPA/法務、usage semantics、price、offline signer/key custody、production dispatch/registry/flag/artifact ACTIVEは個別承認する。承認まではproductionをdeny-allに保つ。

今回の作業はplan文書のレビュー可能化だけであり、A wave/B2のコード、DDL、Maven/Docker suite、provider送信、production enablementは含まない。各実装phaseは専用gateとAstra CODE reviewを分離する。
## 明示的な非承認範囲

この文書の存在またはPLAN review PASSは、provider contract/DPA/region、model token semantics、実料金、FX、usage authenticity acceptance、production key/card registry、実credential、Gemini実送信、Copilot/AI/external-send flag、artifact ACTIVE、database migration、production rolloutを承認しない。production gateway/adapterはdeny-all、現行flagsはOFFのままとする。実provider応答、実データ、外部network、実料金を使わない。
