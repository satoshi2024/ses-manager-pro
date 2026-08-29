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

public endpointの公開、外部送信、A1、A2、B1、B2、production enablementはこの承認では開始しない。
command/exportはdefault denyとし、A2 commandは未承認である。

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
8. scope外detailと不存在detailは同一404/ResourceNotFoundへ収束する。list/countはscope適用後だけを
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

Owner承認済みのため、Plan Reviewへ固定remote Headを渡せる。独立Plan ReviewのPLAN PASSを受領するまで
F1 production code、migration、test source、外部送信、public endpoint、UI変更は開始しない。PLAN FAIL時は
specを修正して同じremote branchへcommit/pushし、再Reviewする。PLAN PASS後のF1ではTask単位にcommit/pushし、
最終的に独立Implementation Reviewへ渡す。
