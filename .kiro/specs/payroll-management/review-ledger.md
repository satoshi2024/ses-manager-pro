# HFP-01 Review Ledger

実装・Demo・独立Reviewの証跡を追記する台帳。秘密情報・給与金額・氏名・外部employee ID・raw API response・Cookieは記載しない。

## 台帳運用規約

1. 過去のRun/Review Roundを削除・並べ替え・上書きしない。訂正は新しい行で旧記録を参照する。
2. Run IDは`HFP-01-RUN-YYYYMMDD-NN`、Review Roundは`HFP-01-REVIEW-YYYYMMDD-NN`、Findingは`HFP-01-REV-NNN`とする。
3. merge前の独立Review合格は`REVIEWABLE`とする。`PASS`はmerge済みcommitとmerge deltaを独立Reviewした場合だけ使う。未実施は`NOT-RUN`、外部条件不足は`BLOCKED`、失敗は`FAIL`とする。
4. `skip`を`PASS`へ含めない。command、実行数、失敗数、skip数、終了codeを記録する。
5. file証跡はrepository相対pathと行番号またはmethod名、外部実行はmask済みrequest ID/日時/件数だけを記録する。
6. Acceptance、Task、FindingのIDは再採番しない。

---

## 実装Runテンプレート（この区切りから複製して末尾へ追記）

### HFP-01-RUN-YYYYMMDD-NN

| 項目 | 値 |
|---|---|
| 実装担当 | `<担当>` |
| worktree / branch | `<絶対パス>` / `<branch>` |
| base / head | `<base SHA>` / `<head SHA>` |
| 開始 / 終了（JST） | `<YYYY-MM-DD HH:mm>` / `<YYYY-MM-DD HH:mm>` |
| 公式OpenAPI固定commit | `52c69a6819ef14979a31b342123df816cb72c742`（存在確認: `<PASS/FAIL>`） |
| freee test事業所 | `<READY/BLOCKED>`（秘密値・事業所名は書かない） |
| Docker / Node | `<READY/BLOCKED>` / `<READY/BLOCKED>` |
| dirty差分の取扱い | `<開始時差分と保全方法>` |

#### 外部preflight

| 条件 | 状態 | 非機微証跡 / 次アクション |
|---|---|---|
| OAuth app / redirect URI | `<READY/BLOCKED>` | `<設定画面確認日時または必要担当>` |
| HR給与・賞与権限 | `<READY/BLOCKED>` | `<権限確認結果>` |
| company_admin test user | `<READY/BLOCKED>` | `<role確認結果>` |
| 計算済み給与/賞与test period | `<READY/BLOCKED>` | `<年月のみ。金額・氏名禁止>` |
| app審査/private運用条件 | `<READY/BLOCKED>` | `<判断記録>` |

#### Task実行証跡

| Task | 状態 | 変更file / method | Test command・結果（run/fail/skip/code） | Demo | Rollback/失敗判定 |
|---|---|---|---|---|---|
| HFP-01-001 | `<PASS/FAIL/BLOCKED/NOT-RUN>` | `<...>` | `<...>` | `<baseline>` | `<...>` |
| HFP-01-002 | `<...>` | `<...>` | `<...>` | `<schema>` | `<...>` |
| HFP-01-003 | `<...>` | `<...>` | `<...>` | `<OAuth lifecycle>` | `<...>` |
| HFP-01-004 | `<...>` | `<...>` | `<...>` | `<contract/pagination/error>` | `<...>` |
| HFP-01-005 | `<...>` | `<...>` | `<...>` | `<mapping/BP/company>` | `<...>` |
| HFP-01-006 | `<...>` | `<...>` | `<...>` | `<salary/bonus>` | `<...>` |
| HFP-01-007 | `<...>` | `<...>` | `<...>` | `<security/cache/audit>` | `<...>` |
| HFP-01-008 | `<...>` | `<...>` | `<...>` | `<desktop/390px/a11y>` | `<...>` |
| HFP-01-009 | `<...>` | `<...>` | `<...>` | `<S11/S15/CashFlow>` | `<...>` |
| HFP-01-010 | `<...>` | `<...>` | `<...>` | `<automated gates>` | `<...>` |
| HFP-01-011 | `<...>` | `<...>` | `<...>` | `<sandbox/handoff>` | `<...>` |

