# Contract Document / CloudSign Review Ledger

## 1. 運用方法

- 本 ledger は追記型とし、過去の FAIL/BLOCKED/finding を削除・上書きしない。再検証は新しい行を追加する。
- task checkbox、commit message、実装AIの完了説明だけでは PASS にしない。
- merge前の合格は`REVIEWABLE`とする。`PASS`はmerge済みcommitについてrequirement/AC、merge delta、実diff、自動test、Demo、必要なsandbox/運用evidenceを独立確認した場合だけ使用する。
- 外部credential、token、実メール、PDF本文、秘密設定値を記録しない。
- finding ID は `HFP-02-REV-NNN`、baseline finding は `HFP-02-FND-NNN` を維持する。

## 2. Review 対象

| 項目 | 値 |
|---|---|
| spec | `contract-document-esign` |
| base commit | `841e10aa`（main） |
| review head | 未設定 |
| merge状態 / merge commit | PRE_MERGE / N/A |
| branch/worktree | `codex/hfp-02-contract-cloudsign` / `%TEMP%\opencode\hfp-02-contract-cloudsign` |
| 実装担当 | codex専任AI |
| 独立reviewer | 未設定 |
| fixed OpenAPI | `0.36.0` / SHA-256 `f832681318e67b9fb5fe9a0bb368a570762401dcd4a62b98a934deebb192a240`（2026-08-14再取得で不変を確認） |
| 全体判定 | NOT_STARTED |

## 3. Task gate

| Task ID | 依存 | 実装 | 定向test | Demo | 独立Review | 判定 | 証跡/再開条件 |
|---|---|---|---|---|---|---|---|
| HFP-02-00 | - | DONE(production変更なし) | DONE(11/0/0/0) | BLOCKED(sandbox未確認) | NOT_STARTED | PARTIAL | 公式schema不変を確認、fixture schema test 11件PASS。Demoはsandbox credential入手後に再実施 |
| HFP-02-01 | 00 | DONE(red testのみ) | DONE(13/13意図どおりred) | DONE(二重send重複riskをtest logで実演) | NOT_STARTED | PARTIAL | baseline defectを13件redで固定。green化はHFP-02-02〜08 |
| HFP-02-02 | 01 | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | migration latest/legacy fixture確認 |
| HFP-02-03 | 00,01 | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | fixed wire fixture |
| HFP-02-04 | 02,03 | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | result-unknown/call-count evidence |
| HFP-02-05 | 03,04 | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | polling/status evidence |
| HFP-02-06 | 02,03,05 | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | 三hash/scan/ledger evidence |
| HFP-02-07 | 04,05,06 | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | role/scope/browser evidence |
| HFP-02-08 | 01-07 | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | verify-like-ci skip0 |
| HFP-02-09 | 00,08 | NOT_STARTED | BLOCKED(sandbox未確認) | BLOCKED | NOT_STARTED | BLOCKED | sandbox credential/受信操作担当 |
| HFP-02-10 | 08,09 | NOT_STARTED | NOT_STARTED | BLOCKED | NOT_STARTED | BLOCKED | P0/P1=0、運用承認 |

## 4. Requirement / Acceptance trace

全 AC を先に登録する。実装時に同じ行の evidence 欄を埋め、複数 AC を「同様」で省略しない。判定履歴を残す必要がある場合は直下へ同じ AC の再検証行を追加する。

