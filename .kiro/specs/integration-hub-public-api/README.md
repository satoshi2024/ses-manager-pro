# NF-05 Integration Hub・公開API・Inbound/Outbound Webhook

## 状態

- 中央台帳の状態: APPROVED
- 本specの状態: F1独立Implementation Review PASSを維持。scope expansion Plan deltaは固定Head
  ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPLAN PASS（P0=0、P1=0、P2=0）。F2は独立Implementation
  ReviewでFAIL（固定Head 220ac86f、P1=4、P2=2）となったため、remediation commit e47025b5を追加し、再Review待ち。
  A1/B1/B2/Mは順次承認、A2は現DecisionでN/A、M未完了
- Decision Gate: DG-05-F1-APPROVAL-20260830-01（F1）／DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02（scope expansion）
- Approved resources/commands: GET-only 11 paths、inventory allow-list。command/exportなし
- Owner: PROJECT_OWNER（OwnerType=ROLE）
- Base branch: origin/main
- Base commit: b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd
- Approved比較参照: origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd
- 専用worktree: C:\work\ses-manager-pro-integration-hub-public-api
- 専用branch: codex/integration-hub-public-api

この文書は、DG-05 Owner承認後のPlan Review対象specとReview remediationの成果物である。scope expansionの
Plan delta PASS後は承認済みwaveを順に実装できるが、F2独立Implementation Review PASSまではA1へ進まず、productionの
外部送信、実顧客credential、public endpoint enablementは開始しない。development/testの
mock/stub providerとloopback test serverに限定し、production enablement、実顧客credential、実provider送信は
行わない。force push、main変更、PR、merge、auto-mergeは禁止する。

## 承認ゲート

| ゲート | 現在 | production変更開始条件 |
|---|---|---|
| Approved resources/commands | APPROVED | GET-only 11 paths、inventory allow-list、command/exportなし。A2はN/A |
| Owner | APPROVED | PROJECT_OWNER、OwnerType=ROLE |
| Base branch/commit | APPROVED | origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd |
| DG-05 | APPROVED | F1: DG-05-F1-APPROVAL-20260830-01／scope expansion: DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02 |
| Threat model | APPROVED | 承認済み11脅威をF1〜Mの受入対象として固定 |
| 認証方式 | APPROVED | HMAC-SHA256 signed service account、OAuth fallbackなし |
| 契約SLA | APPROVED | 月間99.9%、p95 500ms、保守7日前、重大障害60分以内、v1廃止予告180日 |
| 公開field inventory | APPROVED | inventory allow-listのみ、internal entity serialize禁止 |

## Review結果とF1開始

中央の受入後traceabilityとapproval-decision.mdにNF-05のAPPROVED、OwnerRef、DecisionId、Base SHA、
scope、auth、SLA、field inventoryを固定した。R-NF05のF1 Plan/Implementation ReviewはPASS済みであり、
固定Head 7e50bf1360ea8d7271acc0667593635451300268で再オープンしない。scope expansionのdocs-only gateを
同Headから作成し、既存R-NF05へPlan delta Reviewを渡した。固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcは
PLAN FAIL（P0=0、P1=4、P2=2）、固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13もPLAN FAIL（P0=0、P1=3、P2=0）だったが、
remediation後の固定Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPLAN PASS（P0=0、P1=0、P2=0）を受領した。
F2のImplementation Review FAILを受けたremediationを実施済みで、独立再Reviewへ渡す。PASS後にA1→B1→B2→Mを順次実装する。
A2はapproved command=0件のためN/Aとする。

