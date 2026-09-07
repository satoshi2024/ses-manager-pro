# S-NF01〜S-NF10 最終統合 Gate / Evidence

## 1. 統合範囲と固定値

| 項目 | 値 |
|---|---|
| 統合 worktree | `C:\\work\\ses-snf-integration` |
| 統合 branch | `codex/snf-integration-gate` |
| 固定 base | `996289c00983bfccd0b75c4d3bdd3dcc26904136` |
| 通常 checkout | `C:\\work\\ses-manager-pro` は変更なし |
| push / PR | 実施なし |
| 判定日時 | 2026-09-07 Asia/Tokyo |

入力 branch はすべて固定 base の descendant、worktree clean、未commit変更なしであることを確認した。

| 入力 branch | 最終 SHA |
|---|---|
| `codex/snf-status-audit` | `0564b9d5fe8aaf2d9684b167a3a1650b2dda5c76` |
| `codex/nf02-review-remediation` | `723016a46b96254da95fccb969dd191e92ddc8b9` |
| `codex/nf03-review-remediation` | `798e6dbff8143b8697bf98313c29dca677f4c98c` |
| `codex/nf05-review-remediation` | `cc82342d41d2bad928325e15eb231e747c1112d6` |
| `codex/nf08-review-remediation` | `cde28784da9282f2e7112f8176da96529832b31e` |
| `codex/nf10-review-remediation` | `3eb01b1cd4d4686994f005f0b0f30182ffac0f2c` |

## 2. Merge 記録

指定順序で `git merge --no-ff` を実行した。すべて通常の ort merge で、conflict marker・manual semantic resolution は発生しなかった。

| 順序 | branch | 統合 commit |
|---:|---|---|
| 1 | `codex/snf-status-audit` | `08fcd8a98e23ee95fd5d9489611f79cb937a66cb` |
| 2 | `codex/nf02-review-remediation` | `77a355b673fcb405d3f770903d00e713ea0ab3f1` |
| 3 | `codex/nf03-review-remediation` | `46b74608daf37b0a781515ce596bfbae81f8220c` |
| 4 | `codex/nf05-review-remediation` | `185c994aaa4aecbc9571e729f9af91b22b03144a` |
| 5 | `codex/nf08-review-remediation` | `fe184984d674d8f9073e2ee22bb0680c29691f9e` |
| 6 | `codex/nf10-review-remediation` | `6d2953fc5082f7a447dc5727da3d7ae567a39187` |

NF02/NF03/NF05/NF08/NF10 の変更 file pairwise intersection は全組合せで `none` だった。従って共有コードの上書きや推測による conflict 解消はない。

## 3. Migration / schema / shard 監査

- `V150__service_request_atomic_sequence.sql` は1件だけ存在し、NF-02専用の `t_service_request_sequence` を作成する。
- `V151__certification_continuity_group.sql` は1件だけ存在し、NF-03専用の `t_certification_continuity_group` と整合性制約を作成する。
- V150 と V151 の順序は正しく、V1 は変更していない。既存 migration の書き換えもない。
- V150/V151 の存在・一意性・内容・順序を `FlywayMigrationVersionResolutionTest` に統合断言し、同テストと `MySqlTestShardInventoryTest` は `2 / 0 / 0 / 0` で PASS。
- `schema-service-desk-h2.sql` と V150、`schema-certification-learning-skill-gap-h2.sql` と V151 は、それぞれ新規 table、unique/index、FK/check、default を同期している。
- `@Tag("mysql")` の実ファイル 58 件と shard inventory 登録 58 件を比較し、missing 0 / duplicate 0 / extra 0。新規 NF02/NF03 smoke・concurrency test の登録漏れはない。

## 4. Targeted regression

| Feature | 結果 |
|---|---|
| NF-02 | 13 / 0 / 0 / 0 PASS |
| NF-03 | 39 / 0 / 0 / 0 PASS |
| NF-05 | 116 / 0 / 0 / 0 PASS（browser-tagged E2E は別 gate） |
| NF-08 | 38 / 0 / 0 / 0 PASS |
| NF-10 | 30 / 0 / 0 / 0 PASS |
| migration / shard inventory | 2 / 0 / 0 / 0 PASS |

## 5. Required Gate 実測結果

| Gate | 実コマンド / 結果 | 判定 |
|---|---|---|
| diff check | `git diff --check` | PASS |
| fast | `mvn.cmd test` → 3670 tests / 2 failures / 7 errors / 0 skipped | **FAIL** |
| MySQL | `mvn.cmd test -Pmysql-tests` → 118 tests / 0 failures / 1 error / 116 skipped | **BLOCKED / FAIL** |
| performance | `mvn.cmd test -Pperformance-tests` → 1 / 0 / 0 / 0 | PASS |
| verify-like-ci | `scripts\\verify-like-ci.ps1` → exit 1、Dockerなしで前提チェック停止 | **BLOCKED / FAIL** |
| direct browser profile | `mvn.cmd test -Pbrowser-tests` → 15 tests / 0 failures / 15 errors / 0 skipped | **FAIL / BLOCKED** |

fast の内訳は次のとおり。