| Requirement / AC | Task | 実装 file/method | 自動test class/method | Demo / external evidence | Reviewer | 判定 | 備考/rollback |
|---|---|---|---|---|---|---|---|
| HFP-02-AC-01-01 | 00 | research.md §2.1/§2.2 | CloudSignOpenApiFixtureSchemaTest#fixtureMetaは固定OpenAPIのpinと一致する | OpenAPI version/SHA 再取得(2026-08-14, 不変) | - | VERIFIED | curl生bytes固定、gzip展開を除外 |
| HFP-02-AC-01-02 | 00,03,08 | fixture + schema test | CloudSignOpenApiFixtureSchemaTest（token/create/upload/participant/send/get/certificate/decline） | wire契約 | - | IN_PROGRESS | fixture契約成立。client実装はHFP-02-03 |
| HFP-02-AC-01-03 | 03,05,07 | - | - | malformed/unknown fixture | - | NOT_STARTED | - |
| HFP-02-AC-01-04 | 00 | research.md §2.2 | fixtureMetaは固定OpenAPIのpinと一致する | version diff review(差分なし) | - | VERIFIED | 更新時はfixture/pin同時更新まで停止 |
| HFP-02-AC-02-01 | 03 | - | - | host matrix | - | NOT_STARTED | - |
| HFP-02-AC-02-02 | 03,08 | - | - | log/API redaction | - | NOT_STARTED | P0 |
| HFP-02-AC-02-03 | 03 | - | - | token concurrency/401 | - | NOT_STARTED | - |
| HFP-02-AC-02-04 | 03,10 | - | - | readiness fail-closed | - | NOT_STARTED | - |
| HFP-02-AC-02-05 | 00,09,10 | - | - | sandbox/preflight | - | BLOCKED | HFP-02-BLK-01 |
| HFP-02-AC-03-01 | 01,04 | - | - | source preflight | - | NOT_STARTED | P0 |
| HFP-02-AC-03-02 | 03,04 | - | - | source PDF wire hash | - | NOT_STARTED | mutation直列 |
| HFP-02-AC-03-03 | 04 | - | - | provider IDs/preflight | - | NOT_STARTED | - |
| HFP-02-AC-03-04 | 07 | - | - | browser確認modal | - | NOT_STARTED | - |
| HFP-02-AC-03-05 | 04,07 | - | - | payload mismatch | - | NOT_STARTED | - |
| HFP-02-AC-04-01 | 02,04 | - | - | 100 concurrent send | - | NOT_STARTED | P0 |
| HFP-02-AC-04-02 | 04 | - | - | transaction inactive | - | NOT_STARTED | P0 |
| HFP-02-AC-04-03 | 04 | - | - | accepted-timeout call count=1 | - | NOT_STARTED | P0 |
| HFP-02-AC-04-04 | 04,09 | - | - | GET/marker reconciliation | - | BLOCKED | HFP-02-BLK-02/03 |
| HFP-02-AC-04-05 | 04 | - | - | crash/stale claim | - | NOT_STARTED | P0 |
| HFP-02-AC-04-06 | 04,07,10 | - | - | orphan/duplicate runbook | - | NOT_STARTED | - |
| HFP-02-AC-05-01 | 05 | - | - | status mapping | - | NOT_STARTED | - |
| HFP-02-AC-05-02 | 02,04,05 | - | - | state machine | - | NOT_STARTED | - |
| HFP-02-AC-05-03 | 04,05 | - | - | terminal/reminder rejection | - | NOT_STARTED | P0 |
| HFP-02-AC-05-04 | 05,06 | - | - | completed/artifact split | - | NOT_STARTED | - |
| HFP-02-AC-05-05 | 05,07,09 | - | - | `ADOPT`: cancel sandbox / `NOT_ADOPT`: route非公開＋status=3 mapping | - | BLOCKED | HFP-02-BLK-06 の相互排他decision待ち |
| HFP-02-AC-06-01 | 05 | - | - | ShedLock/batch | - | NOT_STARTED | - |
| HFP-02-AC-06-02 | 05 | - | - | manual/poll commit reversal | - | NOT_STARTED | - |
| HFP-02-AC-06-03 | 03,05 | - | - | retry matrix | - | NOT_STARTED | - |
| HFP-02-AC-06-04 | 04,05,09 | - | - | provider delay | - | BLOCKED | HFP-02-BLK-03 |
| HFP-02-AC-06-05 | 05,10 | - | - | metrics/alert | - | NOT_STARTED | - |
| HFP-02-AC-07-01 | 02,06 | - | - | 三hash表 | - | NOT_STARTED | P0 |
| HFP-02-AC-07-02 | 03,05,06,09 | - | - | signed/certificate PDF | - | BLOCKED | HFP-02-BLK-04 |
| HFP-02-AC-07-03 | 06 | - | - | scan/atomic pipeline | - | NOT_STARTED | P0 |
| HFP-02-AC-07-04 | 06 | - | - | same/different hash | - | NOT_STARTED | - |
| HFP-02-AC-07-05 | 06 | - | - | storage/DB failure injection | - | NOT_STARTED | P0 |
| HFP-02-AC-07-06 | 06,07 | - | - | download matrix | - | NOT_STARTED | - |
| HFP-02-AC-08-01 | 07 | - | - | 5role direct API | - | NOT_STARTED | - |
| HFP-02-AC-08-02 | 07 | - | - | scope外404 | - | NOT_STARTED | P0 |
| HFP-02-AC-08-03 | 07,08 | - | - | DTO allow-list | - | NOT_STARTED | P0 |
| HFP-02-AC-08-04 | 06,07 | - | - | no-store/audit | - | NOT_STARTED | - |
| HFP-02-AC-08-05 | 03,07,08 | - | - | log capture | - | NOT_STARTED | P0 |
| HFP-02-AC-08-06 | 07 | - | - | CSRF | - | NOT_STARTED | - |
| HFP-02-AC-09-01 | 03,04,05 | - | - | error/result-unknown matrix | - | NOT_STARTED | P0 |
| HFP-02-AC-09-02 | 03,05 | - | - | token/rate/retry | - | NOT_STARTED | - |
| HFP-02-AC-09-03 | 03,06 | - | - | body/file limits | - | NOT_STARTED | - |
| HFP-02-AC-09-04 | 05,06,10 | - | - | alert matrix | - | NOT_STARTED | - |
| HFP-02-AC-10-01 | 05,07 | - | - | state/role UI/API | - | NOT_STARTED | - |
| HFP-02-AC-10-02 | 04,07 | - | - | queue≠sent UI | - | NOT_STARTED | - |
| HFP-02-AC-10-03 | 04,07,10 | - | - | reconciliation UI/runbook | - | NOT_STARTED | - |
| HFP-02-AC-10-04 | 07 | - | - | desktop/390px | - | NOT_STARTED | - |
| HFP-02-AC-11-01 | 01,03,04,05,08 | - | - | contract/concurrency suite | - | NOT_STARTED | - |
| HFP-02-AC-11-02 | 07,08 | - | - | controller security suite | - | NOT_STARTED | - |
| HFP-02-AC-11-03 | 06,08 | - | - | file failure matrix | - | NOT_STARTED | - |
| HFP-02-AC-11-04 | 09 | - | - | sandbox E2E | - | BLOCKED | sandbox未確認 |
| HFP-02-AC-11-05 | 02,08 | - | - | verify-like-ci skip0 | - | NOT_STARTED | - |
| HFP-02-AC-11-06 | 10 | - | - | 運用承認 | - | BLOCKED | operator未設定 |
| HFP-02-AC-12-01 | 02 | - | - | migration 5形状 | - | NOT_STARTED | - |
| HFP-02-AC-12-02 | 02,06 | - | - | legacy/backfill reconciliation | - | NOT_STARTED | - |
| HFP-02-AC-12-03 | 04,05,10 | - | - | kill switch | - | NOT_STARTED | - |
| HFP-02-AC-12-04 | 04,10 | - | - | rollback drill/export | - | BLOCKED | operator未設定 |

