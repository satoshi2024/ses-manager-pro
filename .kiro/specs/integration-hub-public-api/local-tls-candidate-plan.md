# NF-05 local TLS transport candidate plan

## 目的と基線

この計画はB1 outbound webhookの開発・test範囲に限り、実providerへ接続しないHTTPS transport candidateを本機TLS fixtureだけで検証する。DG-05の既存Owner承認およびユーザーのlocal実装指示を前提にした候補計画であり、新しいOwner承認やDecisionを作成・代行するものではない。

作業branchは`codex/snf-prod-nf05-local-tls`、実装baseはcontrol固定Head `9bac40c9624e7d964bcf6e18f480b3457662206e`である。取得済み`origin/main`参照は`1443fb68943849a3260d7ebe005259f3eb609d60`であり、実装baseと同一ではない。後続の計画・review・実装でこの2つを混同しない。

既存`LoopbackIntegrationHubWebhookTransport`は、現在のsource hashがdeadline evidenceの検証後final tree hashと一致する。接続からrequest write・response headerまでを一つのmonotonic deadlineで制限し、応答本文をdrainせず、loopback literalとport/peer検証を行う。deadline evidenceは4 class合計26 tests、failure/error/skip 0/0/0を記録する一方、OS socket buffer依存のOP_WRITE待機そのものは動的に証明しておらず、sandbox契約・実provider送信・production運用承認も未完了としている。

このcandidateは既存HTTP loopback transportを置き換えず、HTTPS/TLSの局所境界を独立検証する。現時点で遠隔endpoint、実credential、production外送信を許可する計画ではない。

## 審査記録と状態の束縛

- PR #108 head `f99c993a67d63afc3572ceeeb1cd00c957b17101`（`9bac40c9..f99c993a`、4 commit）は独立安全審査でREQUEST CHANGES（P0 0 / P1 3 / P2 4 / P3 10）となった。審査はHC5 5.3.1 / httpcore5 5.2.5のbytecodeで呼出し順を確認している。
- 指摘のうちf99c993aの主張「request channel直前で実socket peerを検証する」は不成立だった。`AsyncRequestProducer.sendRequest`は`execute()`提出時に呼出し側threadで同期実行され、経路決定・接続より前に走るため、endpoint/SSL sessionは常にnullで全送信が`NETWORK_ERROR`になっていた。この主張は撤回し、下記「固定する不変条件」1の実装へ置き換えた。
- 段階Aの修正は`codex/snf-nf05-tls-transport-fix-20261113`（base: `f99c993a`）で行う。修正後のSHAで対象testのsurefire証跡を取り、独立再審査をPASSするまで、統合候補線へ入れずPASSとも扱わない。
- 段階A修正`60a87249`の増分再審査はPASS WITH CONDITIONS（静的、`60a87249`だけに束縛）だった。条件C1（`slowResponseHeadersUseTheSingleSendDeadline`が期限切れcancelのRSTにより必ず失敗する）とC3（停止競合時の結果を`TRANSPORT_CLOSED`とした注釈・報告の誤り）は、`60a87249`を親とするtest・文書だけの後続commitで是正する。C2（修正後SHAでのsurefire証跡）は未取得であり、C4は段階Cの条件として残る。

## 開発対象

候補実装は次のsource/test filesだけを想定する。実装前にAstraがこの計画と各不変条件を確認する。

- `src/main/java/com/ses/service/integrationhub/LoopbackTlsIntegrationHubWebhookTransportCandidate.java`
- `src/main/java/com/ses/service/integrationhub/IntegrationHubLoopbackTlsEndpointGuard.java`
- `src/test/java/com/ses/service/integrationhub/IntegrationHubLoopbackTlsTransportCandidateTest.java`

test fixtureの合成CA・server証明書・秘密鍵はrepositoryへ置かない。test classの実行開始時に、実行中JDKの`keytool`でJUnit管理の一時directoryへ一度だけ生成し、class内で再利用してclass終了時に削除する（合成CA 2種、leaf 5種で計19 process）。server側key entryはJDK APIで組み立て、期限切れleafもkeytoolのreply検証を経ずにchain化する。

