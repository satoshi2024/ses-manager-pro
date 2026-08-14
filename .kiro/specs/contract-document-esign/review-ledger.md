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
| HFP-02-02 | 01 | DONE(V109/entity/CAS/backfill) | DONE(46件/0/0/0) | DONE(MySQL fresh+legacy, H2 CAS/backfill) | NOT_STARTED | PARTIAL | 採番: S12〜S17予約(V103〜V108)と衝突したためV109へ。予約表をV110〜V115へ繰り上げ(文書のみ) |
| HFP-02-03 | 00,01 | DONE | DONE(31件/0/0/0) | DONE(multipart SHA-256一致・token一回・timeout後call=1をtestで実演) | NOT_STARTED | PARTIAL | wire契約をtyped clientで固定。旧CloudSignClientは互換facade化 |
| HFP-02-04 | 02,03 | DONE(queueSend/dispatch/checkpoint/reconciliation) | DONE(13件/0/0/0) | DONE(100同時send・timeout call=1・crash境界・stale claimをtestで実演) | NOT_STARTED | PARTIAL | mutation timeout call count=1を実証。旧send()撤去 |
| HFP-02-05 | 03,04 | DONE(sync/poll/mapping/monitor) | DONE(12件/0/0/0) | DONE(status 1→2/3、未知status、batch失敗継続、cancel非公開をtestで実演) | NOT_STARTED | PARTIAL | BLK-06未決のためcancel非公開で停止。ADOPT決定後にcancel実装 |
| HFP-02-06 | 02,03,05 | DONE(FileKind/artifact回収/ledger/三hash) | DONE(17件/0/0/0) | DONE(三PDF取得・別hash・別archive・scan停止時非公開をtestで実演) | NOT_STARTED | PARTIAL | 旧sync()/facade撤去。legacy移行・no-op・相違hash finding実装済 |
| HFP-02-07 | 04,05,06 | DONE(DTO/role/no-store/監査/UI) | DONE(controller 8件・JS syntax・page render) | DONE(role matrix・queue≠sent・結果不明runbook・三artifact UIをtestで実演) | NOT_STARTED | PARTIAL | 390px実機browser Demoはsandbox/環境なしのためHFP-02-09/10対象 |
| HFP-02-08 | 01-07 | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | NOT_STARTED | verify-like-ci skip0 |
| HFP-02-09 | 00,08 | NOT_STARTED | BLOCKED(sandbox未確認) | BLOCKED | NOT_STARTED | BLOCKED | sandbox credential/受信操作担当 |
| HFP-02-10 | 08,09 | NOT_STARTED | NOT_STARTED | BLOCKED | NOT_STARTED | BLOCKED | P0/P1=0、運用承認 |

## 4. Requirement / Acceptance trace

全 AC を先に登録する。実装時に同じ行の evidence 欄を埋め、複数 AC を「同様」で省略しない。判定履歴を残す必要がある場合は直下へ同じ AC の再検証行を追加する。