## 5. Blocking decisions

| Blocker ID | Decision / 実測 | Owner | 期限 | Evidence | 判定 | 未決時の安全動作 |
|---|---|---|---|---|---|---|
| HFP-02-BLK-01 | sandbox/plan/client ID owner | 未設定（sandbox申請依頼済み: 2026-08-14） | 未設定 | - | OPEN | 本番enable禁止 |
| HFP-02-BLK-02 | CREATE timeout後のmarker一意照合 | 未設定 | 未設定 | - | OPEN | 自動再CREATE禁止、人手照合 |
| HFP-02-BLK-03 | mutation反映遅延/GET照合 | 未設定 | 未設定 | - | OPEN | mutation自動retry禁止 |
| HFP-02-BLK-04 | signed/certificate bytes/content-type | 未設定 | 未設定 | - | OPEN | artifact完了扱い禁止 |
| HFP-02-BLK-05 | scanner/storage/ledger readiness | 未設定 | 未設定 | - | OPEN | 本番enable禁止 |
| HFP-02-BLK-06 | 取消UI/APIの業務採用（`ADOPT/NOT_ADOPT`） | 未設定（業務責任者） | 未設定 | - | OPEN | 未決時はcancel非公開。決定後は該当する一方の証拠だけを要求 |

## 6. Baseline finding closure

`research.md` §4.2 の defect を task/test/Demoで閉じる。コード差分だけで CLOSED にしない。

