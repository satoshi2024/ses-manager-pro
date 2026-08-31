# NF-05 Public API Tasks（scope expansion承認済み・F1/F2/A1 PASS・B1再Review待ち）

## 実行停止規則

F1は独立PLAN/IMPLEMENTATION PASS済みで再オープンしない。scope expansion Plan deltaは固定Head
ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでP0=0、P1=0、P2=0のPASSを受領したため、F2を実施する。
F2の独立Implementation Reviewはfixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でPASSした。A1もfixed Head
`69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASSを受領した。B1を実装済みで独立Reviewへ提出し、
B1 Review PASS後はB2→Mを順次実施し、各waveの独立Reviewと
commit/pushを行う。A2はapproved command=0件のためN/Aとし、command/exportはdefault denyのままとする。
development/testのmock/stub providerとloopback test serverは許可するが、production enablement、実顧客credential、
実provider送信、force push、main変更、PR、merge、auto-mergeは行わない。

## Task 0: threat / contract / field inventory

- [x] Objective: 既存のsecurity filter、secret、outbox/provider/job/idempotency、correlation、rate/IP、DTOを棚卸しし、
  client scope × data scope × command permissionの候補表を作る。
- Implementation: README.md、approval-decision.md、plan.md、requirements.md、design.md、inventory.md、
  completion-matrix.md、review-ledger.md。
- Test requirements: git boundary、通常checkout未変更、dedicated worktree clean、文書の機密情報非掲載を確認。
- Demo: 未承認事項とproduction変更禁止を読み手が確認できる。

## Task 0R: Review remediation（spec / architecture only）

- [x] Objective: ReviewのP1/P2のうち実装AIが解消可能なatomic outbox、candidate OpenAPI、metrics cardinality、
  payload retention、review traceをspecへ反映する。
- Implementation: design.mdのtransaction境界を修正し、openapi-candidate.yaml、review-remediation.mdを追加。
  requirements/tasks/completion-matrix/review-ledgerへ証跡・Owner承認・未着手F1-Mを反映する。
- Test requirements: git diff --check、OpenAPI YAML parse、必須path/schema/assertion、production source/
  migration/test差分0、通常checkout非変更、local/remote Head一致。
- Demo: atomic outboxの同一DB transaction、claim/HTTP/CAS分離、bounded metrics、承認済みretention、
  default-deny command/exportを独立Reviewが確認できる。

## Task 0R-D: Task 0R delta Review remediation（spec only）

- [x] Objective: count surface、client指定asOf、status/code mapping、response correlation headerのdelta指摘を解消する。
- Implementation: engineer-availability count endpointと全client指定asOf parameterを削除し、status別error
  schema、scope外detailの404収束候補、全成功/error responseのX-Correlation-IDをopenapi-candidateへ反映する。
- Test requirements: YAML parse、GET-only path数、engineer count不存在、AsOf query parameter不存在、
  status別code enum、全response correlation header、production source/migration/test差分0。
- Demo: inventoryのoperation表とcandidate OpenAPIのpath/parameter/error/header集合が一致し、未承認のまま
  公開許可・実装PASSへ昇格していないことを確認する。

## Task 0R-P6: scope expansion Plan delta residual remediation（spec / architecture only）

- [x] Objective: security chainのnonce前IP確定・専用監査・error boundary、canonicalTarget完全byte手順、
  disabled deny-only chainとbean/config生成条件をrequirements/design/inventory/plan/ledgerへ同期する。
- Test requirements: docs-only差分、golden vector、filter/error/CORS/CSRF/anonymous境界、default/prod profile、
  disabled route、controller/worker/scheduler/transport bean不存在の受入条件を独立Plan Reviewが照合する。
- Demo: 固定remote Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aの独立Plan PASSを受領した後にF2へ進む。
  0R-P6自体はspec-onlyであり、production endpoint、外部送信、PRは変更しない。

## Task F1: client / credential / scope / idempotency DDL

- [x] Objective: client、credential version、scope、idempotency、usage bucket、nonce replay ledger、
  webhook/inbound、retention hold/checkpointの保存契約を実装する。
- Preconditions: Task 0/0R/0R-D/0R-P/0R-P2/0R-P3完了、approval-decision.md、指定Base再確認、独立Plan Review PLAN PASS。
- Implementation: usage bucketのDB natural keyはclient×scope×tenant×route templateに限定し、minute/day counterと
  burst token bucket（capacity 20、初期20、3秒ごとに1 token refill、clock rollback時は後戻りなし）を同じrowへ保存し、
  minute/day/burstの全条件を一つのlock/predicate transactionでconsumeする。t_api_nonce_replayはclient+nonce hash unique、TTL、bounded purgeを持つ。既存
  t_notification_outboxとAccounting IntegrationJobは変更・二重書込みせず、t_api_deliveryをNF-05専用ledgerとして
  分離する。各retention対象へclass/expiryを付け、t_api_retention_holdとt_api_purge_checkpointをlock/CAS規則で扱う。
  service interfaceは汎用IService/CRUDを公開せず、用途別snapshot allow-listとcanonical state transitionを通るtyped operationだけにする。
  snapshotのpayload/canonicalPayloadは構造化object、changedFieldNames/skillTagCodeはbounded string array、public ID・date・
  date-time・status/resultCode・signature/processing statusはfield固有のpattern/enumに限定し、raw body/PIIのscalar埋込みと
  未型付けfieldを拒否する。quota subjectとpurge checkpointの初期化はupsert-first、quota transactionはREAD COMMITTED＋
  bounded deadlock retryとし、hold/purgeはcheckpoint→target→holdの共通lock順序を使う。delivery purgeはlease token/expiry
  の片方NULLをfail-closedで除外する。
  idempotency/inboundのhash conflictは例外だけで終わらせずCONFLICT rowへ永続化する。purgeはretention_expires_at,idの
  keyset cursorを使い、active hold/lease除外、hold解除・restore時reset、候補末尾reset、delete直前のversion/lease再確認を行う。
  state enumはidempotency=IN_PROGRESS/SUCCEEDED/FAILED/CONFLICT、delivery=PENDING/CLAIMED/RETRYABLE/SUCCEEDED/FAILED/DLQ、
  inbound=RECEIVED/PROCESSING/PROCESSED/DUPLICATE/CONFLICT/DLQをcanonicalとし、別名・terminal逆遷移を実装しない。
  deliveryにはevent/subscription/generation由来の決定的provider idempotency keyを保存し、worker crash/stale lease/replayでも
  provider副作用を一件へ収束できる契約を固定する。
- Test requirements: fresh/legacy/partial/backfill/repair、暗号key version、revoke/expiry/overlap、unique/CAS、
  H2とMySQL、rollback/backup/restore、rate key exact boundary、multi-node increment、burst 20 capacity、3秒refillの
  直前/直後、minute/day境界、clock rollback、Retry-After、片方のquota更新失敗、nonce atomic unique/TTL/purge、
  delivery no-double-write、purge期限境界、legal hold競合、active lease、部分失敗、restore epoch後全件再評価、
  idempotency/delivery/inboundのcanonical enum全値・遷移・terminal retention mapping・alias/逆遷移拒否、service汎用CRUD迂回拒否、
  snapshot構造allow-list、idempotency/inbound conflict永続化、active lease後のkeyset再評価、実service/mapperを使うMySQL複数connection競合（usage unique初期化、delivery CAS、hold/purge、malformed lease、inbound duplicate）を含む。
- Demo: secret原文非表示、同key別payload拒否、rate key/IP分離、nonce replay拒否、t_api_delivery分離、
  burst/refillと三つのquota境界、migration証跡、DB transaction内外の境界、canonical state遷移、hold/purge/restoreの
  状態遷移を示す。
- 実施証跡: `a7654b44`でV129 MySQL migration、H2 schema/init、entity/mapper/service/crypto基盤、
  `a184c1f4`でImplementation Review remediation、`d476614e`でdelivery_generation CAS predicate correction、`5a2a0231`でfollow-up reviewの4 P1を実装・テストremediateし、`96d6801c`でsnapshotのfield固有型検証を追加して、purge/rollback証跡とF1契約テストを実装した。対象F1 suiteは31 tests、failure/error/skipなし。MySQL
  Flyway smokeはempty/legacy V78/normal経路でV129までPASSした。全fast suiteはF1対象外の既存loopback・
  production-config系およびloopback接続環境を含む既存テストの11 errorsと2 failuresで終了している。独立Implementation Reviewの
  旧remediation時点の再Reviewは未実施だった。follow-up remediation後の固定Headを独立Implementation Reviewへ再提出し、
  P1-FU-001の追加修正後に独立Implementation Review PASS（P0/P1/P2=0）を受領した。MySQL
  `IntegrationHubF1MySqlConcurrencyTest` 5件はPASSした。

## Task F2: dedicated security chain

- [x] Objective: /external-api/v1/**専用principal、client scope、data scope、command permission、audit、
  correlation、rate/IP境界を実装する。@Order(0)のsecurityMatcher、既存portal/internal chainとの
  排他、STATELESS、NullSecurityContextRepository、request cache無効、session/form/basic/OIDC/
  anonymous継承なし、認可済みGET以外anyRequest().denyAll()を固定する。filter順序はcorrelation/
  ExternalApiAuditBoundary開始→size/raw target precheck→trusted proxy/source IP解決→HMAC検証→
  client principal確定後のclient CIDR判定（nonce未永続化）→nonce atomic insert commit→scope/data
  scope/command permission→rate/quota→全decisionの監査確定→controllerとし、既存ApiAuditFilterを
  GET監査の正本にせず同一filterの自動
  二重登録を防ぐ。CSRFはexternal chainだけdisable、CORSは許可originなし、anonymousはdisable、
  401/403は専用stable JSON entrypointとcorrelation headerでinternal errorへfall-throughさせない。
- Preconditions: F1 Implementation PASS、scope expansion Decision、R-NF05 Plan delta PASS。F2独立Implementation Review再判定待ち。
- Remediation: 前回F2 FAIL（fixed Head `220ac86f`、P1=4/P2=2）に対し、connector raw-target供給、typed effective scope、専用audit、strict IP parser、finite metrics、
  namespace root boundaryを `e47025b5` へ実装した。独立再ReviewまでF2 PASSへ昇格しない。
- Additional remediation: fixed Head `f57df6d2` のP1（tenant/legal entity矛盾）とP2（mapped IPv6 CIDR比較）を `a16cdcba` へ実装した。
  tenant/legal entityはclient/route各JSONでprincipal singletonへ照合し、intersectionの空authoritative dimensionを保持して拒否する。
  mapped IPv6 source/CIDRは4-byte IPv4へcollapseし、両方向のCIDR比較をtestする。独立再ReviewまでF2 PASSへ昇格しない。
- Implementation guidance: HMAC wire headerはOpenAPI candidateのX-Client-ID、X-Credential-Version、
  X-Key-ID、X-Timestamp、X-Nonce、X-Client-Signatureへ固定し、credentialVersion/keyIdの形式、
  raw header block 16,384 byte/32 field、Content-Length、body 1,048,576 byte、Content-Encodingの
  上限をdesign 3.1どおりに検証する。canonical bytesはraw request-targetをtrusted connectorのimmutable
  attributeから取得し、最初の?で一度だけsplitする。path/queryの?・&・=、値なしと空値、percent
  encoding、canonical pair sort/rebuild、raw body SHA-256、Content-Encoding、各byte上限、UTF-8
  byte length prefix、keyIdを含む固定field順/LF framing、signature decode後32-byte、base64url paddingなし/
  constant-time compareをdesign 3.1のgolden vectorどおりに実装し、JSON再シリアライズや
  Forwarded/XFFの影響を受けない。各profileへpublic-api.enabled=false、external-transport.enabled=false、
  provider.mode=MOCK、STUB、LOOPBACKの明示enumを設定し、missing/unknown/malformed/conflicting config、
  production enablement、real URL/credentialをfail-closedで拒否する。disabled時もdeny-only chainを
  残し、controller、worker、scheduler、transport beanを生成しない。MOCK/STUBまたはliteral loopbackの
  allow-list portだけを許可し、redirect、proxy、hostname/DNS、multi-address/rebinding、non-loopbackを
  config時とconnection直前に拒否する。
- Test requirements: client A/B、scope差、data差、command差、rotation overlap/revoke/expiry、spoof、429、
  Retry-After、filter二重登録、chain順序/排他、stateless/session拒否、unknown method/path default deny、
  CSRF/CORS/anonymous拒否、401/403 stable JSONとcorrelation header、認証前/後principalとallow-list
  route templateを含むGET全decisionの専用監査、
  metrics scrape cardinality。raw request-target取得失敗、?・&・=、値なし/空値、duplicate query、
  percent encoding、Content-Encoding、body/target/header上限、empty body、malformed header/pathの
  golden signature vector、signature 32-byte制約、default-off/未設定/unknown起動fail-closed、
  disabled deny-only chainとcontroller/worker/scheduler/transport bean不存在、MOCK/STUB無接続、
  loopback IPv4/IPv6、DNS/redirect/proxy/peer検証も含む。
- Demo: internal/portal chainと公開chainが相互にprincipalを偽装しない。

- 実施証跡: `src/main/java/com/ses/config/integrationhub/` の専用chain/filter/principal/canonicalizer、raw-target valve、typed scope、専用audit/metrics、
  `src/main/java/com/ses/entity/integrationhub/ExternalApiAudit.java` と `V130__integration_hub_external_api_audit.sql`、
  `src/test/java/com/ses/config/integrationhub/` のF2 unit/security boundary test（対象29件の再確認）および
  `ExternalApiSecurityChainIntegrationTest`。request attribute手動注入なしのenabled connector E2Eも追加したが、Windows loopback接続エラーでHTTP assertion前に停止。
  F2のproduction enablement、B1/B2の実顧客/provider接続は未承認。A1 controllerとB1 development/test transportは実装済み。
- 追加境界証跡: `a16cdcba`、tenant/legal entity authoritative singletonとmapped IPv6 CIDR familyのfocused suite 19 tests、failure/error/skipなし。
- F2独立再Review fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でIMPLEMENTATION PASS（P0/P1/P2=0/0/0）。

## Task A1: v1 read APIs / OpenAPI

- [x] Objective: 承認済みread resourceのlist/detail/count、opaque cursor、stable error、OpenAPIを実装する。
- Preconditions: F2 Implementation PASS、scope expansion Decisionを満たした。初回Reviewのremediation後、fixed Head
  `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASS（P0/P1/P2=0/0/0）。
