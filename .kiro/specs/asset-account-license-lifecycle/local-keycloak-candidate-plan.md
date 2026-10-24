# NF09 Local Keycloak REST candidate 計画

状態: **技術実装Plan案 / Astra独立Plan Review待ち**。固定control `9bac40c9624e7d964bcf6e18f480b3457662206e` を基準とする隔離worktree上の、loopback synthetic 専用候補である。origin/B0は別の比較参照であり、本計画のbaseと同一とは扱わない。DG-09の承認済み開発scopeおよびTD04の技術選定に基づき、既存provider契約に接続しない独立候補を作る。Plan Review PASS前に実装しない。

## 1. 目的と明確な制限

既存のfail-closed provider boundaryの外で、Keycloak Admin REST APIの3操作を生成fixtureとloopback HTTP serverだけで技術検証する。

1. userを無効化する要求
2. userのsession logout要求
3. userとsessionの現在状態を読む要求

本sliceは**本番providerではなくlocal候補**である。Spring Bean、wiring、`ExternalAccountProviderClient`実装登録、condition/profile、scheduler、migration/DDL、既存service/UI、実環境設定・credential読み込みは変更しない。実Keycloak instance、real credential、実provider call、production enablementは対象外である。合成fixture以外へ送信できる経路を持たない。

user無効化のHTTP応答、logout応答、現在状態の読取りは別々の結果として保持する。いずれの組合せでも、全失効範囲を示す`CONFIRMED`を生成しない。特にoffline session、発行済access token、下流SaaS sessionおよびclient側の独自session状態はこの候補で確証できない。

## 2. 公式API契約の根拠

参照版は2026-10-24時点で固定したKeycloak Admin REST API 26.7.4である。

- `PUT /admin/realms/{realm}/users/{user-id}` は userを更新し、成功応答は204。candidateでは`enabled=false`の要求応答を`ACKNOWLEDGED`とだけ分類し、更新済みの確証とはしない。
- `POST /admin/realms/{realm}/users/{user-id}/logout` は当該userに関連する全user sessionを削除し、admin URLを持つclientへsession invalidation通知を送る仕様で、成功応答は204。client側、offline session、tokenや下流SaaSの全失効まではこの応答から推定しない。
- `GET /admin/realms/{realm}/users/{user-id}` はUserRepresentationを返す。`GET /admin/realms/{realm}/users/{user-id}/sessions` は当該userに関連するsession一覧を返す。offline session APIはclient UUIDを指定する経路であり、candidateは全clientを網羅したoffline-session不存在を証明しない。

