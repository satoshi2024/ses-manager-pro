# SES Manager Pro 結合テスト（ITa・ITb）計画書 — 厳格 Review 基準

> **2026-08-14 Review 前版の判定: 旧 4 文書は、そのままでは実行仕様として使用不可だった。**
> 旧版は実装済み機能と未着手機能、実在 URL と予定 URL、実在テーブルと予定テーブルを混在させていた。本改訂では以下の source of truth、freeze、密度、readiness を共通基準とし、`module-test-matrix.md`、`inter-module-integration.md`、`e2e-business-scenarios.md`、`schedule-and-resources.md` を同じ基準へ同期する。文書上のケース定義と実行済み evidence は引き続き区別する。

## 1. 厳格 Review の結論

- Review 前版の `module-test-matrix.md` に定義されていた論理テスト ID は **43 件**だけであり、旧 `schedule-and-resources.md` の「400+ ケース」を裏付けるケース台帳は存在しなかった。ITb 12 項目と E2E 7 項目も、多くが同じ操作の再記述だった。本改訂では frozen inventory からモジュール内 **250+**、ITb **36**、E2E **21**、UI 実操作 **80+**、モンキー **45+ ID** の論理定義へ展開するが、定義数を実行数や合格 evidence へ読み替えない。
- 「合格率 100%」は、実行対象にしたケースが全て合格したという意味にすぎない。ケース密度、画面網羅率、API 網羅率、分岐網羅率を表さない。1 件だけ実行して合格しても合格率は 100% になる。
- Review 前版の「全 36 画面」は frozen inventory に基づく数字ではなかった。現行ツリーは `templates` が 70 ファイル（layout/error を除いても 66）、page controller が 42 クラス、`@GetMapping` が 71 箇所（URL 約 75）、API は 84 個の `@RestController` に **489 端点**（GET 215 / POST 172 / PUT 70 / DELETE 32 / PATCH 0）、状態機は 45 状態フィールド、`@Version` は 52 エンティティ、DataScope 呼出は 104 箇所ある。画面・redirect・互換経路・詳細画面をどう数えるかも定義済みであり、build 対象 commit ごとに route/template inventory を自動生成して分母を固定する。**設計カバレッジ 100% は、この frozen 分母に対する全 inventory 項目の test ID mapping を指す。**
- Review 前版には実在しない経路（例: `/crm/list`、`/audit-log/list`、`/engineer/detail/{id}`、`/project/detail/{id}`、`/attendance/overtime-alert`、`/invoice/jp-pint`、`/accounting/export`、`/portal/engineer-self`、`/ai/feedback-learning`）が含まれていた。現行実装の例は `/crm/leads`、`/crm/opportunities`、`/audit-log`、`/engineer/detail?id=...`、`/project/detail?id=...`、`/work-record/attendance`、`/invoice`、`/reconciliation` である。
- Review 前版にはテーブル名・状態値の不一致もあった。例として、顧客は `m_customer`、候補者履歴は `t_candidate_activity`、AI 実行ログは `t_ai_log`、月次締めは `m_system_config` の `closing.confirmed-months` JSON であり、旧版記載の `t_customer`、`t_candidate_history`、`t_ai_match_score`、`t_monthly_closing` ではない。勤怠状態も `SUBMITTED` / `APPROVED` ではなく `入力中` / `提出済` / `差戻し` / `確定` である。
- 候補者の「要員化」は自動 INSERT ではない。`/api/candidates/{id}/convert-to-engineer` は初期値を返し、要員を手動確認・登録した後に候補者へ紐付ける。候補者ステージは `応募受付`〜`入社` であり、`要員化済` という状態はない。
- 担当営業の主担当変更は、旧主担当を副担当へ降格するが `released_at` は設定しない。解除は別操作であり、主担当を他担当が残ったまま解除する操作は拒否される。
- 営業歩合は `SalesPerformanceServiceImpl` が照会時に算出する。`t_sales_commission_snapshot` への保存や「歩合計算実行」ボタンは存在せず、共通ルールの編集先は `/system-config` である。
- 現行ロールは **管理者 / 営業 / HR / マネージャー / 要員** の 5 種類である。`経理` と `外部BP` はロールとして存在しない。財務系ケースは、管理者の superuser 境界、`m_menu` / `t_role_menu`、permission group の action（例: `invoice.view`、`reconciliation.*`、`management-accounting.*`）を同時に満たす実在ユーザーで実行する。財務専用の最小権限ロールが必要なら、テスト前提ではなく別の実装要件として扱う。
- S10〜S17 は一括して「並行開発済み」と扱えない。中央実行台帳では S10 は `IN PROGRESS`、S11 のみ `PASS`、S12〜S17 は `NOT READY` である。未実装機能を PASS 対象へ含めてはならない。
- 300 人データは機能データ量であり、300 同時接続の負荷試験を意味しない。`V100` の存在だけでは、同時ログイン、同時更新、デッドロック、p95/p99、DB pool 枯渇を検証したことにならない。
- JaCoCo は `pom.xml` で `prepare-agent` と `report` のみ設定され、`check` 閾値はない。そのため、現状の build は 95% 未達でも失敗しない。手元の `target/site/jacoco/jacoco.csv` は参考値として line **70.79%**、branch **53.44%** だが、対象 commit に紐付いていないため release evidence には使用しない。
- 旧版の「U 次元」はモジュールごとに最低 1 回という条件で、実ブラウザ操作の密度を担保していなかった。本改訂では、操作プリミティブ inventory を分母にした **UI 実操作シミュレーション**（`ui-real-user-simulation.md`、実ユーザーのクリック・連打・ドラッグ・back/forward・再読込・IME・モバイル操作を再現）と、設計ケースでは到達しない入力を seed 固定ランダムで検証する **モンキーテスト**（`monkey-testing.md`、UI ランダム操作 / API フズィング / 状態機械・並行ランダム）を追加した。UI レイヤーは「操作・表示・復帰」、モンキーは「検出・異常率・探索カバレッジ」で判定し、ITa/ITb/E2E の API・DB oracle と混ぜない。

