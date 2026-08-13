# 実装・Review 収束ハンドブック

## 1. 目的と適用範囲

本書は HFP-01〜03 の実装、子 Agent 分担、証拠採取、独立 Review、修正、再 Review、merge、release gate に適用する。目的は指摘を表面的に減らすことではなく、実装前に判断を固定し、重大欠陥を初回実装と初回 Review で見つけ、同じ論点を有限回で閉じることである。

実装者と Reviewer は「より良さそうな設計」を途中で追加してはならない。明示要件にない判断が必要なら、production code を変更する前に `requirements.md` または `design.md` の decision table を更新し、発注者判断が必要かを記録する。

## 2. 状態機械

```text
SPEC DRAFT → NOT READY → READY → IN PROGRESS → REVIEWABLE → REVIEW
                 │                                  │          ├─ PASS
                 └─ BLOCKED                         │          ├─ CONDITIONAL PASS
                                                    └──────────└─ FAIL → FIX → RE-REVIEW
```

- 実装 AI が変更できる最終状態は `REVIEWABLE`。自己成果を `PASS` にしない。
- `READY` は開始 gate と file ownership が確認済みという意味であり、実装済みという意味ではない。
- credential、sandbox、Docker、実 MySQL 等が不足すれば `BLOCKED`。未実施を mock で代替して状態を進めない。
- `CONDITIONAL PASS` は P0/P1=0 で、残件が外部環境だけに限定され、owner、期限、再実行手順、合格条件、本番 block が明記された場合だけ使う。
- 最終 `PASS` は merge 済み head に対する独立 Review だけが付与できる。

## 3. 開工前 Readiness

各 spec の最初の production 変更前に、実装 AI は次を埋める。

```text
READINESS PACKET
- program/spec/task:
- base commit / branch / worktree:
- dirty files and owner:
- requirements / acceptance IDs:
- current implementation evidence:
- official contract source / version / checked date:
- external environment and credential availability:
- provider/API/DB spike evidence:
- current Flyway latest / gaps / proposed ownership:
- allowed files / prohibited files:
- shared consumers and regression scope:
- security/privacy/data-loss risks:
- assumptions:
- blockers:
- decision: GO / STOP
```

次のいずれかに該当すれば `STOP` とする。

- 公式契約と既存実装のどちらを正とするか未決。
- 外部 API endpoint/field/status を推測しなければ実装できない。
- 同じ shared file を別 Agent が変更中である。
- migration latest または既存 dirty diff を安全に分離できない。
- 復元先の絶対識別、隔離性、dry-run、安全 snapshot が確認できない。
- acceptance を test/Demo へ追跡できない。

`STOP` の場合は調査、fixture、decision request、test plan だけを行い、production code、migration、task checkbox を変更しない。

## 4. Task 契約

各 task の着手時に次を短く提示する。task本文に既に書かれている項目は参照でよい。

```text
TASK CONTRACT
- task ID / objective:
- requirements / acceptance:
- customer-visible result:
- allowed files / prohibited files:
- existing code to reuse:
- API / DB / state / file contract:
- actor/role allow-deny matrix:
- null/missing/calculating/error behavior:
- transaction/concurrency/idempotency:
- secrets/PII/logging:
- rollback / recovery:
- test matrix and commands:
- Demo and evidence path:
- completion conditions:
```

実装中にこの契約外の判断が発生したら、暗黙に実装せず task を停止し、decision を spec に戻す。

## 5. 外部契約の固定

HFP-01/HFP-02 は次を満たさなければ provider adapter task を開始しない。

1. 公式一次資料 URL、document/schema version、確認日を `research.md` に記録する。
2. endpoint、HTTP method、query/body、required header、pagination、rate limit、error/status を表にする。
3. 実 sandbox response または公式 fixture を allow-list DTO に対応付ける。
4. repository に置く fixture は token、氏名、email、external ID、金額、契約本文を mask する。
5. 公式資料と実 response が矛盾する場合は両方を記録し、provider に確認する。都合のよい方を選ばない。
6. provider の未公開/不明 endpoint を推測して production code に入れない。

