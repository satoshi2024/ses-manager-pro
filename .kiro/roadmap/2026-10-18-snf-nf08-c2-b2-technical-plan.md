# NF08 C2-B2 実usage provenance・費用証拠の技術計画

**状態: A wave PLAN REVIEW PENDING。実装承認・provider送信承認・本番料金承認ではない。B2 provenance設計は未解決であり、この文書では承認・実装対象にしない。**

## 次wave A: B1の不足故障・tenant証拠だけを追加

このwaveは `CopilotRunService.completeSummaryAttempt` のDB境界を検証する追加MySQL試験だけに限定する。実装変更は試験用fault injection / fixtureに限り、ACTUAL、provider transport、usage provenance、費用評価、DDL/schema変更、production flag、registry/key、credentialへ触れない。試験classは `CopilotRunServiceMySqlIntegrationTest`、`@Tag("mysql")`、shard 1回登録を維持する。

### 固定する実装点と実transaction

- 対象beanは `@SpringBootTest` が注入する `CopilotRunService`。各成功/失敗経路で `AopUtils.isAopProxy(runService)` を確認し、`@Transactional(rollbackFor = Exception.class)` のSpring proxyを呼ぶ。手動 `new CopilotRunService` は使わない。
- transaction managerはcontextから注入する既定 `PlatformTransactionManager`（現構成のMySQL datasource transaction manager）。独立 `TransactionTemplate(transactionManager)` は外側transaction rollbackを作る場合だけ使用する。test method/classに `@Transactional` を付けない。
- baseline rowは `createFixture("tenant-a")` で作り、`readIndependently` の `DataSource.getConnection()` から全列snapshot（tenant、trace、use_case、status/version、cost、error、JSON、hashを含む全DB列）を取得する。tenant B sentinelも別fixtureとしてcommitし、failure前後を独立connectionで全列比較する。

### 故障注入・期待結果

1. **二tenant sentinel**: tenant A対象行と同時にtenant Bの独立sentinel rowを用意し、各失敗とstale経路の前後で両行の全列を比較する。context tenant不一致は既存の `AccountingTenantContextHolder.runWithTenant` で起こす。workerを使う場合は `finally` で `AccountingTenantContextHolder.clear()` と `SecurityContextHolder.clearContext()` を必ず行う。
2. **書込前JSON serialization failure**: `ObjectMapper.writeValueAsString` をspy/stubするtest-scoped beanで、対象completion metadata（`outcome` を故障fixture識別値にする場合など）に限定して例外を投げる。production `CopilotRunService` proxyを呼ぶため、`completeAttempt` 内の `toJson` がCAS SQLより前に失敗することを確認する。元のserialization exceptionが伝播し、対象A行・B sentinel両方が全列不変であることを独立readbackする。グローバルに全ObjectMapper呼出しを壊さない。
3. **実MySQL SQL failure**: test-only MySQL `BEFORE UPDATE` triggerを対象run IDに限定して作成し、`SIGNAL SQLSTATE '45000'` で実UPDATEを失敗させる。trigger作成/削除は専用JDBC connectionで管理し、テスト終了時finallyでdropする。mapperをmockして早期throwする方法は使わない。Spring proxy呼出しのSQL exceptionが伝播し、A/B全列が不変であることを独立readbackする。
4. **commit前failure / rollback**: `TransactionTemplate` で外側transactionを開始し、service proxyが `COMPLETED` を返した後、登録済み`TransactionSynchronization.beforeCommit` が専用marker exceptionを投げる。これはDB commit開始前の失敗を実発生させ、例外後にA/Bの全列がtransaction前snapshotへrollbackされたことを独立connectionで確認する。Springがtransactionをrollback-onlyにする挙動も記録する。
5. **commit後ACK喪失の意味**: commit完了後に呼出側へ応答が届かなかった状態をrollbackと偽装しない。これはrollback試験ではなく結果不明 `UNKNOWN` の仕様境界である。現行service APIにdurable commit receiptがないのでA waveでは本番状態遷移を追加せず、テストで「commit済み行をrollback扱いしない」事実だけを明記する。後続設計でack/reconciliationを扱うまでは、呼出側再試行が新たな送信を意味しないよう勝手に推論しない。

全fault caseで元exceptionをassertし、失敗を`STALE`や成功へ変換しない。Astra review後にこのA waveのみ実装する。

## B2: 小分け実装計画（PLAN REVIEW PENDING）