| Requirement / AC | Task | 実装 file/method | 自動test class/method | Demo / external evidence | Reviewer | 判定 | 備考/rollback |
|---|---|---|---|---|---|---|---|
| HFP-02-AC-01-01 | 00 | research.md §2.1/§2.2 | CloudSignOpenApiFixtureSchemaTest#fixtureMetaは固定OpenAPIのpinと一致する | OpenAPI version/SHA 再取得(2026-08-14, 不変) | - | VERIFIED | curl生bytes固定、gzip展開を除外 |
| HFP-02-AC-01-02 | 00,03,08 | CloudSignApiClient(create/upload/participant/get/send/download)・DTO・CloudSignTokenProvider | CloudSignClientContractTest(12) | wire契約 | - | VERIFIED | request captureでform/multipart/Bearer/順序/binary hash/call countを検証 |
| HFP-02-AC-01-03 | 03,05,07 | requireDocumentFields、CloudSignErrorClassifier | 必須field欠落schemaError test・未知status fixture | malformed/unknown fixture | - | IN_PROGRESS | schema error/error分類DONE。未知statusの業務mappingはHFP-02-05 |
| HFP-02-AC-01-04 | 00 | research.md §2.2 | fixtureMetaは固定OpenAPIのpinと一致する | version diff review(差分なし) | - | VERIFIED | 更新時はfixture/pin同時更新まで停止 |
| HFP-02-AC-02-01 | 03 | CloudSignProperties.resolveBaseUri | CloudSignPropertiesTest(6) | host matrix | - | VERIFIED | prod/sandbox公式hostのみ。HTTP/userinfo/query/fragment/path付きを拒否 |
| HFP-02-AC-02-02 | 03,08 | CloudSignTokenProvider(メモリのみ) | token test・log captureはHFP-02-08 | log/API redaction | - | IN_PROGRESS | token値はmemoryのみ・例外に含めない。log captureはHFP-02-08 |
| HFP-02-AC-02-03 | 03 | CloudSignTokenProvider | tokenSingleFlight・status401一回再取得 test | token concurrency/401 | - | VERIFIED | single-flight 1回・401再取得は一操作一回 |
| HFP-02-AC-02-04 | 03,10 | CloudSignProperties.validate(@PostConstruct) | enabled=trueでclientId欠落failClosed test | readiness fail-closed | - | IN_PROGRESS | config fail-closedDONE。scanner/storage/ledger readinessはHFP-02-10 |
| HFP-02-AC-02-05 | 00,09,10 | - | - | sandbox/preflight | - | BLOCKED | HFP-02-BLK-01 |
| HFP-02-AC-03-01 | 01,04 | ContractDocumentServiceImpl.verifySourcePdf | queueSend系test・SOURCE_HASH_CHANGED test | source preflight | - | VERIFIED | 存在/正規化path/magic/EOF/size/hash一致をqueue時とworker時に検査 |
| HFP-02-AC-03-02 | 03,04 | CloudSignApiClientImpl(4工程直列) | 公式4工程を厳密な順序で直列実行する test | source PDF wire hash | - | VERIFIED | multipartの送信原本SHA-256一致をrequest captureで証明 |
| HFP-02-AC-03-03 | 04 | doPreflightAndSend(preflight GET) | PREFLIGHT_MISMATCH test | provider IDs/preflight | - | VERIFIED | file/participant/statusを送信前にGETで再確認 |
| HFP-02-AC-03-04 | 07 | sendConfirmModal・contract-document.js | queue≠sent test | browser確認modal | - | VERIFIED | 契約番号/hash prefix/宛先/言語を確認後にJSON payloadでqueue |
| HFP-02-AC-10-02 | 04,07 | queueSendレスポンス(CloudSignOperationDto) | queue≠sent test・UI文言 | queue≠sent UI | - | VERIFIED | 「送信処理を受け付けました」で送信完了を偽装しない |
| HFP-02-AC-03-05 | 04,07 | CloudSignPayloadHasher・send_payload_sha256 | payload不一致拒否 test・SOURCE_HASH_CHANGED test | payload mismatch | - | IN_PROGRESS | queue/worker両方のhash検証DONE。UI再確認はHFP-02-07 |
| HFP-02-AC-04-01 | 02,04 | ContractDocumentMapper.casTransition/casClaim/casCheckpoint、ContractDocumentDispatchStateTest | 100 concurrent sendはHFP-02-04 | 状態CAS(version+state)を実DBで検証済 | - | IN_PROGRESS | CAS実装DONE。sendのqueue化はHFP-02-04 |
| HFP-02-AC-04-02 | 04 | TransactionTemplate checkpoint・assertNoTransaction | transaction active test | transaction inactive | - | VERIFIED | provider呼出しはtx外、checkpointは短いtx |
| HFP-02-AC-04-03 | 04 | handleApiFailure(uncertain)・verifyThenAdvance | timeout call count=1・accepted-then-timeout test | accepted-timeout call-count=1 | - | VERIFIED | mutation再実行なし。GET照合のみ |
| HFP-02-AC-04-04 | 04,09 | CloudSignReconciliationService | 2worker race test | GET/marker reconciliation | - | BLOCKED | BLK-02未PASSのためCREATE ID不明は人手照合のみ |
| HFP-02-AC-04-05 | 04 | reconcileStaleClaims | stale claim test | crash/stale claim | - | VERIFIED | 自動未実行へ戻さず結果不明へ |
| HFP-02-AC-04-06 | 04,07,10 | - | - | orphan/duplicate runbook | - | NOT_STARTED | - |
| HFP-02-AC-05-01 | 05 | CloudSignStatusMapper | status全値/未知 test | status mapping | - | VERIFIED | 0/1/2/3/4/未知を明示mapping、4は送信対象外 |
| HFP-02-AC-05-02 | 02,04,05 | DispatchState enum、V109 dispatch_state列 | ContractDocumentDispatchStateTest | 状態機械 | - | IN_PROGRESS | 工程enum/列はDONE。遷移実装はHFP-02-04/05 |
| HFP-02-AC-05-03 | 04,05 | SENDING→GET照合（再POST禁止） | SEND_STILL_DRAFT test | terminal/reminder rejection | - | IN_PROGRESS | send再実行禁止DONE。pollでのterminal逆戻り防止はHFP-02-05 |
| HFP-02-AC-05-04 | 05,06 | - | - | completed/artifact split | - | NOT_STARTED | - |
| HFP-02-AC-05-05 | 05,07,09 | - | - | `ADOPT`: cancel sandbox / `NOT_ADOPT`: route非公開＋status=3 mapping | - | BLOCKED | HFP-02-BLK-06 の相互排他decision待ち |
| HFP-02-AC-06-01 | 05 | CloudSignPollingScheduler | ShedLock annotation test | ShedLock/batch | - | VERIFIED | active行のみbatch・古い順・request scope非依存 |
| HFP-02-AC-06-02 | 05 | CloudSignSyncService・casStatusSync | commit順反転 test | manual/poll commit reversal | - | VERIFIED | GETはtx外、保存はversion CAS。逆戻りをCASで拒否 |
| HFP-02-AC-06-03 | 03,05 | handleGetFailure(backoff/failFinal) | GET429 backoff・GET4xx恒久 test | retry matrix | - | VERIFIED | 429/5xxはbounded backoff、4xxは恒久 |
| HFP-02-AC-06-04 | 04,05,09 | - | - | provider delay | - | BLOCKED | HFP-02-BLK-03 |
| HFP-02-AC-06-05 | 05,10 | CloudSignMonitor | monitor snapshot/alert判定 | metrics/alert | - | IN_PROGRESS | counter/alert判定DONE。外部monitoring接続はHFP-02-10 |
| HFP-02-AC-07-01 | 02,06 | V109 signed_pdf_sha256/certificate_sha256列、ContractDocument | 締結済hash再計算backfill test | 三hash表 | - | IN_PROGRESS | 列/entity/backfill hash再計算DONE。signed取得はHFP-02-06 |
| HFP-02-AC-07-02 | 03,05,06,09 | downloadFile/downloadCertificate(送信時file ID一致) | artifact回収 test | signed/certificate PDF | - | IN_PROGRESS | 実装DONE。sandbox実bytesはBLK-04でBLOCKEDのまま |
| HFP-02-AC-07-03 | 06 | FileKind.CONTRACT_PDF・quarantine→検証→scan→hash→ledger | CLEAN/INFECTED/UNAVAILABLE・MAGIC_EOF・CONTENT_TYPE test | scan/atomic pipeline | - | VERIFIED | fail-closedで公開しない。SKILL_SHEET不使用 |
| HFP-02-AC-07-04 | 06 | 同一hash=no-op・相違hash=旧版保持+finding | noOp・HASH_CHANGED test | same/different hash | - | VERIFIED | 二重登録なし・上書きなし |
| HFP-02-AC-07-05 | 06 | registerReceived→casArtifactSave(DB失敗はorphan補償) | DB保存失敗はfinding | storage/DB failure injection | - | IN_PROGRESS | orphan safety windowは既存DocumentService規約。保存失敗findingは実装済 |
| HFP-02-AC-07-06 | 06,07 | downloadSigned/downloadCertificate(ledger経由・別名) | download test | download matrix | - | IN_PROGRESS | 別endpoint/別名はHFP-02-07。no-store/監査もHFP-02-07 |
| HFP-02-AC-08-01 | 07 | ContractDocumentApiController sync @PreAuthorize | HR拒否 test(green化) | 5role direct API | - | IN_PROGRESS | HR sync拒否DONE。全role matrixはHFP-02-07 |
| HFP-02-AC-08-02 | 07 | assertDocumentAllowed/assertContractVisible | scope外404 test | scope外404 | - | VERIFIED | 親契約DataScope/組織scopeで404秘匿 |
| HFP-02-AC-08-03 | 07,08 | ContractDocumentListDto/DetailDto/OperationDto | DTO allow-list test | DTO allow-list | - | VERIFIED | path/renderedHtml/errorを非公開 |
| HFP-02-AC-08-04 | 06,07 | CacheControl.noStore・ApiAuditFilter isDownloadUri | no-store/attachment test | no-store/audit | - | VERIFIED | list/detail/artifactはno-store、download成功/拒否は監査対象 |
| HFP-02-AC-08-05 | 03,07,08 | - | - | log capture | - | NOT_STARTED | P0 |
| HFP-02-AC-08-06 | 07 | 既存Cookie CSRF維持 | csrf付きrequestのみ成功 | CSRF | - | VERIFIED | send/sync/cancel(非公開)は更新系としてCSRF対象 |
| HFP-02-AC-09-01 | 03,04,05 | CloudSignErrorClassifier・CloudSignApiException(uncertain) | error分類/504/timeout test | error/result-unknown matrix | - | IN_PROGRESS | client分類DONE。dispatchへの接続はHFP-02-04 |
| HFP-02-AC-09-02 | 03,05 | CloudSignRateLimiter | CloudSignRateLimiterTest(4) | token/rate/retry | - | IN_PROGRESS | budget≤800DONE。poll/syncへの接続はHFP-02-05 |
| HFP-02-AC-09-03 | 03,06 | CloudSignProperties.maxPdfBytes・streamToTempFile | download size上限 test | body/file limits | - | IN_PROGRESS | download上限DONE。upload側・binary非展開はHFP-02-04/06 |
| HFP-02-AC-09-04 | 05,06,10 | - | - | alert matrix | - | NOT_STARTED | - |
| HFP-02-AC-10-01 | 05,07 | 状態別button・sec:authorize + API PreAuthorize | 5role direct API test | state/role UI/API | - | VERIFIED | 下書き以外send不可・terminal再送不可・HR更新不可 |
| HFP-02-AC-10-02 | 04,07 | - | - | queue≠sent UI | - | NOT_STARTED | - |
| HFP-02-AC-10-03 | 04,07,10 | runbook表示・operation ID表示 | result-unknown UI test | reconciliation UI/runbook | - | IN_PROGRESS | 再送button非表示+runbookDONE。運用実行はHFP-02-10 |
| HFP-02-AC-10-04 | 07 | table-responsive・text+icon併用 | PageRenderingTest・JS syntax | desktop/390px | - | PARTIAL | 実機browser DemoはHFP-02-09/10(環境) |
| HFP-02-AC-11-01 | 01,03,04,05,08 | - | - | contract/concurrency suite | - | NOT_STARTED | - |
| HFP-02-AC-11-02 | 07,08 | - | - | controller security suite | - | NOT_STARTED | - |
| HFP-02-AC-11-03 | 06,08 | - | - | file failure matrix | - | NOT_STARTED | - |
| HFP-02-AC-11-04 | 09 | - | - | sandbox E2E | - | BLOCKED | sandbox未確認 |
| HFP-02-AC-11-05 | 02,08 | - | - | verify-like-ci skip0 | - | NOT_STARTED | - |
| HFP-02-AC-11-06 | 10 | - | - | 運用承認 | - | BLOCKED | operator未設定 |
| HFP-02-AC-12-01 | 02 | V109、schema-contract-document-h2.sql、ContractDocument | FlywayContractDocumentDispatchSchemaSmokeTest(2) | fresh/legacy MySQL | - | VERIFIED | V1/V20無編集。V109はS12予約衝突により繰り上げ採番 |
| HFP-02-AC-12-02 | 02,06 | ContractDocumentDispatchBackfill | ContractDocumentDispatchStateTest#backfill系(5) | legacy分類 | - | IN_PROGRESS | 分類5形状DONE。archive移行候補化の履行はHFP-02-06 |
| HFP-02-AC-12-03 | 04,05,10 | CloudSignDispatchScheduler/dispatchDueのenabled判定 | kill switch test | kill switch | - | IN_PROGRESS | dispatch/queue停止DONE。poll停止はHFP-02-05 |
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
| 2026-08-14 | 02 | `mvn -B clean test -Dtest=FlywayContractDocumentDispatchSchemaSmokeTest,SpecDispatchConsistencyTest,MigrationScriptIntegrityTest,ContractDocumentDispatchStateTest` | 46 | 0 | 0 | 0 | 0(外部呼出なし) | PASS | MySQL fresh/legacy(V102実形状→V109)・H2 CAS/backfill 8件・採番整合9件・migration整合27件。採番調整: S12予約V103と衝突 → HFP-02はV109、S12〜S17予約表をV110〜V115へ繰り上げ(customer-product-expansion-2026文書一式、SpecDispatchConsistencyTestが検証) |
| 2026-08-14 | 03 | `mvn -B test -Dtest=CloudSignClientContractTest,CloudSignPropertiesTest,CloudSignRateLimiterTest,CloudSignOpenApiFixtureSchemaTest,ContractDocumentDispatchStateTest,MessageBundleConsistencyTest` | 45 | 0 | 0 | 0 | MockWebServer(0実) | PASS | typed client wire契約12件(request captureでmultipart SHA-256一致)、host allow-list 6件、rate limiter 4件。旧CloudSignClientは互換facade化(service red test 6件はHFP-02-04/06の対象のままred) |

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
