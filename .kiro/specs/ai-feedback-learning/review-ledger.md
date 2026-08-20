# Review Ledger — ai-feedback-learning (S17)

## 1. 現行判定

| 項目 | 値 |
|---|---|
| spec | `ai-feedback-learning` |
| handbook | `v2.0` |
| state | `IMPLEMENTER COMPLETE`（T109〜T115。独立Review待ち） |
| base | `9d2c229b6fe5033f1cc8275da116f65e98f2cd59` |
| head | T111〜T115 working tree（T110 Head `0e12c894`） |
| merge | `main` に T109〜T110 が入っている。T111〜T115 は本commit |
| latest review | Round 1（独立、対象 T109 only） |
| verdict | T109 intermediate **PASS**。T111〜T115 は独立Review前。spec総合 **NOT REVIEWABLE**（独立Review未実施 + `GATE-S17-G10-PROD`） |
| issue count | P0=0 / P1=0 / P2=3（FIXED_BY_IMPLEMENTER、VERIFIED_CLOSEDはReviewのみ） |
| next action | 独立Review。次specは開始しない。本番 PASS は出さない |

## 2. OPEN Issue Register

| issue ID | severity | AC | file:line | reproduction | impact | minimum fix | regression scope | state | fix commit | verified by |
|---|---|---|---|---|---|---|---|---|---|---|
| ai-feedback-learning-R1-P2-01 | P2 | R4.1 / allowlist §4.3 / R3.3 | `g10-pii-allowlist.md` workLocation | `t_project.work_location` に番地 | T111 が番地を外部送信 | §6 grain=prefecture-municipality | T111 canary | FIXED_BY_IMPLEMENTER（未verify） | `214c6852` | Review 待ち |
| ai-feedback-learning-R1-P2-02 | P2 | R2.2 / design §5.1 | outcome EARLY_EXIT 表 | `status=解約` かつ満了相当 | 満了解約が早期離場 | `occurred_at < original_end_date`。当日解約は除外 | T112 fixture | FIXED_BY_IMPLEMENTER（未verify） | `214c6852` | Review 待ち |
| ai-feedback-learning-R1-P2-03 | P2 | T109 L0 非交差 | `AiG10AllowlistDocumentTest` | `engineer.age` 追加でも JSON 完全一致は通る | 将来の allowlist 追加を止めない | design §5.2 対応表 + prefix token | `AiG10AllowlistDocumentTest` | FIXED_BY_IMPLEMENTER（未verify） | `214c6852` | Review 待ち |

## 3. Closed/Deferred Issue

| issue ID | final state | root cause | fix commit | verification evidence | closed round | reopen condition |
|---|---|---|---|---|---|---|
| GATE-S17-G10-PROD | DEFERRED | 実provider DPA/region/署名は本番gate | — | T109がmock/rule既定を記録 | T109 | 発注者がDPAと署名を閉じたとき |

owner: 発注者 / security / HR / product owner。期限: 本番release前。T115 Mの本番PASSを阻害する。開発task T110〜T114は阻害しない。

## 4. 最新Review Packet

```text
REVIEW PACKET（Review側でgitから再固定）
- handbook version: v2.0
- spec/tasks: T109 のみ（T110〜T115 は Head に diff なし）
- base: 9d2c229b6fe5033f1cc8275da116f65e98f2cd59
- head: 835c8c867de4b33cd12f04251980719a555495ef（origin/main と一致、worktree clean）
- merge: main に入っている（T109 docs commit）
- changed files: 9 files / +913 / -4（src/main なし）
- requested verdict: intermediate（T109）
- 最終PASS対象ではない（T110〜T115未実装、M未実施）
```

Review は git を正とした。実装ledgerの「未commit Head」は破棄。

## 5. Requirements Trace

| requirement/AC | customer effect | implementation | automatic test | Demo | unverified | verdict |
|---|---|---|---|---|---|---|
| 前提 mock/rule | 実データが外部AIへ出ない | G10決定、`productionExternalSend=false` | `AiG10AllowlistDocumentTest` | 決定記録 | 実provider未使用 | T109 PASS（中間） |
| R4.1 allowlist/mask | 氏名・連絡先等が送信されない | `g10-pii-allowlist.md` §4-6。P2-01 grain | 非交差 + workLocation grain | 文書 | T111でgateway | T109 PASS。P2-01 文書修正 |
| R4.2 provider確認 | 送信項目・保存期間・region・opt-outが見える | §7 表 | provider retention assert | 文書 | DPA未締結 | T109 PASS |
| R3.1 metric | version別採用率等が定義される | §8 | JSON metrics | 文書 | T113/T114 | T109 PASS |
| R3.3 segment | 少数・機微属性を出さない | min-segment=5、禁止属性非交差 | segment軸assert | 文書 | T114 | T109 PASS |
| R2.2 EARLY_EXIT | 早期離場の口径 | P2-02: 当初終了日前のみ | JSON outcomes.earlyExit | 文書 | T112 fixture | T109 文書修正 |
| R5 canary | canaryがrequest/log/DBに出ない | canary文字列を予約 | markdownに固定 | 文書 | T111/T115 | T109 reserved |
| design §5.2 禁止属性 | matching/segmentに使わない | 対応表 + prefix token（P2-03） | L0 bindings test | 文書 | — | T109 文書/L0修正 |

