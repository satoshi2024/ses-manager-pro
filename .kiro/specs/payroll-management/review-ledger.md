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

### HFP-01-RUN-20260814-01

| 項目 | 値 |
|---|---|
| 実装担当 | 実装AI（opencode / deepseek-v4-flash） |
| worktree / branch | `C:\Users\pc\Documents\ses-manager-pro-hfp-01` / `codex/hfp-01-payroll-freee` |
| base / head | `841e10aaf67deb295d5b3397321f30e9d08c0fce` / 本Run末のcommit（HFP-01-001） |
| 開始 / 終了（JST） | 2026-08-14 00:05 / 2026-08-14 01:10 |
| 公式OpenAPI固定commit | `52c69a6819ef14979a31b342123df816cb72c742`（存在確認: **PASS**、2026-08-14 GitHub API） |
| freee test事業所 | **BLOCKED**（`FREEE_CLIENT_ID`等の環境変数が未設定。秘密値は会話・repoへ不掲載） |
| Docker / Node | BLOCKED（com.docker.service停止中。Docker Desktopを起動試行済み）/ READY（v24.18.0） |
| dirty差分の取扱い | 開始時 `git status --porcelain` 空（dirtyなし）。既存変更の上書きなし |

#### 外部preflight

| 条件 | 状態 | 非機微証跡 / 次アクション |
|---|---|---|
| OAuth app / redirect URI | BLOCKED | 環境変数未設定。提供依頼（secret store/環境変数推奨） |
| HR給与・賞与権限 | BLOCKED | app権限確認はapp登録後に実施 |
| company_admin test user | BLOCKED | test事業所提供後に確認 |
| 計算済み給与/賞与test period | BLOCKED | 事業所提供後に架空data用意 |
| app審査/private運用条件 | BLOCKED | 事業所提供後に判断記録 |

#### Task実行証跡

| Task | 状態 | 変更file / method | Test command・結果（run/fail/skip/code） | Demo | Rollback/失敗判定 |
|---|---|---|---|---|---|
| HFP-01-001 | **PASS**（sandbox条件のみBLOCKED） | `research.md`（再確認追記）、`src/test/resources/freee/README.md`＋fixture 11件、`src/test/java/com/ses/service/impl/FreeeContractBaselineTest.java`（10 test） | `mvn test -Dtest=FreeeContractBaselineTest` → 10 run / **10 fail** / 0 error / 0 skip / exit 1（redが正しい） | 公式endpoint/field⇔失敗test 1対1対応を以下に提示 | 旧OAuth host・旧payroll path・旧root/field・company_id欠落・null→0・BP誤判定を各assertで再現。production変更なし |

**Demo（HFP-01-001）: 公式契約 ↔ 失敗test の1対1対応**

| 公式契約（固定OpenAPI） | 失敗test | 失敗理由（実際の観測） |
|---|---|---|
| 認可host `accounts.secure.freee.co.jp/public_api/authorize`、`prompt=select_company`、scopeなし | `authorizationUrlは公式OAuth契約に従う` | 現行URL `https://api.freee.co.jp/oauth/authorize?...scope=read:hr employees:read payrolls:read...` |
| token URL `accounts.secure.freee.co.jp/public_api/token` | `handleCallbackは公式tokenURLを使う` | Request URI expected=公式 / was=`https://api.freee.co.jp/oauth/token` |
| token応答の`company_id`保存・必須 | `handleCallbackはcompany_idを保存する` | expected 123 / was null |
| employees `GET /api/v1/companies/{company_id}/employees`（raw配列, limit/offset） | `employeesは公式companyPathを使う` / `employeesは公式rawArrayを返す` | was `/hr/api/v1/employees`、old root解析で0件 |
| employees要素に`employment_type`なし（BP enumなし） | `employeesはfreeeのemploymentTypeでBP除外しない` | expected 3 / was 2（旧BP除外） |
| salary `GET /api/v1/salaries/employee_payroll_statements`（company_id/year/month必須） | `statementsは公式salaryEndpointを使う` | was `/hr/api/v1/payroll-statements?year=...&month=...&type=...` |
| root `employee_payroll_statements`、`gross_payment_amount`等 | `statementsは公式rootとfieldで変換する` | old root `statements` で0件 |
| 金額はstring・nullable（計算中null） | `statementsは計算中nullを0へ変換しない` | expected null / was 0 |
| `payments`/`deductions`/`deductions_employer_share`/`allowances`明細 | `statementsは区分付きitemsを返す` | itemsがnull（一切設定しない） |

#### 自動gate集計（HFP-01-001時点）

| Gate | Command | 実行数 | Failure | Skip | Exit | 状態 | 証跡 |
|---|---:|---:|---:|---:|---|---|
| 既存freee関連test（baseline前） | `mvn test -Dtest=FreeeIntegrationServiceApiTest,FreeeAttendanceProviderTest,PayrollLandmarkA11yTest` | 11 | 0 | 0 | 0 | PASS | green baseline |
| 新規baseline test | `mvn test -Dtest=FreeeContractBaselineTest` | 10 | 10 | 0 | 1 | FAIL（意図通り） | surefire-reports/com.ses.service.impl.FreeeContractBaselineTest.txt |
| fixture秘密scan | 目視＋pattern scan（`access-token`/`refresh-token`は`fixture-` prefixのみ） | — | — | — | — | PASS | 実token・氏名・給与・外部IDなし |

