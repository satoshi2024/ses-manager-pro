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

- deadlineは単調時計で計測し、provider送信開始からrequest enqueue、connect、request headers/body、response headers/body、parse開始までの残時間を一つのbudgetとして伝える。各readの応答timeoutを総deadlineの代用にしない。
- 現行の`org.apache.httpcomponents.client5:httpclient5`（Spring Boot 3.3.5管理の5.3.1）に含まれるApache HttpClient 5 async APIを候補とする。専用singleton transport beanで`HttpAsyncClients.custom()`の`CloseableHttpAsyncClient`をbuild/startし、request単位で`SimpleHttpRequest`へbody/headerと`RequestConfig`を設定して`execute`する。HTTP I/OはApacheの有限IO reactorで行い、アプリケーション独自の無界executorや共通AI gateway用executorは追加しない。
- 呼出元threadは`Future.get(remaining, NANOSECONDS)`で総deadlineまでだけ待つ。期限超過時は`Future.cancel(true)`を呼ぶ。`RequestConfig.Builder.setHardCancellationEnabled(true)`も明示し、接続がpoolへ再利用されず破棄される契約をtransport testで確認する。`connectTimeout` / `connectionRequestTimeout` / `responseTimeout` は残り時間以下に設定する補助制限であり、総deadlineの代用ではない。
- clientはSpring bean lifecycleで一度だけ開始/終了し、HTTP I/O以外に無界threadを作らない。`AiGatewayRequest`のdeadlineはCopilot専用経路のみ消費し、proposal/matching/ingest/learning等の既存`generate(String)`契約・3〜60秒既存timeoutを変えない。
- 現行mock/ruleの実行は外部I/Oなしと判定できるためcost 0を記録する。providerの実費を上限内と事前保証できない場合は送信前にfail-closedし、summary attemptの費用をNULL、redacted outcomeを`COST_UNKNOWN`/`UNKNOWN`相当として記録する。未知費用を0円へ丸めない。
- testは割当HTTP serverが応答を少しずつ送るslow-drip caseで総deadline前にunavailableになること、cancel後にactive exchange/connectionが残らず再使用されないこと、timeout中も呼出元がboundedであること、mock/rule 0とcost unknown拒否、外部送信0を確認する。fakeが例外を自発的に投げるだけのtimeout testでは代替しない。
- `OutboundUrlGuard.validateExactHostHttpsUrl` は許可host/scheme/portを検査するがDNS解決はしない。transportはHTTPS、完全一致host、port 443、redirect無効、API keyをbody/logへ出さない条件を維持する。DNS/connection/request/response全体をdeadline下へ含められること、cancel後のreactor資源回収をslow-drip fixtureで証明できない実装は完了扱いにしない。

### 3.3 pinされたartifactと分離attempt

- query開始時に一つのimmutable execution snapshotとしてartifact id、provider、model、prompt version、config hashを取得する。同じsnapshotをquery/summary双方のmetadataとsummary gatewayへ渡し、途中でACTIVE artifactを再照会しない。
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