HFP-03 は MySQL version、binlog format、GTID、timezone、base snapshot、binlog interval、対象時刻の inclusive/exclusive を復元計画に固定する。local time を暗黙に UTC として扱わない。

## 6. Test と証拠の層

| Level | 目的 | 例 | 代替不可のもの |
|---|---|---|---|
| L0 | 静的整合 | compile、syntax、schema/fixture validation、`git diff --check` | 実動作 |
| L1 | 単体契約 | DTO、状態機械、暗号化、retry 判定、時刻選択 | HTTP/DB/file integration |
| L2 | component | MockRestServiceServer、MockWebServer、MockMvc、temp file、script dry-run | sandbox、実 MySQL PITR |
| L3 | infrastructure | H2/MySQL Testcontainers、隔離 filesystem、browser、concurrency | provider sandbox |
| L4 | external/operation | freee/CloudSign sandbox、隔離 PITR、restore drill | なし |
| L5 | repository gate | `verify-like-ci`、zero skip CI、merge後直接回帰 | L4 |

各 acceptance ID に、正常、拒否/異常、境界、競合/再送、必要 Level、証拠 path を最低一行ずつ持たせる。test class 名だけでなく assertion 対象を記録する。

証拠は次の形式で残す。

```text
TEST/DEMO EVIDENCE
- task / acceptance:
- commit:
- command or操作:
- environment (secretを除く):
- expected:
- actual:
- tests / failures / errors / skipped / exit code:
- artifact/log/screenshot path:
- sensitive-data scan result:
- executed by / date:
```

実 provider response body、給与額、契約本文、DB dump は証拠に貼らない。必要な場合は field 名、件数、hash、mask済み ID だけを残す。

## 7. Task Definition of Done

次をすべて満たす task だけ `- [x]` にする。

- task ID と requirements/acceptance の trace がある。
- TASK CONTRACT と実 diff が一致し、範囲外変更がない。
- 正常、拒否、境界、競合/再送、rollback の必要 test が成功した。
- 変更した public API、service、schema、file path の全 consumer を `rg` で列挙し、直接回帰を実行した。
- DB 変更は対象の導入履歴を確認し、V1 に既存定義がある対象だけ V1 を同期した。post-baseline table は V1 へ追加せず、new migration、対象 H2 schema/replay、entity、fresh/legacy MySQL smoke を同期した。
- secret/PII/raw payload scan が成功した。
- task指定 Demo が実施済み。外部環境不足なら checkbox を閉じず release gate を記録した。
- skip、未検証、既知 issue、rollback が `review-ledger.md` にある。
- `git diff --check` と task指定 command が成功した。

「コードを書いた」「compileした」「mockがgreen」「画面を開いた」だけでは完了ではない。

## 8. Review 開始契約

実装 AI は次の packet を提出する。不足時、Reviewer は推測せず `NOT REVIEWABLE` とする。

```text
REVIEW PACKET
- handbook/spec version:
- task IDs:
- base commit / head commit / merge status:
- changed files grouped by task:
- requirements/acceptance → code → test → Demo trace:
- official contract and fixture version:
- migration latest/reserved/applied:
- test/Demo evidence:
- skipped/unverified/release gates:
- known issue IDs:
- out-of-scope files:
- rollback/recovery:
- requested verdict: intermediate / final
```

working tree の Review は中間確認だけ。最終判定は commit hash で固定する。

## 9. 独立 Review 方法

Reviewer は実装対話の要約、checkbox、test 名を信用せず、次の順で確認する。

1. packet、base/head、対象 task、外部契約 version を固定する。
2. acceptance → design decision → task → diff → assertion → Demo を追跡する。
3. 変更された public contract の全 consumer を独立に検索する。
4. authorization、CSRF、scope、secret/PII、cache、transaction、concurrency、idempotency、timeout/retry、file atomicity、migration、rollback を攻撃的に確認する。
5. happy path だけでなく、401/403/404/409/422/429/5xx/timeout、null/unknown field、二重 click、二重 poll、途中失敗、再開を対象 spec の error matrix に従って確認する。
6. sandbox/隔離実機の証拠が本当に production path を通るか確認する。
7. issue register と判定を更新する。原則 Review 中に production code を修正しない。

