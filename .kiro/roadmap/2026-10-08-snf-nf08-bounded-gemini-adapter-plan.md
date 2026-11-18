# NF08 bounded Gemini summary adapter 接線計画

## 1. 目的と状態

- 対象: NF08 summary専用のGemini bounded adapterを実経路へ接続する。
- 基準: `codex/snf-prod-nf08-summary-boundary`。現時点の実装基準は `020dc4d1`。この文書は実装前の計画であり、Astraの静的PLAN review待ちとする。
- 目標: 既存の `CopilotSummaryService` → `AiExecutionGatewayImpl` の認可・tenant・artifact境界を保ったまま、Gemini summaryだけを専用HC5 transportへ通し、同一monotonic deadline内にimmutable invocationからrequest生成、送信、bounded parse、summary JSON parse、数値禁止検査を完了する。
- 実装は契約・production deny-all、strict adapter、attempt cost伝播の3段階に分ける。第一sliceではproduction cost policyを固定deny-allとし、外部provider requestを一切許可しない。合成cost policyは`src/test`内のloopback fixtureだけで使う。実provider向けcost policyと実運用解放は別の証明・承認gateに残す。
- 本作業ではproduction flag、外部送信、実credential、実provider、実data、artifact publish、既存DB row、Flyway migrationを変更しない。push、PR、deployも行わない。

## 2. 現在の実装境界と未接続点

`CopilotQueryService` はquery開始時に `CopilotRunService.pinActiveArtifact()` を一度だけ呼び、query attemptとsummary attemptへ同じ `CopilotArtifactSnapshot` を渡す。`CopilotSummaryService` はsummary入口で15秒の `deadlineNanos` を作り、`AiGatewayRequest` にdeadline、cost budget、artifact id/provider/model/prompt/config identityを設定する。`AiExecutionGatewayImpl` は送信直前にcontext、provider/model/prompt/config identityを比較し、旧 `generate(String)` ではなく `CopilotBoundedAiTextService.generateCopilotSummary` を呼ぶ。

ただし `CopilotBoundedAiTextService` を実装しているのは現状 `MockAiTextServiceImpl` と `RuleAiTextServiceImpl` だけである。`GeminiTextServiceImpl` は通常用途の `generate(String)` を `RestTemplate` で実装するが、Copilot bounded interfaceを実装せず、Copilot専用transportも呼ばない。通常経路は変更せず、Copilot経路だけ別メソッドへ接続する必要がある。

`CopilotBoundedHttpTransport`、`CopilotDnsSnapshotResolver`、`CopilotTransportProperties`、`CopilotTransportConfiguration` は存在する。`ai.copilot.transport.hosts` は期限付きnumeric address snapshotを受け取り、未設定時は空である。初期化時に設定snapshotを読み込み、runtime DNS fallbackや自動refreshはない。productionでは `generativelanguage.googleapis.com` の承認済みaddress bytesとexpiryがなければ送信できない設計を保つ。

`CopilotBoundedJsonParser` はbyte cap、深さ、文字列、数値、UTF-8、末尾token、deadlineを検査できるが、現在どのsummary経路からも呼ばれていない。Gemini provider envelopeのparseと、その `candidates[0].content.parts[0].text` に含まれるsummary JSONのparseをbounded parserへ接続する必要がある。現行 `CopilotSummaryService.parseProviderJson` は共有 `ObjectMapper` でparseし、fenced JSONを許容してから `CopilotSummaryValidator` を呼ぶ。Copilot経路はstrict parserを使い、fenceを剥がして不正応答を通す挙動をやめる。