- Test requirements: external DTO allow-list、entity serialization negative、scope一致、cursor tamper/expiry、
  count/detail/list非列挙、error body secret/PII/内部情報なし。
- Demo: internal entityを一つもserializeせず、OpenAPI candidateのGET-only 11 pathsとexternal DTO allow-listへ一致させる。invoice customer scope、
  複数contract非偽装、初回as-of snapshotのinsert/update/delete/reparent、非canonical cursor拒否を確認する。snapshot purgeは公開readから分離し、
  expiry index順の有限batch、FK cascade、複数batch、再実行、部分失敗rollback、無通信時期限超過を確認する。
- 実装証跡: `ExternalApiReadController`、`ExternalApiReadService`、`ExternalApiReadMapper`、4 resource DTO、
  `ExternalApiPublicIdCodec`、`ExternalApiCursorCodec`、enabled key未設定時のfail-closed起動検証。
- 検証結果: remediation focused/integration suite 24 tests、failure/error/skipなし。Windows browser profileはcrypto fixture修正後もloopback接続確立失敗でHTTP assertion前に停止したため、
  独立ReviewのPASS根拠にはしない。A1独立Implementation Review PASS後にB1を開始した。

## Task A2: limited command APIs（N/A under current decision）

- [ ] Status: NOT_APPLICABLE_UNDER_CURRENT_DECISION。approved command=0件のため実装対象なし。
- Preconditions: command/exportはdefault denyのまま。command実装、source変更、migration、test、Demoを
  このDecisionでは作成しない。A2の未着手はapproved scope全体の完了をblockしない。