## 2. Source of Truth と freeze 規則

実行計画を作る際は、次の順序で事実を確定する。

1. **実装 inventory**: page/API controller の mapping、Thymeleaf template、static JS の呼出先、`SecurityConfig`、`MenuPermissionFilter`、action permission、service の transaction / data-scope guard。
2. **DB inventory**: `src/main/resources/db/migration/` の frozen migration 集合、entity の `@TableName` / `@Version` / field mapping、test 用 H2 schema。予定 migration や設計上の表名を実在扱いしない。
3. **readiness**: `.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md` の現行判定。個別設計書の将来形より中央台帳の `PASS` / `IN PROGRESS` / `NOT READY` を優先する。
4. **300 人 baseline**: `.kiro/specs/scale-300-e2e/`、`ops/e2e/scale-300/`、`scripts/seed-scale-300/` と実際の seed。既存の画面回帰結果を再利用するが、スクリーンショット成功を DB oracle や業務 E2E 成功へ読み替えない。

各テスト build の開始時に、最低限次を manifest として freeze する。

- commit SHA、branch、dirty file の有無
- page route、API の `method + normalized path`、template、menu/action permission の一覧と件数
- migration location、最新 version、Flyway history、DB 製品/version、test profile/H2 replay の差
- feature flag と adapter（`ai.provider`、CloudSign、attendance/freee provider など）
- seed ファイルの SHA-256、active/disabled actor 一覧、基準日・timezone
- browser/version、viewport、JDK、Node、Docker、Tomcat thread、Hikari pool の設定

freeze 後に実装や migration が変わった場合、差分 inventory を再生成し、影響ケースを再設計する。文書に URL・表・状態値を書くだけでは freeze したことにならない。実装と計画が衝突した場合は fail closed とし、そのケースを `BLOCKED_SPEC_MISMATCH` にする。

