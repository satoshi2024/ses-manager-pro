# NF-05 Review Ledger（Owner approval + R-NF05 Plan remediation中）

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
| NF05-PLAN-001 | P1 | rate/quota保存キーが承認値と一致しない | design.md 2.1、requirements IH-R1-8、inventory 7 | SPEC_ADDRESSED。R-NF05再Review待ち |
| NF05-PLAN-002 | P1 | nonce replay ledgerのatomic unique、TTL、purge契約が不足 | design.md 2.1、requirements IH-R1-9/IH-R3-3 | SPEC_ADDRESSED。R-NF05再Review待ち |
| NF05-PLAN-003 | P1 | 第二outbox禁止とt_api_deliveryのreuse/分離方針が未確定 | design.md 2/2.1、requirements IH-R3-5/6、inventory 5 | SPEC_ADDRESSED。R-NF05再Review待ち |
| NF05-PLAN-004 | P1 | retention/legal holdの保存モデル、競合、restore後purgeが不足 | design.md 2.1/8、requirements IH-R6-3、tasks F1 | SPEC_ADDRESSED。R-NF05再Review待ち |
| NF05-PLAN-005 | P1 | burst 20のcapacity、refill、atomic predicate、clock rollbackが未固定 | design.md 2.1、requirements IH-R1-8、tasks F1 | SPEC_ADDRESSED。R-NF05再Review待ち |
| NF05-PLAN-006 | P1 | idempotency/delivery/inboundのcanonical enumとterminal retention mappingが不一致 | design.md 2.1/5.3、requirements IH-R6-3、tasks F1 | SPEC_ADDRESSED。R-NF05再Review待ち |

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
- F1、F2、A1、A2、B1、B2、M: 実装・テスト・運用証跡なし。未着手。
- N/A扱いのテストはない。必須テストは各Taskのpreconditionとして保持する。
- 本ledger作成時点で外部送信、migration、production Java、UI変更は行っていない。docs-only remote pushのみ実施した。

## Required gates before F1

1. R-NF05へspec remediationを再提出し、独立Plan ReviewのPLAN PASSを受領する。
2. F1開始時にorigin/mainをfetchし、migration最大値、H2 schema/init経路、backup/rollback前提を再確認する。
3. 実装はapproved implementation scopeへ限定し、public endpoint、外部送信、A1/A2/B1/B2、production enablementを行わない。