#### 自動gate集計

| Gate | Command | 実行数 | Failure | Skip | Exit | 状態 | 証跡 |
|---|---|---:|---:|---:|---:|---|---|
| Task対象test | `<...>` | `<n>` | `<n>` | `<n>` | `<n>` | `<...>` | `<report path>` |
| Security/privacy/audit | `<...>` | `<n>` | `<n>` | `<n>` | `<n>` | `<...>` | `<report path>` |
| MySQL migration smoke | `<...>` | `<n>` | `<n>` | `<n>` | `<n>` | `<...>` | `<report path>` |
| verify-like-ci | `scripts/verify-like-ci.ps1` | `<n>` | `<n>` | `<n>` | `<n>` | `<...>` | `<report path>` |

#### Demo / sandbox E2E

| Scenario | Desktop | 390px | Sandbox | 状態 | 非機微証跡 / 観測結果 |
|---|---|---|---|---|---|
| 接続・事業所検証 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| 対応付け・BP拒否 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| 給与・賞与・計算中・0件 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| refresh・再認可 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| revoke成功・既失効・一時障害 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| keyboard/a11y | `<...>` | `<...>` | `N/A` | `<...>` | `<...>` |

#### 実装担当の残件

| ID | Requirement/AC | 状態 | 内容 | Owner / 外部条件 | 再実行command |
|---|---|---|---|---|---|
| `<HFP-01-RUN-ISSUE-NN>` | `<HFP-01-Rxx / ACxx>` | `<OPEN/BLOCKED>` | `<...>` | `<...>` | `<...>` |

---

## 独立Review Roundテンプレート（この区切りから複製して末尾へ追記）

### HFP-01-REVIEW-YYYYMMDD-NN

| 項目 | 値 |
|---|---|
| Reviewer | `<実装担当と別の担当>` |
| 対象Run | `<HFP-01-RUN-...>` |
| base / reviewed head | `<base SHA>` / `<head SHA>` |
| merge状態 / merge commit | `<PRE_MERGE/MERGED>` / `<N/Aまたはmerge SHA>` |
| 開始 / 終了（JST） | `<YYYY-MM-DD HH:mm>` / `<YYYY-MM-DD HH:mm>` |
| 独立再実行環境 | `<OS/JDK/Maven/Node/Docker。秘密情報禁止>` |
| Verdict | `<REVIEWABLE/PASS/FAIL/BLOCKED>` |

#### Acceptance trace