公式資料: [Keycloak Admin REST API 26.7.4](https://www.keycloak.org/docs-api/26.7.4/rest-api/index.html)（Users: user GET / PUT、`/logout`、`/sessions`、`/offline-sessions/{clientUuid}`）。HTTP 204はAPI要求の応答に限り、別の認証面や全resourceの失効確認とは同一視しない。

## 3. 実装候補と固定ファイル範囲

main packageは`com.ses.service.provider.impl.localcandidate`とし、全classをpackage-private、Spring annotationなしとする。

- `src/main/java/com/ses/service/provider/impl/localcandidate/LocalKeycloakExternalAccountProviderCandidate.java`
  - 明示引数だけを受ける独立候補。disable、logout、queryの操作別immutable結果を返し、結果の集約値に`CONFIRMED`を定義しない。
- `.../LocalKeycloakCandidateGuard.java`
  - URIを検査し、scheme=`http`、hostがIPv4 `127.0.0.1`またはIPv6 loopback literal、port明示かつ有効、userinfo/query/fragmentなし、許可外pathなしを要求する。candidate生成時にimmutableなfixture endpoint allowlist（loopback IP + exact port）を確定し、接続後にsocketの実peer address/portをallowlistと照合する。localhost名、DNS解決、allowlist外port、non-loopback、proxy、環境変数由来endpointを拒否する。
- `.../LocalKeycloakCandidateRequest.java` / `.../LocalKeycloakCandidateResult.java`
  - immutable synthetic requestと操作別結果。外部ID/realmは空値、制御文字、separatorを拒否し、URI path segmentとしてUTF-8 percent-encodeする。JSON bodyはdisable操作の`enabled=false`だけを送る。tokenはDTO/toString/equality/evidenceへ含めず、Authorization headerの一時値としてのみ使う。
- `.../BoundedLoopbackHttp.java`
  - Java 17 `SocketChannel` non-blocking I/Oと`Selector`で同期実行する。connect、実peer照合、partial write、status line、headers、bodyがすべて同じabsolute `System.nanoTime()` deadlineを共有し、read/write進捗ごとにtimeoutをリセットしない。独自executor/future、proxy、redirect、retryは使わない。response byte cap、同時実行数上限も実装し、resource上限を超えた呼出しは即時拒否し待ち行列を作らない。
- `.../BoundedHttp1ResponseParser.java`
  - raw HTTP/1.1 response framingをbounded parserで処理する。status/header CRLF、最大header count/line/aggregate bytes、framing headerの重複、`Content-Length`、`Transfer-Encoding`、body長を検証する。曖昧・不正framingは`UNKNOWN`とし、勝手なfallback/HTTP library parserへ移譲しない。

test-only範囲:

- `src/test/java/com/ses/service/provider/impl/localcandidate/LocalKeycloakExternalAccountProviderCandidateTest.java`
- `src/test/java/com/ses/service/provider/impl/localcandidate/LocalKeycloakLoopbackFixture.java`

JDKのloopback `HttpServer`上にsynthetic user/session dataとdummy bearer tokenを用意する。既存provider interface、Mock/FailClosed実装、Spring test contextまたは既存migrationを変更しない。

## 4. bounded通信と結果分類

- endpointはliteral loopbackだけでなく、candidate構築時にfixtureが明示したimmutable IP+port allowlistとの一致を必須とする。接続成功後、request bytes送信前に`SocketChannel.getRemoteAddress()`の実peer IP/portを同じallowlistへ照合する。host名、DNS、allowlist外port、非loopback、暗黙port、userinfo、fragment、query、unexpected base pathを拒否する。
- realm/userIdはUTF-16 surrogateを検査し、長さ上限（realm 128 UTF-8 bytes、userId 256 UTF-8 bytes）、空値/control/CRLF拒否、`.`/`..` segment拒否、raw `/` `\\` とpercent-encoded slash/backslash/dot separator拒否を行う。残る有効UTF-8 bytesだけをRFC 3986 path-segmentとしてpercent-encodeし、encoded pathをallowlisted routeと比較する。
- synthetic bearer tokenだけを明示的に引数で受ける。最大4096 UTF-8 bytes、CR/LF/全control character禁止。credential store、system property、environment variable、config binding、ログ・例外・result/evidenceへのsecret出力を設けない。request headerは最大12個/8 KiB合計、個別header 4 KiBとし、重複Authorization/Host/Content-Lengthや制御文字を拒否する。
- 1操作系列あたり単一monotonic absolute deadline（計画値 5秒）を持ち、non-blocking connect、write、status/header/body読取りすべてでremaining timeをSelectorへ渡す。部分write/readのたびに期限を更新しない。selector無進捗時も同じ期限で中断する。redirect/retryは常に無効。全responseはheader 32個/各4 KiB/総16 KiB、body 64 KiBを上限とする。JSON responseはUTF-8厳密decode、最大nesting depth 8、string 4 KiB、field名128 bytes、duplicate key拒否。超過・不正JSON・予期しないcontent typeは`UNKNOWN`とする。
- HTTP/1.1 framingは固定subsetだけを許可する。response status lineは一行、HTTP/1.1、100-599に限定し、interim 1xx、obs-fold、bare LF、invalid token/header、duplicate `Content-Length`、`Transfer-Encoding`（chunkedを含む）、TE+CL、EOF-delimited success bodyは拒否し`UNKNOWN`にする。204はbodyなし（Content-Length absentまたは0のみ）。200 JSON GETは単一有効Content-Length必須であり、宣言長と実body bytesが一致しない場合は`UNKNOWN`。その他statusはframing headerが有効であることを確認してからnon-successとして分類し、bodyはcap内のみ読み捨てる。接続closeはdeadline内で行い、未読bodyを無限drainしない。
- 同時実行は最大2件、semaphore permitを使い、満杯なら待機せず`NOT_STARTED`で返す。ネットワーク処理はcaller threadで同期実行し、独自executorやunbounded queueを作らない。timeout/parse failure後はchannelを閉じ、late responseを無視する。
- 副作用HTTP呼出しに入る前は`NOT_STARTED`。要求送信を開始する直前に内部stateを`MAY_HAVE_BEEN_SENT`へ進める。応答を受け取れない、timeout、切断、予測外statusはその曖昧性を成功扱いせず`MAY_HAVE_BEEN_SENT`または分類不能なら`UNKNOWN`として返す。公式期待の204を受けた場合だけその個別操作を`ACKNOWLEDGED`とする。
- disableとlogoutは独立実行・独立結果を返す。前段disableのACKを根拠にlogout成功を合成せず、片方の失敗後に残りを勝手に再試行しない。user enabled=falseのGET再読取り、sessions GETのempty listはそれぞれ観測として記録できるが、全体の失効確証にはしない。
- この候補の結果状態は`NOT_STARTED`、`ACKNOWLEDGED`、`MAY_HAVE_BEEN_SENT`、`UNKNOWN`に限定する。combined resultもこれらの個別結果だけを保持し、全scopeの`CONFIRMED`、`REVOKED`または退社blocker解除を生成しない。
- evidence/logにはoperation、safe result code、elapsed time、response size、synthetic correlation refだけを出す。bearer token、authorization header、raw response、外部subject文字列は出力しない。

## 5. synthetic loopback test matrix

1. Guard/peer: exact fixture allowlistのIPv4/IPv6 loopback IP+portのみ受理。hostname、private/public IP、portなし、userinfo/query/fragment/path prefix、encoded slash/dot-segment、surrogate/control/header injectionはrequest byte 0。fixtureと違うloopback port、および接続後の実remote address mismatchもrequest byte 0で拒否する。
2. Disable: dummy bearer tokenがheaderにだけ渡ること、PUT path/bodyの厳密一致、204は`ACKNOWLEDGED`、4xx/5xxは非成功状態、既存enabled値のGET readback true/false/404/不正JSONをそれぞれ分類する。HTTP response 204単独でconfirmedにならない。
3. Logout: POST exact path、204 ACK、redirect/3xx拒否、timeout・server close・部分応答時の曖昧statusを確認する。disable ACK後のlogout failureでもoverall confirmationなし。
4. Current-state query: user representationのenabled fieldをstrict parseし、sessions一覧のみ別観測する。offline session endpointはclient UUID単位であることから全client網羅・token失効を主張しない。
5. Ambiguous timing: call前deadline expiryは`NOT_STARTED`かつserver hits 0。送信開始後に応答を遅延/切断して`MAY_HAVE_BEEN_SENT`/`UNKNOWN`、late responseを成功へ昇格しない。
6. Resource/framing limits: deterministic nonblocking channel seamでpartial-write stallとSelector期限を検証し、raw loopback fixtureでconnect stall、slow status/header/body、response >64KiB、全体deadline、並行2枠飽和時のimmediate rejection、permit回収を確認する。timeout後に遅延bodyをdrainしない。duplicate/conflicting Content-Length、TE、TE+CL、chunked、EOF body、bad CRLF、oversized header/count/body、bad JSON depth/string/duplicate-keyはfail closedでrequest resultに生bytesを含めない。
7. Redirect/retry/proxy: 302 Locationが外部URLを指してもfollowしない。server hitは最大1、proxy fixture hit 0。timeout再試行がないこと。
8. Secret boundary: dummy tokenをexception、result `toString`, logs/evidenceに出さない。raw JSON・subject文字列はevidenceに出ない。
9. Composition: disable/logout/queryの各成功・失敗・部分成功・未知結果を組み合わせ、全て`CONFIRMED`/`REVOKED`を返さない。

## 6. 残存リスクと後続依存

- loopback parserの限定HTTP/1.1 subsetはKeycloak server実装/versionとの互換性、PUT update semantics、Keycloak error body/status変化を証明しない。未対応/曖昧なframing/content-type/statusは`UNKNOWN`に保ち、pinned Keycloak 26.7.4 synthetic instanceによる別integration候補を後続で計画する。
- in-memory requestの`MAY_HAVE_BEEN_SENT`分類は再起動後に残らず、durable idempotency claim、crash recovery、再起動後UNKNOWN保持は本batchに含まれない。既存DB側を勝手に更新せず、この候補を既存schedulerから呼ばない。
- GET sessionsはKeycloakが返すsession一覧の観測に限る。offline sessionの全client網羅、already-issued tokenの即時無効化、downstream service session・client admin URLの実働は未証明。
- 204 acknowledgement、user disabled readback、active session list emptyの組合せでも全scope `CONFIRMED`とはしない。正式な退社gate完了条件は既存policy/後続Decisionが確定するまで維持する。
- 完了判定はPlan PASS後の候補コード、loopback test、固定SHAの独立Reviewに限定し、NF09全機能またはproduction readinessへ拡張しない。

## 7. 計画Reviewと実装順序

1. Astraがこの固定Plan SHAに対し、TD04/DG-09との整合、URI/subject encoding、timeout/resource bounds、status semantics、no wiring/no secret、remaining risksを独立Reviewする。
2. `PLAN PASS`後に限り上記package-private classとloopback fixture testsを実装する。
3. 候補SHA固定後、対象unit testsとfresh `javac`を実施し、rootが必要なsuiteを別途直列実行する。
4. Astraは固定candidate SHAでdiff、test evidence、実装が`CONFIRMED`を生成しないこと、既存provider/profile/scheduler/DDLに接続していないことを再Reviewする。

このPlanは開発scope内のlocal candidateだけを扱い、追加の人手批准を前提にしない。production credential、approved destination、実Keycloak接続、実provider enablement、正式owner/運用gateは別の既存gateで管理する。