F1初回実装commitは `a7654b44`、Review remediation commitは `a184c1f4`、delivery CAS generation correctionは
`d476614e`、follow-up remediationは `5a2a0231`、typed snapshot correctionは `96d6801c`。V129 MySQL Flyway smoke、F1 H2 targeted
suite（31 tests、failure/error/skipなし）、MySQL `IntegrationHubF1MySqlConcurrencyTest`（5 tests、failure/error/skipなし）を確認した。
follow-upの独立Implementation Reviewは固定Head `dff90b3961b647035436abd378a352b1fa000dd1`でFAIL（P0=0、P1=4、P2=0）だったため、
`5a2a0231`の再Reviewを行った。固定Head `f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`ではP1=1（nested scalar bypass）が残ったため、
`96d6801c`でfield固有pattern/enum、型、深度の検証を追加した。FU-002〜004は独立検証でクローズ済みで、固定Head
`0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`の独立Implementation ReviewでP0/P1/P2=0のPASSを受領した。M、F2以降、
public endpoint、外部送信、production enablementは未完了である。F2はremediation済みだが独立Implementation
Review再判定待ちで、A1/B1/B2/Mは順次未着手とする。
全fast suiteはF1/F2対象外の既存loopback・production-config系11 errorsと2 failuresで
終了しているため、全体PASSとは扱わない。F1の独立Implementation ReviewはPASSだが、MとF2以降のgateは残っている。

## Task 0R remediation

ReviewのP1/P2指摘に対し、atomic outbox、非公開OpenAPI candidate、metrics cardinality、payload retention、
review traceをspecへ反映した。対応状況はreview-remediation.mdを正本とし、SPEC_ADDRESSED、OWNER_APPROVED、
PLAN PASS、IMPLEMENTATION PASSを混同しない。

## F2 Implementation Review remediation

固定Head `220ac86f531d6e656aeac0ef19225e9596b9385b` の独立Implementation ReviewはFAIL（P0=0、P1=4、P2=2）だった。
実装可能な指摘を `e47025b5` でremediateした。Tomcat connector valveからのみraw request-targetを受け、typed data-scopeの
intersectionとimmutable effective scopeを認可へbindし、専用audit table/service、strict literal IP parser、有限metrics label、
namespace root matcherを追加した。enabled connector E2Eは手動request属性を注入しない形で追加したが、実行環境のloopback接続確立失敗により
HTTP assertion到達前に停止している。このためF2をPASSまたは公開可能とは扱わず、独立Implementation再Reviewを要求する。

## 既知の重要差分

1. 既存の通知outboxはNF-05では再利用せず、NotificationOutboxDispatcher.dispatchOne は
   claimから外部Webhook送信・結果更新までを一つのREQUIRES_NEW transactionに包み、
   transaction中にwebhookNotifier.notifyNowを呼ぶ。NF-05の「外部callをDB transaction内に置かない」
   条件を満たすためにはNF-05専用t_api_deliveryを使い、claim、外部call、結果CASを分離する必要がある。
2. 内部SecurityFilterChainとPortalSecurityFilterChainは存在するが、公開API用chain、client principal、
   credential version、scope、quotaの実装は存在しない。
3. 相関IDは会計provider等で呼出側が生成・伝播する方式が中心で、全公開requestを横断する
   correlation ID filterは確認できない。
4. PortalRateLimiterImplはプロセス内の1分sliding windowであり、公開clientのmulti-node rate boundary
   としては不十分である。ClientIpResolverもtrusted proxy設定時のX-Forwarded-For先頭値を使うだけなので、
   proxy chain、IPv6、spoof試験をF1/F2で実施する。IPは承認済みrate保存キーへ含めない。
5. Freee tokenの暗号文にはtoken_versionとrefresh leaseがあるが、crypto key version付きの
   API credential envelopeを一つの共通基盤として提供していない。token versionをkey versionと
   混同しない。
6. 署名nonceはt_api_nonce_replayのclient+nonce hash uniqueで一度だけ受付け、TTL purgeする。retention対象は
   class/expiryを持ち、t_api_retention_holdとt_api_purge_checkpointのlock/CASおよびrestore後全件再評価を使う。

## Review引渡し方針

承認後に限り、approved plan/spec/tasksとcompletion-matrixを実装Taskごとに更新し、Task単位の
commit/pushを行う。実装対話ではPRを作らず、最後にremote Head、Base、全gate、未検証、rollback、
対応表を独立Reviewへ渡す。独立ReviewのPLAN PASSとIMPLEMENTATION PASSの双方が記録されるまで
PRは作成しない。force push、merge、auto-merge、branch削除は行わない。
