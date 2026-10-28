# NF-05 local TLS transport candidate plan

## 目的と基線

この計画はB1 outbound webhookの開発・test範囲に限り、実providerへ接続しないHTTPS transport candidateを本機TLS fixtureだけで検証する。DG-05の既存Owner承認およびユーザーのlocal実装指示を前提にした候補計画であり、新しいOwner承認やDecisionを作成・代行するものではない。

作業branchは`codex/snf-prod-nf05-local-tls`、実装baseはcontrol固定Head `9bac40c9624e7d964bcf6e18f480b3457662206e`である。取得済み`origin/main`参照は`1443fb68943849a3260d7ebe005259f3eb609d60`であり、実装baseと同一ではない。後続の計画・review・実装でこの2つを混同しない。

既存`LoopbackIntegrationHubWebhookTransport`は、現在のsource hashがdeadline evidenceの検証後final tree hashと一致する。接続からrequest write・response headerまでを一つのmonotonic deadlineで制限し、応答本文をdrainせず、loopback literalとport/peer検証を行う。deadline evidenceは4 class合計26 tests、failure/error/skip 0/0/0を記録する一方、OS socket buffer依存のOP_WRITE待機そのものは動的に証明しておらず、sandbox契約・実provider送信・production運用承認も未完了としている。

このcandidateは既存HTTP loopback transportを置き換えず、HTTPS/TLSの局所境界を独立検証する。現時点で遠隔endpoint、実credential、production外送信を許可する計画ではない。

## 開発対象

候補実装は次のsource/test filesだけを想定する。実装前にAstraがこの計画と各不変条件を確認する。

- `src/main/java/com/ses/service/integrationhub/LoopbackTlsIntegrationHubWebhookTransportCandidate.java`
- `src/main/java/com/ses/service/integrationhub/IntegrationHubLoopbackTlsEndpointGuard.java`
- `src/test/java/com/ses/service/integrationhub/IntegrationHubLoopbackTlsTransportCandidateTest.java`
- `src/test/resources/com/ses/service/integrationhub/tls/`配下のsynthetic test-only CA、server certificate、private test key fixture

transport classとguardはpackage-private/finalとし、constructorもpackage-privateでfixture用`SSLContext`を明示的に受け取る。production default trustを置き換えるglobal JVM property、global `HttpsURLConnection` defaults、trust-all manager、hostname verifier overrideは持たない。candidateは`IntegrationHubWebhookTransport`の試験用実装として扱うが、`@Component`、`@Bean`、Spring configuration import、profile property、provider mode enum、production workerへのwiringは追加しない。

送信は既存Apache HttpComponents Client 5依存を用いたasync client候補を優先する。NF08の`CopilotBoundedHttpTransport`は、明示connection pool上限、hard cancellation、無retry/redirect、単調deadline、admission/exchange permit、atomicな一度限りrelease、保守的な`MAY_HAVE_BEEN_SENT`状態の設計参考として読み取りだけ行う。NF08のsource、config、testは変更せず、NF08固有URL/DNS/response/secret規則を複写しない。Classic request factoryはper-request closeとTLS/DNS pinningの参考に留め、B1候補には無制限のconnection creationを持ち込まない。

HC5でdeadline中のwrite/TLS/header待機をhard-cancelし、shutdownまでpool/threadをboundedに保つことを検証で示せない場合は、実装完了を宣言せず計画へ戻す。JDK 21専用APIを使わずJava 17 bytecode/API範囲を守る。`HttpClient.close()`などJava 21以降のAPIに依存しない。

## 固定する不変条件

1. **接続先は本機だけ**: candidate guardは`https` URI、厳密なIP literal `127.0.0.1`または`::1`、明示port allow-listを必須とする。hostname/DNS、proxy、redirect、userinfo、query/fragment、invalid/out-of-range port、dot-segment pathを拒否する。接続先のpeer IP/portを接続直前とTLS接続後に照合する。全テストはloopback fixtureだけに接続し、外部hostを名前解決・接続しない。
2. **通常のTLS検証**: certificate chain trust、有効期間、TLS hostname/IP SANを既定の証明書検証で検査する。test専用private CAはtest SSLContextだけへ直接注入し、プロセス/global trust storeを書き換えない。SAN不一致、期限切れ、未信頼chainは拒否する。test fixture証明書・秘密鍵は合成値でtest resourcesに限定する。
3. **一つの総期限**: `send` entryで開始した単調時刻deadlineがadmission待ち、connection lease、TLS handshake、request write、response header受信/parse、response close、transport result確定までを包含する。各段階は残時間を再評価し、その残時間だけをHC5のrequest/connection/socket timeoutへ設定する。deadline後のHTTP responseは成功扱いしない。response bodyはdrainせず、header確定後にstream/socketをcloseする。
4. **cancelの意味を過大評価しない**: timeout/interruption後のfuture/socket cancellationは、request未送信または相手側処理取消しの証拠ではない。dispatch前だけ`NOT_STARTED`、HTTP execute開始境界を越えた後は結果不明を保守的に記録する。timeout後も副作用未発生・socket已閉鎖と推定しない。再試行で同じdeliveryの既存provider idempotency keyを維持し、成功直後のCAS障害をtransport failureへ読み替えない。
5. **資源の有限性**: client数、connection pool全体/route別、in-flight交換、admitted交換、I/O reactor thread数を固定上限化する。任意のwork queueを導入する場合は容量固定、拒否policyはAbort/即時拒否とし、CallerRunsで呼出し元にHTTP処理を実行させない。permitはtimeout時に早期解放せず、未submit時のrollbackまたはHC5 completion/cancel callbackのどちらか一度だけ解放する。cancelが戻っただけでconnectionやin-flight permitを解放しない。遅延callback、同期submit例外、cancel競合でもpermit二重解放/漏れを起こさない。
6. **wire headerの単一正本**: caller headerは総byte数、個数、個別name/value byte上限で拒否する。caller指定`Host`、`Content-Length`、transportが所有するhop-by-hop/encoding headerとの大小文字を無視した重複・衝突を拒否し、transportがauthorityとbody長から一つずつ生成する。bodyは既存`IntegrationHubWebhookRequest.MAX_BODY_BYTES`を超えない。redirect/retryをHTTP client層で自動実行しない。
7. **safe result**: 2xxはsuccess、429/5xx/timeout/networkはbounded retryable code、その他3xx/4xxはnon-retryable resultへ分類する。raw response body、secret、credential、header値、例外文をlog/resultへ出さない。既存HMAC署名headerと`Idempotency-Key`は改変しない。
8. **Java 17**: 実装/API/testは`<java.version>17</java.version>`を守り、Java 21専用APIを使わない。

