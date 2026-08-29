# NF-05 Public API Tasks（Owner承認済み・Plan Review待ち）

## 実行停止規則

Owner承認済みscopeはF1 persistence基盤に限定する。独立Plan ReviewのPLAN PASS前はproduction code、migration、
test source、API、UI、外部送信を開始しない。PLAN PASS後もpublic endpoint、A1/A2/B1/B2、production enablement、
command、exportはこの承認の対象外である。approval-decision、Review remediation、Plan Reviewのdocs-only
commit/pushは指定remote branchへ実施できる。force push、main変更、PR、merge、auto-mergeは行わない。

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

## Task F1: client / credential / scope / idempotency DDL

- [ ] Objective: client、credential version、scope、idempotency、usage bucket、nonce replay ledger、
  webhook/inbound、retention hold/checkpointの保存契約を実装する。
- Preconditions: Task 0/0R/0R-D/0R-P/0R-P2/0R-P3完了、approval-decision.md、指定Base再確認、独立Plan Review PLAN PASS。
- Implementation: usage bucketのDB natural keyはclient×scope×tenant×route templateに限定し、minute/day counterと
  burst token bucket（capacity 20、初期20、3秒ごとに1 token refill、clock rollback時は後戻りなし）を同じrowへ保存し、
  minute/day/burstの全条件を一つのlock/predicate transactionでconsumeする。t_api_nonce_replayはclient+nonce hash unique、TTL、bounded purgeを持つ。既存
  t_notification_outboxとAccounting IntegrationJobは変更・二重書込みせず、t_api_deliveryをNF-05専用ledgerとして
  分離する。各retention対象へclass/expiryを付け、t_api_retention_holdとt_api_purge_checkpointをlock/CAS規則で扱う。
  state enumはidempotency=IN_PROGRESS/SUCCEEDED/FAILED/CONFLICT、delivery=PENDING/CLAIMED/RETRYABLE/SUCCEEDED/FAILED/DLQ、
  inbound=RECEIVED/PROCESSING/PROCESSED/DUPLICATE/CONFLICT/DLQをcanonicalとし、別名・terminal逆遷移を実装しない。
- Test requirements: fresh/legacy/partial/backfill/repair、暗号key version、revoke/expiry/overlap、unique/CAS、
  H2とMySQL、rollback/backup/restore、rate key exact boundary、multi-node increment、burst 20 capacity、3秒refillの
  直前/直後、minute/day境界、clock rollback、Retry-After、片方のquota更新失敗、nonce atomic unique/TTL/purge、
  delivery no-double-write、purge期限境界、legal hold競合、active lease、部分失敗、restore epoch後全件再評価、
  idempotency/delivery/inboundのcanonical enum全値・遷移・terminal retention mapping・alias/逆遷移拒否。
- Demo: secret原文非表示、同key別payload拒否、rate key/IP分離、nonce replay拒否、t_api_delivery分離、
  burst/refillと三つのquota境界、migration証跡、DB transaction内外の境界、canonical state遷移、hold/purge/restoreの
  状態遷移を示す。

## Task F2: dedicated security chain

- [ ] Objective: /external-api/v1/**専用principal、client scope、data scope、command permission、audit、
  correlation、rate/IP境界を実装する。
- Preconditions: F1完了と別途F2/public endpoint scope承認。現在は未着手・scope外。
- Test requirements: client A/B、scope差、data差、command差、rotation overlap/revoke/expiry、spoof、429、
  Retry-After、filter二重登録、CSRF/anonymous webhook非混入、metrics scrape cardinality。
- Demo: internal/portal chainと公開chainが相互にprincipalを偽装しない。

## Task A1: v1 read APIs / OpenAPI

- [ ] Objective: 承認済みread resourceのlist/detail/count、opaque cursor、stable error、OpenAPIを実装する。
- Preconditions: 初期contractは承認済みだが、public endpoint enablementとA1 implementation scopeの別承認。
- Test requirements: external DTO allow-list、entity serialization negative、scope一致、cursor tamper/expiry、
  count/detail/list非列挙、error body secret/PII/内部情報なし。
- Demo: internal entityを一つもserializeせず、OpenAPIと実レスポンスが一致する。

## Task A2: limited command APIs

- [ ] Objective: 承認済みの最小commandだけをCAS、audit、idempotency付きで実装する。
- Preconditions: 未承認。command/exportはdefault denyのまま。
- Test requirements: same key/same payload、same key/different payload、並行claim、CAS失敗、scope/command拒否、
  external call outside transaction。
- Demo: commandごとにclient scope、data scope、command permissionが独立して評価される。

## Task B1: outbound webhook

- [ ] Objective: signed event、subscription scope、delivery claim/lease、retry/backoff、DLQを実装する。
- Preconditions: persistence contractは承認済みだが、外部送信/B1 implementation scopeは未承認。
- Test requirements: signature/timestamp/key overlap、duplicate、claim競合、timeout、429/5xx、4xx no-retry、
  backoff、DLQ、manual replay、provider/correlation ID、snapshot purge。
- Demo: 外部HTTPがDB transaction外で、replayが監査・replay generation付きで実行される。

## Task B2: inbound webhook / DLQ / admin UI

- [ ] Objective: provider event unique/hash、processing state、duplicate/conflict、DLQ、内部admin replay UIを実装する。
- Preconditions: persistence contractは承認済みだが、外部受信/UI/B2 implementation scopeは未承認。
- Test requirements: signature/timestamp/replay/duplicate、raw hash conflict、transaction rollback、replay safety、
  audit、PII/secret masking、raw bytes非永続化、期限purge、backup/restore後purge。
- Demo: 同一provider eventは副作用一度、hash違いはconflict/DLQとなる。

## Task M: penetration / recovery / performance

- [ ] Objective: security review、負荷、障害訓練、key rotation、scan、runbookを完了し、Headを固定する。
- Preconditions: F1-B2完了、production-like config、observability、rollback plan。
- Test requirements: penetration、rate/IP boundary、DB/worker/provider停止、restore、stale lease、rotation/revoke、
  secret/PII scan、metrics cardinality、payload retention/purge、負荷SLA、alert。
- Demo: evidence index、runbook、review PLAN/IMPLEMENTATION PASS、remote/local fixed Headを独立Reviewへ渡す。