費用境界も未完了である。`SummaryRequest.costBudgetYen` は常に `0`、`AiGatewayRequest.costBudgetYen` もその値を受け取る。gatewayはGeminiの `localOnly=false` を見て `COPILOT_COST_UNKNOWN` を送信前に返す。summary成功時の `CopilotSummaryService` はproviderに関係なくcostを `0` にしている。`AiGatewayResult` とbounded serviceの戻り値にprovider usage/cost情報がなく、Copilot cost policy、rate card、正確な入力token上限契約もないため、このままGeminiを有効化してはいけない。`maxOutputTokens` / `ai.maxTokens` は出力上限であり、prompt tokenの上限証明ではない。Geminiは`count_tokens`で入力tokenを数えられるが、第一sliceで外部`countTokens` callを追加して費用証明に使わない。Googleの公式説明は[トークン計測](https://ai.google.dev/gemini-api/docs/generate-content/tokens)、[GenerateContent応答とusageMetadata](https://ai.google.dev/api/generate-content)、[モデル別価格](https://ai.google.dev/gemini-api/docs/pricing)を参照する。

summary attemptの失敗費用も未保持である。現在の`SummaryResponse.unavailable()`はcostがnullで、`CopilotRunService.completeSummaryAttempt()`はcost非nullだけを`KNOWN`として記録する。このため、外部dispatch後にtimeout・不正JSON・数値検査失敗・通信切断が起きた場合の費用状態を明示できない。schema変更なしでredacted attempt metadataを拡張し、preflight cost evidenceとdispatch outcomeを不可変値として追跡する必要がある。

Copilot経路はmutableな`AiConfig`をgateway検査時と`GeminiTextServiceImpl`送信時に別々に読み得る。`CopilotBoundedAiTextService.generateCopilotSummary(String,long)`にはexpected provider/model/config identityやpreflight証拠がなく、gateway検査後に設定が変わると実送信先・generation settingsが検査値と異なる余地がある。startup時の不可変`CopilotConfigSnapshot`と、secretを持たないimmutable `CopilotInvocation`（expected identity/deadline/preflight evidence）を導入し、identity検査と実request組立てを同じsnapshotに結び付ける。

運用上の既定は `ai.provider=mock`、`ai.management-copilot-enabled=false`、`ai.external-send-enabled=false`。`AiProductionApprovalGate` はowner、approved catalog、allowed roles、provider contract、NF07、DG08、既存AI gate、retention、cost limit、human escalationの全承認を要求する。加えてgatewayには外部providerの既知costを拒否する独立判定がある。いずれもこの計画で有効化・承認しない。

`V148__ai_management_copilot_f1_artifact.sql` は `mock` 用の固定placeholder `config_hash` をACTIVEとしてseedする。これは実adapterのcanonical identityではないため、既存rowを修正・上書きせず、provider/model/config hash不一致でsummaryをfail-closedしtyped resultを保つ。`AiArtifactVersionService` はSHADOW昇格と評価済み候補の昇格を実装するが、候補を安全に作成・refreshするproduction向けpublish手順は確認できていない。評価APIは `test`/`dev` profileに限定される。Gemini artifactの作成、評価、承認、ACTIVE化はこのadapter接線とは別のartifact refresh gateとして扱う。

## 3. adapterの設計

### 3.1 Provider入口とrequest

`GeminiTextServiceImpl` を単一 `AiTextService` beanのまま `CopilotBoundedAiTextService` 対応にする。既存 `generate(String)` と通常AI機能の `RestTemplate` 呼出し・timeout・response parseは変更しない。新しい `generateCopilotSummary` だけが `CopilotBoundedHttpTransport` を使い、legacy methodへ委譲しない。

Copilot requestはgatewayが作ったallowlist済みpromptから、Geminiの `contents[].parts[].text` と、snapshotに固定したgeneration settingsだけを組み立てる。request bodyのserializeとsize checkも同じdeadlineに含める。`maxOutputTokens` は出力上限のみに使い、入力token上限や費用上限の証明として扱わない。第一sliceのproduction policyはdeny-allのため、実providerは送信されない。redirect/retryを行わない。

startup時に`CopilotConfigSnapshot`を一度だけ構築し、provider、canonical endpoint、model、prompt/schema identity、generation settings、設定identityをimmutableに保持する。実request組立てとgateway identity比較はどちらも同じsnapshotを使い、送信時に`AiConfig`を読み直さない。`CopilotInvocation`はimmutableかつsecretを含まず、expected provider/model/prompt/config identity、同一monotonic deadline、preflight cost evidence、送信可能判定を持つ。mutable `AiConfig` をgateway検査直後に変更する競合テストで、実endpoint/model/settingsが検査時snapshotの値のままか、もしくは送信前denyされ、変更後の値へ切り替わらないことを確認する。

URLはHTTPS、port 443、完全一致host `generativelanguage.googleapis.com`、固定 `:generateContent` endpointに限定する。userinfo/query/fragment/IP literal/別host/任意portは拒否する。configured modelは一つのpath segmentとしてencodeし、canonical URLのidentity照合後にだけ使う。`OutboundUrlGuard.validateExactHostHttpsUrl` のallowlist検査を保ち、専用resolver snapshotが接続addressを固定する。URI hostは元hostnameのまま保持し、既定のTLS hostname verification/SNIを維持する。trust-all、証明書検証無効化、IP URL接続を導入しない。

API keyはimmutable invocation、config snapshot、gateway request/result、artifact、ログ、例外文、attempt metadataへ入れない。dispatch時にsecret storeからkeyを解決し、blankなら送信前に拒否する。keyはHC5の `x-goog-api-key` headerへ設定する瞬間だけ利用し、URI/body/DTO/toStringへ保持しない。synthetic fixtureでもheaderの一致を真偽だけで検証し、key値を失敗文やcapture artifactへ保存しない。

transportはHC5 5.3.1のHTTP/1.1専用bounded実装を再利用する。現行 `post(URI, byte[], deadline)` には任意headerの入力経路がないため、任意header map/credential DTOは作らず、Gemini key headerだけを安全に渡す専用APIまたは限定header setterを設ける。redirect無効、retry無効、HTTP/1.1 hard cancel、DNS fresh check、64KiB response上限、pool/admission上限を維持する。API keyやbodyを例外・ログに出さない。Copilot経路のproduction dispatchはcost policyで送信前に必ず拒否し、このtransportを通れるのはtest sourceにあるloopback-only合成設定だけとする。

### 3.2 bounded parseとsummary validation

同じ `deadlineNanos` をtransport、outer provider envelope parse、inner summary JSON parse、schema検査、`CopilotSummaryValidator` に渡す。outer response bytesは `CopilotBoundedJsonParser` でstrict UTF-8 JSONとしてparseする。許容するroot fieldを固定し、成功responseではちょうど1 candidate、そのcandidateのfinish reasonが`STOP`、contentにちょうど1つの非-thought text part、明示したmime/schema設定の一致、必要usage metadataだけを許す。tool call、複数candidate、複数part、thought part、blocked/no-candidate、未知構造は拒否する。重複key、数値/string coercion、未知fieldも拒否する。raw provider bodyを例外やlogに含めない。429、HTTP error、malformed envelopeはreason codeだけを返す。

抽出したtextをUTF-8 bytesへ変換し、同じ専用parserでinner JSONをparseする。inner objectは`summaryText`と`claimKeys`の正確に2 fieldだけを持ち、duplicate keyを拒否する。`summaryText`はJSON string、`claimKeys`はJSON string配列であり、coercion・未知field・不正型をsuccessにしない。宣言したJSON MIME type/schemaと不一致なら拒否するか、schemaを宣言しない固定requestを用いる。shared `ObjectMapper`の緩いcoercion設定は変更しない。fenced JSONを自動補正しない。既存`CopilotSummaryValidator`でnumeric claim、HTML、canaryを検査し、claim key allowlistも検査する。検査の前後でdeadlineを確認し、期限後の成功を返さない。期限切れはnumeric rejectよりdeadline outcomeを優先する。

Gemini responseの`usageMetadata`はbounded parseから取り出す。ACTUALとするには、全ての必要usage dimensionが存在し、JSON integer型で0以上かつ実装上限内であり、model/pricing policyが認識する全てのbillable token dimensionと合計値が整合することを要求する。欠落、負値、decimal/string/boolean、overflow、範囲外、dimension不整合はACTUALにせず、事前証拠からUPPER_BOUNDを計算できなければUNKNOWNにする。bounded interface/gateway resultにはtext、usage、immutable preflight evidence、dispatch outcome/cost stateを持つCopilot専用typed resultを設ける。通常AI用途の`AiGatewayResult`互換性を壊さない。API keyは応答型へ含めない。

### 3.3 費用deny policy、送信結果、attempt記録

この第一sliceでは`CopilotProviderCostPolicy`のproduction実装を固定deny-allにする。Gatewayはproviderがremoteなら常にdispatch前に`COST_UNKNOWN`/`NOT_SENT`で拒否し、application setting、`maxOutputTokens`、cost approval boolean、artifact値だけで解除できない。`src/test`に限り、明示的なsynthetic policyをloopback hostとtest transportへ結び、protocol/error/費用伝播を検証する。実Gemini token count用の追加HTTP call、特に外部`countTokens` preflightを暗黙導入しない。

将来の独立gateでは、provider、model/version、region、tier、rate-card version/effective window、通貨とFX source/time、token dimension、rounding rule、input upper boundの証明、output cap、thinking policy、cached/tool/media利用禁止を全て固定したpolicy evidenceを要する。`maxOutputTokens`は出力上限であってprompt上限ではない。character数によるtoken推定だけでは上限証明にしない。公式docsが示すように入力tokenは`count_tokens`またはresponse usageで観測し、thinking tokenも課金対象になり得る。第一sliceはこの条件を満たす運用policyを作らず、全production external sendをdenyする。

送信直前にimmutable `PreflightCostEvidence` を作り、policy identity、rate-card/effective期間、currency/FX/rounding、入力と出力の上限根拠、計算済みworst-case上限、budget、dispatch許可/拒否を束縛する。`CopilotInvocation`にこのevidenceを含め、Gatewayの判定とadapter送信が同一のevidenceを使う。immutable `DispatchOutcome`は`NOT_STARTED`、`MAY_HAVE_BEEN_SENT`、`RESPONSE_RECEIVED`のような状態を記録し、送信を否定できる場合だけ`NOT_SENT`とする。transport起動後のtimeout/断線で送信有無が確定できなければ、誤ってNOT_SENTにせず保守的に扱う。

cost stateは固定enumとして`NOT_SENT`、`ACTUAL`、`UPPER_BOUND`、`UNKNOWN`を持つ。dispatch前の拒否、またはwire送信へ進んでいないことをtransportが証明できる失敗だけを`NOT_SENT`で0円記録する。送信後に妥当なusage dimensionが揃いrate cardにより算定できれば`ACTUAL`。response失敗・timeout・断線・cap超過・malformed envelope/inner JSON・数値検査失敗でも、送信済みまたは送信有無不明でusageから算定できないときは、同じpreflight evidenceに有効な上限がある場合のみ`UPPER_BOUND`、なければ`UNKNOWN`とする。JSON parse失敗を`NOT_SENT`や既定0/KNOWNへ変換しない。responseを受けた時点でACTUALが証明できてもsummary validation失敗ならcost evidenceをattemptへ保持する。

`SummaryResponse.unavailable()`はprovider statusに加えて費用状態/evidence参照とdispatch outcomeを保持できる型へ設計変更する。例外経路でも`completeSummaryAttempt`は受け取ったcost stateを保ち、成功時だけでなく失敗・timeout・malformed・numeric rejectも既存`PENDING` rowへterminal更新する。redacted metadataへ `costState`、該当時だけ`costYen`/`costUpperBoundYen`、`dispatchOutcome`、固定reason code、preflight policy/rate-card identityを保存する。raw prompt/provider body、key、非redacted evidenceは保存しない。既存列で記録できる範囲にとどめ、schema migrationを追加しない。query attempt、typed result、citationsは変更しない。

### 3.4 固定artifact identity

`copilotModelId()`、`copilotPromptVersion()`、`copilotConfigHash()`はimmutable `CopilotConfigSnapshot`からだけ返し、実際に送るprovider/model、canonical endpoint、prompt template hash、output schema/parser version、generation settings、固定deny-all cost policy identityを表す。secret/key、DNS address bytes、expiry値をhashへ含めない。identityは再現可能で、設定不一致はgateway送信前にrejectする。`AiConfig`をgateway検査直後にmutateする回帰テストで、adapterの実endpoint/model/settingsが同じ初期snapshotを使い、期待identityを後から差し替えられないことを検証する。API keyはsnapshot/invocation/toStringから除外し、dispatch時に専用secret accessorから得た値をheader設定の局所変数としてのみ扱う。

query開始時の `CopilotArtifactSnapshot`と同じ固定config identityを`CopilotInvocation`へ束縛し、summary入口で一度生成して最後まで渡す。gatewayはinvocationのsnapshot provider/model/prompt/configとadapterのimmutable `CopilotConfigSnapshot`を比較し、adapter自身も同invocationのdeadline、preflight evidence、model/endpoint identityを照合する。途中で`AiConfig`、ACTIVE artifact、URL/model/settingsを再取得しない。artifact mismatch時もtyped query result/citationsを保持する。

V148のplaceholder active artifactは変更しない。adapter codeとsynthetic protocol testが揃っても、V148のrowはGemini configに一致しないため実summaryは成功しない。別途、Gemini canonical identityを持つSHADOW候補を作成する承認済みpublish手順、offline evaluation、admin approval、ACTIVE切替、rollbackをレビューするartifact refresh taskが必要であり、本計画の実装・テストではDB publish/SQLを実行しない。

## 4. 実装sliceとgate

### Slice A: invocation・費用契約とproduction deny-all

- startup時の`CopilotConfigSnapshot`、secretを含まないimmutable `CopilotInvocation`、immutable `PreflightCostEvidence` / `DispatchOutcome`の契約を定義する。認可済みqueryのdeadline、expected artifact/provider/model/prompt/config identityとcost preflightを一つのinvocationへ結ぶ。
- production cost policyを固定deny-allにし、remote providerではdispatch境界を越えないことをrequest-count=0で確認する。設定値やapproval booleanで緩められないことをテストする。
- `src/test`だけにloopback専用synthetic policy/transport fixtureを用意し、test以外から設定できない構成とする。このfixtureはreal provider、Internet、production DNSを使用しない。
- gateway検査後にmutable `AiConfig`を変更するrace testを加え、expected identityを差し替えられず、adapterのURL/model/generation settingsが当初snapshotの値を使うことを検証する。API keyはinvocation/snapshotの文字列表現にも含めない。
- `CopilotRunService` attempt metadataに列追加なしでcost state/outcomeを保存できる型境界を決め、`NOT_SENT`/`ACTUAL`/`UPPER_BOUND`/`UNKNOWN`の意味・遷移と境界値を固定する。

### Slice B: bounded Gemini adapterとstrict parser

- Copilot専用transportを`CopilotInvocation`とsnapshotで駆動し、legacy `generate(String)`やshared `RestTemplate`へ委譲しない。API keyはheaderだけへ設定する。
- outer envelopeはちょうど1 candidate、finishReason=`STOP`、ちょうど1 non-thought text part、許可fieldのみ、usage dimensionsの重複なし/型厳密/非負/上限内を要求する。tools、追加candidate/part、thought part、未知構造、duplicate JSON key、coercionを拒否する。
- inner summary JSONは重複keyを含まないobjectで正確に`summaryText`と`claimKeys`の2 fieldだけを許可し、それぞれ文字列と文字列配列を厳密確認する。fence、未知field、coercionを受け入れない。requestのJSON MIME/schema指定とresponse解釈を一致させる。
- response usageが欠落・不正・不完全ならACTUALにしない。preflight upper boundが証明されなければUNKNOWNにする。preflightのproduction policyはこのsliceでもdeny-allのまま。
- test-only TLS fixtureでは、正しいhostnameの合成証明書だけ成功し、誤hostnameとuntrusted certificateは失敗することを確認する。既存TLS stall testは別のtimeout動作のまま残し、hostname verification証明の代わりにしない。

### Slice C: summary・attempt費用伝播と回帰

- `SummaryResponse.unavailable`を含む成功/失敗型を拡張し、dispatch前拒否、sent後のactual usage、上限証拠あり/なしのtimeout・JSON・numeric・connection・response cap失敗をattemptへ保持する。
- `CopilotRunService.completeSummaryAttempt`がnon-nullなら無条件KNOWNとする契約をやめ、明示cost state/outcomeと必要なactual/upper-bound yenだけをredacted metadataへ記録する。nullを費用なしと解釈しない。
- typed result、citations、query attemptを維持し、summary attemptだけをterminal更新する既存境界を回帰テストする。mock/ruleのlocal 0円扱いとlegacy AI pathは従来どおりに保つ。

各sliceは先にPLAN再レビューし、実装・テスト・独立implementation reviewを個別に行う。実provider cost policyとproduction解放は別ゲートであり、Slice A〜Cの完了条件に含めない。

## 5. 追加・変更候補

- `service/ai/impl/GeminiTextServiceImpl.java`: bounded interfaceとCopilot専用HC5呼出しを追加し、既存`generate(String)`を不変にする。
- `service/ai/CopilotBoundedAiTextService.java`: immutable invocationを受け、usage/cost evidenceとdispatch outcomeを返すCopilot専用契約へ拡張する。
- `service/ai/impl/CopilotBoundedHttpTransport.java`: API key headerだけを安全に設定できる専用request seamを追加。任意URI/headerを許さない。
- `service/ai/copilot/summary/CopilotSummaryService.java`: 専用parser、schema/claim/numeric検証を一deadlineに束ね、remote cost 0固定を除く。
- `service/ai/impl/AiExecutionGatewayImpl.java` とCopilot専用result型: immutable invocation identity/preflight検証、remote `NOT_SENT` deny-by-default、dispatch結果の伝播を実装する。
- `config/AiConfig.java`またはCopilot専用properties class: startup snapshotを組み立てるためのnon-secret設定のみbindする。production cost policyは固定deny-all。`application.yml`のprovider/feature/external-send既定値を変えない。secret実値やDNS address値は追加しない。
- `service/ai/copilot/summary/PreflightCostEvidence.java`等のimmutable型: policy identity、upper-bound根拠、budget、dispatch outcomeを束縛する。secret/raw provider responseは保持しない。
- `service/ai/copilot/CopilotRunService.java`: summary attempt metadataへ明示cost state/outcomeを保存する。既存schemaを変えない。
- `service/ai/copilot/summary/CopilotSummaryPromptTemplate.java`: provider adapter prompt/output schema identity hashを実Gemini経路の設定選択に合わせて拡張する。
- 専用test: snapshot mutation race、production deny-all、test-only loopback policy、Gemini adapter/transport protocol、TLS hostname/untrusted、strict parser、attempt費用状態matrix、既存legacy/mock/rule経路の互換性。

実装レビュー前にAstra PLAN reviewを行い、特にcost upper-boundの実現可能性、Gemini usage semantics、header-only secret流、endpoint canonicalization、test seamがproductionに露出しないことを確認する。

## 6. synthetic testと受け入れ条件

外部通信を行わないloopback fixtureでadapter→HC5 transport→strict provider envelope parse→inner summary JSON parse→numeric/claim validationを実行し、実Gemini protocol形状を確認する。test専用 `.test` endpoint/snapshot/keyを使い、test用constructor/config seamからのみ到達可能にする。実credential、実provider、Internet、production DNS、production TLS設定変更は使わない。TLSのsuccessful fake serverを用意する場合はtest-only合成certificate/trust contextに限定し、通常transportのproduction trust manager・hostname verificationは無効にしない。既存のTLS stall fixtureも残す。

受け入れ試験は以下を含む。

1. Gateway preflightが拒否したremote Geminiのserver request countは0であり、production policyは全設定値やapproval booleanに対してdeny-allであること。synthetic policyは`src/test`内loopbackのみで使用でき、実providerへ到達するrouteを持たないこと。
2. mutable `AiConfig`をgateway identity検査後に変更しても、immutable snapshotとinvocationのexpected identityは変化せず、実送信endpoint/model/generation settingsはsnapshot値に一致すること。keyがinvocation、snapshotのtoString、URI/body/log/error/DTO/run metadataに現れず、synthetic serverが受ける`x-goog-api-key` headerの真偽だけを検証すること。
3. 正常envelopeはcandidate 1件、STOP、非thought text part 1件、宣言したJSON mime/schemaだけを受け入れること。extra candidate/part、tool、thought、duplicate key、不明field、文字列数値coercion、invalid UTF-8/trailing tokenを拒否すること。inner JSONは2-field schemaだけを許可すること。
4. usageMetadataの必要dimensionがJSON integerで非負・有界・整合するケースだけACTUALとすること。field欠落/負値/decimal/string/overflow/inconsistent totalはACTUALでないことを確認し、証拠付きならUPPER_BOUND、証拠なしならUNKNOWNになること。
5. attempt matrixはpreflight deny=`NOT_SENT`、valid complete usage=`ACTUAL`、送信後のtimeout/断線/response cap/malformed JSON/numeric rejectionをそれぞれ実行する。valid preflight upper boundがあれば`UPPER_BOUND`、なければ`UNKNOWN`を保持し、どのsent後失敗もcost 0/KNOWNへ変換しないこと。query attempt、typed result、citationsを保ちsummary attemptだけterminal更新すること。
6. raw response over 64KiB、深さ/string/name/number境界、claim key逸脱、numeric summary、HTML/canaryを拒否し、raw body/keyをlog/exception/attemptへ残さないこと。response header/body stall、pool queue、TLS handshake stallが単一deadlineでcancelされ、pool lease/admissionが解放され、次のsynthetic healthy requestが成功すること。barrierをfinallyで解放しexecutor terminationをassertすること。
7. TLS loopback fixtureはGemini hostnameをSANに持つtest-only certificateと明示test trustで正しいhostnameだけ成功させ、誤hostnameとuntrusted certificateは失敗させること。TLS stall fixtureは独立timeout証拠として残す。
8. expired deadlineおよびparse後deadlineでは成功を返さないこと。`ai.provider=mock/rule`のlocal cost 0経路とlegacy Gemini `generate(String)`、proposal/matching/ingest等のshared `RestTemplate`動作が変わらず、Copilot bounded pathだけが専用transportを通ること。

## 7. 別ゲートと除外

- provider DPA、region/data transfer、retention、provider contract、価格/rate card、owner、catalog、allowed roles、NF07/DG08、既存AI production approval、cost limit、human escalationの実承認。
- `ai.management-copilot-enabled=true`、`ai.external-send-enabled=true`、`ai.provider=gemini` のproduction設定、`AI_API_KEY`実値、DNS snapshot実値の発行。
- V148または適用済みmigrationの変更、DDL、artifact rowの直接SQL更新、Gemini SHADOW作成/昇格、production evaluation/publish。
- push、PR、merge、deploy、実provider送信、全suite実行済みの表示。

Slice A〜Cのsynthetic testでproduction networkを使わずadapter/parser/error propagationをreviewできる。実provider向けcost policyの実装、入力token上限の独立証明、運用rate cardとFX/rounding、contract/region、全ての既存approval gates、artifact refresh gateが別途完了するまで、実provider requestは禁止しNF08 productionはBLOCKEDのままとする。