B2はA waveのMySQL境界証拠が完了し、別のAstra PLAN reviewを通った後にだけ実装する。A waveまたはB2のCODE PASSはprovider契約、usage意味、production料金、provider送信、鍵配布、production flagの承認にならない。今回の計画はtransport provenance、dispatch claim、私有費用能力を順に実装可能な小さな変更へ分ける。production policy / adapter / key registryは各batch後もdeny-allとする。

### 現行実装で確認した5つの阻断点

| 阻断点 | 現在のコード/DB | B2で満たす契約 |
|---|---|---|
| transport応答のauthority | `CopilotBoundedHttpTransport.postGemini` は公開 `Response(statusCode, contentType, body)` recordを返し、`CopilotGeminiResponseParser.parseEnvelope(byte[])` は任意byte列を受け取る。`GeminiTextServiceImpl` はusageを公開 `CopilotProviderUsage` へ平坦化する。request attempt、nonce、config、tenant、trace、endpoint、TLS検証との結合が型に残らない。 | parser/issuerは実送信transportだけが発行できる不変 `TransportBoundResponse` capabilityを要求する。raw body、public DTO、DB JSONからprovenanceを作らない。test用synthetic issuerは別型・test sourceに閉じ、production issuerが受け付けない。 |
| offline keyとprovider usageの混同 | `SignedUsageReceipt` は署名済みexecution inputを作るが、現状はtest-only verifier内で使われるsynthetic receiptである。Gemini自体はprovider応答usageへ署名しない。 | offline keyはimmutable `SignedRateCard` と承認済み `SignedUsagePolicy` だけを署名する。runtime usageはTLS hostname検証済みtransport受領とstrict parserに基づくアプリ内observationとし、provider署名/非否認を主張しない。synthetic receiptはproduction pathへ渡さない。 |
| 送信重複 | `completeSummaryAttempt` の `PENDING/status_version=0` CASは完了行の競合を防ぐが、CAS前に外部送信する構造なので一回限りの送信を保証しない。`TrustedRateCardVerifier.completedAttempts` はprocess-local cacheで、再起動/複数nodeのfenceではない。`t_ai_recommendation_run` にdispatch claim/nonce/expiry列はない。 | socketより前にDB上のdurable one-shot claimをcommitする。claim commit後・socket前を含むclaim後のcrashは未送信と推測せず `UNKNOWN`、同じattemptを再claim/再送しない。現schemaにはnonce・binding・claim stateのauthority列がないため、別migration proposalを要する。version番号は未割当とし、このplanではDDLを追加しない。 |
| 費用結果の偽造 | `SummaryCostEvidence` はpublic record/constructorで、`CopilotRunService.completeSummaryAttempt` は `SummaryResponse.costEvidence()` の `ACTUAL` をそのまま金額とmetadataへ保存できる。package-private `VerifiedCostEvaluation` の存在だけでは、この公開保存経路を閉じない。 | completion writerは公開DTO/`CopilotProviderUsage`をACTUAL authorityとして扱わない。検証器だけが作れるopaque capabilityを受け、同一transactionで現在attempt bindingを再読込・照合してからcost/status CASする。capabilityはDB JSONや通常DTOから復元不可。 |
| 順序と有効時間 | `CopilotQueryService` はsummary attemptを作ってから `CopilotSummaryService` / gatewayへ進み、preflightは `AiExecutionGatewayImpl.assertCopilotBoundedRequest` で行われる。nonce/attempt TTL/card dispatch時刻は未実装。transportは `System.nanoTime()` deadlineを持つがUTCのcard時間とattemptの期限を束ねない。 | 下記の固定順でpreflight、nonce/attempt発行、card時間、TTL、現在DB fields、claim、socket、応答検証、completionを行う。UTC `Instant` はcard/発行/応答時刻、monotonic deadlineは経過時間だけに使い、相互変換しない。 |

### authority型とtransaction境界

