# NF-05 Integration Hub・公開API・Inbound/Outbound Webhook

## 状態

- 中央台帳の状態: CANDIDATE
- 本specの状態: DISCOVERY 完了、実装開始不可
- Decision Gate: DG-05 未承認
- Approved resources/commands: 未提供（入力値は <APPROVED_SCOPE> のまま）
- Owner: 未提供（入力値は <OWNER> のまま）
- Base branch: 未提供（入力値は <BASE_BRANCH> のまま）
- Base commit: 未提供（入力値は <BASE_COMMIT> のまま）
- Discovery比較参照: origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd
- 専用worktree: C:\work\ses-manager-pro-integration-hub-public-api
- 専用branch: codex/integration-hub-public-api

この文書は、候補段階で許可されるread-only discovery、Review remediation、spec作成の成果物である。DG-05、脅威モデル、
認証方式、契約SLA、公開field inventory、Owner、実値のBaseが承認されるまで、production Java、
SQL/migration、画面、既存shared file、production test、外部送信、production変更のpushを開始しない。
明示されたReview remediationのdocs-only pushはこの停止規則の対象外である。

## 承認ゲート

| ゲート | 現在 | production変更開始条件 |
|---|---|---|
| Approved resources/commands | 未提供 | 対象resource、command、許可commandを実値で承認 |
| Owner | 未提供 | repository外の責任主体をOwnerRefで一意に確定 |
| Base branch/commit | 未提供 | fetch後の実branchとSHAをspec/ledgerへ固定 |
| DG-05 | 未承認 | 利用者、SLA、OAuth provider、secret保管/rotation、IP、version、rate、課金、webhook retry/DLQを承認 |
| Threat model | 未承認 | client impersonation、IDOR/scope、replay、secret漏洩、SSRF、DLQ復旧等を受入条件付きで承認 |
| 認証方式 | 未承認 | OAuth2 client credentialsまたは署名service account等を選択し、fallbackを定義 |
| 契約SLA | 未承認 | 可用性、p95、rate/quota、version廃止、障害通知、DLQ retentionを定義 |
| 公開field inventory | 未承認 | resource/field/operation単位のallow-listを承認 |

## 確認済みの停止理由

中央の受入後traceabilityはNF-05をCANDIDATE、Owner/Decision/再評価日を未定としている。
DG-05欄も未決定項目だけを列挙しており、承認DecisionId、scope、Base SHAは存在しない。
したがって候補段階の規則に従い、T0 discovery/spec作成と明示されたTask 0R remediationだけを行い、
production実装で停止する。

## Task 0R remediation

ReviewのP1/P2指摘に対し、atomic outbox、非公開OpenAPI candidate、metrics cardinality、payload retention、
review traceをspecへ反映した。対応状況はreview-remediation.mdを正本とし、SPEC_ADDRESSEDとOWNER_GATEを
混同しない。

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
