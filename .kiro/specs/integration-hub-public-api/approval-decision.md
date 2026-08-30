# NF-05 Approval Decision

## Decision metadata

| 項目 | 正本値 |
|---|---|
| DecisionId | DG-05-F1-APPROVAL-20260830-01 |
| Decision date | 2026-08-30 |
| OwnerRef | PROJECT_OWNER |
| OwnerType | ROLE |
| Feature | integration-hub-public-api |
| NF-05 status | APPROVED |
| Approved Base branch | origin/main |
| Approved Base SHA | b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd |
| Implementation branch | codex/integration-hub-public-api |
| Allowed remote push | origin/codex/integration-hub-public-api only |
| Prohibited | force push、main変更、PR作成、merge、auto-merge |

個人実名は記録しない。Ownerの責任主体はOwnerRef/OwnerTypeで表す。

## Approved implementation scope

この承認で直ちに実装できるのは、Task 0/0R/0R-Dの正本化とTask F1のclient、credential、scope、
idempotency、usage bucket、webhook-inbound-outbound persistence contractのDDL、entity、mapper、
service基盤、H2/MySQL migration、test、purge、rollback証跡、およびF1に必要な最小config/crypto abstraction
である。ただし同じ開工対話で独立Plan ReviewのPLAN PASSを先に受領する。

F1 Decision時点ではpublic endpointの公開、外部送信、A1、A2、B1、B2、production enablementを開始しない。
後続のscope expansion DecisionでF2/A1/B1/B2/Mの開発だけを追加承認したが、production enablement、
実顧客credential、実provider送信は禁止し、command/exportはdefault deny、A2はN/Aである。

## Scope expansion decision

| 項目 | 正本値 |
|---|---|
| DecisionId | DG-05-IMPLEMENTATION-SCOPE-EXPANSION-20260830-02 |
| Decision date | 2026-08-30 |
| OwnerRef | PROJECT_OWNER |
| OwnerType | ROLE |
| Base | origin/main@b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd |
| Current reviewed remote Head | 7e50bf1360ea8d7271acc0667593635451300268 |
| F1 gate | PLAN PASS / IMPLEMENTATION PASS（P0/P1/P2=0）を維持。再オープンしない |
| Implementation branch | codex/integration-hub-public-api |
| Allowed remote push | origin/codex/integration-hub-public-api only |

このDecisionは、F1 PASS後の開発scopeを拡張する。F2 dedicated security chain、A1 v1 GET-only read
API/OpenAPI、B1 outbound webhook、B2 inbound webhook/DLQ/admin UI、Mのpenetration/recovery/
performance/scan/runbook、および各waveのspec、migration、H2/MySQL、contract/security test、docs trace、
commit/push、独立Review remediationを承認する。開発・test環境のmock/stub providerとloopback test serverも
承認する。production enablement、実顧客credential、実providerへの外部送信、main変更、force push、mergeは
引き続き禁止する。

| Wave | Decision status | 境界 |
|---|---|---|
| F2 | APPROVED_NOT_STARTED | Plan delta PASS後に着手。専用security chain、client principal、scope/data scope、audit、rate/IP |
| A1 | APPROVED_SEQUENCED | F2 Implementation PASS後。GET-only 11 paths、inventory allow-list、external DTOのみ |
| A2 | NOT_APPLICABLE_UNDER_CURRENT_DECISION | approved command=0件。command/exportはdefault denyで全体完了をblockしない |
| B1 | APPROVED_SEQUENCED | A1 Review後。mock/loopbackのみ、実provider送信なし |
| B2 | APPROVED_SEQUENCED | B1 Review後。inbound/DLQ/admin UI、実外部受信のenablementなし |
| M | APPROVED_SEQUENCED | B2 Review後。security、負荷、障害訓練、rotation、scan、runbook、固定Head |

## Approved contract and security values

1. API利用者は管理者が事前登録したB2B system clientのみとする。clientはtenant、legal entity、data
   scopeへserver-side bindし、internal role/portal userへ変換しない。