transport classとguardはpackage-private/finalとし、constructorもpackage-privateでfixture用`SSLContext`を明示的に受け取る。production default trustを置き換えるglobal JVM property、global `HttpsURLConnection` defaults、trust-all manager、hostname verifier overrideは持たない。candidateは`IntegrationHubWebhookTransport`の試験用実装として扱うが、`@Component`、`@Bean`、Spring configuration import、profile property、provider mode enum、production workerへのwiringは追加しない。

送信は既存Apache HttpComponents Client 5依存を用いたasync client候補を優先する。NF08の`CopilotBoundedHttpTransport`は、明示connection pool上限、hard cancellation、無retry/redirect、単調deadline、admission/exchange permit、atomicな一度限りrelease、保守的な`MAY_HAVE_BEEN_SENT`状態の設計参考として読み取りだけ行う。NF08のsource、config、testは変更せず、NF08固有URL/DNS/response/secret規則を複写しない。Classic request factoryはper-request closeとTLS/DNS pinningの参考に留め、B1候補には無制限のconnection creationを持ち込まない。

HC5でdeadline中のwrite/TLS/header待機をhard-cancelし、shutdownまでpool/threadをboundedに保つことを検証で示せない場合は、実装完了を宣言せず計画へ戻す。JDK 21専用APIを使わずJava 17 bytecode/API範囲を守る。`HttpClient.close()`などJava 21以降のAPIに依存しない。

## 固定する不変条件

1. **接続先は本機だけ**: candidate guardは`https` URI、厳密なIP literal `127.0.0.1`または`::1`、明示port allow-listを必須とする。hostname/DNS、proxy、redirect、userinfo、query/fragment、invalid/out-of-range port、dot-segment・encoded separator pathを拒否する。許可portはguard構築時のsnapshotだけを使い、可変propertiesを後から読まない。実socket peerの検証は次の3層で行う。
   - send入口: guardがURIを検証する（DNS・socketは使わない）。
   - DnsResolver: DNSを使わず、guard snapshotで許可済みのliteralだけを返す（loopback候補ではrebinding窓がない）。
   - TLS upgrade入口: `ClientTlsStrategyBuilder`のTlsStrategyを包む。HC5 5.3.1では`DefaultAsyncClientConnectionOperator$1.completed(IOSession)`がTCP接続完了後に`DefaultManagedAsyncClientConnection`を生成し、直後に`TlsStrategy.upgrade(TransportSecurityLayer, NamedEndpoint, Object, Timeout, FutureCallback)`を呼ぶ。この時点で`HttpConnection.getRemoteAddress()`（`IOSessionImpl`が`SocketChannel.socket().getRemoteSocketAddress()`を返す実peer）を、HC5が渡すNamedEndpointをguardで再検証した期待宛先と照合する。不一致なら接続をIMMEDIATEで閉じ、ClientHelloもHTTP bytesも送らずに`PEER_REJECTED`とする。handshake完了callbackでもHTTP送信前にTLS sessionの存在と実peerを再確認する。callbackなしの旧`upgrade`は平文続行を防ぐため拒否する。
   全テストはloopback fixtureだけに接続し、外部hostを名前解決・接続しない。
