# NF-05 Public API 実装計画（Owner承認済み・R-NF05 Plan PASS・F1 follow-up remediation中）

## 現在のゲート

NF-05はAPPROVEDであり、DG-05-F1-APPROVAL-20260830-01（2026-08-30）、OwnerRef=PROJECT_OWNER、
Base=origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd、scope=GET-only 11 pathsとF1 persistence基盤を
approval-decision.mdへ固定した。R-NF05の独立Plan Reviewは固定Head 1db3b2fc2657831b7c6c1e59217301302b7caa80で
PLAN PASS（P0=0、P1=0、P2=2）となった。Owner Gateは再オープンしていない。F1は承認済みpersistence基盤に
限定し、public endpoint、外部送信、A1/A2/B1/B2、production enablement、command/exportを開始しない。

## 推奨順序

| 順序 | Task | 成果物 | 開始条件 |
|---|---|---|---|
| 0 | threat / contract / field inventory | approval ledger、filter/secret/outbox/correlation/rate/DTO inventory | 完了。production変更なし |
| 0R | Review remediation | atomic outbox、candidate OpenAPI、metrics cardinality、payload retention、review trace | docs-only範囲で完了。実装PASSではない |
| 0R-D | delta Review remediation | count/asOf/status-code/correlation header契約 | docs-only範囲で完了。実装PASSではない |
| 0R-P | R-NF05 Plan finding remediation | rate key、nonce ledger、delivery分離、retention/hold/restore contract | docs-only修正後、R-NF05再Review |
| 0R-P2 | R-NF05 residual Plan remediation | burst algorithm、canonical state/terminal retention mapping | docs-only修正後、R-NF05再Review |
| F1 | client / credential / scope / idempotency DDL | Flyway、H2、migration evidence、rollback、purge | R-NF05 PLAN PASS、F1 scope、schema方針確認 |
| F2 | dedicated security chain | client principal、scope/data scope/command permission、audit、rate/IP | F1完了。公開endpointは別承認まで禁止 |
| A1 | v1 read APIs / OpenAPI | external DTO、cursor/count/error contract、contract tests | 別のimplementation scope承認 |
| A2 | limited command APIs | permission、idempotency、CAS、audit | 未承認。default deny |
| B1 | outbound webhook | subscription、signed event、claim/lease/retry/DLQ | 未承認。外部送信禁止 |
| B2 | inbound webhook / DLQ / admin UI | event uniqueness、replay、safe admin operations | 未承認。外部受信/UI禁止 |
| M | penetration / recovery / performance | review evidence、load、failure drill、runbook、fixed head | 全機能完了、security review PASS、追加承認 |

## R-NF05 P1 remediationの完了条件

1. rate/quotaの保存キーが client × scope × tenant × route template に固定され、IPやraw pathを含まず、
   burstをcapacity 20、初期20、3秒ごとに1 token refillの固定token bucketとしてminute/day条件と同一transactionで
   原子的に扱うこと。clock rollback、refill境界、Retry-Afterを含めて一意なpredicateを持つこと。
2. t_api_nonce_replayのclient + nonce hash atomic unique、credential rotation跨ぎの再利用拒否、
   max(accepted_at, signed_timestamp) + 5分のTTL、bounded purgeを保存契約とすること。
3. 第二の汎用outboxを作らず、既存notification outbox/Accounting IntegrationJobを変更・二重書込みせず、
   t_api_deliveryをNF-05専用delivery ledgerとして分離し、event/subscription/generation uniqueと
   atomic insert、claim/HTTP/CAS境界を固定すること。
4. idempotency/delivery/inboundのcanonical enumと全遷移を一つに固定し、全terminal stateをretention class/起算点へ
   漏れなく接続すること。retention_class、retention_expires_at、t_api_retention_hold、t_api_purge_checkpointの
    保存モデルと、hold/purgeのlock/CAS競合、active lease、部分失敗、restore epoch後の全件再評価を固定すること。

## F1 Implementation Review remediation（再Review待ち）

独立Implementation Reviewは初回固定Head `b420911b63177763544edd1e02d663bf528d9dc1` に対して
FAIL（P0=0、P1=7、P2=2）だった。F1 approved scope内で、typed snapshot/service boundary、conflictの
canonical persistence、delivery CAS、purge keyset/hold/lease競合、credential overlap、route templateを
修正し、implementation commit `a184c1f4`へpushした。その後、delivery CASへgeneration predicateを追加する
残存指摘を`d476614e`で修正した。H2 F1対象31 testsとMySQL multi-connection concurrency
3 testsはPASSした。follow-up reviewは固定Head `dff90b3961b647035436abd378a352b1fa000dd1`に対して
FAIL（P0=0、P1=4、P2=0）となったため、`5a2a0231`でsnapshotのfield-specific構造検証、NULL leaseの
fail-closed、checkpoint→target→holdの共通lock順序、実service/mapperを使うMySQL 5競合証跡を追加した。
H2 F1対象31 testsとMySQL 5 testsはPASSしたが、独立Implementation Reviewの再判定を受けるまでF1をPASS扱いしない。

再Reviewは固定Head `f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`でFAIL（P0=0、P1=1、P2=0）となり、lease fail-closed、
lock順序、MySQL競合証跡はクローズされた。残存したnested scalar bypassに対し、`96d6801c`でpublic ID、date/date-time、
status/resultCode、signature/processing status、error codeをfield固有pattern/enumで検証し、nested object深度と配列項目も
boundedにした。H2 F1対象31 testsは再実行でPASSし、同Headの再独立Review待ちである。

follow-up remediationの実装境界:

- ExternalDtoSnapshotのpayload/canonicalPayloadはallow-list済み構造化object、changedFieldNames/skillTagCodeはbounded string array、
  public ID・date/date-time・status/resultCode・signature/processing status・error codeはfield固有pattern/enumとし、raw body/PIIを
  許可scalarへ埋め込めないようにする。
- delivery purgeはlease tokenとlease expiryが両方NULL、または両方non-NULLかつexpiry<=nowの場合だけ候補/削除可能とする。
- retention hold/purgeのrow lock順序はcheckpoint→target→holdへ統一し、checkpoint初期化とquota subject初期化はgap-lockを避けるinsert/upsert-firstとする。
- MySQL 8上で実service/mapperを複数connectionから呼び、usage unique初期化、delivery CAS、hold/purge、malformed lease、inbound duplicateを検証する。

F2、A1、A2、B1、B2、M、public endpoint、外部送信、production enablement、command/exportは引き続き未着手・禁止である。

これらはPlan ReviewをPASS扱いにする自己判定ではない。0R-P2 docs-only commit後、R-NF05が独立に再判定する。

## 完了・引き渡し条件

各Taskは専用branchで検証し、Task単位のcommitを作る。production変更のpushはapproved scopeに含まれるremoteだけに限定し、
force pushは行わない。明示されたReview remediationのdocs-only commit/pushはレビュー証跡固定のために行う。
最終remote Head、commit一覧、plan/spec/tasks、completion matrix、evidence indexを
独立Reviewへ渡す。ReviewのPLAN/IMPLEMENTATION双方がPASSになるまでPRは作成しない。
