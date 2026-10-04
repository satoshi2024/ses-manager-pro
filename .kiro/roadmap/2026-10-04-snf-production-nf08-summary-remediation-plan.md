# NF08 summary 境界修復計画

## 1. 基線と状態

- 対象: `NF08-R-P2-01`（summary自由文の数値）、`NF08-R-P2-02`（実行deadline/cost）、`NF08-R-P2-03`（query/summary attemptとartifact固定）
- 状態: **計画レビュー待ち**。本書は実装許可やproduction承認ではない。
- Base: `origin/main@1443fb68943849a3260d7ebe005259f3eb609d60`
- Branch: `codex/snf-prod-nf08-summary-boundary`
- Provider: ローカルmock/ruleのみ。`ai.external-send-enabled=false` と `ai.management-copilot-enabled=false` を維持する。
- NF-07、DG-08、`GATE-S17-G10-PROD`、catalog/owner、DPA、実provider費用・regionの未解決状態は変更しない。
- 既存migrationの改変、新規DDL、migration番号予約は行わない。
- push、PR、deploy、real credentialの読取・使用、実provider送信、feature flag有効化は行わない。

## 2. 静的確認で判明した欠陥

`CopilotSummaryValidator` は連続したASCII数字2桁以上だけを検査する。単桁、小数、Unicode数値文字、通貨表記が漏れる。

`SummaryRequest` は15秒deadlineと0円budgetを保持するが、`AiGatewayRequest` に渡されず、gateway/transportも実行時に消費しない。AI用 `RestTemplate` は `AppConfig` でconnect 5秒/read 60秒、`SimpleClientHttpRequestFactory` を使う。read timeoutは少しずつ応答byteを送る相手への総時間上限にならない。これはソースの静的確認であり、動的再現は未実施。

`CopilotQueryService` はsummary前に `CopilotRunService.recordQueryRun` で `SUCCEEDED`、latency 0、cost 0を保存する。summaryはgatewayへ `persistRun(false)` を渡し、結果はquery runへ書き戻されない。query runとsummary serviceが別々にACTIVE artifactを取得するため、実行中のartifact切替でidentityが分離し得る。

## 3. 実装契約

### 3.1 自由文の数値禁止

- 数値判定はUTF-16 `char`単位にせず、Unicode code pointを走査する。
- Unicode general category `Nd` / `Nl` / `No` をすべて拒否し、通貨記号カテゴリ`Sc`、ASCII/fullwidth等のpercent・per-mille記号、一般的な漢数字・大字（例: 零〇一二三四五六七八九十百千万億兆、壱弐参拾佰阡萬）で構成される数値表現も拒否する。
- 検証対象はsummary自由文のみ。`claimKeys`は既存allowlistで検証し、periodを含む正当なkeyを数値本文扱いしない。typed resultの値・単位・期間はそのまま保持する。
- 拒否時はsummaryのみunavailable/rejectedとし、typed resultと再認可済みcitationを保持する。
- testsは単桁、負数、小数、指数、各Unicode category、円/ドル/全角通貨、%/‰、漢数字表現、数字を含まない自然な日本語のpositive、正当なclaim key、typed result維持を含める。

### 3.2 summary deadlineと費用上限

