# NF-05 Review Ledger（Discovery時点）

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
| NF05-DISC-002 | P1 | NotificationOutboxDispatcherがclaim→外部notify→resultをREQUIRES_NEW transaction内で実行 | NotificationOutboxDispatcher.java:44-70 | F1/F2前に境界再設計 |
| NF05-DISC-003 | P1 | provider token versionと暗号key versionが分離された公開credential envelopeなし | IntegrationConnection、FreeeIntegrationServiceImpl、MFA crypto services | 専用credential設計 |
| NF05-DISC-004 | P1 | ApiAuditFilterは公開API境界を監査しない | ApiAuditFilter.java:103-117 | dedicated edge audit |
| NF05-DISC-005 | P1 | 公開client quotaのmulti-node rate limiterなし。既存portal limiterはJVMローカル | PortalRateLimiterImpl.java:15-45 | atomic shared boundary |
| NF05-DISC-006 | P1 | InvoiceDetailDtoがInvoice entityを継承する既存前例 | dto/.../InvoiceDetailDto | public DTOでは禁止 |
| NF05-DISC-007 | P1 | 既存/api/webhooks/**はpermitAllかつCSRF ignore | SecurityConfig.java:130-145, 312-315 | 公開inboundと分離 |
| NF05-DISC-008 | P2 | correlation IDのglobal edge filter/MDC propagationを確認できない | ad hoc provider/service経路のみ | F2で横断実装 |

## Evidence status

- T0: read-only inventory、文書ドラフト、dedicated worktree、通常checkout非変更を確認。Discovery commit seriesはb085c47f、909bbe26、cb468f19。
- F1、F2、A1、A2、B1、B2、M: 実装・テスト・運用証跡なし。未着手。
- N/A扱いのテストはない。必須テストは各Taskのpreconditionとして保持する。
- 本ledger作成時点で外部送信、migration、production Java、UI変更は行っていない。

## Required decisions before implementation

1. approved resources/commands、Owner、DG-05 DecisionId。
2. Base branchとBase commit、remote push許可範囲。
3. threat model、API user population、認証方式、secret storage/key provider、rotation/revoke/expiry。
4. trusted proxy/IP、rate/quota、SLA、version retirement、usage/billing。
5. public field、data scope predicate、command permission、count/export/non-enumeration。
6. webhook canonical signature、timestamp tolerance、retry max/backoff、DLQ retention、manual replay authority。