- `CopilotBoundedHttpTransport` の公開response recordを削除し、constructorを持たない公開面またはpackage-private accessorの `TransportBoundResponse` capabilityへ置き換える。唯一のissuerはbounded transportの `client.execute` response consumerとし、capabilityは正規endpoint/host、hostname/TLS identity確認結果、attempt/trace/tenant/use-case、nonce hash、config/model/preflight binding hash、request/response digest、HTTP status/content type、dispatch-start / response-received UTC時刻を不変保持する。API key、authorization header、prompt、raw bodyはcapabilityや例外/logへ複製しない。raw response bytesへのaccessは `com.ses.service.ai.impl` 内のparserだけに限定する。
- `CopilotGeminiResponseParser` のproduction入口は`byte[]`ではなく `TransportBoundResponse` を要求する。outer envelopeと全usage dimensionが有効な場合だけ、private constructorの `CopilotProviderUsageProvenance` をissueする。inner summary schema/claim検証が失敗しても有効envelopeのprovenanceはtyped failure内に保持する。outer envelope、usage、HTTP status/content type、期限のいずれかが不正ならprovenanceを発行しない。
- `CopilotProviderResponse`、`CopilotExecutionFailure`、`AiGatewayResult`、`CopilotProviderUsage`、`SummaryCostEvidence` は表示/summary outcome用の通常値であり、どれもprovenanceまたは費用認証をmintしない。特にLombok `@Data` のsetterや `SummaryCostEvidence` public constructorをauthorityとして使わない。DB JSON/`input_hash`も能力issuerとして扱わない。
- test sourceの `SyntheticBoundResponse` / synthetic issuer / synthetic rate verifierをproduction capabilityと型分離し、各結果に `SYNTHETIC_TEST_ONLY` を付ける。production verifierはsynthetic typeを受け取らず、production card/key registryは空、`ProductionDenyAllCopilotCostPolicy`とGemini production dispatch denyを維持する。
- offline署名はcard価格と`SignedUsagePolicy`（採用API/model revision、許容dimension、subset/total式、最大値、billable分類、rounding policy version）に限る。実runtime usageはoffline keyで署名しない。`TransportBoundResponse`はアプリが検証TLS接続から受け取った事実を束ねるもので、Google等providerの電子署名ではない。

### 事前順序: preflight → nonce/attempt → card time/TTL → current fields → claim → socket

| 順 | 実行場所 | 要求と失敗時の扱い |
|---|---|---|
| 1. context / config | `CopilotQueryService`からsummary実行境界へ | authenticated tenant、query/use-case、scope、artifact snapshot、provider/model/config hash、feature flag、budget、既存monotonic deadlineを検査する。tenant/artifact/config不一致なら送信せず拒否。 |
| 2. preflight | DB claim前、network外 | immutable approved policy/card snapshotからusage boundsとbudgetを評価し、preflight digestを確定する。deny、欠損bounds、未知usage semantics、budget超過、registry emptyならnonceもsummary dispatch attemptも作らず、送信しない。preflightのpublic recordは自身を証明しない。 |
| 3. nonceとattempt | preflight許可後、`beginSummaryAttempt` transaction | CSPRNGで独立の128-bit以上nonceとidempotency keyを生成する。UTC `issuedAt` と `expiresAt=issuedAt+configured TTL` を付け、hashとimmutable execution-binding digestをDBへ保存する。summary attemptは `PENDING/status_version=0/dispatch_state=READY` で一度だけ作る。nonceはlogに出さず、providerがidempotencyを保証すると仮定しない。 |
| 4. card time / TTL | claim直前とtransport直前 | `claimAt=Clock.instant()` をUTCで取得し、`effectiveFrom <= claimAt < effectiveUntil` を判定する。`claimAt >= issuedAt`、`claimAt < expiresAt`、monotonic deadline残量>0、host/config/policy/card digestがsnapshotと一致することを要求する。壁時計逆行、TTL expiry、card window境界はclaimせずfail-closed。response/evaluation UTC時刻が逆行またはattempt期限外ならusageを評価せず `UNKNOWN`。 |
| 5. current fields | claim transaction内 | primary rowをtenant + attempt id + trace + use_caseでlock/readし、`PENDING/v0/READY`、artifact/provider/model/config hash、preflight digest、nonce/idempotency hash、binding hash、issued/expires、scope/input identityが発行snapshotと完全一致することを確認する。DB row/JSONからcapabilityを再構成せず、DB値は発行済snapshotとの一致検査にだけ使う。 |
| 6. durable claim | 独立commit済み transaction、socketより前 | exact current fieldsをWHERE fenceに含む `READY → CLAIMED` one-row CASを実行し、claim ID/timeを保存する。更新件数が1でなければsocketへ進まない。commitが呼出側に不明ならclaim済みの可能性としてattemptを消費し、UNKNOWN/no retryとする。process cacheではclaimを代用しない。 |
| 7. transport / parse | claim commitの正常応答後のみ | 同じopaque claim permitをtransportへ渡し、同attemptのsocket exchangeを最大1回だけ開始する。HTTP response/strict usage envelopeが返ればtransport capabilityを発行する。claim後のtimeout/reset/crash/partial bodyには自動retryをしない。 |
| 8. verify / completion | provider I/O外の短い単一DB transaction | verifierがprivate cost capabilityを同じtransaction中に作り、attempt rowを再読込/lockしてclaim ID、nonce hash、binding hash、card/policy digest、status/version、expiry、dispatch observationを再照合する。summary result、verified cost metadata、`status=PENDING/v0 → SUCCEEDED|FAILED/v1`、dispatch terminal evidenceを原子的に保存する。CAS失敗はSTALE、SQL/JSON/commit errorはrollback/propagate。外部送信は既に一度消費済みであり再実行しない。 |

