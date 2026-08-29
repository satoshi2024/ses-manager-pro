# NF-05 Review Ledger（Discovery + Task 0R）

## Approval gate

| 項目 | 現在値 | 判定 |
|---|---|---|
| NF-05 status | CANDIDATE | NOT APPROVED |
| DG-05 | DecisionId / approved recordなし | BLOCKED |
| approved resources/commands | <APPROVED_SCOPE>のまま | BLOCKED |
| Owner | <OWNER>のまま | BLOCKED |
| Base branch | <BASE_BRANCH>未提供。比較はorigin/main | BLOCKED |
| Base commit | <BASE_COMMIT>未提供。comparison baseは未承認 | BLOCKED |
| threat model | 承認記録なし | BLOCKED |
| auth method | OAuth2 client credentials / signed service account未決定 | BLOCKED |
| contract SLA | 未決定 | BLOCKED |
| public field inventory | 候補のみ。承認記録なし | BLOCKED |

## Findings

| ID | Severity | Finding | Evidence | Disposition |
|---|---|---|---|---|
| NF05-DISC-BLOCK-001 | BLOCKER | approval ledgerがCANDIDATEで、DG-05と開始条件が未承認 | .kiro/roadmap/2026-08-27-post-acceptance-traceability.md | production変更停止 |
| NF05-DISC-002 | P1 | NotificationOutboxDispatcherがclaim→外部notify→resultをREQUIRES_NEW transaction内で実行 | NotificationOutboxDispatcher.java:44-70 | SPEC_ADDRESSED。実装は未着手 |
| NF05-DISC-003 | P1 | provider token versionと暗号key versionが分離された公開credential envelopeなし | IntegrationConnection、FreeeIntegrationServiceImpl、MFA crypto services | 専用credential設計 |
| NF05-DISC-004 | P1 | ApiAuditFilterは公開API境界を監査しない | ApiAuditFilter.java:103-117 | dedicated edge audit |
| NF05-DISC-005 | P1 | 公開client quotaのmulti-node rate limiterなし。既存portal limiterはJVMローカル | PortalRateLimiterImpl.java:15-45 | atomic shared boundary |
| NF05-DISC-006 | P1 | InvoiceDetailDtoがInvoice entityを継承する既存前例 | dto/.../InvoiceDetailDto | public DTOでは禁止 |
| NF05-DISC-007 | P1 | 既存/api/webhooks/**はpermitAllかつCSRF ignore | SecurityConfig.java:130-145, 312-315 | 公開inboundと分離 |
| NF05-DISC-008 | P2 | correlation IDのglobal edge filter/MDC propagationを確認できない | ad hoc provider/service経路のみ | F2で横断実装 |
| NF05-DISC-009 | P1 | OpenAPI、HTTP status/error、version互換規則がなく外部契約をreviewできない | openapi-candidate.yaml追加前 | SPEC_ADDRESSED。candidateのみ、A1未着手 |
| NF05-DISC-010 | P2 | metrics cardinalityと禁止labelの具体設計がない | design.md / requirements.md | SPEC_ADDRESSED。F2/M未着手 |
| NF05-DISC-011 | P2 | payload retention、legal hold、purgeの契約がない | design.md / requirements.md | SPEC_ADDRESSED。F1/B1/B2/M未着手 |
| NF05-DISC-012 | P2 | Review Headをcompletion traceへ固定する方式が曖昧 | completion-matrix.md | SPEC_ADDRESSED。最終Headは外部handoffで固定 |

## Evidence status

- T0: read-only inventory、文書ドラフト、dedicated worktree、通常checkout非変更を確認。Discovery Review Headは6e0f5067。
- Task 0R: atomic outbox、candidate OpenAPI、metrics cardinality、payload retention、review traceをdocs-onlyでSPEC_ADDRESSED。
  remediation commitは48037c923224f684968dbaf3410cdb37307ed100。
- Task 0R-D: count/asOf/status-code/correlation headerのdelta指摘をdocs-onlyでSPEC_ADDRESSED。実装とOwner Gateは未完了。
  delta remediation commitは11ee82c15a5cdf8f961b2a2d0518a52d81f4de71。
- F1、F2、A1、A2、B1、B2、M: 実装・テスト・運用証跡なし。未着手。
- N/A扱いのテストはない。必須テストは各Taskのpreconditionとして保持する。
- 本ledger作成時点で外部送信、migration、production Java、UI変更は行っていない。docs-only remote pushのみ実施した。

## Required decisions before implementation

1. approved resources/commands、Owner、DG-05 DecisionId。
2. Base branchとBase commit、remote push許可範囲。
3. threat model、API user population、認証方式、secret storage/key provider、rotation/revoke/expiry。
4. trusted proxy/IP、rate/quota、SLA、version retirement、usage/billing。
5. public field、data scope predicate、command permission、count/export/non-enumeration。
6. webhook canonical signature、timestamp tolerance、retry max/backoff、DLQ retention、manual replay authority。
