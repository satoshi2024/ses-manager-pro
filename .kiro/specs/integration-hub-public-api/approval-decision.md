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
| Current implementation Head | `251461f1`（B2 quota/error contract remediation code commit。docs trace commit後の最終remote Headは外部handoffで固定） |
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
| Current reviewed remote Head | ca27f45532bbf96d29da7b9ba87ca52b9cf96d8a（scope expansion Plan PASS） |
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
| F2 | IMPLEMENTATION_PASS | 独立再Review fixed Head `d022e60039880dc5d4743f336661819cda7fc3f4`、P0/P1/P2=0/0/0 |
| A1 | IMPLEMENTATION_PASS | fixed Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`、P0/P1/P2=0/0/0 |
| A2 | NOT_APPLICABLE_UNDER_CURRENT_DECISION | approved command=0件。command/exportはdefault denyで全体完了をblockしない |
| B1 | IMPLEMENTATION_PASS | 独立再Review fixed Head `f897d748cb93ade26c41d6ba4cb1a88efb29a29d`、P0/P1/P2=0/0/0。実provider送信なし |
| B2 | IMPLEMENTATION_REVIEW_PENDING | `cc468e4f`で独立Review指摘をremediate済み。provider/subscription、resource binding、admin principal、opaque reference、strict content typeを追加検証。production受信enablementなし |
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
再オープンしない。scope expansionのPlan deltaは固定Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aで
PLAN PASS（P0=0、P1=0、P2=0）を受領した。F2は同じ専用worktreeで実装済みだが、固定Head `220ac86f531d6e656aeac0ef19225e9596b9385b` の独立Implementation ReviewがFAIL
（P0=0、P1=4、P2=2）となったため、`e47025b5`でremediationし、独立再Reviewへ渡した。その再Review固定Head
`f57df6d2cd962c4695d41b9a1980cc4b621cb408`でもP1=1、P2=1が残ったため、`a16cdcba`で追加remediationした。独立再Review fixed Head
`d022e60039880dc5d4743f336661819cda7fc3f4`でP0/P1/P2=0/0/0のF2 IMPLEMENTATION PASSを受領した。

Plan deltaは固定Head ca27f45532bbf96d29da7b9ba87ca52b9cf96d8aでPASS済みである。F2 PASS後、A1を
`466bd9aa44e8699f58cfe0ac033c9c444a7de71e`で実装し、remediationを経て固定Head `69f857d3ac7d513b66265b02871688b28d2e7e5d`で
独立Implementation Review PASS（P0/P1/P2=0/0/0）を受領した。B1を`971c17d7`で実装し、独立B1 Implementation Reviewへhandoffする。初回B1 Reviewはfixed Head
`0f1a92974ea914d16de07ccf5a586fac215283f0`でFAIL（P0=0/P1=4/P2=1）となったため、`30199db8`で署名/envelope、replay再認可、
audit/payload retention分離、fresh-clock/CAS、実DB failure/concurrency証跡をremediateした。独立再Review受領までB1 PASSとは扱わない。
B1再Review PASS後はB2→Review→M→最終Reviewの順に継続する。B1再Review fixed Head `29d749bb6db1aad9ca98a9dd253b30d375dbba5c`のP1-006/P1-007を
`2684ff8f1303b6d0cc6550882601405d3d78f3b2`でremediateした後の残存P1-007へ、V134 primary binding、secondary専用opaque ID、
現行`deleted_flag`/parent relation mapper、project×customer・invoice×customer×contract・soft-delete/reparent/contract付替えtestを追加した。
code remediation commits `5c94367c` → `0618d983`と後続docs trace commitを同じR-NF05へ独立再Reviewとして依頼する。各waveはTask単位でcommit/pushし、production enablement、実顧客credential、実provider送信、
PR、merge、auto-mergeは最終PLAN/IMPLEMENTATION PASS後も別途許可されるまで行わない。

NF05-IMPL-B1-008（初回送信前primary binding未検証）の追加remediationをcode commit
`c2cbfb99133d0df3f8d5eee285be340163747e31`で固定した。enqueue保存前とworker外部HTTP前に同一validatorでclient bindingからHMAC opaque IDを再計算し、
envelopeとprimary DTO fieldの一致を要求する。DuplicateKey収束でもpayload hash・primary type・primary IDを同時比較し、同時enqueueの別primary、type/ID不一致を拒否する。
同じR-NF05へdocs trace commit後の固定Headを独立再Implementation Reviewとして提出する。

## B2 implementation remediation checkpoint

固定Head `0514e00a1cd27fdedba8d15b5bc87d2fd02d706c` の独立B2 Implementation Reviewで示されたP1=4/P2=1を、code commit
`cc468e4f`でremediateした。受信前にapproved providerとactive client×provider×eventType subscription、receive permission、
tenant/legal entity/data-scope intersectionを検証し、resource eventはprimary/secondaryのopaque IDと現行DB membership、
deleted/reparent状態を再確認する。replayは有効・非ロックの`LoginUser`、内部user ID、ROLE_管理者、
`integration.webhook.replay` permissionをservice boundaryで要求し、operator referenceはprincipalからのみ導出する。
admin projectionとreplay URLはopaque referenceだけを使い、Content-Typeは`application/json`と許可charsetだけに限定する。

独立再ReviewまでB2はIMPLEMENTATION_REVIEW_PENDINGとし、B2 PASSへ自己昇格しない。Windows connector E2EはTomcatのloopback
接続エラーでHTTP到達前に実行不能だったためPASS証拠に数えず、Linux実connector E2Eで再確認する。production受信enablement、
実credential、実provider送信、PR、mergeは引き続き禁止する。

## B2 quota/error contract remediation checkpoint

前回remediation後の独立Review fixed Head `7f16cc1d9aecf3ebd688d69f981f0610567d4d1` で、inbound routeがquota allow-listから
漏れているP1と、unknown providerの期待status/codeがspecとtestで不一致のP2が示された。`251461f1`で
`ExternalApiRouteCatalog.QUOTA_ROUTE_TEMPLATES`をcanonical route templateの単一正本とし、
`/external-api/v1/webhooks/{provider}`を含めた。usage bucketはraw provider pathではなくこのtemplateをclient×scope×tenant×route templateの
subject keyへ渡す。unknown providerは403/`FORBIDDEN_SCOPE`へtestと実装を同期した。

B2は独立再ReviewまでIMPLEMENTATION_REVIEW_PENDINGとし、Linux実Tomcat connector 5/5の再確認をPASS証拠として先取りしない。