## 3. 300 人データの正確な前提

- 自動適用 seed の実在パスは `src/main/resources/db/migration-dev/V100__seed_r3_scale_300.sql` である。共通 `db/migration` ではなく **dev profile 専用**で、MySQL 構文を含む。test profile は Flyway を無効化して curated H2 schema/data を使用するため、「V100 を H2 に適用する」を開始条件にしてはならない。
- `V100` 自体が追加する `s300.*` は 299 アカウントであり、V2 の既存 `admin` を加えて DB 全体で 300 アカウントになる。内訳は、既存 `admin` + `s300.admin01`、営業 25、HR 8、マネージャー 10、要員 255 である。
- 要員 username は `s300.member001`〜`s300.member255` であり、`s300.eng001`〜`s300.eng255` ではない。
- `s300.sales07`、`s300.hr05`、`s300.member200` は意図的に disabled である。ログイン oracle は **297 active 成功 / 3 disabled 拒否**（V2 `admin` が active である前提）とし、「300 アカウント全てログイン成功」を期待してはならない。
- 既存の `.kiro/specs/scale-300-e2e/` は role/menu、desktop/mobile、代表的 API 権限の回帰 baseline である。本計画はその再実行と差分確認から開始し、未検証の業務更新、DB 落盤、競合、外部連携を追加する。

## 4. 論理ケースと実行 instance

- **論理ケース**: 一意の事前条件、1 つの検証目的、操作、期待 HTTP/UI、DB before/after、後処理を持つケース。単に actor や browser を変えただけでは別論理ケースにしない。
- **実行 instance**: 論理ケース × actor/role × browser/viewport × DB/profile × data partition × adapter × concurrency level の具体的な組合せ。
- ケース数、instance 数、assertion 数を別々に報告する。300 人分のデータを 1 回一覧表示しても 300 ケースとは数えない。
- `PASS`、`FAIL`、`BLOCKED`、`NOT_RUN`、根拠付き `N/A` を分離し、合格率は `PASS / (PASS + FAIL)`、実行率は `(PASS + FAIL) / 全 in-scope instance` とする。`BLOCKED` や `NOT_RUN` を分母から隠さない。

## 5. 密度を担保するケース設計規則

各 inventory item へ次の適用可能な観点を全て割り当てる。固定件数の「400+」を先に置かず、この展開結果をケース台帳の分母とする。

| 対象 | 必須観点 |
|---|---|
| 一覧/検索 | 正常、0件、複合 filter、sort、先頭/中間/最終/out-of-range page、soft-delete 除外、role/data scope 隔離、特殊文字/XSS 表示 |
| 詳細 | 存在、存在しない ID、scope 外 ID、削除済み ID、query/path parameter 契約、関連データ欠損 |
| 登録/更新/削除 | 正常、required、型/桁/境界、重複、業務 conflict、CSRF 欠落/不正、role/action deny、data scope deny、二重送信、DB before/after、audit、rollback |
| 状態遷移 | 許可される全 edge、禁止 edge、同一状態の冪等性、履歴、通知、承認待ち、同時更新 conflict |
| 複数表 transaction | 各落盤先、途中例外、全 rollback、再試行、採番/unique conflict、外部 side effect との整合 |
| file/PDF/CSV/XML | MIME、filename、文字コード、schema/項目、金額・丸め、0件、大容量、権限、再実行、ブラウザ download、目視 evidence |
| 操作/UX（UI 実操作レイヤー） | クリック/連打、ドラッグ&ドロップ、back/forward、再読込、IME、モバイル/タッチ、キーボード、ネットワーク変調、セッション変動、入力保持、空/読込/エラー 3 態、二重送信、focus/aria。全 page × 適用可能プリミティブを `ui-real-user-simulation.md` へ mapping |
| ランダム探索（モンキー） | ランダム操作・入力を seed 固定で大量実行し、500/未捕捉例外/白画面/想定外 4xx、DB invariant 違反、権限突破、UI 破壊を検出。時間予算・検出数・異常率・探索カバレッジで報告し、合格率には換算しない |
| 外部 adapter | disabled/未設定、stub success、timeout、4xx、429、5xx、不正 payload、retry、idempotency、secret 非漏洩、callback/webhook 重複 |
| batch/concurrency | 単一実行、重複 scheduler、複数 JVM lock、同一行競合、異なる行並行、deadlock retry、最終整合、pool timeout |

