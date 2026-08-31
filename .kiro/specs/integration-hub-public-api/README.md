# NF-05 Integration Hub・公開API・Inbound/Outbound Webhook

## 現在のゲート（2026-08-31）

F1/F2/A1/B1の独立Implementation Review PASSを維持する。B1は固定Head
`f897d748cb93ade26c41d6ba4cb1a88efb29a29d`でP0/P1/P2=0/0/0のPASSを受領した。
B2は実装commit `122c7c3bb5653eb788d58040c6defc816ff67013`をremoteへpush済みで、独立Implementation Review待ちである。
最終trace commit後のremote Headを既存R-NF05へhandoffし、Reviewが完了するまでB2 PASSへ自己昇格させない。
production enablement、実顧客credential、実provider送信、PR、merge、force pushは行わない。

## 状態

- 中央台帳の状態: APPROVED
- 本specの状態: F1/F2/A1/B1独立Implementation Review PASSを維持。A1は固定Head
  `69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASS（P0/P1/P2=0/0/0）を受領した。
  B1は初回Review FAIL（fixed Head `0f1a92974ea914d16de07ccf5a586fac215283f0`、P0=0/P1=4/P2=1）を
  `30199db8`でremediateした。続く再Review（fixed Head `29d749bb6db1aad9ca98a9dd253b30d375dbba5c`、P0=0/P1=2/P2=0）の
  operator/admin permissionとnumeric scope→opaque public ID指摘を`2684ff8f`でremediateしたが、さらにP1-007（primary/secondary binding・
  current DB membership再検証）が残ったため追加remediation済みで、NF05-IMPL-B1-008（初回送信前primary binding未検証）も
  `c2cbfb99133d0df3f8d5eee285be340163747e31`でremediateした。B1は固定Head `f897d748cb93ade26c41d6ba4cb1a88efb29a29d`で独立再Review PASSを受領した。
  B2は固定Head `122c7c3bb5653eb788d58040c6defc816ff67013`で実装済み・独立Implementation Review待ち、MはB2 Review後、A2は現DecisionでN/A、production enablementは未完了
- Decision Gate: DG-05-F1-APPROVAL-20260830-01（F1）／DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02（scope expansion）
- Approved resources/commands: GET-only 11 paths、inventory allow-list。command/exportなし
- Owner: PROJECT_OWNER（OwnerType=ROLE）
- Base branch: origin/main
- Base commit: b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd
- Approved比較参照: origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd
- 専用worktree: C:\work\ses-manager-pro-integration-hub-public-api
- 専用branch: codex/integration-hub-public-api

この文書は、DG-05 Owner承認後のPlan Review対象specとReview remediationの成果物である。scope expansionの
Plan delta PASS後は承認済みwaveを順に実装できる。F2/A1独立Implementation Review PASSを受領し、B1実装を開始した。productionの
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
F2のImplementation Review FAILを受けたremediationを実施し、fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`で独立再Review PASS
（P0/P1/P2=0/0/0）を受領した。A1は`69f857d3ac7d513b66265b02871688b28d2e7e5d`で独立Implementation Review PASS（P0/P1/P2=0/0/0）を受領した。
B1を`971c17d7`で実装し、独立Review FAILを`30199db8`、再ReviewのP1-006/P1-007を`2684ff8f`でremediateした。残存P1-007へcode `5c94367c` → `0618d983`でprimary/secondary bindingと現行DB membership再検証を追加し、NF05-IMPL-B1-008をcode `c2cbfb99133d0df3f8d5eee285be340163747e31`で追加remediateした。独立B1再Review PASS後にB2→Mを順次実装する。
A2はapproved command=0件のためN/Aとする。

F1初回実装commitは `a7654b44`、Review remediation commitは `a184c1f4`、delivery CAS generation correctionは
`d476614e`、follow-up remediationは `5a2a0231`、typed snapshot correctionは `96d6801c`。V129 MySQL Flyway smoke、F1 H2 targeted
suite（31 tests、failure/error/skipなし）、MySQL `IntegrationHubF1MySqlConcurrencyTest`（5 tests、failure/error/skipなし）を確認した。
follow-upの独立Implementation Reviewは固定Head `dff90b3961b647035436abd378a352b1fa000dd1`でFAIL（P0=0、P1=4、P2=0）だったため、
`5a2a0231`の再Reviewを行った。固定Head `f4e3bf7f0c0a8c85d0ca22294471546313e5df1f`ではP1=1（nested scalar bypass）が残ったため、
`96d6801c`でfield固有pattern/enum、型、深度の検証を追加した。FU-002〜004は独立検証でクローズ済みで、固定Head
`0b52e3de7908d57c2dbac8b9ce1b0972c1be83c3`の独立Implementation ReviewでP0/P1/P2=0のPASSを受領した。M、F2以降、
public endpoint、外部送信、production enablementは未完了である。F2はremediation済みだが独立Implementation
Review再判定でPASS済み、A1は独立再Review PASS、B1は実装済みで独立Review待ち、B2/Mは順次未着手とする。
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