2. 初期公開契約はinventory/OpenAPI candidateのGET-only 11 pathsとする。engineer-availabilityは
   list/detail、project、contract-status、invoice-statusはlist/detail/countとする。公開fieldはinventoryの
   allow-listだけで、internal entity serialize、command、exportは行わない。
3. 認証はHMAC-SHA256 signed service accountの一方式に固定する。canonical署名対象はclientId、
   credentialVersion/keyId、timestamp、nonce、method、canonical path/query、body SHA-256とする。
   許容時刻差は±5分、nonce replayは拒否し、OAuth fallbackは持たない。
4. secretはAES-256-GCM envelopeとし、AADへclientId、credentialVersion、purposeをbindする。crypto keyは
   環境注入keyringから取得し、DBへ平文保存しない。原文は発行時に一度だけ表示し、log、audit、metrics、
   exceptionへ再表示しない。rotation overlapは24時間、revokeは即時、credential有効期間は90日とする。
5. IPはclientごとのCIDR allow-listをdefault denyで適用する。Forwarded/X-Forwarded-Forは明示設定された
   trusted proxyからのみ採用し、unknown、malformed、multi-hop不正を拒否する。IPv4/IPv6を正規化する。
6. rate/quotaはclient×scope×tenant×route templateで60 req/min、burst 20、1日50,000とする。
   超過時は429とRetry-Afterを返す。初期課金はなく、利用量だけを計測する。高cardinality IDはmetrics
   labelへ入れない。
7. SLAは月間99.9%、p95 500msとする。対象は同時接続・payload上限内で、計画保守を除外する。計画保守は
   7日前、重大障害は60分以内に通知する。v1廃止予告は180日とする。
8. scope外detailと不存在detailは同一404/RESOURCE_NOT_FOUNDへ収束する。list/countはscope適用後だけを
   返し、empty scopeを全件として扱わない。as-ofはserver clockで固定し、client指定asOfは持たない。
9. webhookはHMAC-SHA256、timestamp±5分、event/provider ID replay拒否とする。timeout/429/5xxだけを
   最大8回、指数backoff+jitterでretryし、その他4xxはretryしない。失敗後はDLQとする。manual replayは
   専用admin permission、reason、generation、再scope検証を必須とする。
10. retentionはsucceeded payload 30日、failed/DLQ 90日、audit metadata 1年とする。raw secret、PII、
    raw bodyは永続化しない。legal hold中はpurgeを停止する。
11. threat modelの受入対象はclient impersonation、credential leakage/rotation、signature replay、
    IDOR/data-scope bypass、cursor tamper、rate/IP spoof、SSRF、outbox/inbound duplicate、claim race、
    DLQ replay、secret/PII leakage、backup/restore後purgeである。
12. F1 migrationは現行migration最大値を再確認し、未使用の次番号を採用する。既存migration番号は上書きせず、
    V1 consolidated baselineとH2 schema/init経路をAGENTS.mdに従って同期する。

## Required next gate

F1は固定Head 7e50bf1360ea8d7271acc0667593635451300268でPLAN PASS / IMPLEMENTATION PASS済みであり、
再オープンしない。scope expansionの固定Head 1547871caed049ba14d1e5e4a25ad50fa19771fcはPLAN FAIL
（P0=0、P1=4、P2=2）となり補正したが、固定Head 9cca2deec9ab1bd5417aaba98f859ed14210da13も
PLAN FAIL（P0=0、P1=3、P2=0）だった。security chain監査/error boundary、canonicalTarget byte
生成、disabled deny-only/bean/config契約のspec/architecture remediationだけを同じbranchへcommit/pushし、
既存R-NF05へPlan delta再Reviewとして渡す。Plan delta PASS前はF2 implementationを開始しない。

Plan delta PASS後はF2→独立Implementation Review→A1→Review→B1→Review→B2→Review→M→最終Reviewの順に
継続する。各waveはTask単位でcommit/pushし、production enablement、実顧客credential、実provider送信、
PR、merge、auto-mergeは最終PLAN/IMPLEMENTATION PASS後も別途許可されるまで行わない。