2. **通常のTLS検証**: certificate chain trust、有効期間、TLS hostname/IP SANを既定の証明書検証（PKIXとHC5 `DefaultHostnameVerifier`）で検査する。test専用private CAはtest SSLContextだけへ直接注入し、プロセス/global trust storeを書き換えない。SAN不一致は`TLS_IDENTITY_REJECTED`（再試行不可）、期限切れ・未信頼chain・handshake中の切断は`TLS_HANDSHAKE_REJECTED`（再試行可、HTTP未送信）、handshake停滞は`TIMEOUT`とする。TLS upgrade段階の失敗はcandidate内部のmarker例外で包み、HTTP送信後のSSL例外と区別する。test fixture証明書・秘密鍵は実行時生成の合成値で、上記の一時directoryにだけ存在する。
3. **一つの総期限**: `send` entryで開始した単調時刻deadlineがadmission待ち、connection lease、TLS handshake、request write、response header受信/parse、transport result確定までを包含する。各段階は残時間を再評価し、その残時間だけをHC5のrequest/connection/socket timeoutへ設定する。deadline後のHTTP responseは成功扱いしない（response consumerと呼出し側が同じ判定参照をCASで先取りし、期限側が先に確定した後のheaderは拒否される）。response bodyはdrainせず、header確定後にexchangeをcancelしてstream/socketを閉じる。
4. **cancelの意味を過大評価しない**: timeout/interruption後のfuture/socket cancellationは、request未送信または相手側処理取消しの証拠ではない。execute提出前に確定した失敗だけを未送信（`TIMEOUT_BEFORE_SEND`、`NETWORK_UNAVAILABLE`、`TRANSPORT_CLOSED`、`TRANSPORT_BUSY`）とし、提出後は結果不明を保守的に記録する（`TIMEOUT`、`NETWORK_ERROR`）。`TRANSPORT_CLOSED`はsend入口で停止済みを確認した場合だけである。HC5の`InternalAbstractHttpAsyncClient.doExecute`は停止済みclientの`CancellationException`（`IllegalStateException`系）を同期throwせず`ComplexFuture.failed`へ変換するため、入口確認後に停止した競合はfailed callback経由の`NETWORK_ERROR`（再試行可）になる。`NETWORK_UNAVAILABLE`はtest seamの`ExchangeExecutor`が同期例外を投げた経路でだけ発生する。timeout後も副作用未発生・socket閉鎖済みと推定しない。再試行で同じdeliveryの既存provider idempotency keyを維持し、成功直後のCAS障害をtransport failureへ読み替えない。
5. **資源の有限性**: client数、connection pool全体/route別（8/4）、in-flight交換（8）、admitted交換（12）、I/O reactor thread数（2）を固定上限化する。任意のwork queueを導入する場合は容量固定、拒否policyはAbort/即時拒否とし、CallerRunsで呼出し元にHTTP処理を実行させない。permitは未submit時のrollbackまたはHC5 terminal callback（completed/failed/cancelled）のどちらか一度だけ解放し、遅延callback、同期submit例外、cancel競合、重複callbackでも二重解放/漏れを起こさない。HC5の`BasicFuture.cancel`は`FutureCallback.cancelled()`を呼出し側threadで同期実行し、`ComplexFuture.cancel`はその後にdependencyを取消す。したがってcancelが戻った時点でpermitは解放済みであり、I/Oの取消しと接続closeはその後に進む。cancel後に残るsocket/TLS処理の上限はconnection pool（8/4）であり、permitではない。
6. **wire headerの単一正本**: caller headerは総byte数、個数、個別name/value byte上限で拒否する。caller指定`Host`、`Content-Length`、`Content-Type`、`Content-Encoding`、`Transfer-Encoding`、`Connection`、`Keep-Alive`、`Expect`などtransportが所有するhop-by-hop/encoding headerとの大小文字を無視した重複・衝突を`REQUEST_HEADER_REJECTED`で拒否し、transportがauthorityとbody長から一つずつ生成する。bodyは既存`IntegrationHubWebhookRequest.MAX_BODY_BYTES`を超えない。redirect/retryをHTTP client層で自動実行しない。
7. **safe result**: 結果はresponse header受信時に確定する。2xxはsuccess、429/5xxは`HTTP_RETRYABLE`、3xxは`HTTP_3XX`、その他4xxは`HTTP_4XX`、600以上は`HTTP_STATUS_UNEXPECTED`とする。header確定後に届いたbodyは読まずに破棄し（初期受信windowを超えて読まない）、body段階の失敗・cancel・遅いterminal callbackは確定済みの分類を変えない。分類の確定とpermit解放は別に追跡する。停止後の`TRANSPORT_CLOSED`は未送信が確定しているため再試行可能とする。raw response body、secret、credential、header値、例外文をlog/resultへ出さない。既存HMAC署名headerと`Idempotency-Key`は改変しない。
8. **Java 17**: 実装/API/testは`<java.version>17</java.version>`を守り、Java 21専用APIを使わない。

## 検証計画