全更新系 API は少なくとも CSRF、role/action permission、data scope（適用機能のみ）、監査ログ、月次締め（適用機能のみ）を横断観点として持つ。`ApiAuditFilter` は更新系 API を対象とするため、「全 API/画面リクエストが監査表へ保存される」という期待値にはしない。

## 6. 可計算な coverage gate

| Gate | 計算式 | 完了条件 |
|---|---|---|
| Page route | navigation 済み frozen page route / in-scope frozen page route | 100%。許可 role の 200/描画と非許可 role の拒否を別 assertion 化 |
| API operation | 1件以上の正常/認可検証がある `method + path` / in-scope API operation | 100% |
| Role/action matrix | 実行済み allow/deny cell / 適用可能な 5 role + 未認証の cell | 100% |
| State transition | 検証済み allowed/denied edge / frozen transition matrix の全 edge | 100% |
| Mutation DB oracle | before/after と不変条件を検証した mutation case / mutation case | 100% |
| Cross-cutting | CSRF、scope、audit、closing、idempotency、rollback の実行済み applicable cell / 全 applicable cell | 100% |
| Evidence | request/response、DB oracle、screenshot/download、log、run ID が揃う instance / 実行 instance | 100% |
| Defect retest | 修正 commit と再現/回帰 evidence が揃う closed defect / closed 対象 defect | 100% |

JaCoCo の C0/C1 は上記の業務 coverage と別の指標である。95% を目標にする場合は、まず対象 package、除外、基準 commit、現行値を freeze し、`jacoco:check` を build に設定して機械的に fail させる。設定前の「95%」は exit gate ではなく未承認目標値として扱う。

## 7. 17 モジュールの現行 readiness

以下は 17 分類をそのまま拡張し、現行実装と roadmap readiness を分離したもの。`現行実装あり` は、そのモジュールの既存部分が自動的に PASS であることを意味しない。**実在 inventory と計画の突合結果**: 計画初版では Analytics・給与(freee)・Resume/Project 取込・Todo/Tasks・検索/Autocomplete・Files・Saved Views・Break-glass・Permission Groups・Identity Provider・Batch Operations・BP Migration・Cashflow・Skill Tags・Followups/Retention・Customer Timeline/Activities 等の実装済み機能が欠落しており、これらを MOD-01〜15 の拡張と新設 MOD-16/17 で補完した。また S12 の Position/Allocation/Staffing は実装済み route が存在するため、現行 smoke（MOD10-29 等）を current scope に置き、S12 spec の受入判定は中央 ledger に従う。