| Finding ID | Severity | 根本原因/影響 | Owner task | 必須close evidence | 状態 |
|---|---|---|---|---|---|
| HFP-02-FND-001 | P0 | source PDF未upload、公式工程不一致 | 03,04 | captured multipart hash + sandbox | OPEN |
| HFP-02-FND-002 | P0 | 静的token、期限/更新不備 | 03 | token expiry/single-flight/401 test | OPEN |
| HFP-02-FND-003 | P0 | 外部成功後DB保存、重複/孤児 | 02,04 | 100 concurrent + accepted-timeout | OPEN |
| HFP-02-FND-004 | P1 | sync transaction内外呼出し | 04,05 | transaction inactive assert | OPEN |
| HFP-02-FND-005 | P0 | file→DB部分失敗 | 06 | storage/DB failure injection | OPEN |
| HFP-02-FND-006 | P1 | artifact download例外握り潰し | 03,05,06 | error state/alert test | OPEN |
| HFP-02-FND-007 | P1 | status/binary責務混在 | 03,06 | granular client/stream test | OPEN |
| HFP-02-FND-008 | P0 | source hash上書き | 02,06 | 三hash不変test + migration | OPEN |
| HFP-02-FND-009 | P0 | SKILL_SHEET scanner誤用 | 06 | CONTRACT_PDF scan matrix | OPEN |
| HFP-02-FND-010 | P1 | certificateを.dat扱い | 06 | certificate PDF/hash/filename | OPEN |
| HFP-02-FND-011 | P1 | pollingなし | 05 | ShedLock/poll/manual sync | OPEN |
| HFP-02-FND-012 | P1 | 状態無関係send/偽成功UI | 04,07 | state UI + queue≠sent Demo | OPEN |
| HFP-02-FND-013 | P0 | entity/path/html露出 | 07 | DTO allow-list response test | OPEN |
| HFP-02-FND-014 | P1 | download no-store/filename欠落 | 06,07 | header/audit test | OPEN |
| HFP-02-FND-015 | P1 | HR manual sync可 | 05,07 | role direct API test | OPEN |
| HFP-02-FND-016 | P0 | official wire/sandbox E2Eなし | 00,03,09 | contract + sandbox evidence | OPEN |

## 7. 独立 Review findings

新規findingは次のtemplateで追記する。

| Finding ID | Severity | Task/AC | file/method/line | 再現/観測 | 影響 | 最小修正/再test | 状態 | Reviewer/日時 |
|---|---|---|---|---|---|---|---|---|
| HFP-02-REV-___ | P_ | HFP-02-__ / HFP-02-AC-__ | - | - | - | - | OPEN | - |

## 8. Test / Demo execution log

| 日時 | Task | command / 手順 | tests | failure | error | skip | provider call count | 結果 | Evidence path/注記 |
|---|---|---|---:|---:|---:|---:|---|---|---|
| 2026-08-14 | 00 | `mvn -B test -Dtest=CloudSignOpenApiFixtureSchemaTest` | 11 | 0 | 0 | 0 | 0(外部呼出なし) | PASS | `target/surefire-reports/TEST-com.ses.cloudsign.CloudSignOpenApiFixtureSchemaTest.xml`。OpenAPI再取得はcurl生bytes(147111 bytes)でSHA一致を確認 |
| 2026-08-14 | 01 | `mvn -B test -Dtest=CloudSignClientContractTest,ContractDocumentServiceImplTest,ContractDocumentApiControllerTest` | 19 | 13 | 0 | 0 | Mock(0実) | RED(意図どおり) | 新規13件が全部defect再現でred。既存6件(ContractDocumentServiceImplTest)はgreen。surefire XML: `TEST-com.ses.cloudsign.CloudSignClientContractTest.xml` / `TEST-com.ses.service.impl.ContractDocumentServiceImplTest.xml` / `TEST-com.ses.controller.api.ContractDocumentApiControllerTest.xml`。二重send test: provider create call=2を観測 |

## 9. Sandbox / production operation ledger

| 日時 | 環境 | operation ID | local doc | provider doc/file（マスキング可） | source hash | signed hash | certificate hash | status timeline | 外部件数 | 判定/承認者 |
|---|---|---|---|---|---|---|---|---|---:|---|
| - | sandbox | - | - | - | - | - | - | - | - | BLOCKED |

## 10. Release decision

| Gate | 条件 | 現在 |
|---|---|---|
| G1 | HFP-02-00〜08、定向test/verify-like-ci skip0 | NOT_STARTED |
| G2 | HFP-02-09 sandbox閉ループ/障害注入 | BLOCKED |
| G3 | P0/P1 OPEN/BLOCKED=0 | FAIL（baseline OPEN） |
| G4 | scanner/storage/ledger/ShedLock/alert/kill switch readiness | NOT_STARTED |
| G5 | production canary/rollback drill/運用承認 | BLOCKED |
| G6 | merge済みcommitのmerge delta/共有consumer/main回帰を独立Review | NOT_STARTED |
| **総合** | G1〜G6全PASS | **NOT_READY** |

本番 `cloudsign.enabled=true` は総合 PASS 後のみ許可する。
