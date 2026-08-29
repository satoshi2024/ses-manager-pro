# NF-05 Integration Hub・公開API・Inbound/Outbound Webhook

## 状態

- 中央台帳の状態: APPROVED
- 本specの状態: Owner承認済み、Plan Review待ち、F1実装開始前
- Decision Gate: DG-05-F1-APPROVAL-20260830-01（2026-08-30）
- Approved resources/commands: GET-only 11 paths、inventory allow-list。command/exportなし
- Owner: PROJECT_OWNER（OwnerType=ROLE）
- Base branch: origin/main
- Base commit: b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd
- Approved比較参照: origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd
- 専用worktree: C:\work\ses-manager-pro-integration-hub-public-api
- 専用branch: codex/integration-hub-public-api

この文書は、DG-05 Owner承認後のPlan Review対象specとReview remediationの成果物である。独立Plan ReviewがPASSするまで、
production Java、SQL/migration、画面、既存shared file、production test、外部送信、public endpointを開始しない。
F1のdocs-only計画証跡は許可されたremote branchへpushできるが、force push、main変更、PR、mergeは行わない。

## 承認ゲート

| ゲート | 現在 | production変更開始条件 |
|---|---|---|
| Approved resources/commands | APPROVED | GET-only 11 paths、inventory allow-list、command/exportなし |
| Owner | APPROVED | PROJECT_OWNER、OwnerType=ROLE |
| Base branch/commit | APPROVED | origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd |
| DG-05 | APPROVED | DG-05-F1-APPROVAL-20260830-01 |
| Threat model | APPROVED | 承認済み11脅威をF1〜Mの受入対象として固定 |
| 認証方式 | APPROVED | HMAC-SHA256 signed service account、OAuth fallbackなし |
| 契約SLA | APPROVED | 月間99.9%、p95 500ms、保守7日前、重大障害60分以内、v1廃止予告180日 |
| 公開field inventory | APPROVED | inventory allow-listのみ、internal entity serialize禁止 |

## 確認済みの停止理由

中央の受入後traceabilityとapproval-decision.mdにNF-05のAPPROVED、OwnerRef、DecisionId、Base SHA、
scope、auth、SLA、field inventoryを固定した。ただしapproved scopeはF1 persistence基盤までであり、
独立Plan ReviewのPLAN PASS前はproduction実装を開始しない。

## Task 0R remediation

ReviewのP1/P2指摘に対し、atomic outbox、非公開OpenAPI candidate、metrics cardinality、payload retention、
review traceをspecへ反映した。対応状況はreview-remediation.mdを正本とし、SPEC_ADDRESSED、OWNER_APPROVED、
PLAN PASS、IMPLEMENTATION PASSを混同しない。

## 既知の重要差分

1. 既存の通知outboxは再利用候補だが、NotificationOutboxDispatcher.dispatchOne は
   claimから外部Webhook送信・結果更新までを一つのREQUIRES_NEW transactionに包み、
   transaction中にwebhookNotifier.notifyNowを呼ぶ。NF-05の「外部callをDB transaction内に置かない」
   条件を満たすためにはclaim、外部call、結果CASを分離する必要がある。
2. 内部SecurityFilterChainとPortalSecurityFilterChainは存在するが、公開API用chain、client principal、
   credential version、scope、quotaの実装は存在しない。
3. 相関IDは会計provider等で呼出側が生成・伝播する方式が中心で、全公開requestを横断する
   correlation ID filterは確認できない。
4. PortalRateLimiterImplはプロセス内の1分sliding windowであり、公開clientのmulti-node rate boundary
   としては不十分である。ClientIpResolverもtrusted proxy設定時のX-Forwarded-For先頭値を使うだけなので、
   proxy chain、IPv6、spoof試験をDG-05で確定する必要がある。
5. Freee tokenの暗号文にはtoken_versionとrefresh leaseがあるが、crypto key version付きの
   API credential envelopeを一つの共通基盤として提供していない。token versionをkey versionと
   混同しない。

## Review引渡し方針

承認後に限り、approved plan/spec/tasksとcompletion-matrixを実装Taskごとに更新し、Task単位の
commit/pushを行う。実装対話ではPRを作らず、最後にremote Head、Base、全gate、未検証、rollback、
対応表を独立Reviewへ渡す。独立ReviewのPLAN PASSとIMPLEMENTATION PASSの双方が記録されるまで
PRは作成しない。force push、merge、auto-merge、branch削除は行わない。
