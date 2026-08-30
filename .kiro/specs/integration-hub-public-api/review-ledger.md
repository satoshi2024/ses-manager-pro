# NF-05 Review Ledger（Owner approval + R-NF05 Plan PASS）

## Approval gate

| 項目 | 現在値 | 判定 |
|---|---|---|
| NF-05 status | APPROVED | OWNER_APPROVED |
| DG-05 | DG-05-F1-APPROVAL-20260830-01（2026-08-30） | APPROVED |
| approved resources/commands | GET-only 11 paths、inventory allow-list、command/exportなし | APPROVED |
| Owner | PROJECT_OWNER（OwnerType=ROLE） | APPROVED |
| Base branch | origin/main | APPROVED |
| Base commit | b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd | APPROVED |
| threat model | 承認済み11項目 | APPROVED |
| auth method | HMAC-SHA256 signed service account、OAuth fallbackなし | APPROVED |
| contract SLA | 月間99.9%、p95 500ms、保守7日前、重大障害60分以内、v1廃止予告180日 | APPROVED |
| public field inventory | inventory allow-listのみ、internal entity serialize禁止 | APPROVED |

## Findings

| ID | Severity | Finding | Evidence | Disposition |
|---|---|---|---|---|
| NF05-DISC-BLOCK-001 | BLOCKER | approval ledgerがCANDIDATEで、DG-05と開始条件が未承認 | approval-decision.md、中央traceability | OWNER_GATE_RESOLVED |
| NF05-DISC-002 | P1 | NotificationOutboxDispatcherがclaim→外部notify→resultをREQUIRES_NEW transaction内で実行 | NotificationOutboxDispatcher.java:44-70 | SPEC_ADDRESSED。実装は未着手 |
| NF05-DISC-003 | P1 | provider token versionと暗号key versionが分離された公開credential envelopeなし | IntegrationConnection、FreeeIntegrationServiceImpl、MFA crypto services | F1 scope APPROVED。実装未着手 |
| NF05-DISC-004 | P1 | ApiAuditFilterは公開API境界を監査しない | ApiAuditFilter.java:103-117 | F2へ延期。公開endpointは未実装 |
| NF05-DISC-005 | P1 | 公開client quotaのmulti-node rate limiterなし。既存portal limiterはJVMローカル | PortalRateLimiterImpl.java:15-45 | F1 usage bucket scope APPROVED。実装未着手 |
| NF05-DISC-006 | P1 | InvoiceDetailDtoがInvoice entityを継承する既存前例 | dto/.../InvoiceDetailDto | public DTOでは禁止 |
| NF05-DISC-007 | P1 | 既存/api/webhooks/**はpermitAllかつCSRF ignore | SecurityConfig.java:130-145, 312-315 | 公開inboundと分離 |
| NF05-DISC-008 | P2 | correlation IDのglobal edge filter/MDC propagationを確認できない | ad hoc provider/service経路のみ | F2で横断実装 |
| NF05-DISC-009 | P1 | OpenAPI、HTTP status/error、version互換規則がなく外部契約をreviewできない | openapi-candidate.yaml | SPEC_APPROVED_FOR_PLAN。A1未着手 |
| NF05-DISC-010 | P2 | metrics cardinalityと禁止labelの具体設計がない | design.md / requirements.md | SPEC_ADDRESSED。F2/M未着手 |
| NF05-DISC-011 | P2 | payload retention、legal hold、purgeの契約がない | design.md / requirements.md | SPEC_ADDRESSED。F1/B1/B2/M未着手 |
| NF05-DISC-012 | P2 | Review Headをcompletion traceへ固定する方式が曖昧 | completion-matrix.md | SPEC_ADDRESSED。最終Headは外部handoffで固定 |
| NF05-PLAN-001 | P1 | rate/quota保存キーが承認値と一致しない | design.md 2.1、requirements IH-R1-8、inventory 7 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-002 | P1 | nonce replay ledgerのatomic unique、TTL、purge契約が不足 | design.md 2.1、requirements IH-R1-9/IH-R3-3 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-003 | P1 | 第二outbox禁止とt_api_deliveryのreuse/分離方針が未確定 | design.md 2/2.1、requirements IH-R3-5/6、inventory 5 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-004 | P1 | retention/legal holdの保存モデル、競合、restore後purgeが不足 | design.md 2.1/8、requirements IH-R6-3、tasks F1 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-005 | P1 | burst 20のcapacity、refill、atomic predicate、clock rollbackが未固定 | design.md 2.1、requirements IH-R1-8、tasks F1 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |
| NF05-PLAN-006 | P1 | idempotency/delivery/inboundのcanonical enumとterminal retention mappingが不一致 | design.md 2.1/5.3、requirements IH-R6-3、tasks F1 | SPEC_ADDRESSED。R-NF05 PLAN PASSでクローズ |

## F1 Implementation Review findings

初回の独立Implementation Reviewは固定Head `b420911b63177763544edd1e02d663bf528d9dc1` に対し、
FAIL（P0=0、P1=7、P2=2）だった。以下はapproved F1 scope内のremediationであり、再Review前にPASSへ昇格させない。

| ID | Severity | Finding | 対応 | Status |
|---|---|---|---|---|
| NF05-IMPL-001 | P1 | secret/PII/raw body非永続化とgeneric CRUD迂回がservice境界で保証されない | typed ExternalDtoSnapshotの用途別構造allow-list、safe response/inbound/outbound検証、F1 serviceからIService/ServiceImpl継承を除去 | IMPLEMENTED_PENDING_REVIEW |
| NF05-IMPL-002 | P1 | inbound DuplicateKey hash conflictがCONFLICTへ永続化されずRECEIVEDに残る | provider event rowをFOR UPDATEで再読し、RECEIVED/PROCESSINGをversion CASでCONFLICTへ遷移。unit test追加 | IMPLEMENTED_PENDING_REVIEW |
| NF05-IMPL-003 | P1 | active holdを含むpurge batchでcheckpointがstarveする | active holdを候補から除外し、hold acquire/release時に対象class cursorをreset。keyset末尾resetを仕様・実装へ追加 | IMPLEMENTED_PENDING_REVIEW |
| NF05-IMPL-004 | P1 | purge deleteがactive leaseとrow versionを直前に再確認しない |対象row lock後にversion、retention、terminal、lease expiryをdelete predicateへ含め、H2/MySQL test追加 | IMPLEMENTED_PENDING_REVIEW |
| NF05-IMPL-005 | P1 | idempotency conflictが永続化されずCONFLICT状態へ到達しない | mismatch時に固定409 code、terminal/90日retentionをCAS保存してから例外を返す | IMPLEMENTED_PENDING_REVIEW |
| NF05-IMPL-006 | P1 | delivery result CASがgeneration/provider idempotency keyを要求しない | row version、lease token、payload hash、provider idempotency key、generation由来のCAS契約へ修正し、`d476614e`でSQL predicateにもdelivery_generationを追加。H2/MySQL test更新 | IMPLEMENTED_PENDING_REVIEW |
| NF05-IMPL-007 | P1 | F1のmulti-node/境界/遷移/hold-purge証跡が不足 | MySQL multi-connection usage、delivery CAS、active lease purge 3件とH2 inbound/purge境界を追加。残る網羅的境界・M証跡は未完了 | IMPLEMENTED_PENDING_REVIEW |
| NF05-IMPL-008 | P2 | credential OVERLAPのNULL期限がfail-open | overlap_until IS NOT NULL AND overlap_until > server_nowへ修正 | IMPLEMENTED_PENDING_REVIEW |
| NF05-IMPL-009 | P2 | raw pathをroute templateとしてusage bucketへ保存可能 | OpenAPI candidateの11 fixed route template exact setへ制限し、raw resource path test追加 | IMPLEMENTED_PENDING_REVIEW |

## F1 implementation trace

| Task | Evidence | Status | Review boundary |
|---|---|---|---|
| F1 persistence foundation | `a7654b44`、V129 MySQL migration、H2 schema/init、entity/mapper/service/crypto、purge/rollback証跡 | IMPLEMENTED_PENDING_REVIEW | `a184c1f4`でIMPL findingsをremediate。F1 H2 31 tests、MySQL concurrency 3 testsはPASS。独立Implementation Review再Review待ち。F2/A1/A2/B1/B2/M、public endpoint、外部送信、production enablementは未着手 |

## Evidence status

- T0: read-only inventory、dedicated worktree、通常checkout非変更を確認。Discovery Review Headは6e0f5067。
- Task 0R: atomic outbox、candidate OpenAPI、metrics cardinality、payload retention、review traceをdocs-onlyでSPEC_ADDRESSED。
  remediation commitは48037c923224f684968dbaf3410cdb37307ed100。
- Task 0R-D: count/asOf/status-code/correlation headerのdelta指摘をdocs-onlyでSPEC_ADDRESSED。実装とPlan Gateは未完了。
  delta remediation commitは11ee82c15a5cdf8f961b2a2d0518a52d81f4de71。
- Owner approval: approval-decision.mdと中央traceabilityへDecisionId、OwnerRef、Base、scope、auth、SLA、
  field inventory、threat modelを正本化した。
- R-NF05 Plan Review: fixed Head 257ffe60773d5c612c8b6ffcfeaf65ef30c2c5ecでPLAN FAIL（P0=0、P1=4）。
  Owner Gateは再オープンせず、NF05-PLAN-001〜004のspec remediation後に同Reviewへ再提出する。
- R-NF05 remediation: b0151e7d8acc54da124c4464db1df263e4b3f716でNF05-PLAN-001〜004をSPEC_ADDRESSEDへ更新した。
  再ReviewのPLAN PASSまではF1を開始しない。
- R-NF05 delta Plan Review: fixed Head 678eac3f09b7ed54419655fcf326e0b15c6d7d62でPLAN FAIL（P0=0、P1=2）。
  NF05-PLAN-005/006をspec remediation後、同Reviewへ再提出する。Owner GateとNF05-PLAN-001〜004は再オープンしない。
- R-NF05 residual remediation: a3b63d70f53bc799d1abcb6e26e34ad163aa9843でNF05-PLAN-005/006をSPEC_ADDRESSEDへ更新した。
  再ReviewのPLAN PASSまではF1を開始しない。
- R-NF05 state mapping cleanup: fdea4bb18db3d3ae6542dc0c534425783dd28a24で旧aliasを除去し、canonical enum/terminal
  retention mappingをdesign/tasksへ同期した。
- R-NF05 Plan Review: 1db3b2fc2657831b7c6c1e59217301302b7caa80でPLAN PASS（P0=0、P1=0、P2=2）。P2は非blocking。
- F1: Approved scopeのpersistence基盤を`a7654b44`で実装完了。F1 targeted suiteは23 tests PASS、MySQL Flyway smokeもPASS。
- F1 Implementation Review: `b420911b63177763544edd1e02d663bf528d9dc1`でFAIL（P0=0、P1=7、P2=2）。
  `a184c1f4`および`d476614e`でapproved F1 scope内のP1/P2 remediationを実装し、H2 F1対象31 testsとMySQL multi-connection
  concurrency 3 testsをPASSした。独立Implementation Review再Review待ち。F2、A1、A2、B1、B2、M: 未着手。
- N/A扱いのテストはない。必須テストは各Taskのpreconditionとして保持する。
- Plan Review完了時点では外部送信、migration、production Java、UI変更は行っていなかった。以降は承認済みF1
  persistence基盤の実装に限定している。

## F1 gate evidence

1. F1開始時にorigin/mainをfetchし、migration最大値、H2 schema/init経路、backup/rollback前提を再確認する（完了）。
2. 実装はapproved implementation scopeへ限定し、public endpoint、外部送信、A1/A2/B1/B2、production enablementを行わない。
3. 実装commit `a7654b44`とremediation commit `a184c1f4`を許可されたbranchへpush済み。F1対象suiteはfailure/error/skipなし、全fast
   suiteのF1対象外failure/errorは全体PASSへ昇格させない。