- Test requirements: command endpointが存在せず、read-only clientからcommand/exportへ到達できないことを
  default-deny testで確認する。
- Demo: A2を実装完了へcheckせず、inventory/plan/requirements/completion-matrixのN/A状態が一致する。

## Task B1: outbound webhook

- [ ] Status: IMPLEMENTATION_REMEDIATED_REVIEW_PENDING。初回FAIL（fixed Head `0f1a9297`、P0=0/P1=4/P2=1）を`30199db8`でremediateした。再Review fixed Head `29d749bb`のP1=2を`2684ff8f`でremediateし、独立再Review待ち。
- [x] Objective: signed event、subscription scope、delivery claim/lease、retry/backoff、DLQを実装する。
- Preconditions: A1 Implementation Review PASS（fixed Head `69f857d3`）、scope expansion Decision。production enablement、実顧客credential、
  実provider送信なし。development/testのMOCK/STUB/LOOPBACKのみ。
- Implementation: 第二outboxを作らず`t_api_delivery`を再利用し、atomic enqueue、claim/lease transaction、DB transaction外のtransport、
  provider idempotency key・payload hash・generation・lease token付き結果CASを実装する。固定framing HMAC-SHA256へcredential versionと
  provider idempotency keyを含め、outbound event envelopeとledger値を送信前に一致検証する。credential version/key ID、
  correlation、8回上限の指数backoff+jitter、429/5xx/timeout retry、その他4xx no-retry、DLQ、新generation manual replay auditを含む。
  LOOPBACKはstrict literal IP、allow-list port、peer検証、redirect/proxy/DNSなしとする。manual replayは呼出側operatorRefを受け取らず、
  認証済み内部admin principalと`integration.webhook.replay` permissionをservice boundaryで検証し、current numeric scopeからHMAC opaque IDを
  再計算してenvelope/payload membershipを照合する。resource dimension不在、reparent、削除、scope縮小、不一致はfail-closedとする。