`IntegrationHubLoopbackTlsTransportCandidateTest`は本機`HttpsServer`（有界executor付き）、raw `SSLServerSocket`、ephemeral loopback portだけを使う。test classはSpringを起動しない。候補クラスのclose、server、executor、socketの有限終了を`@AfterEach`で保証する。実行時は既存HTTP loopback回帰も同じ限定gateで確認するが、確認前にPASSを主張しない。

| ケース | 期待する証拠（対応test） |
|---|---|
| TLS成功、正しいloopback IP SAN / test CA | 204とsafe provider request IDだけを返し、serverが受け取ったbodyとHMAC/idempotency headersが期待値と一致（`trustedIpSanHttpsFixtureReturnsOnlySafeMetadata`）。`[::1]`でも成功（`ipv6LoopbackLiteralSucceedsWithIpSan`） |
| 実socket peer | 成功時にTLS upgrade入口の検証が1回だけ走る。DnsResolverを127.0.0.2へ差し替えると`PEER_REJECTED`、127.0.0.2側fixtureの受信0。同じfixtureが127.0.0.1名義の証明書・hostname検証を通ることを対照で示す（`actualSocketPeerIsVerifiedInTlsUpgradeBeforeHandshake`） |
| TLS negative | IP SAN不一致は`TLS_IDENTITY_REJECTED`、期限切れは`TLS_HANDSHAKE_REJECTED`、未信頼CAは`TLS_HANDSHAKE_REJECTED`。同じtransportが信頼済みfixtureへ成功する正の対照を同じtestに置き、HTTP handler到達0（`tlsIdentityAndValidityFailuresUseTlsCodesWhileTrustedFixtureSucceeds`、`untrustedCaIsRejectedWhileSameTransportAcceptsItsOwnCa`） |
| destination rejection | HTTP、hostname、userinfo、query/fragment、port未許可/省略、`10.0.0.1`、`169.254.169.254`、`0.0.0.0`、`127.0.0.2`、`[::ffff:127.0.0.1]`、非正規形`::1`、ULA、traversalを接続前に拒否。DnsResolver呼出し0、socket 0（`rejectsDestinationsBeforeAnyDnsOrSocketActivity`） |
| response handling | redirectを追跡しない（3xxの`Location`先fixture受信0）。header後のslow body、1回のwriteで届く200+body、1MiB body、無限chunked bodyでsuccessのまま期限内に戻り、serverは接続closeを観測する（`wireStatusClassificationDoesNotFollowRedirects`、`slowBodyIsNotDrainedAndReturnsAfterHeaders`、`twoHundredWithBodyInSingleWriteIsSuccess`、`largeAndUnboundedResponseBodiesAreNotDrainedAndConnectionIsClosed`） |
| total deadline | slow response headerとTLS handshake stallで同じ総期限が働き、requestがfixtureへ届いたことまたはTCP accept・peer検証が走ったことを併せて確認する。期限切れcancelはIMMEDIATE close（linger 0のRST）を呼出し側threadで同期実行するため、fixtureの遅い2xxはclientへ届かない。wire上では期限後にfixtureが応答書込みへ進んでも結果が`TIMEOUT`のままであることを確認し（`slowResponseHeadersUseTheSingleSendDeadline`、`tlsHandshakeStallExpiresInsideTheSharedDeadline`）、header未受信で確定した後のheaderがdecisionのCASで拒否されることは`ExchangeExecutor`経由で直接確認する（`delayedTerminalCallbackAndSynchronousExecuteFailureReleasePermitsOnce`） |
| ambiguity/cancel | test seamのexecute同期失敗は`NETWORK_UNAVAILABLE`、send入口での停止確認は`TRANSPORT_CLOSED`（いずれも再試行可）。入口確認後の停止競合と提出後の停止中断は再試行可能な結果不明（`NETWORK_ERROR`または`TIMEOUT`）（`closeCancelRaceAndPostCloseRejectionDoNotLeakPermits`、`delayedTerminalCallbackAndSynchronousExecuteFailureReleasePermitsOnce`） |
| bounded concurrency | admission/exchange/route上限を超える要求は`TRANSPORT_BUSY`でboundedに拒否し、終了後にpermitが12/8/0へ収束（`admittedAndActiveExchangeLimitsRejectExcessAndReleaseAfterCompletion`）。HC5 `BasicFuture`で、cancel復帰時点のpermit解放、重複terminal callbackでの二重解放なし、header確定後の遅いterminal callbackまでpermitを保持することを示す（`delayedTerminalCallbackAndSynchronousExecuteFailureReleasePermitsOnce`、`lateTerminalCallbackAfterHeadersKeepsOutcomeAndReleasesPermitsOnce`、`published2xxHeadersRemainSuccessWhenTerminalFailsAfterwards`）。close後はreactor停止、pool 0（`closeCapturesReactorPoolAndPeerSocketEvidence`） |
| header/body boundary | aggregate header bytes/count/name/value上限、case-insensitive transport-owned header衝突、制御文字を`REQUEST_HEADER_REJECTED`（`transportOwnedAndOversizedRequestHeadersAreRejectedBeforeDispatch`）。wire上`Host`/`Content-Length`/`Content-Type`/`Connection`が各1回、`Transfer-Encoding`/`Expect`なし、caller headerとbodyが原文どおり（`wireRequestCarriesSingleHostAndContentLength`）。応答framing衝突・header数/行長超過を`NETWORK_ERROR`（`wireMalformedResponseFramingAndParserBoundsAreRejected`、`responseHeaderParserRejectsFramingConflictsAndCountOrByteOverflow`） |
| bounded results | 3xx/4xx/429/5xxの分類をwire経由で確認（`wireStatusClassificationDoesNotFollowRedirects`）。HC5本体をDEBUG、wire/header loggerをOFFにした状態で、signature・Idempotency-Key・bodyがlog/resultに出ず、candidate自身はlogを出さない（`logsAndResultsDoNotExposeSecretsWithHc5DebugEnabled`） |
| proxy | proxy system propertyとJVM既定ProxySelectorを設定しても参照・接続しない（`proxySystemPropertiesAndDefaultSelectorAreIgnored`） |
| isolation | candidate/guardがpackage-private/final、annotationなし、public constructorなしで、production sourceから参照されない（`candidateAndGuardStayPackagePrivateWithoutSpringWiring`）。production profile flags false/provider.mode MOCKと既存MOCK/STUB/HTTP LOOPBACK workerは本変更で変えない |