| MOD | 現行の実在入口（代表） | Review 時点の扱い |
|---|---|---|
| MOD-01 認証/権限/監査 | `/login`, `/user/list`, `/mfa/setup`, `/mfa/challenge`, `/audit-log`, `/api/security/**` | 現行実装あり。5 role、menu/action 二層権限、CSRF、session、MFA、500/403 の密度を追加 |
| MOD-02 候補者 | `/candidate/list`, `/candidate/detail` | 現行実装あり。`t_candidate_activity`、実在 stage、手動確認を含む要員紐付けへ全面修正 |
| MOD-03 エンジニア/担当営業 | `/engineer/list`, `/engineer/detail?id=...` | 現行実装あり。主担当降格と解除を別ケース化し、data scope cache invalidation を検証 |
| MOD-04 顧客/CRM | `/customer/list`, `/customer/{id}`, `/crm/leads`, `/crm/opportunities`, `/crm/opportunities/kpi` | 現行実装あり。`m_customer` と `source_opportunity_id` を正として修正 |
| MOD-05 案件/AI matching | `/project/list`, `/project/detail?id=...`, `/ai/matching` | 現行実装あり。固定 88%/5名を禁止し、選択 adapter と fixture から oracle を算出 |
| MOD-06 提案/メール | `/proposal/kanban`, `/email/template/list` | 現行実装あり。実在 proposal 状態、履歴、契約生成の成功/失敗/冪等を展開 |
| MOD-07 契約/署名/S10 | `/contract/list`, `/contract/detail/{id}`, `/contract-document`, `/compliance` | 契約・文書の既存部分は実装あり。**S10 は IN PROGRESS**。G2 の未受入部分を PASS 扱いしない |
| MOD-08 勤怠/休暇/月次締め/S11 | `/my/timesheet`, `/work-record`, `/work-record/attendance`, `/my/attendance`, `/my/leave`, `/leave`, `/monthly-closing` | **S11 PASS**。ただし本計画の URL、表、英語状態値は修正し、残る release gate を明示 |
| MOD-09 請求/消込/S16 | `/invoice`, `/reconciliation` | 請求・消込は現行実装あり。**S16 JP PINT は NOT READY**で対象外 |
| MOD-10 BP/S12/S13 | `/bp-company`, `/bp-availability/list`, `/bp-availability-ingestion` | BP 既存部分のみ実装あり。**S12/S13 は NOT READY**で、capacity/外部 portal を実在扱いしない |
| MOD-11 BP支払/S15 | `/api/work-records/{id}/bp-payments`, `/api/invoices/bp-payments/**` | BP支払の backend は現行実装あり。`/bp-payment/list` はなく、**S15 は NOT READY** |
| MOD-12 S14 要員 portal v2 | — | **S14 は NOT READY**。既存 `/my/timesheet` を S14 portal v2 の完成証拠にしない |
| MOD-13 S17 AI feedback | — | **S17 は NOT READY**。feedback 学習画面/表を実在扱いしない |
| MOD-14 組織/管理会計/歩合/dashboard | `/dashboard`, `/dashboard/profit`, `/organization`, `/management-accounting`, `/sales-performance`, `/system-config` | 現行実装あり。歩合は照会時算出、設定は system-config、snapshot 表なし |
| MOD-15 承認/帳票/受注/検収/文書 | `/approval`, `/approval/requests`, `/approval/routes`, `/quotation`, `/sales-order`, `/acceptance`, `/document/list` | 現行実装あり。実在 route、action、participant、approval adapter の関連を正として修正。responsibilities/delegations、export、`/api/my/acceptances` を追加 |
| MOD-16 給与連携/freee | `/payroll`, `/integrations/freee/authorize|callback`, `/api/payroll/**` | 現行実装あり。OAuth フロー、token 暗号化、refresh 並行ガード、権限（管理者/HR）を追加。attendance provider（MOD-08）とは分離 |
| MOD-17 タスク/通知/検索/共通基盤 | `/todo`, `/api/tasks/**`, `/api/notifications/**`, `/api/search`, `/api/autocomplete/**`, `/api/files/**`, `/api/saved-views/**`, `/api/profile/**`, `/api/permission-groups/**`, `/api/identity-providers/**`, `/api/security/break-glass/**` | 現行実装あり。横断基盤として全 endpoint の許可・scope・監査を inventory mapping で判定。要員は notifications/profile のみ許可 |

S10〜S17 の判定を変更できるのは、各 spec の完了チェック、独立 Review、必要な MySQL/browser evidence、中央実行台帳への反映が揃った場合だけである。結合テスト計画側だけで `PASS` や `READY` へ昇格しない。

## 8. 外部連携・AI・並行試験の固定条件