## 検証計画

`IntegrationHubLoopbackTlsTransportCandidateTest`は本機`HttpsServer`とephemeral loopback portだけを使う。synthetic test certificateに正しいloopback IP SANを付け、test-only CAを明示注入する。テストは候補クラスのclose、server、test executor/socketの有限終了をfinallyで保証する。実行時は対象4 classの既存HTTP loopback回帰も同じ限定gateで確認するが、確認前にPASSを主張しない。

| ケース | 期待する証拠 |
|---|---|
| TLS成功、正しいloopback IP SAN / test CA | 202/2xxとsafe provider request IDだけ返し、serverが受け取ったbodyとHMAC/idempotency headersはfixture期待値と一致 |
| TLS negative | hostname/IP SAN不一致、expired cert、untrusted CAでTLS失敗。trust-all/global overrideなし |
| destination rejection | HTTP、hostname、userinfo、query/fragment、port未許可、非loopback literal、traversalを接続前に拒否。外部socket/DNS callは0 |
| response handling | redirectを追跡しない。header-only応答の後にslow/infinite bodyを送ってもdrainせず期限内に戻る |
| total deadline | admission飽和、connection lease待ち、TLS handshake stall、request write stall、slow-drip response headerそれぞれで同じmonotonic総期限が働き、期限後のlate 2xxを成功にしない |
| ambiguity/cancel | execute前拒否はNOT_STARTED。execute開始後timeout/cancelはMAY_HAVE_BEEN_SENT相当。timeout後にside-effect無しと断定しない |
| bounded concurrency | connection/in-flight/admission/executor上限を超える要求はCallerRunsせずboundedに拒否またはdeadlineで終了。cancel後もcallbackまではpermit保持し、遅延callback後にpermitが一度だけ戻る。pool/thread/queue/permitが最終的に0へ収束 |
| header/body boundary | aggregate header bytes/count/name/value上限、case-insensitive Host/Content-Length conflict、body max超過を拒否。wire上Host/Content-Length重複なし |
| bounded results | 2xx/3xx/4xx/429/5xx/network/timeout分類、raw body/exception detail/secretがresult/logにない |
| isolation | `ApplicationContextRunner`等でcandidate/guardがSpring beanとして生成されないこと、production profile flags false/provider.mode MOCKのまま、workerは既存MOCK/STUB/HTTP LOOPBACKだけから変化しないこと |

期限テストはelapsed timeだけに依存せず、test barrier/phase observer・fixture状態・request受信有無・connection/permit数・terminal callbackを併用する。test hookはpackage-private constructor経由だけでproduction public APIに露出させない。再現不能なOS write stallは成功証拠と誇張せず、拒否/観察可能性を明記する。

## 変更禁止と後続境界

このwaveでは`IntegrationHubExternalApiProperties.ProviderMode`、`IntegrationHubTransportModeCondition`、application profileのpublic-api/external-transport flags、B1 worker/scheduler、DDL/migration、credential/keyring、subscription endpoint、実provider URL、production connection wiringを変更しない。integration-hubの通常transport構成はMOCK、public APIとexternal transportはfalseのままにする。実provider endpoint、実credential注入、遠隔egress、production enablement・送信は行わない。

Astraの実装Reviewがlocal TLS fixture境界、total deadline、wire header、cancel ambiguity、bounded resourcesをPASSするまでは、この計画を実装済み・PASSとして扱わない。将来のreal remote endpoint/provider/sandboxは、本機fixtureの実装・reviewから推定しない。remote egress policy、特定providerとのsandbox/contract確認、credential lifecycle、SLA/運用手順、production enablementはOwnerによる別の明示判断と運用承認が必要であり、この文書はそれらを代行・付与しない。
