# NF-05 Public API Tasks（未承認ドラフト）

## 実行停止規則

DG-05、threat model、認証方式、契約SLA、public field inventory、approved resources/commands、Owner、
Base branch、Base commitのうち一つでも未承認・未提供なら、productionコード、migration、API、UI、
外部送信、production変更のpushを開始しない。Review remediationなどのdocs-only commit/pushは、
明示されたReview指示の範囲で実施できる。候補設計のレビューとread-only inventoryだけを継続する。

## Task 0: threat / contract / field inventory

- [x] Objective: 既存のsecurity filter、secret、outbox/provider/job/idempotency、correlation、rate/IP、DTOを棚卸しし、
  client scope × data scope × command permissionの候補表を作る。
- Implementation: README.md、plan.md、requirements.md、design.md、inventory.md、completion-matrix.md、review-ledger.md。
- Test requirements: git boundary、通常checkout未変更、dedicated worktree clean、文書の機密情報非掲載を確認。
- Demo: 未承認事項とproduction変更禁止を読み手が確認できる。

## Task 0R: Review remediation（spec / architecture only）

- [x] Objective: ReviewのP1/P2のうち実装AIが解消可能なatomic outbox、candidate OpenAPI、metrics cardinality、
  payload retention、review traceをspecへ反映する。
- Implementation: design.mdのtransaction境界を修正し、openapi-candidate.yaml、review-remediation.mdを追加。
  requirements/tasks/completion-matrix/review-ledgerへ証跡・未承認Owner Gate・未着手F1-Mを反映する。
- Test requirements: git diff --check、OpenAPI YAML parse、必須path/schema/assertion、production source/
  migration/test差分0、通常checkout非変更、local/remote Head一致。
- Demo: atomic outboxの同一DB transaction、claim/HTTP/CAS分離、bounded metrics、candidate retention、
  default-deny command/exportを独立Reviewが確認できる。

## Task F1: client / credential / scope / idempotency DDL

- [ ] Objective: client、credential version、scope、idempotency、usage bucket、webhook/inboundの保存契約を実装する。
- Preconditions: Task 0R完了、DG-05、Owner、Base、auth、key provider、retention、DDL/H2方針の承認。
- Test requirements: fresh/legacy/partial/backfill/repair、暗号key version、revoke/expiry/overlap、unique/CAS、
  H2とMySQL、rollback/backup/restore、purge期限境界、legal hold。
- Demo: secret原文非表示、同key別payload拒否、migration証跡、DB transaction内外の境界を示す。

## Task F2: dedicated security chain

- [ ] Objective: /external-api/v1/**専用principal、client scope、data scope、command permission、audit、
  correlation、rate/IP境界を実装する。
- Preconditions: F1完了、trusted proxy/IP、rate/quota、CSRF/署名方式、metrics label setの承認。
- Test requirements: client A/B、scope差、data差、command差、rotation overlap/revoke/expiry、spoof、429、
  Retry-After、filter二重登録、CSRF/anonymous webhook非混入、metrics scrape cardinality。
- Demo: internal/portal chainと公開chainが相互にprincipalを偽装しない。

## Task A1: v1 read APIs / OpenAPI

- [ ] Objective: 承認済みread resourceのlist/detail/count、opaque cursor、stable error、OpenAPIを実装する。
- Preconditions: public field inventory、SLA、non-enumeration、count/export方針の承認。
- Test requirements: external DTO allow-list、entity serialization negative、scope一致、cursor tamper/expiry、
  count/detail/list非列挙、error body secret/PII/内部情報なし。
- Demo: internal entityを一つもserializeせず、OpenAPIと実レスポンスが一致する。

## Task A2: limited command APIs

- [ ] Objective: 承認済みの最小commandだけをCAS、audit、idempotency付きで実装する。
- Preconditions: command permission matrix、state machine、side-effectとSLAの承認。
- Test requirements: same key/same payload、same key/different payload、並行claim、CAS失敗、scope/command拒否、
  external call outside transaction。
- Demo: commandごとにclient scope、data scope、command permissionが独立して評価される。

## Task B1: outbound webhook

- [ ] Objective: signed event、subscription scope、delivery claim/lease、retry/backoff、DLQを実装する。
- Preconditions: event field inventory、signature canonicalization、timestamp、retry max、DLQ retention、
  payload retention、legal holdの承認。
- Test requirements: signature/timestamp/key overlap、duplicate、claim競合、timeout、429/5xx、4xx no-retry、
  backoff、DLQ、manual replay、provider/correlation ID、snapshot purge。
- Demo: 外部HTTPがDB transaction外で、replayが監査・replay generation付きで実行される。

## Task B2: inbound webhook / DLQ / admin UI

- [ ] Objective: provider event unique/hash、processing state、duplicate/conflict、DLQ、内部admin replay UIを実装する。
- Preconditions: provider contract、admin permission、retention、legal hold、manual replay authorityの承認。
- Test requirements: signature/timestamp/replay/duplicate、raw hash conflict、transaction rollback、replay safety、
  audit、PII/secret masking、raw bytes非永続化、期限purge、backup/restore後purge。
- Demo: 同一provider eventは副作用一度、hash違いはconflict/DLQとなる。

## Task M: penetration / recovery / performance

- [ ] Objective: security review、負荷、障害訓練、key rotation、scan、runbookを完了し、Headを固定する。
- Preconditions: F1-B2完了、production-like config、observability、rollback plan。
- Test requirements: penetration、rate/IP boundary、DB/worker/provider停止、restore、stale lease、rotation/revoke、
  secret/PII scan、metrics cardinality、payload retention/purge、負荷SLA、alert。
- Demo: evidence index、runbook、review PLAN/IMPLEMENTATION PASS、remote/local fixed Headを独立Reviewへ渡す。