- deadlineはsummary入口で単調時計から一度だけ設定し、budget/snapshot検査、request enqueue、pool lease、connect/TLS、request headers/body、response headers/body、parse/validation完了まで同じ残時間を使う。残時間0以下では送信しない。parse/validation終了時も期限を再検査し、遅れた結果を成功として公開しない。各readの応答timeoutを総deadlineの代用にしない。
- 現行の`org.apache.httpcomponents.client5:httpclient5`（Spring Boot 3.3.5管理の5.3.1）を使うCopilot専用singleton async transportとする。有限IO reactor、有限connection pool・待機数を設定し、gatewayの認可/tenant context/監査は呼出元threadで完了する。provider I/O以外を独自executorへ移さない。**専用clientはHTTP/1.1に固定**し、旧AI clientのprotocolやtimeoutを変更しない。
- DNSはnetwork lookupを行わない専用resolverだけを使う。事前解析済みの正規host→`InetAddress`配列をimmutable snapshotとして設定し、数値address byteから構築する。`InetAddress.getAllByName`、`SystemDefaultDnsResolver`、未登録hostのfallbackは禁止する。未設定・期限切れ・snapshot不整合は送信前に`DNS_UNAVAILABLE`として拒否する。接続addressを固定しても元hostnameをTLS SNI/証明書検証へ渡し、IP指定による証明書検証無効化を禁止する。運用上のaddress取得元、TTL、有効期間、更新、許可addressの承認はproduction gateとして残す。外部DNSをexecutorへ包んでcancelする案は採らない。
- 呼出元threadは`Future.get(remaining, NANOSECONDS)`で待ち、期限超過時は`Future.cancel(true)`と明示したhard cancellationで当該HTTP/1.1 exchange/connectionを破棄する。`execute`前後にも残時間を確認し、同期処理部分も無制限のresolver等を含めない。`connectTimeout` / `connectionRequestTimeout` / `responseTimeout`は補助制限とする。cancel後のactive exchangeと当該connection解放、後続healthy request成功を観測する。OS schedulingを無視した厳密な実時間保証とは呼ばず、testではdeadlineとcleanup観測の許容幅を分けて記録する。
- responseはstreaming consumerで最大64 KiBを超えた時点でcancelする。`SimpleResponseConsumer`による無制限の全量bufferを使わない。JSON depth最大16、文字列token最大8192文字、number token最大64文字、末尾token拒否を専用parserへ設定し、shared ObjectMapperを緩和しない。本文をlog/例外/永続metadataへ出さない。
- clientはSpring bean lifecycleで一度だけ開始/終了し、HTTP I/O以外に無界threadを作らない。`AiGatewayRequest`のdeadlineはCopilot専用経路のみ消費し、proposal/matching/ingest/learning等の既存`generate(String)`契約・3〜60秒既存timeoutを変えない。
- 現行mock/ruleの実行は外部I/Oなしと判定できるためcost 0を記録する。providerの実費を上限内と事前保証できない場合は送信前にfail-closedし、summary attemptの費用をNULL、redacted outcomeを`COST_UNKNOWN`/`UNKNOWN`相当として記録する。未知費用を0円へ丸めない。
- testはDNS snapshot欠落/期限切れ、禁止したblocking DNS delegateが呼ばれないこと、pool lease待機、TCP/TLS未完了、header/bodyのslow-drip、byte上限、JSON上限、parse後期限超過を分ける。HTTP serverが実際に遅延し、timeout時に当該exchange/connectionが回収され後続healthy requestが成功することを確認する。mock/rule 0と未知costの送信前拒否も確認する。fixtureの自発例外だけをtimeout証拠にしない。fault barrierには明示release/finallyと上限を設け、テスト自体が永久にDNS等を占有しない。
- `OutboundUrlGuard.validateExactHostHttpsUrl` はURL allowlist検査のみ。transportはHTTPS、完全一致host、port443、redirect無効、通常のTLS検証を維持する。loopback/fake serverと合成keyはtestだけに限定し、本番設定に持ち込まない。
- 上記はAstraの5.3.1公式source確認に対する修訂である。既定async DNSも同期呼出を含み、HTTP/2のhard cancellationはconnection破棄を保証しないため、この専用契約で除外する。
  参照: [MultihomeIOSessionRequester](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.3.1/httpclient5/src/main/java/org/apache/hc/client5/http/impl/nio/MultihomeIOSessionRequester.java)、[RequestConfig](https://raw.githubusercontent.com/apache/httpcomponents-client/rel/v5.3.1/httpclient5/src/main/java/org/apache/hc/client5/http/config/RequestConfig.java)。

### 3.3 pinされたartifactと分離attempt

- query開始時に一つのimmutable execution snapshotとしてartifact id、provider、model、prompt version、config hashを取得する。同じsnapshotをquery/summary双方のmetadataとsummary gatewayへ渡し、途中でACTIVE artifactを再照会しない。
- snapshotを実際の`AiConfig` provider/model、選択adapter、送信先と照合する。相違またはbounded入口非対応は送信前に拒否し、旧`generate(String)`へのdefault delegationを禁止する。専用経路から旧経路が呼ばれないことと、既存AI featureが専用経路へ迂回しないことを両方向のtestで確認する。
- queryとsummaryは既存`t_ai_recommendation_run`に別rowとして記録し、それぞれ異なるUUID trace IDを使う（`uk_ai_run_trace`が一意）。redacted metadataには用途、attempt kind、親query trace/correlation ID、固定artifact identity、query/catalog/schema/data/prompt version、outcome、latency、cost stateを記録する。raw質問、raw prompt、provider request/response本文は保存しない。
- query実行開始から正本service完了までをquery latencyとして記録する。summary attemptはprovider呼出開始からparse/validation outcome確定までの実測latencyを記録する。query成功はsummary成功を意味しない。失敗・reject・timeoutもattemptとしてbounded outcomeを記録し、typed resultはsummary failure時に維持する。
- DB既存列`latency_ms`/`cost_jpy`はnullable、`status`/`error_code`と`redacted_summary_json`も利用可能。cost不明はNULLと明示状態を併記する。V108 `trace_id`一意制約を守る。よって本scopeのDDLは不要。
- testsはquery/summary分離、各latency/status/cost state、成功/429/timeout/invalid JSON/reject、execution中のACTIVE artifact切替、親correlation一致、raw prompt/body非保存、trace ID重複なしを確認する。

## 4. 予定変更範囲

### 計画に従う場合の実装候補

- `src/main/java/com/ses/service/ai/copilot/summary/CopilotSummaryValidator.java`
- `src/main/java/com/ses/service/ai/copilot/summary/CopilotSummaryService.java`
- `src/main/java/com/ses/service/ai/copilot/summary/SummaryRequest.java`
- `src/main/java/com/ses/service/ai/copilot/summary/SummaryResponse.java`
- `src/main/java/com/ses/service/ai/copilot/CopilotQueryService.java`
- `src/main/java/com/ses/service/ai/copilot/CopilotRunService.java`
- `src/main/java/com/ses/service/ai/AiGatewayRequest.java`
- `src/main/java/com/ses/service/ai/AiTextService.java`
- `src/main/java/com/ses/service/ai/impl/AiExecutionGatewayImpl.java`
- `src/main/java/com/ses/service/ai/impl/GeminiTextServiceImpl.java`（Copilot専用のbounded transport seamのみ。既存利用経路は変更しない）
- `src/main/java/com/ses/config/AppConfig.java` または専用の新規Copilot transport configuration/client。共通`aiRestTemplate`の60秒設定は変更しない。
- `src/test/java/com/ses/service/ai/copilot/summary/CopilotSummaryValidatorTest.java`
- `src/test/java/com/ses/service/ai/copilot/summary/CopilotSummaryServiceTest.java`
- `src/test/java/com/ses/service/ai/copilot/CopilotQueryServiceTest.java`
- bounded transport用の専用test、新旧AI gateway経路の互換test（必要な場合）

### 今回除外

- migration/DDL、UI/API契約、catalog/role/scope、legacy `/api/ai/**`、他NF、provider追加、credential設定、production/provider/feature flag gate。
- review-ledgerのPASS化、本番準備完了宣言、push、PR、deploy。

## 5. 検証と環境制約

- 計画レビュー後、実装変更のテスト対象は前節のvalidator/summary/query専用test、slow-drip transport test、必要時のgateway互換testに限定する。
- 現時点でMavenは停止中のため実行しない。総括agentがNF04との順序とJava実行を統一調整した後に実行する。
- 実施時のJava起動は既存`JAVA_TOOL_OPTIONS`を保持したまま `-Djdk.net.unixdomain.tmpdir=C:\work\jdk-uds` を追加する。
- 外部Gemini provider、実credential、実データ、real outbound requestは使わない。CI/全gateを実行済みと表記しない。

## 6. 実装後の状態

この修復は三つの静的Review findingを閉じる候補であり、独立Astra code reviewと指定test結果が揃うまでは閉鎖済みとしない。NF08はconditionalのままとし、NF-07/DG-08/owner/catalog/provider/費用/DPA/production gateを引き続きBLOCKEDとして扱う。