#### 実装担当の残件

| ID | Requirement/AC | 状態 | 内容 | Owner / 外部条件 | 再実行command |
|---|---|---|---|---|---|
| HFP-01-RUN-ISSUE-01 | HFP-01-R12-4/5, AC15 | BLOCKED | freee test事業所・OAuth app・credential未提供。sandbox E2E（HFP-01-011）と全体PASS不可 | 発注者 / `FREEE_CLIENT_ID`等の環境変数提供 | HFP-01-011手順 |
| HFP-01-RUN-ISSUE-02 | HFP-01-R12-4 | BLOCKED | Docker daemon停止中。MySQL migration smoke（HFP-01-002/010）は起動後に実施 | ローカル / Docker Desktop起動 | `scripts/verify-like-ci.ps1` |

---

### HFP-01-RUN-20260814-02

| 項目 | 値 |
|---|---|
| 実装担当 | 実装AI（opencode） |
| worktree / branch | `C:\Users\pc\Documents\ses-manager-pro-hfp-01` / `codex/hfp-01-payroll-freee` |
| base / head | 前Run末commit / 本Run末commit（HFP-01-002） |
| 開始 / 終了（JST） | 2026-08-14 01:30 / 2026-08-14 04:10 |
| 公式OpenAPI固定commit | `52c69a6819ef14979a31b342123df816cb72c742`（前Runで確認済み） |
| freee test事業所 | BLOCKED（継続。secret不掲載） |
| Docker / Node | **READY**（Docker Desktop起動、daemon 29.6.1）/ READY（v24.18.0） |
| dirty差分の取扱い | 開始時dirtyなし |

#### Task実行証跡

| Task | 状態 | 変更file / method | Test command・結果（run/fail/skip/code） | Demo | Rollback/失敗判定 |
|---|---|---|---|---|---|
| HFP-01-002 | **PASS** | `db/migration/V103__freee_company_boundary.sql`（新規）、`FreeeConnection.connectionStatus`、`FreeeEmployeeLink.freeeCompanyId`、`schema-freee-payroll-h2.sql`、`engineer-schema-h2.sql`、`FlywayMigrationSmokeTest`（V103 assert追記）、`FlywayV103FreeeCompanyBoundarySmokeTest`（新規）、`FreeeCompanyBoundarySchemaH2Test`（新規） | H2: `mvn test -Dtest=FreeeCompanyBoundarySchemaH2Test` → 5/0/0/0 code 0。MySQL: `-Dtest=FlywayV103FreeeCompanyBoundarySmokeTest` → 2/0/0/0 code 0。`-Dtest=FlywayMigrationSmokeTest` → 2/0/0/0 code 0（V103 assert含む） | schema metadata: 下記Demo | 適用済みmigration編集なし。forward migrationのみ |

**Demo（HFP-01-002）: schema metadata + 3 unique case**

- `t_freee_connection.connection_status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED'`（MySQL実DBで確認）
- `t_freee_employee_link.freee_company_id BIGINT NULL`（legacyは一意company時のみbackfill、複数company時はNULLのまま要再確認）
- 旧`uk_freee_link_employee`削除 → 新`uk_freee_link_company_employee (freee_company_id, freee_employee_id)`2列UNIQUE（information_schema.statisticsで確認）
- 3 unique case（実MySQL・個人データなしfixture）: 同一employee別company登録**可** / 同一company内**拒否** / engineer重複（別companyでも）**常に拒否**
- V102適用済み相当（V21含む）からのupgradeでbackfill適用・NULL残存の両経路を確認

#### 自動gate集計（HFP-01-002時点）

| Gate | Command | 実行数 | Failure | Skip | Exit | 状態 | 証跡 |
|---|---:|---:|---:|---:|---|---|
| H2 schema/unique | `mvn test -Dtest=FreeeCompanyBoundarySchemaH2Test` | 5 | 0 | 0 | 0 | PASS | surefire-reports |
| MySQL upgrade smoke | `mvn test -Dtest=FlywayV103FreeeCompanyBoundarySmokeTest` | 2 | 0 | 0 | 0 | PASS | surefire-reports |
| MySQL full smoke | `mvn test -Dtest=FlywayMigrationSmokeTest` | 2 | 0 | 0 | 0 | PASS | surefire-reports |

#### 実装担当の残件

| ID | Requirement/AC | 状態 | 内容 | Owner / 外部条件 | 再実行command |
|---|---|---|---|---|---|
| HFP-01-RUN-ISSUE-01 | AC15 | BLOCKED | sandbox credential未提供（継続） | 発注者 | HFP-01-011手順 |

---

## 独立Review Roundテンプレート（この区切りから複製して末尾へ追記）

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