- loopback 起因の7 errors: `PinningHttpsTransportTest`、`PrometheusScraperLabE2ETest`、`CapacityBaselineScriptTest` 3件、`WebhookNotifierLoopbackIntegrationTest` 2件。
- NF-05統合回帰: `ExternalApiReadSnapshotIntegrationTest` が旧期待値 `募集中` に対し公開コード `OPEN` を返した。
- NF-10統合回帰: `TestIsolationAuditTest` が新規 `ManagementReportDeliveryApiControllerTest` と `ReportDeliveryTransactionIntegrationTest` を非transactional SpringBootTest として検出した。

後2件は feature semantics に関係するため、統合 branch で推測修正していない。NF-05/NF-10 の feature AI で修正し、各 branch の最終 SHA を更新したうえで再統合する必要がある。

MySQL は Testcontainers が Docker 環境を発見できず、`ConcurrentUpdateTest` が `IllegalState: Previous attempts to find a Docker environment failed` となった。116 skipped は許容済み skip ではなく、CI 契約違反として FAIL 扱いにした。

## 6. NF04 real Browser Gate

Chrome extension に接続し、Chrome セッションを作成して `http://localhost:8080/login` を開いた。しかし本機の loopback 制約により実アプリ Tomcat を起動できず、Chrome で得られたのは機能ページではなく `オフライン | SES Manager Pro` の offline fallback だけだった。H2/test classpath を指定した起動も `Unable to establish loopback connection` で Tomcat 起動前に停止した。

従って次の証跡は未取得である。

- Cache Storage の全 entry URL、route cache 0、static allow-list の実測。
- 旧 cache cleanup。
- logout / user switch 後の旧 IndexedDB queue 不可視。
- 30日超過 record の送信抑止。
- 409 diff UX の no auto-merge / no auto-retry。

unit test、静的 probe、offline fallback は real Browser evidence の代替にしない。NF04 は **BROWSER_BLOCKED** を維持する。

## 7. 最終状態（implementation と production approval を分離）

| NF | Implementation / automated evidence | Independent Review | Production approval | 最終状態 |
|---|---|---|---|---|
| NF-01 | 既存実装・既存証跡 PASS | 既存独立 Review PASS | 既存 COMPLETE 記録を維持 | COMPLETE（再昇格ではない） |
| NF-02 | targeted PASS。全体 fast/MySQL は未PASS | PENDING | Owner/DG-02 未確定、未承認 | IMPLEMENTED / INDEPENDENT_REVIEW_PENDING |
| NF-03 | targeted・V151/H2 断言 PASS | 独立再Review PENDING | NF-07等 release gate 未完 | IMPLEMENTED / INDEPENDENT_REVIEW_PENDING |
| NF-04 | automated/static evidence は PASS | PENDING | Browser evidence 未取得 | IMPLEMENTED / BROWSER_BLOCKED / INDEPENDENT_REVIEW_PENDING |
| NF-05 | targeted PASSだが統合 snapshot 回帰 OPEN | PENDING | mock/test限定、production enablement不可 | IMPLEMENTATION_BLOCKED / PRODUCTION_BLOCKED |
| NF-06 | 実装未着手 | N/A | CANDIDATE | DISCOVERY（未完） |
| NF-07 | 実装未着手 | N/A | CANDIDATE | DISCOVERY（未完） |
| NF-08 | R-NF08 implementation evidence PASS、flag OFF | Plan CONDITIONAL、実装 Review evidenceあり | 本番AI gate BLOCKED、`ai.management-copilot-enabled=false`、`ai.external-send-enabled=false` | CONDITIONAL_PASS / PRODUCTION_BLOCKED |
| NF-09 | 対象実装 evidence PASS、全体 gate 未PASS | **PENDING（PASSではない）** | 未承認 | IMPLEMENTED / INDEPENDENT_REVIEW_PENDING |
| NF-10 | targeted PASSだが隔離監査回帰 OPEN | PENDING | browser screenshotなし | IMPLEMENTATION_BLOCKED / INDEPENDENT_REVIEW_PENDING |

NF-02〜NF-10 の feature tasks は、今回の全量 Gate、Browser/MySQL evidence、独立 Review の未達を理由に COMPLETE へ変更していない。CANDIDATE、DISCOVERY、CONDITIONAL_PASS、INDEPENDENT_REVIEW_PENDING、PRODUCTION_BLOCKED を COMPLETE として扱わない。

## 8. 未解決 blocker と Review 条件

1. Docker Desktop を利用できる標準環境で MySQL gate を再実行し、0 failure / 0 error / 0 skipped を確認する。
2. loopback を許可した標準環境で fast、browser profile、実アプリ Chrome Gate を再実行する。
3. NF-05 は公開 status code 変更と既存 snapshot test の期待値契約を feature AI が解決する。
4. NF-10 は新規 SpringBootTest の transaction / 明示 allow-list 契約を feature AI が解決する。
5. NF04 は実 Chrome/Edge で指定された Cache Storage、IndexedDB、expiry、409 UX の screenshot/log evidence を取得する。
6. NF-02/NF-03/NF-04/NF-05/NF-08/NF-09/NF-10 の独立 Review が、それぞれ固定 Head に対して PASS を記録する。

現時点は独立 Review を最終提出できる状態ではない。修正後の再統合、全量 Gate、NF04 Browser evidence、独立 Review PASS が揃って初めて production approval の判断対象となる。