その後の独立再Review（fixed Head `f57df6d2cd962c4695d41b9a1980cc4b621cb408`）で、explicit tenant/legal entityの矛盾を許可するP1と、
IPv4-mapped IPv6 CIDR比較のP2が指摘された。`a16cdcba`でauthoritative tenant/legal entity singletonをeffective populationへ注入し、
scope入力の明示値をprincipalと照合、空intersectionを保持してfail-closedにした。またmapped IPv6 source/CIDRを4-byte IPv4へcollapseし、
mapped/IPv4双方のCIDR比較を追加した。独立再Review fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`でF2 IMPLEMENTATION PASSを受領した。

## A1 v1 read API実装

`466bd9aa44e8699f58cfe0ac033c9c444a7de71e`で、承認済みGET-only 11 pathsを専用
`ExternalApiReadController`、`ExternalApiReadService`、`ExternalApiReadMapper`、external DTOへ実装した。
DB queryは許可された列とeffective scopeのID集合だけを選択し、internal entity・internal ID・secret・PII・金額/原価/粗利・provider raw bodyを
レスポンスへ渡さない。opaque public IDはHMAC-SHA256、cursorはAES-GCMでclient/tenant/legal entity/route/scope/as-of/expiryへbindし、
list/detail/countは同じimmutable effective populationを使う。client指定asOfは受け付けず、as-ofはrequest受信時のserver clockで固定する。
enabled時にpublic ID keyが未設定なら起動を拒否し、production設定は引き続きpublic API disabledのままである。

初回独立Reviewはinvoice customer scope、cursor snapshot、canonical Base64URL、DTO/E2E証跡の不足を指摘した。
`874fface3bfe90dd27b766ddf9aeff4e00eae591`で、invoice list/detail/countへ`invoiceIds × customerIds`を同一predicateで適用し、
複数contract invoiceのpublic contract IDをnullにした。cursorは初回as-of時点のmembershipとallow-list DTO JSONを短期materialized snapshotへ保存し、
snapshot IDをopaque cursorへbindする。Base64URLはpaddingなし再encode一致を要求する。4 DTO、11 path、entity negative、
snapshot insert/update/delete/reparentの契約テストを追加し、E2E fixtureへtest crypto keyを明示した。
focused remediation suiteはcursor 3、service 5、DTO 5、mapper 2、snapshot 1（計16 tests）、failure/error/skipなしでPASSした。
Windowsのbrowser profileはcrypto設定エラーを解消した後もTomcat loopback接続確立失敗でHTTP assertion前に停止したため、この環境結果をPASS根拠にはしない。
A1独立再Review PASSを受領したため、B1を`971c17d7`で開始した。B1初回Review FAILは`30199db8`でremediate済みであり、独立再Review PASSまではB2を開始しない。

## B1 outbound webhook実装・Review remediation

`971c17d7`で、NF-05専用`t_api_delivery`を再利用するoutbound delivery workerを追加した。業務stateとdelivery rowのatomic insert、
短いclaim/lease transaction、transaction外のHTTP、provider idempotency key・payload hash・generation・lease tokenを含む結果CASを分離し、
timeout/429/5xxだけを最大8回backoff+jitter、その他4xxをFAILED、上限到達をDLQへ収束させる。DLQ replayは新generationとsafe audit metadataへ固定する。

署名は固定framingのHMAC-SHA256、credential version/key ID、timestamp、correlation、payload hash、provider idempotency keyをbounded headerへ出力する。
MOCK/STUBは無接続、LOOPBACKはstrict literal IP・allow-list port・peer検証・redirectなし・proxy/DNSなしである。V132、H2 schema、
properties/transport/worker/replay testを追加したが、実顧客credential・実provider送信・production enablementは行わない。

初回B1 Implementation Review（fixed Head `0f1a92974ea914d16de07ccf5a586fac215283f0`）のP1-001〜004/P2-005を
`30199db8`でremediateした。署名のcanonical framingへcredential versionとprovider idempotency keyを含め、outbound envelopeとdelivery ledgerの
一致を送信直前に検証する。manual replayは`integration.webhook.replay` permission、active subscription、client/permission/subscriptionの
current data scope、tenant/legal entity、payload membershipをDBから再取得して再計算する。V133でreplay auditとdelivery payloadのlifecycleを
分離し、delivery purgeはauditを阻害せず、audit metadataは独立1年purgeとする。workerはclaim直前・HTTP完了後にclockを再取得し、結果CAS障害は
transport retryへ変換せずlease recoveryへ委ねる。focused unit/H2/MySQL証跡はPASS済みだが、独立再Review受領まではB1 IMPLEMENTATION PASSへ昇格しない。

再ReviewのP1-006/P1-007は`2684ff8f`で、呼出側operatorRef入力の廃止、認証済み内部admin principalと
`integration.webhook.replay` action permissionの検証を追加した。P1-007残存指摘への追加remediationでは、V134でdeliveryへprimary
resource type/内部IDをbindし、`publicResourceId`はprimaryだけへ適用、project×customer・invoice×customer×contractのsecondaryは各専用
opaque IDで照合する。現行`deleted_flag`、active parent/customer/project/contract、invoice item/work record relationはmapperで再照会し、
scope据置のsoft-delete/reparent/contract付替えをfail-closedとする。実顧客credential、実provider送信、production enablementは行わない。

NF05-IMPL-B1-008では、enqueue保存前とworker外部HTTP前に共通binding validatorを実行し、client bindingからprimary opaque IDをHMAC再計算して
envelopeとprimary DTO fieldの一致を要求する。DuplicateKey収束でもpayload hash・primary type・primary IDを同時比較し、type/ID不一致と同時enqueueの
別primaryをfail-closedとする。code commitは`c2cbfb99133d0df3f8d5eee285be340163747e31`、独立再Review待ちである。

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