## 6. 横断契約

T109は本番schema/APIを変えない。T110以降は `design.md` 決定表と本allowlistに従う。

### 6.1 Scope consumer inventory

T109対象外。T114で作成する。

### 6.2 Temporal/NULL matrix

`design.md` §5.1 を採用。逸脱なし。未判断feedbackは却下ではない。EARLY_EXIT は当初終了日前のみ（P2-02）。

### 6.3 Transaction/cache matrix

T109対象外。

### 6.4 Migration matrix

T109はDDLなし。T110で正式migration **V108** を追加。欠番埋めなし。G0のため tenant 列なし。ACTIVE一意は生成列 `active_use_case`。

## 7. Test Evidence

| level | command | environment | tests | failures | errors | skipped | exit | commit | executor |
|---|---|---|---:|---:|---:|---:|---:|---|---|
| L0 | `mvn test -Dtest=AiG10AllowlistDocumentTest,SpecDispatchConsistencyTest` | JDK17 / Head `835c8c86` | 16 | 0 | 0 | 0 | 0 | `835c8c86` | Review 再実行 |
| L0 | `git diff --check` | Head `835c8c86` | — | 0 | 0 | 0 | 0 | `835c8c86` | Review |
| L0 | 同上（P2-01〜03 修正後） | 作業tree | 17 | 0 | 0 | 0 | 0 | `214c6852` | implementer |
| L0 | `mvn test -Dtest=AiFeedbackSchemaContractTest` | JDK17 | 1 | 0 | 0 | 0 | 0 | 本commit | implementer |
| L1-L3 | `mvn test -Dtest=AiFeedbackLearningSchemaTest` | H2 / test profile | 6 | 0 | 0 | 0 | 0 | 本commit | implementer |
| L0 | `SpecDispatchConsistencyTest` + `MySqlTestShardInventoryTest` + G10 | JDK17 | 18 | 0 | 0 | 0 | 0 | 本commit | implementer |
| L3 | `mvn test -Pmysql-tests -Dtest=FlywayAiFeedbackSchemaSmokeTest` | ローカル Docker なし | 1 | 0 | 0 | 1 | 0 | 本commit | implementer（`disabledWithoutDocker`。CI shard-1） |
| L4 | `mvn test` | JDK17 / H2 / excludedGroups=`mysql \| performance \| browser` | 2506 | 0 | 0 | 0 | 0 | 本commit | implementer |
| L0 | `git diff --check` | 作業tree | — | 0 | 0 | 0 | 0 | 本commit | implementer |

L4 checkpoint: T115 完了。除外は pom 既定の mysql/performance/browser。MySQL smoke はクラスと shard-1 登録済み。ローカル Docker なしのため未実行。本番 PASS は `GATE-S17-G10-PROD` まで出さない。

## 8. Demo Evidence

| Demo ID | role/data | viewport/environment | steps | expected | actual | evidence | verdict |
|---|---|---|---|---|---|---|---|
| T109-G10 | 主実装が推奨既定を記録 | spec文書 | allowlist・DPA表・metricを正本化 | mock/rule既定、禁止属性と非交差、provider別保存期間 | `g10-pii-allowlist.md` / `decision-log.md` | 本ledger | PASS（開発baseline） |
| T110-ACTIVE | 2 SHADOW を同時 ACTIVE 昇格 | H2 | `promoteToActive` を並列実行 | 成功1 / 409が1 / ACTIVEは1 | `AiFeedbackLearningSchemaTest` | 本ledger | PASS |
| T111-PII | canary/番地/injection | H2 gateway | allowlist外・番地・`[TASK:]` を投入 | outboundに出ない / TASK無効 | `AiExecutionGatewayPiiTest` | 本ledger | PASS |
| T112-OUTCOME | 成約・解約 | H2 | 重複WIN・当日解約 | WIN 1件 / 当日は EARLY_EXIT なし | `AiFeedbackOutcomeTest` | 本ledger | PASS |
| T113-PROMOTE | FAILED version | H2 | 自動昇格せず承認のみ | FAILEDは409。rollback後 run.version 不変 | `AiOfflineEvaluationTest` | 本ledger | PASS |
| T114-DASH | 営業/HR/管理者 | MVC | dashboard / cost / segment | HR 403。costは管理者のみ。少数segment非表示 | `AiEvaluationApiControllerTest` + PageRendering | 本ledger | PASS |
| T115-L4 | mock既定 | `mvn test` | 既存AI回帰 | 2506/0/0/0 | surefire | 本ledger | PASS（開発）。本番はG10 |
| GATE-S17-G10-PROD | security/HR/PO署名 | 本番前 | DPA・region・opt-out承認 | 実送信許可 | 未実施 | — | 本番gate |