期限テストはelapsed timeだけに依存せず、fixtureのrequest受信有無・TCP accept・peer検証回数・connection/permit数・terminal callbackを併用する。test hookはpackage-private constructor経由だけでproduction public APIに露出させない。

未検証として残すもの: request write stall（OS socket buffer依存で再現不能）、connection lease待ち単独の期限切れ（route上限を占有するexchange自体が同じ総期限で終わるため、lease待ちとheader待ちを決定的に分離できない）、`ApplicationContextRunner`によるSpring context単位のisolation。これらは成功証拠と誇張しない。

## 変更禁止と後続境界

このwaveでは`IntegrationHubExternalApiProperties.ProviderMode`、`IntegrationHubTransportModeCondition`、application profileのpublic-api/external-transport flags、B1 worker/scheduler、DDL/migration、credential/keyring、subscription endpoint、実provider URL、production connection wiringを変更しない。integration-hubの通常transport構成はMOCK、public APIとexternal transportはfalseのままにする。実provider endpoint、実credential注入、遠隔egress、production enablement・送信は行わない。

Astraの実装Reviewがlocal TLS fixture境界、total deadline、wire header、cancel ambiguity、bounded resourcesをPASSするまでは、この計画を実装済み・PASSとして扱わない。将来のreal remote endpoint/provider/sandboxは、本機fixtureの実装・reviewから推定しない。remote egress policy（全解決addressの非global判定、期限付きDNS snapshot、TLS upgrade入口での実peer検証、hostname保持によるSNI/hostname検証）、production TLS trust・protocol・失効確認・pinning方針、特定providerとのsandbox/contract確認、credential lifecycle、SLA/運用手順、production enablementはOwnerによる別の明示判断と運用承認が必要であり、この文書はそれらを代行・付与しない。