全指摘は次の形式を必須とする。

```text
- issue ID: HFP-<nn>-R<round>-P<severity>-<number>
- severity: P0 / P1 / P2 / NOTE
- violated requirement / acceptance:
- file:line:
- reproduction (data/role/time/environment):
- expected / actual:
- customer/security/operation impact:
- evidence:
- minimum acceptable fix:
- direct regression scope:
- discovered in: original head / fix delta / pre-existing out-of-scope
```

要件 ID、再現可能性、影響のいずれかが無いものは P0/P1 にしない。好み、将来拡張、範囲外 refactor は NOTE/backlog とし、合格を block しない。

- **P0**: data破壊/復元不能、credential/給与/契約漏洩、認証回避、誤会社データ、誤文書送信、本番 DB 誤操作、migration不能。
- **P1**: 明示 acceptance 不達、主要結果誤り、重要な権限/状態/競合/外部障害欠陥、必須実機 gate の虚偽合格。
- **P2**: 限定 UX/品質/test不足。回避可能で主要 acceptance は満たす。
- **NOTE**: 非必須改善、別 spec、設計上の好み。

## 10. Issue と再 Review の収束

```text
OPEN → FIXED_BY_IMPLEMENTER → VERIFIED_CLOSED
  ├─ REJECTED（Reviewerが根拠を確認）
  └─ DEFERRED（P2/NOTEのみ、owner/期限/release影響付き）
```

- 同じ root cause を consumer ごとに別 issue として起票しない。一つの issue に affected consumers を列挙する。
- 実装 AI は `FIXED_BY_IMPLEMENTER` まで、Reviewer だけが `VERIFIED_CLOSED` にする。
- closed issue の再開には同じ head または fix delta での新しい再現証拠を必須とする。
- Round 1 は全面 Review。
- Round 2 は OPEN issue、fix diff、direct regression、変更 contract の consumer、新規導入 P0/P1 だけを見る。
- Round 3 は OPEN P0/P1 だけ。新規 P0/P1 は見落とし原因を記録する。
- Round 4 が必要になった時点で通常 Review を停止し、requirements/design/error matrix/fixture の欠陥を先に修正する。
- 別 spec の既存問題は central backlog へ分離し、現在の Review へ混ぜない。

## 11. 判定

```text
PASS: P0=0 / P1=0 / unmanaged acceptance=0 / release gates=0
CONDITIONAL PASS: P0=0 / P1=0 / release gates=<IDs>
FAIL: open blockers=<issue IDs>
NOT REVIEWABLE: missing=<packet fields>
```

最終 `PASS` には次を必須とする。

- merge済み head の独立 Review。
- acceptance trace の未管理 `UNVERIFIED` が 0。
- 必須 test failure/error が 0。skip は名称、理由、release gate がある。
- DDL は fresh/legacy 実 MySQL、UI は指定 role/desktop/390px、外部機能は sandbox、PITR は隔離復元の証拠がある。
- OPEN P0/P1 が 0。
- spec `tasks.md`、spec `review-ledger.md`、中央 `execution-ledger.md` が同じ状態を示す。

## 12. 禁止運用

- Reviewer が Review 中に要件を拡張し、その新要件違反を P1 にする。
- commit 未固定の worktree 全体を毎 round 再監査する。
- test件数、mock呼出し回数、HTTP 200だけを acceptance 証明にする。
- 外部環境不足を「環境依存なので問題なし」として PASS にする。
- P2/NOTE を無期限 blocker にする。
- 旧 head の見落としを fix delta が生んだ欠陥として記録する。
- 実装 AI と Reviewer が同一人物/対話の自己確認だけで最終 PASS を出す。
- 本番 credential、実給与、契約本文、DB dump を Review のために repository へ追加する。