| Acceptance | 状態 | Requirement | Owner task | Source/Test/Demo/Sandbox証跡 | Reviewer所見 |
|---|---|---|---|---|---|
| HFP-01-AC01 | `<PASS/FAIL/BLOCKED>` | HFP-01-R01, R02 | HFP-01-001,003,004 | `<...>` | `<...>` |
| HFP-01-AC02 | `<...>` | HFP-01-R02 | HFP-01-003 | `<...>` | `<...>` |
| HFP-01-AC03 | `<...>` | HFP-01-R02, R03 | HFP-01-002,003 | `<...>` | `<...>` |
| HFP-01-AC04 | `<...>` | HFP-01-R03 | HFP-01-003 | `<...>` | `<...>` |
| HFP-01-AC05 | `<...>` | HFP-01-R03 | HFP-01-003 | `<...>` | `<...>` |
| HFP-01-AC06 | `<...>` | HFP-01-R04, R06 | HFP-01-004,005 | `<...>` | `<...>` |
| HFP-01-AC07 | `<...>` | HFP-01-R05 | HFP-01-004,006 | `<...>` | `<...>` |
| HFP-01-AC08 | `<...>` | HFP-01-R04, R05 | HFP-01-005,006 | `<...>` | `<...>` |
| HFP-01-AC09 | `<...>` | HFP-01-R06 | HFP-01-004 | `<...>` | `<...>` |
| HFP-01-AC10 | `<...>` | HFP-01-R07 | HFP-01-004 | `<...>` | `<...>` |
| HFP-01-AC11 | `<...>` | HFP-01-R08 | HFP-01-007 | `<...>` | `<...>` |
| HFP-01-AC12 | `<...>` | HFP-01-R08, R09 | HFP-01-007 | `<...>` | `<...>` |
| HFP-01-AC13 | `<...>` | HFP-01-R10 | HFP-01-008 | `<...>` | `<...>` |
| HFP-01-AC14 | `<...>` | HFP-01-R11, R12 | HFP-01-009,010 | `<...>` | `<...>` |
| HFP-01-AC15 | `<REVIEWABLE/PASS/FAIL/BLOCKED>` | HFP-01-R09, R12 | HFP-01-010,011 | `<merge前E2E/Review、merge後delta/consumer/main回帰>` | `<...>` |

#### Error / recovery matrix再検証

| Case | 期待処理 | Test/再現 | 実結果 | 状態 |
|---|---|---|---|---|
| expired access token | row-lock refresh 1回後に1回再送 | `<...>` | `<...>` | `<...>` |
| invalid_grant / re_authorization_required | `REAUTH_REQUIRED`、自動retryなし | `<...>` | `<...>` | `<...>` |
| user/app/plan permission | 日本語next action、自動refreshなし | `<...>` | `<...>` | `<...>` |
| 429 | Retry-After尊重、上限あり | `<...>` | `<...>` | `<...>` |
| 5xx / timeout | bounded retry、空成功禁止 | `<...>` | `<...>` | `<...>` |
| root欠落 / 反復page / invalid amount | provider契約エラー、有限終了 | `<...>` | `<...>` | `<...>` |
| revoke一時障害 | local接続保持、再試行可能 | `<...>` | `<...>` | `<...>` |

#### Security / privacy matrix再検証

| Subject/検査 | Page | Read API | Link/Revoke | CSRF | no-store | Audit/非漏洩 | 状態 |
|---|---|---|---|---|---|---|---|
| 管理者 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| HR | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| 営業 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| マネージャー | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| 要員 | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` | `<...>` |
| 未認証 | `<redirect/login>` | `<401/redirect contract>` | `<denied>` | `N/A` | `<...>` | `<...>` | `<...>` |

#### Findings

| ID | Severity | Status | Requirement/AC | Evidence / 再現 | Expected / Impact | 最小修正 / 再test |
|---|---|---|---|---|---|---|
| HFP-01-REV-001 | `<P0/P1/P2/NOTE>` | `<OPEN/FIXED_BY_IMPLEMENTER/VERIFIED_CLOSED/REJECTED/DEFERRED>` | `<HFP-01-Rxx / ACxx>` | `<file:line, command, actual>` | `<...>` | `<...>` |

Findingがない場合は上の例示行を削除し、`Findingなし（Reviewer再実行済み）`と記す。過去RoundのFinding行は削除しない。

#### Verdict根拠

- 未達Acceptance: `<なし / ID一覧>`
- 未解決P0/P1: `<0 / ID一覧>`
- 未管理Acceptance: `<0 / ID一覧>`
- 延期P2/NOTE: `<なし / ID、発注者承認、owner、期限、release影響>`
- 未実施/skip必須gate: `<なし / gateと理由>`
- rollback/feature disable手順の検証: `<PASS/FAIL/BLOCKED + 証跡>`
- 最小の次アクション: `<なし / Owner・条件・再実行command>`
- 最終Verdict: `<REVIEWABLE/PASS/FAIL/BLOCKED>`（`PASS`はMERGED commitのみ）