- Test requirements: signature/timestamp/key overlap、各署名field改ざん、envelope/ledger不一致、duplicate、claim競合、timeout、429/5xx、4xx no-retry、
  backoff、attempt 8/DLQ、provider成功直後CAS障害、stale recovery、slow transport、manual replayのpermission/current scope再検証、
  replay後payload/audit purge、atomic rollback、provider/correlation ID、snapshot purge、実loopback server、idempotency header、設定fail-closed。
- Remediation evidence: `30199db8`、`2684ff8f`、V133、H2 retention、MySQL 8 concurrency/retentionを追加。focused unit/H2/MySQL suiteはfailure/error/skipなしでPASS。
- Demo: 外部HTTPがDB transaction外で、replayが監査・replay generation付きで実行される。独立再Review受領まではB1 PASSと扱わず、
  remote/local Head一致を再確認してR-NF05へ渡す。

## Task B2: inbound webhook / DLQ / admin UI

- [ ] Objective: provider event unique/hash、processing state、duplicate/conflict、DLQ、内部admin replay UIを実装する。
- Preconditions: B1 Implementation Review PASS、scope expansion Decision。APPROVED_SEQUENCED、production受信enablementなし。
- Test requirements: signature/timestamp/replay/duplicate、raw hash conflict、transaction rollback、replay safety、
  audit、PII/secret masking、raw bytes非永続化、期限purge、backup/restore後purge。
- Demo: 同一provider eventは副作用一度、hash違いはconflict/DLQとなる。

## Task M: penetration / recovery / performance

- [ ] Objective: security review、負荷、障害訓練、key rotation、scan、runbookを完了し、Headを固定する。
- Preconditions: F1-B2完了、各wave Review PASS、development/test mock/loopback config、observability、rollback plan。
- Test requirements: penetration、rate/IP boundary、DB/worker/provider停止、restore、stale lease、rotation/revoke、
  secret/PII scan、metrics cardinality、payload retention/purge、負荷SLA、alert。
- Demo: evidence index、runbook、review PLAN/IMPLEMENTATION PASS、remote/local fixed Headを独立Reviewへ渡す。