## 9. Round履歴

### Round 1（独立Review、対象 T109 only、Head `835c8c86`）

```text
ai-feedback-learning / Round 1 / handbook v2.0
Base 9d2c229b / Head 835c8c86 / origin/main 一致 / worktree clean
対象固定: T109 only。T110-T115 は Head に実装なし。
P0=0, P1=0, P2=3 (R1-P2-01 workLocation粒度 / R1-P2-02 EARLY_EXIT=全解約 / R1-P2-03 L0がdesign表をparseしない)
必須test=L0 Allowlist 6/0/0/0 + SpecDispatch 10/0/0/0（Review再実行、commit 835c8c86）、git diff --check 0
skip=L4/MySQL/browser（T109対象外、次checkpoint=T115）
Demo=G10文書。release gate=GATE-S17-G10-PROD（DEFERRED）
T109 intermediate=PASS。spec総合=NOT REVIEWABLE。
次task=T110 V108 DDL。次spec=開始しない。
```

P2 は T110 DDL の blocker ではない。P2-01〜03 は `214c6852` で文書/L0修正済。P2-01 の grain 実装は T111、P2-02 の fixture は T112。

## 10. 転記用最終結論

```text
ai-feedback-learning / handbook v2.0
T109 intermediate=PASS（Head 835c8c86）。P2=3 FIXED_BY_IMPLEMENTER（214c6852）。
T109〜T115 implementer complete。L4 2506/0/0/0。spec総合=NOT REVIEWABLE（独立Review未実施、GATE-S17-G10-PROD）。
次task=独立Review。次spec=開始しない。本番 PASS は出さない。
```

## Task log

| task | requirements | 変更file | test | Demo | commit | risk |
|---|---|---|---|---|---|---|
| T109 | 前提, R3.1, R3.3, R4.1, R4.2, R5 | `g10-pii-allowlist.md`, `g10-allowlist.json`, `design.md`（ポインタ）, `tasks.md`, `decision-log.md`, `spec-execution-ledger.md`, expansion `README.md`, `review-ledger.md`, `AiG10AllowlistDocumentTest.java` | L0 AllowlistDocument, `git diff --check` | G10推奨既定記録 | `835c8c86` | 実DPA未取得。既存Gemini経路のdescription/fullName leakはT111まで残る。本番送信はG10で禁止 |
| T110 | R1.1–R1.3, §5.3 UNIQUE/CAS | `V108__ai_feedback_learning.sql`, H2 schema, entities/mappers, `AiArtifactVersionService`, retention purge | L0 contract 1、H2 schema 6、Dispatch/shard/G10、git diff --check。MySQL smoke は Docker 欠如で skip（CI shard-1） | 同時 ACTIVE 昇格で片方 409 | `0e12c894` | G0 のため tenant 列なし。生成列 UNIQUE。ローカル MySQL smoke 未実行 |
| T111 | R4.1, R4.3, R5, §5.4 | `AiExecutionGateway*`, mask, WorkLocationNormalizer, rewire matching/draft/chat/ingest | L2 `AiExecutionGatewayPiiTest`, scan, ProposalDraft, git diff --check | canary/番地が outbound に出ない。injection の TASK 無効化 | 本working tree | gemini+external-send=false は mock へ fail-closed。G10 未閉じ |
| T112 | R2, §5.1 EARLY_EXIT, 冪等 | V108_1 proposal trace, feedback API, outcome hooks | `AiFeedbackOutcomeTest` | 重複 WIN 1件。当日解約は EARLY_EXIT なし | 本working tree | AIは業務状態を変更しない。feedback NULL は却下ではない |
| T113 | R3.2, §5.3 自動promotion禁止 | offline eval fixture, promoteApproved, rollback | `AiOfflineEvaluationTest` | FAILED は昇格拒否。rollback後も過去 run の version 不変 | 本working tree | shadow は ACTIVE 以外。承認は管理者 |
| T114 | R3.3, §5.2 cost/HR | `/ai/evaluation`, `/api/ai/evaluations`, i18n 4言語, menu `ai-evaluation` | `AiEvaluationApiControllerTest`, PageRendering, MessageBundle | 少数segment非表示。HR 403。cost は管理者のみ | 本working tree | 最長 prefix `/api/ai/evaluations` |
| T115 | M 回帰 | L4修正（H2 mismatch列、browser tag、PowerShell gate） | L4 `mvn test` **2506/0/0/0 BUILD SUCCESS skip 0**。MySQL smoke は Docker 欠如で未実行（CI shard-1）。browser Demo は markup + `-Pbrowser-tests` へ隔離 | mock既定の既存AI回帰。390px は `MobileResponsiveLayoutTest` `/ai/evaluation` | 本commit | 本番 PASS は GATE-S17-G10-PROD まで出さない。実Chrome Demo は browser profile |