- AI は既定で `ai.provider=mock`。固定の適合率や再学習を期待せず、mock/rule/gemini ごとに fixture と oracle を分ける。S17 のモデル学習は未実装である。
- CloudSign は `cloudsign.enabled=false` が既定で、未設定時は `cloudsignNotConfigured` になる。成功試験には明示的な stub server と設定を用意し、未設定拒否、timeout、invalid response、status polling、重複送信を別ケースにする。「モック連携成功」を無条件の期待値にしない。
- freee 給与/OAuth と attendance provider は分ける。attendance は `mock` が既定で、`freee` mapping には release gate が残る。使用 adapter、credential の有無、stub/実 sandbox を manifest に記録する。
- `@Version` は付与された entity にだけ楽観ロックが効く。`t_work_record` の 300 同時送信を「MyBatis-Plus 楽観ロック」で一括保証してはならない。ShedLock は scheduler/batch の多重起動防止であり、通常の 300 HTTP 更新を直列化しない。
- 負荷試験では virtual user 数だけでなく、ramp-up、arrival rate、think time、duration、操作 mix、同一/異なる record の割合、Tomcat/Hikari 設定、p50/p95/p99、timeout/error/deadlock、DB 最終整合を固定する。機能 E2E と負荷/競合試験の結果を別に報告する。

## 9. 本版の構成と実行順序

1. **Inventory freeze**: 対象 commit から page/API/template/menu/action/table/state/adapter inventory と環境 manifest を生成し、分母と SHA-256 を固定する。操作プリミティブ inventory（`I`）も本ステップで固定する。差分が出た場合は後続のケース展開を先に更新する。
2. **モジュール内 250+ ケース**: `module-test-matrix.md` で 17 モジュールを §5 の観点へ展開する。現行 tree の実在 inventory（489 API / 71 page route / 45 状態機 / 52 @Version / 104 DataScope 呼出）から機械集計した ID が分母であり、250 は設計下限である。S10、S12〜S17 の未到達部分は `FUTURE_GATE` / `BLOCKED_NOT_READY` として定義だけを分離し、実行済みや PASS に数えない。
3. **ITb 36 連携**: `inter-module-integration.md` で source→target、API/service、FK/DB、transaction、permission/scope、通知/監査、失敗/rollback を組にして 36 連携を定義する。未実装 target への連携は future gate として現行 completion 分母から分離する。
4. **E2E 21 シナリオ**: `e2e-business-scenarios.md` で実装済み貫通、異常/回復、security/scope、external adapter、競合/負荷を 21 シナリオへ分ける。300 人 dataset の機能 E2E と同時実行負荷試験は別 run/evidence にする。
5. **UI 実操作シミュレーション**: `ui-real-user-simulation.md` で全 page をブラウザで実操作し（スモーク・モジュール別・横断 UX パターン・E2E リプレイ）、U 次元の密度を `I` inventory と browser matrix（Chromium/Firefox/WebKit × desktop/tablet/mobile）で担保する。判定は操作・表示・復帰のみで、API/DB oracle は他レイヤーに委ねる。
6. **モンキーテスト**: `monkey-testing.md` で UI ランダム操作、API フズィング、状態機械・並行ランダムを seed 固定・時間予算で実行し、500/例外/白画面、DB invariant 違反、権限突破を検出する。発見は defect 台帳へ載せ、合格率の対象にしない。
7. **Schedule と exit gate**: `schedule-and-resources.md` は確定した論理ケース数、実行 instance 数、環境数、future/blocked 数、再試験率から工数を算出する。工数式には `H_UI`（UI 実操作）と `H_MT`（モンキー）を含める。250+/36/21/80+ の文書化だけでは entry/exit 条件を満たさず、§6 の coverage、CI skip 0、defect gate、実行 evidence、UI レイヤーとモンキーの完了条件が揃った時点で初めて完了判定する。JaCoCo 95% は commit 固定と `jacoco:check` 実装後にだけ強制 gate とする。