`NOT_SENT` はdurable claimが一度もcommitされず、denyが送信前に確定した場合だけである。claim rowの`CLAIMED`で結果情報がないもの、claim commit後のsocket前crash、socket後/response前crash、timeout、partial response、response/usage検証失敗は `UNKNOWN`（独立に検証可能なboundsがある場合のみ `UPPER_BOUND`）として扱い、同じattemptをretryしない。HTTP/TLS接続に成功した事実をprovider usageへの署名や請求額の証明と呼ばない。

### unallocated schema migration proposal

現在の `t_ai_recommendation_run`（V108 + V161 + V173）はtenant/trace/use_case/artifact、`PENDING/status_version` CAS、scope/input metadataを持つが、durable dispatch state、nonce/idempotency hash、preflight/binding digest、claim/response timestampsを持たない。`redacted_summary_json`はJSON metadataであり、dispatch authority/current capabilityの格納先にはしない。よってstatus CASだけを使う既存schemaでは上記の一回送信条件を明示・検査できない。A waveにも本実装にもDDLを足さず、次の独立migration reviewへ次のshapeを提案する。version番号はrootが別途割り当てるまで未割当。

提案は `t_ai_recommendation_run` へCopilot summary用nullable/legacy-safe列を追加する: `dispatch_state` (`NOT_APPLICABLE`/`READY`/`CLAIMED`/`UNKNOWN`)、`dispatch_nonce_hash BINARY(32)`、`dispatch_idempotency_hash BINARY(32)`、`dispatch_binding_hash BINARY(32)`、`preflight_snapshot_hash BINARY(32)`、`dispatch_request_sha256 BINARY(32)`、`dispatch_issued_at DATETIME(6)`、`dispatch_expires_at DATETIME(6)`、`dispatch_claimed_at DATETIME(6)`、`dispatch_started_at DATETIME(6)`、`response_received_at DATETIME(6)`、`response_sha256 BINARY(32)`。query/legacy rowsは`NOT_APPLICABLE`/NULLを保ち、summary rowはpreflight済みnonce/bindingを持つ`READY`で作る。tenant-scoped unique indexesをnonce/idempotency hashへ付け、同一summary attemptの `READY → CLAIMED` updateはtenant/id/trace/use_case/status/status_version/dispatch stateと全snapshot hashをWHERE条件に含める。claim commit後未完了の `CLAIMED` はread時にUNKNOWNとして表示し、reconcilerも再送claimを作らない。migration reviewでは既存version列、tenant nullability、index長、JSON consumers、H2 schema replay、migration backfill/default、並行claim時のaffected-rowを検証し、承認前に番号やDDLを確定しない。

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

1. **Astra PLAN review（未完了）** — capability issuer、実装順、schema gap/migration proposal、claim後crashの扱い、private cost capabilityとtest evidenceをレビューする。PASSしてもコード・DDL・本番承認にはならない。
2. **Migration proposal review（別gate）** — 列、unique/index設計、legacy backfill/default、H2 schema replay、MySQL locking/affected-row挙動をDB ownerが確認し、versionを割り当てるか判断する。承認前はschema依存のB2b以降を開始しない。
3. **B2a → B2b → B2c → B2d → B2e** — 各batchを上記の対象と完了証拠に限定し、Astra CODE reviewと必要なfast/MySQL gateを通してから次へ進む。migration未承認中にprocess cacheやJSON列で代用しない。
4. **外部資料・human releaseは別タスク** — provider API/DPA/法務、usage semantics、price、offline signer/key custody、production dispatch/registry/flag/artifact ACTIVEは個別承認する。承認まではproductionをdeny-allに保つ。

今回の作業はplan文書のレビュー可能化だけであり、A wave実装、B2 code/DDL、Maven/Docker suite、provider送信、production enablementは含まない。各実装phaseは専用fast/MySQL gateとAstra CODE reviewを分離する。
## 明示的な非承認範囲

この文書の存在またはPLAN review PASSは、provider contract/DPA/region、model token semantics、実料金、FX、usage authenticity acceptance、production key/card registry、実credential、Gemini実送信、Copilot/AI/external-send flag、artifact ACTIVE、database migration、production rolloutを承認しない。production gateway/adapterはdeny-all、現行flagsはOFFのままとする。実provider応答、実データ、外部network、実料金を使わない。
