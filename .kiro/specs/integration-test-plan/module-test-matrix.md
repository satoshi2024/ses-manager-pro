# SES Manager Pro モジュール内結合テスト（ITa）詳細マトリクス

本書は、実装済みの画面・API・DBスキーマを基準にしたモジュール内結合テストの設計台帳である。ケース数や合格率だけを「密度100%」と呼ばない。実行時点のルート、状態遷移、入力規則、認可、データスコープ及び書込み先を棚卸しし、下記の分母に対する実測値で網羅性を判定する。

## 0. 網羅率の定義と共通実行規則

### 0.1 分母を固定するインベントリ

テスト開始時に、対象コミットから次のインベントリを生成し、コミットSHAとともに証跡へ保存する。

| 記号 | 分母 | 算出方法 |
|---|---|---|
| `R` | 画面/APIルート | `RequestMappingHandlerMapping` から抽出した HTTP method + path の一意集合。画面ルートも含む |
| `T` | 状態遷移 | Service の許可遷移表及び承認Adapterの一意な `from → to` 辺 |
| `V` | 入力規則 | DTO Bean Validation、Service業務検証、ファイル形式・件数上限の一意規則 |
| `A` | 認可判定 | SecurityConfig、メニュー権限、permission group action に定義された「主体×操作」の許可/拒否組 |
| `S` | データスコープ | 営業担当、組織、本人所有、基準日 `asOf` ごとの許可/拒否組 |
| `W` | 書込み経路 | Controller/Serviceから到達するテーブル更新単位。成功後条件と失敗時不変条件を1組として数える |
| `X` | 外部・帳票契約 | PDF/CSV/Excel/XML、メール、電子署名、ファイル保管の形式・失敗契約 |

計測は次の3指標を分離する。判定単位はテストID数ではなく、`R/T/V/A/S/W/X` inventoryの一意項目である。各項目にtest IDと結果をtraceする。

- `設計カバレッジ = test IDへmapping済みのinventory項目数 ÷ inventory総項目数`
- `実行カバレッジ = 結果がPASS又はFAILのinventory項目数 ÷ inventory総項目数`
- `合格率 = PASSのinventory項目数 ÷ (PASS + FAILのinventory項目数)`

`BLOCKED` と `NOT_RUN` はinventory分母に残し、設計済みなら設計カバレッジの分子には入るが、実行カバレッジの分子及び合格率の分母には入らない。したがって、未実装項目を除外して実行カバレッジ100%とすることはできない。1つのテストIDに複数の必須パラメータがある場合、パラメータ別にinventory結果を記録し、全パラメータを実行するまで当該ID全体をPASS表示しない。

### 0.2 全モジュール共通の強制次元

| 次元 | 意味 | 最低条件 |
|---|---|---|
| `N` | 正常 | 主要業務の成功経路と画面反映 |
| `B` | 境界 | 0/1/上限、日付境界、金額・時間・ページ境界 |
| `E` | 異常 | バリデーション、外部失敗、存在しない参照 |
| `A` | 権限/IDOR | 許可ロールと拒否ロール、別IDを差し込む直接API操作 |
| `S` | データスコープ | 25営業、組織、本人所有、過去時点の可視性 |
| `C` | 冪等/同時実行 | 二重クリック、再送、2セッション競合、CAS/一意制約 |
| `D` | DB/ロールバック/監査 | 正確な行数・値・関連行、失敗時0更新、現行フィルタの対象methodに対する監査結果 |
| `P` | 300人規模/性能 | V100母集団で件数、クエリ数、応答時間、メモリを計測 |
| `U` | UI | 表示、再読込、エラー復元、キーボード、モバイルの代表導線 |

各モジュールは適用可能な全次元を最低1回含める。非適用は理由と承認者をインベントリへ記録し、黙って分母から除外しない。性能環境はMySQL 8、同一JVM/heap、同一ブラウザ、同一V100 snapshotへ固定し、25→50→100→300 VUの段階負荷を用いる。環境固有の初回baselineを保存したうえで、標準JSON GETは `p95 ≤ max(1秒, baseline×1.20)`、更新系は `p95 ≤ max(1.5秒, baseline×1.20)`、PDF/Excel/一括処理は `p95 ≤ max(10秒, baseline×1.20)`、全試験でHTTP 5xx率0、想定外4xx率0とする。一覧のSQL回数が返却件数に比例して増えた場合は時間内でもFAILとする。

現行 `ApiAuditFilter` の対象は `/api/**` の `POST/PUT/DELETE` とdownload系 `GET` であり、`PATCH` は対象外である。またCSRF欠落/不正は先行する `CsrfFilter` で終了し、同filterの監査を必ず通るとは限らない。従って、CSRF試験のoracleは `403` と業務更新0件とし、監査行の有無はfilter chain実測を保存する。要件がCSRF拒否やPATCHも監査対象とする場合は、結果を捏造せずinventory gap/defectとしてExitを止める。メニュー権限拒否は `MenuPermissionFilter` 自身が記録する `PERMISSION_DENIED` と突合する。

### 0.3 300人データの事実

`V100__seed_r3_scale_300.sql` は `src/main/resources/db/migration-dev` にある MySQL 開発用シードであり、H2用Flyway migrationではない。`SET FOREIGN_KEY_CHECKS` を含むため、MySQL 8へ適用して検証する。既存 `admin` を含めて300アカウント、内訳は管理者2、営業25、HR8、マネージャー10、要員255である。無効アカウントは `s300.sales07`、`s300.hr05`、`s300.member200` の3件であり、認証期待値は有効297件成功、無効3件拒否とする。実行前にSQLでロール別・status別件数を再集計し、固定値との差異があればテストを開始しない。

### 0.4 M-PASS（未実装機能の実行ゲート）

S12〜S17は、次のM-PASSを満たした後だけ実行可能とする。

1. 実装コミットから生成した route inventory に画面/APIの method + path が存在する。
2. migration inventory と Entity/Mapper inventory が一致し、MySQL空DB migration smoke testがPASSする。
3. 5ロール（管理者/営業/HR/マネージャー/要員）及びpermission group actionの許可表が確定する。`経理`、`外部BP` 等の未定義ロールを前提にしない。財務操作は管理者又は明示的な会計担当permission groupで表現する。
4. UIからAPIまでの導線、CSRF、監査、エラー形式が確認できる。

M-PASS前のケースは `BLOCKED(M-PASS)` であり、PASS/FAIL及び実行済みには数えない。一方、対応する予定inventory項目は実行カバレッジの分母から除外しない。予定ルート名・予定テーブル名は実装済み資産として記載しない。

---

## 1. MOD-01: 認証・アカウント・権限・MFA・監査・セッション

- 実装済み画面: `/login`, `/user/list`, `/mfa/setup`, `/mfa/challenge`, `/audit-log`
- 実装済みAPI: `/api/users/**`, `/api/role-menus/**`, `/api/security/**`, `/api/audit-logs`
- 実装済みDB: `sys_user`, `m_menu`, `t_role_menu`, `t_user_mfa`, `t_mfa_recovery_code`, `t_mfa_attempt_guard`, `t_user_session`, `t_audit_log`

| テストID | 次元 | 実行条件・操作 | DB/外部断言 | 期待結果 |
|---|---|---|---|---|
| MOD01-01 | N,D | 有効な `s300.admin01` で正しいパスワードを入力してログイン | `sys_user.failed_count=0`, `locked_until IS NULL`; `t_user_session` に生IDではなくhashを1件記録 | 認証成功後 `/` へ遷移し、APIレスポンスやログへpassword/session IDを漏らさない |
| MOD01-02 | B,E,D | 同一有効ユーザーで失敗1〜4回、5回目、ロック30分直前/直後を固定Clockで検証 | 1〜4回は `failed_count` 増加、5回目は `locked_until` 設定、期限後の成功で状態リセット | 閾値前は通常拒否、5回目はロック表示、期限後のみ再ログイン可能 |
| MOD01-03 | A,D,U | シードmanifestを再集計し297有効/3無効をログイン試験。`sales07/hr05/member200` も個別実行 | 無効3件はsession追加なし・status変更なし。有効297件の成功/拒否数を証跡化 | 「300件すべて成功」ではなくmanifestどおり297成功・3拒否。ロール別遷移も正しい |
| MOD01-04 | N,E,D | 管理者が5ロールのユーザーを作成し、重複username、空白、弱いpasswordも送信 | 成功は `sys_user.password` を環境のencoderで保存しstatus=1。失敗はINSERT 0件 | 一覧に追加。重複/不正値は4xx JSONで、入力値を保持して再編集可能 |
| MOD01-05 | A,E,D | 自分自身のロール変更・無効化・削除、現任担当営業が残る営業の無効化をAPI直送 | `sys_user`、`t_engineer_sales`、組織所属に変更なし。監査へ失敗結果を記録 | すべて業務エラーで拒否し自己ロックアウトや孤児割当を作らない |
| MOD01-06 | A,U,D | 営業の `invoice` メニューを外し再ログイン後、サイドバーとページ/API直URLを確認。管理者でも確認 | `t_role_menu` は選択どおり。管理者行を外してもsuperuser bypassを維持 | 営業は非表示かつ直URL/API 403、管理者は到達可能。最長prefixの判定も一致 |
| MOD01-07 | N,B,C,D | TOTP設定→有効化→正しいcode、同一time-step再利用、±許容step、recovery code再利用を試験 | secretは暗号化、`last_used_step` 前進、recovery codeは一度だけused、原文非保存 | 初回のみ成功。replay/範囲外/使用済みcodeは拒否し、失敗回数制限を適用 |
| MOD01-08 | A,C,D,U | 2ブラウザでログインし「他を失効」、管理者reset、ユーザー無効化後に旧sessionから再要求 | 対象 `t_user_session.revoked_at/revoke_reason` 更新。現sessionのみ残す操作では1件だけ有効 | 次のリクエストから失効sessionを拒否し、AJAXはログインへ安全に戻る |
| MOD01-09 | A,E,D | CSRFなし/不正tokenでPOST、未認証でAPI/ページ、GET `/logout`、メニュー許可外APIを実行 | 業務更新0件。CSRF拒否はfilter chain実測と監査gapを記録し、メニュー拒否は`PERMISSION_DENIED`監査を突合 | APIは401/403 JSON、ページは適切な画面、GET logoutは実行されず、CSRF拒否へ監査行を捏造しない |
| MOD01-10 | E,D,U | API例外とページ例外で404/403/500を発生させる | 業務transactionはrollback、監査に失敗結果。stack traceやSQLをレスポンスへ出さない | APIは`ApiResult`、ページは自己完結`error.html`で表示 |
| MOD01-11 | C,P | 有効297アカウントを段階的に並列ログインし、同一ユーザー同時login上限も試験 | active session数、revoke数、一意性、DB pool待ちを採取 | 3無効は常に拒否。deadlock/500なし、p95とエラー率が基準内 |
| MOD01-12 | U,A | login/MFA/user/監査画面をキーボード、狭幅、session切れ、失敗後再送で操作 | UI操作とAPI/監査のcorrelationを保存 | focus可視、モーダル復元、二重送信防止、秘密値のDOM/画面残留なし |
| MOD01-13 | S,A,D | tenant識別を改変したrequest、別tenant fixtureのsession hash/user IDを使う失効・参照を実行 | 自tenant以外の`sys_user`/`t_user_session`/MFA/監査行は更新・返却0件 | request値でtenantを切替できず、存在有無を漏らさない |

---

## 2. MOD-02: 採用・候補者管理

- 実装済み画面: `/candidate/list`, `/candidate/detail?id={id}`
- 実装済みAPI: `/api/candidates/**`
- 実装済みDB: `t_candidate`, `t_candidate_activity`, `t_engineer`

| テストID | 次元 | 実行条件・操作 | DB断言 | 期待結果 |
|---|---|---|---|---|
| MOD02-01 | N,D,U | HRが氏名、連絡先、スキル、次回予定日を登録 | `t_candidate` 1件、`current_stage='応募受付'`, `created_by` 設定 | 一覧先頭に表示し、再読込後も同値 |
| MOD02-02 | B,P,U | 300人環境で氏名/ステージ/skillKeyword、page 1/最終/最終+1、size 0/上限超過を検索 | SELECTのみ。totalとrecords数を独立SQLで突合 | 上限へ正規化し全件無制限取得を許さず、空ページも崩れない |
| MOD02-03 | N,D | `応募受付→書類選考→一次面談→最終面談→内定→入社` を1段ずつ変更 | 各回 `t_candidate_activity` 1件追加、`current_stage` と最新履歴が一致 | 履歴を降順表示し、changed_by/changed_atを保持 |
| MOD02-04 | E,D,U | `応募受付→内定` の飛越し、不明ステージ、空ステージをAPI直送 | candidate/activityとも更新0件 | 4xxで拒否し、Kanban/詳細は元ステージへ戻る |
| MOD02-05 | B,E,D | `不採用`/`内定辞退` を理由なし、空白、理由ありで変更 | 理由なしは0更新、理由ありのみactivityにreason保存 | 必須理由を画面/API双方で強制 |
| MOD02-06 | B,N | nextActionDateが昨日/今日/明日、終端ステージの候補者でoverdue取得 | DB値不変。返却ID集合をSQL条件と突合 | `<= 今日` かつ終端外だけを返し境界を曖昧にしない |
| MOD02-07 | N,E,D | 入社前/入社後に変換初期値取得。入社後は画面で補完してengineer作成後にlink | 初期値取得だけではINSERTなし。link後のみ`converted_engineer_id`設定 | 自動変換と誤記せず、手動確認導線を経て関連付ける |
| MOD02-08 | C,D | 同一候補者へ同一engineerを再送、別engineerを2セッション同時link | 同一再送は1関連のまま、競合は片方だけ成功。重複関連0件 | 冪等成功又は409を仕様どおり返し、二重要員化しない |
| MOD02-09 | A,S | 管理者/営業/HRと、マネージャー/要員で画面・API直URLを試験 | 拒否操作は更新0件。候補者がdata scope非適用ならその理由をS inventoryに明記 | menu/permission定義どおり許可し、未定義の候補者スコープを推測しない |
| MOD02-10 | E,D,U | 候補者を論理削除し、存在しないIDのdetail/update/deleteも試験 | `deleted_flag=1`; activity履歴は保持。不存在は更新0件 | 一覧から消え、直URLは404 JSON/エラー画面。履歴を物理消去しない |

---

## 3. MOD-03: エンジニア・職歴・スキル・担当営業

- 実装済み画面: `/engineer/list`, `/engineer/detail?id={id}`
- 実装済みAPI: `/api/engineers/**`
- 実装済みDB: `t_engineer`, `t_engineer_skill`, `t_engineer_career`, `t_engineer_sales`, `t_engineer_account_link`

| テストID | 次元 | 実行条件・操作 | DB断言 | 期待結果 |
|---|---|---|---|---|
| MOD03-01 | N,D,U | 営業/HRが要員を登録・更新し、許可されたstatusを切替 | `t_engineer` の入力値、監査列、statusが一致 | 一覧/詳細/再読込で同値、金額は円単位表示 |
| MOD03-02 | B,E,D | 必須氏名欠落、未知status、終了日逆転等DTO/業務境界を送信 | 不正時更新0件 | 4xxで項目エラー表示、500やMySQL ENUM例外にしない |
| MOD03-03 | N,B,P | JavaとSpringのskillIds、status、雇用形態、担当営業、accountLinkedを組合せて255要員検索 | ID集合/totalを独立SQLで突合。複数skillは全条件一致 | ページ間重複/欠落なし、未知statusは安全に0件 |
| MOD03-04 | S,A | `scope.sales-own-data-only=true` でsales01/02が相互の担当要員を一覧/detail/update/delete直送 | 担当外は更新0件、404へ正規化 | 一覧、option、詳細、子resourceの全経路で同じscope |
| MOD03-05 | S,B | 組織異動の前日/当日/翌日をasOfにして営業scopeとの積集合を確認 | 期待ID集合を所属履歴と担当履歴から再計算 | 現在値で過去月を汚さず、空集合を全件扱いしない |
| MOD03-06 | N,E,D | 職歴を追加/更新/削除し、periodTo=periodFrom、periodTo<periodFromを試験 | `t_engineer_career` のowner/期間、論理削除を確認 | 同日境界は成功、逆転は0更新で拒否 |
| MOD03-07 | A,S,D | engineer AのURLにengineer Bのcareer idを差し込みGET/PUT/DELETE | Bのcareerは不変 | すべて404でIDORを防止し存在有無を漏らさない |
| MOD03-08 | N,D | 担当0件の要員へ副担当指定で初回割当、その後主担当を追加 | 初回は強制`primary_flag=1`; 新主担当時は旧主担当0 | 常に現任主担当が1件、履歴を保持 |
| MOD03-09 | E,D | 非営業、無効営業`sales07`、重複現任営業を割当。副担当がいる主担当を解除 | すべて更新0件、released_at未設定 | 業務メッセージで拒否し主担当欠落を作らない |
| MOD03-10 | C,D | 2セッションで別営業を同時に主担当化/割当 | active primaryの件数が最終的に1、重複active 0件 | 一方を直列化/競合拒否し、deadlock時もtransaction全rollback |
| MOD03-11 | D,E | 要員削除時に現任担当、account link、契約/提案等の参照ガードを試験 | 許可された関連だけ解除/論理削除。拒否時は全表不変 | 孤児行や不可視履歴を作らず、失敗監査を残す |
| MOD03-12 | P,U | 255件一覧、複合filter、detailのcareer/skill/sales履歴を反復表示 | SQL回数、p95、payload、heapを採取しN+1有無を確認 | 基準内で描画し、狭幅/キーボードでも履歴操作可能 |

---

## 4. MOD-04: 顧客・コンタクト・CRMリード・商談

- 実装済み画面: `/customer/list`, `/customer/{id}`, `/crm/leads`, `/crm/opportunities`, `/crm/opportunities/kpi`
- 実装済みAPI: `/api/customers/**`, `/api/crm/leads/**`, `/api/crm/opportunities/**`
- 実装済みDB: `m_customer`, `t_customer_contact`, `t_sales_activity`, `t_lead`, `t_opportunity`
- `m_customer` にインボイス登録番号は存在しないため、MOD-04の分母へ架空項目を追加しない。BP会社の登録番号はMOD-10で扱う。

| テストID | 次元 | 実行条件・操作 | DB断言 | 期待結果 |
|---|---|---|---|---|
| MOD04-01 | N,D,U | 営業が顧客を登録し会社名、商流、信用度、住所を更新 | `m_customer` 1件。旧contact列へ新規書込みなし | 一覧/詳細へ反映し、連絡先は専用APIで管理 |
| MOD04-02 | B,E,D | 会社名空白/100/101文字、信用度1/2文字、住所255/256文字、存在しないIDを送信 | 許可境界のみ保存、不正時INSERT/UPDATE 0件 | 項目別4xx、SQL/stack trace非表示 |
| MOD04-03 | A,S | sales01/02と組織scopeでcustomer list/options/detail/update/deleteを相互試験 | 担当外更新0件、返却集合を担当/組織積集合と突合 | 担当外は404、optionsやsummaryからも漏れない |
| MOD04-04 | A,U | `customer.pii.view` 有/無permission groupでemail/phoneを表示・API取得 | DB原文不変。権限なしresponseのみmask | DOM、export、候補一覧を含め一貫してmask |
| MOD04-05 | N,B,D | primary連絡先を有効期間の非重複/境界接触で登録し、退職/異動処理 | `t_customer_contact` のperiod/status/primaryが整合 | asOf時点に有効な主担当を最大1件表示 |
| MOD04-06 | C,E,D | 同じversionで2画面から連絡先更新、同時に主担当登録 | 一方だけversion更新、主担当期間重複0件 | stale更新は409、入力を失わず再読込案内 |
| MOD04-07 | N,D | リード登録後、隣接stage更新と商談化を実行 | `t_lead` 更新、`t_opportunity` 1件、source link一致 | リード/商談画面の双方に整合して表示 |
| MOD04-08 | E,D | 商談確度0/100/範囲外、stage既定と異なる確度を理由なし/ありで保存 | 不正時0更新、理由ありのみoverride保存 | 確度と上書き理由の規則を強制 |
| MOD04-09 | N,E,D | 許可遷移を順に実行し、失注理由なし、終端後編集、飛越しを試験 | stage/version/stage_changed_atが成功時のみ更新 | 不正遷移は409、UIカードを元列へ戻す |
| MOD04-10 | C,D | 受注商談を2セッションで同時に案件・見積へ変換し再送 | source_opportunity_idごとに`project`/`quotation`各1件、商談link一致 | 一度だけ生成し、部分生成時は全rollback又は既存を冪等返却 |
| MOD04-11 | D,S | 顧客summary/KPIの案件・提案・成約・失注を既知fixtureで計算 | 分子分母、0件時null、scope内集計を独立SQLで突合 | sales01にsales02の金額/件数を混在させない |
| MOD04-12 | P,U | 300人データの顧客/連絡先/リード/商談をfilter・scroll・KPI表示 | p95、SQL回数、payload、ブラウザ描画時間を保存 | N+1なし、200件上限/ページングが機能しモバイルでも操作可能 |

---

## 5. MOD-05: SES案件・要件スキル・AIマッチング

- 実装済み画面: `/project/list`, `/project/detail?id={id}`, `/ai/matching`
- 実装済みAPI: `/api/projects/**`, `/api/ai/matching/project/{projectId}`, `/api/ai/match/engineer-to-projects`, `/api/ai/chat`
- 実装済みDB: `t_project`, `t_project_skill`, `t_engineer_skill`（マッチ結果の永続キャッシュテーブルは存在しない）

| テストID | 次元 | 実行条件・操作 | DB断言 | 期待結果 |
|---|---|---|---|---|
| MOD05-01 | N,D,U | 顧客、案件名、円単位の単価幅、期間、必須/尚可skillを登録 | `t_project` 1件、`t_project_skill` が入力集合と一致 | 一覧/詳細に単価・期間・skill区分を正しく表示 |
| MOD05-02 | B,E,D | 単価min=max、min>max、start=end、end<start、顧客欠落を送信 | 許可境界のみ保存。不正はproject/skillとも0更新 | Bean Validationを4xx表示し部分保存しない |
| MOD05-03 | N,C,D | skill集合を全置換、空集合、同一要求再送、2セッション同時置換 | project_id配下に最終集合だけ、重複0件 | transaction単位で置換し中間空状態を外部へ見せない |
| MOD05-04 | A,S | sales01/02が担当外案件のlist/options/detail/skills PUT/AI matchingを直送 | 担当外は更新0件・結果0件/404 | 親案件scopeを子skillとAIにも継承しIDORを防止 |
| MOD05-05 | N,B | rule providerで必須skill充足49%/50%/100%、尚可0件を採点 | DB変更なし。計算内訳をfixture期待値と突合 | 50%未満だけ除外、空必須は50点、score順が安定 |
| MOD05-06 | B | 希望単価が範囲内、±9,999円、±10,000円、±100,000円、稼働日0/1/30/31日遅れ | DB変更なし | 1万円単位減点と日付境界が仕様どおり |
| MOD05-07 | E,D | null/不存在engineerId・projectId、AI無効時chat、provider例外を実行 | 書込み0件、秘密key/個人情報を監査本文へ残さない | 400/404/500を規約どおり返し画面は手動入力へfallback |
| MOD05-08 | D,A | mock/rule provider双方で同一入力を反復し、別営業の要員を混ぜる | `t_ai_match_score` 等へのINSERTがないことを確認 | 固定「5名」等を期待せず実結果件数をfixtureから算出、scope外0件 |
| MOD05-09 | P | 255要員×案件の逆引きmatchingをcold/warmで複数回実行 | p50/p95、SQL回数、heap、結果件数・順序を保存 | N+1/全件entity再読込を検出し基準内、timeout/500なし |
| MOD05-10 | U | filter→detail→matching modal→結果選択をkeyboard/狭幅/0件/失敗で操作 | DB不変、画面console error 0 | score理由・不足skillを読め、modal focusとerror復元が正しい |

---

## 6. MOD-06: 提案Kanban・メールテンプレート・成約連携

- 実装済み画面: `/proposal/kanban`, `/email/template/list`
- 実装済みAPI: `/api/proposals/**`, `/api/email-templates/**`, `/api/ai/proposal-draft`
- 実装済みDB: `t_proposal`, `t_proposal_history`, `m_email_template`, `t_contract`, `t_notification`, `t_mail_delivery`

| テストID | 次元 | 実行条件・操作 | DB断言 | 期待結果 |
|---|---|---|---|---|
| MOD06-01 | N,D,U | scope内の要員と案件で提案を新規作成 | `t_proposal.status='書類選考中'`, proposed_by/at設定。Bench要員は提案中へ連動 | Kanbanの書類選考中列に1枚表示し再読込後も一致 |
| MOD06-02 | E,C,D | 同一要員×案件を二重クリック/2セッション同時POST | active提案1件、重複INSERT 0件 | 一方だけ成功、他方409。ボタンを再有効化し二重カードなし |
| MOD06-03 | N,D | `書類選考中→一次面接→二次面接→結果待ち` の各許可辺を移動 | 各辺につきhistory 1件、from/to/changed_by一致 | カードと履歴が同期し時刻を表示 |
| MOD06-04 | E,D,U | `書類選考中→成約`、終端からの再移動、不明statusをAPI直送 | proposal/history/contract/notificationすべて不変 | 4xxで拒否しdrag cardを元位置・元件数へ復元 |
| MOD06-05 | N,D | 結果待ちから成約へ変更 | proposal閉鎖、history 1件、契約ドラフト1件、通知1件を同一transactionで確認 | 契約確認導線を表示し、クライアントから契約を二重作成しない |
| MOD06-06 | E,D | 成約時の契約INSERT又は通知発行を故障注入 | proposal/status/history/contract/notificationが全rollback | 部分成約を残さず再実行可能 |
| MOD06-07 | N,D | 見送りへ遷移し、同要員に他active提案あり/なしを比較 | closed_at/historyを保存。他activeなしのときだけ要員status解放 | status連動が他提案を破壊しない |
| MOD06-08 | A,S | sales01/02が互いのKanban/detail/status/mail/skill-sheetへID直送 | scope外は全表更新0件、ファイル生成0件 | 一覧だけでなく全子操作を404で遮断 |
| MOD06-09 | N,B,E | UI公開の `{customer_name}/{engineer_name}/{project_name}` と、提案メールAPIが渡す `customerName/engineerName/projectName/contactPerson/unitPrice` を単/二重波括弧、未定義key、HTML文字、空宛先でendpoint別に試験 | template原文不変。snake↔camelを解決し、未定義は二重波括弧のみ空、一重波括弧は原文保持。送信成功時だけdelivery記録 | `TemplateRenderer` の互換契約と各endpointのparameter allow-listを固定し、UI表示はescape、空宛先は4xx |
| MOD06-10 | E,D | SMTP/mock外部失敗、添付生成失敗、virus scan拒否を注入 | mail status/errorを記録、proposalの送信済み相当更新なし、孤児file 0 | 失敗Toastと再送導線、秘密/宛先の過剰ログなし |
| MOD06-11 | C,D | 成約status requestを同時再送しtransaction境界を検証 | proposal history、contract draft、notificationの業務キー各1件 | 二重契約/二重通知を作らず競合を4xxへ正規化 |
| MOD06-12 | P,U | 300人データで各status列をpage追加読込、keyword検索、mail modalを操作 | total/recordsをSQL突合しp95/SQL回数/DOM node数採取 | 全件一括描画せず、列件数・さらに表示・focusが正しい |

---

## 7. MOD-07: 契約・単価改定・派遣/請負コンプライアンス・電子署名

- 実装済み画面: `/contract/list`, `/contract/detail/{id}`, `/contract-document`, `/compliance`
- 実装済みAPI: `/api/contracts/**`, `/api/contract-documents/**`, `/api/compliance/**`, `/api/compliance-gate/**`
- 実装済みDB: `t_contract`, `t_contract_price_history`, `t_contract_document`, `t_contract_compliance_profile`, `t_contract_compliance_snapshot`, `t_contract_compliance_worker_snapshot`, `t_compliance_finding` 等
- S10/G2中央ledgerはIN PROGRESSである。route/tableが存在しても、実在責任者assignment、対象version/hashへの実actor承認、freeze済みpolicyを満たす実在external review、実在CLEAN evidence、T066及び最終Reviewが揃うまで `ACTIVE` 化・本番帳票交付・S10全体PASSを認めない。

| テストID | 次元 | 実行条件・操作 | DB断言 | 期待結果 |
|---|---|---|---|---|
| MOD07-01 | N,D,U | 要員/案件/顧客、売上単価、原価、精算幅、期間、担当営業で契約作成 | `t_contract.selling_price/cost_price` 等が円単位で一致 | 一覧/詳細に正しい名称・粗利・精算条件を表示 |
| MOD07-02 | B,E,D | selling/cost=0、負数、精算min=max/min>max、start=end/end<start、commission 0/100/範囲外 | 許可境界のみ保存、不正時0更新 | 4xx項目エラーで部分保存なし |
| MOD07-03 | A,S | sales01/02と組織異動前後でlist/options/detail/update/delete/price/documentを直送 | 担当外は全表更新0件 | 契約・文書・改定履歴の全経路で同一scope、404へ正規化 |
| MOD07-04 | N,E,D | `準備中→稼動中→終了`、解約日必須/期間外、無効status辺を試験 | 許可遷移だけstatus更新。解約情報の整合を確認 | 不正遷移409、稼働開始など承認対象はAdapter結果に従う |
| MOD07-05 | N,D | 提案/見積/注文行から契約ドラフトを生成し主担当営業あり/無効/なしを比較 | source link一意、active主担当時だけsales_user_id default、status準備中 | 未帰属を隠さず一覧で設定を促す |
| MOD07-06 | N,B,D | 契約開始月、将来月、同月上書きで単価改定。過去確定工数/請求ありでも試験 | `t_contract_price_history` のeffective month一意、初期履歴を保持 | 月別単価解決が正しく、ロック対象の過去改定は拒否 |
| MOD07-07 | C,D | 同一契約・同一適用月へ異なる単価を2セッション同時保存 | 最終行1件、version/updated_at整合、請求計算が曖昧にならない | CAS/lockで直列化しdeadlock時は片方全rollback |
| MOD07-08 | N,E,D | 契約PDF生成→CloudSign mock送信→syncを通常実行し、宛先不正、templateなし、外部4xx/5xxも注入 | 成功時document status/external idを突合。外部呼出し前失敗はDB/ファイル0更新 | 署名待ち/完了を表示し、通常成功経路と外部拒否を区別する |
| MOD07-09 | E,C,D,X | **KNOWN_RISK/RELEASE-BLOCKING** CloudSign外部POST成功直後のDB update失敗と、署名済みPDF/certificate保存後のDB update/scan失敗を注入して同要求を再送 | provider文書ID/call数、DB external id、storage object/hashを照合。期待は外部重複0・孤児file 0だが、現行にidempotency key/補償がなければFAILとして記録 | 「安全に再送可能」と先に断言しない。重複外部文書又は孤児を検知した時点でreleaseを止め、reconcile/補償完了を要求 |
| MOD07-10 | N,B,D | 実装済みFR-10警告サブセットで多重段数、direct-command、契約種別×工数不整合、抵触日31/30/1/0日前を比較 | warning/findingとAuditLog、締めchecklist件数を独立fixtureで突合 | 法的適否を自動確定/ブロックせず、設定閾値どおり警告する。G2/T066のPASSとは数えない |
| MOD07-11 | B,C,A,D | **BLOCKED(G2/T066)** workplace単位の責任者assignmentを `[from,to)` の隣接/部分重複/open-ended/同時登録で試験 | M-PASS後にanchor lock、overlap判定、current assignment一意性を確定schemaへ突合 | 隣接のみ許可し、重複は全rollback。対象contractのworkplaceをrequest値から切替できない |
| MOD07-12 | A,S,D,X | **BLOCKED(G2/T066)** asOf時点の実在assigned actor本人によるapproval、管理者bypass、旧担当/別workplace承認流用、dynamic external reviewer policyを試験 | actor/assignment/version/hash、distinct reviewer、freeze済policy hash、CLEAN evidenceをevent/credential暗号化状態と突合 | 実actor以外は管理者でも承認不可。不足/撤回/破損policyはfail-closedで、本番 `ACTIVE`/交付不可 |
| MOD07-13 | B,C,D | **BLOCKED(G2/T066)** `DRAFT→PROVISIONAL_REVIEWED→ACTIVE→SUPERSEDED`、future候補、effective境界、同時ACTIVE、timezone欠落を実行 | append-only event、expected-version CAS、current/future slot一意、source/policy freezeを確定schemaへ突合 | 飛越し・期限前ACTIVE・SUPERSEDED再ACTIVE・非DRAFT編集を拒否し、timezone不明時はfail-closed |
| MOD07-14 | N,D,X | **BLOCKED(G2/T066)** ACTIVE gate後、法定document群を同一contract/profile/worker snapshotから生成し、再生成と版差分を比較 | mapping/version/hash、worker snapshot ID/hash、render input hash、PDF SHA-256、document versionを突合 | 同一snapshotの帳票間で責任者/労働者/条件が一致し、日本語font・mask版・版差分を説明可能 |
| MOD07-15 | N,C,D,X | **BLOCKED(G2/T066)** document生成→交付→受領確認、90/60/30日前deadline通知、同一/別idempotency key再送を実行 | delivered/confirmed日時、recipient/display snapshot、delivery business key、rendition/notification各1組を突合 | 同じstable inputは重複交付せず同一結果を返し、gate変更後は旧結果を新規交付として再利用しない |
| MOD07-16 | N,E,C,D,U | **BLOCKED(G2/T066)** findingを未確認→acknowledged→対応中→解消、例外申請/承認/根拠添付し、解消と例外承認を同時実行 | status/action/evidence/versionの履歴を突合し、current findingは最大1、競合側0更新 | 許可遷移と理由/証拠必須を強制し、法的適否の自動判定や履歴上書きをしない |
| MOD07-17 | C,E,D | **BLOCKED(G2/T066)** 同一operation keyの同payload/異payload、PROCESSING lease中、commit後response喪失、rollback後retryを実行 | operation ledgerのrequest hash/state/result reference/failure retryabilityが1業務結果へ収束 | 進行中409、成功再送200同一結果、異payload/retry不可を決定的に拒否し、外部副作用を重複させない |
| MOD07-18 | P,U | 300人データで実装済み契約page/filter/gantt/renewal、FR-10 finding、PDFを計測。G2/T066依存画面はBLOCKED件数を別掲 | p95/SQL回数/payload/生成時間、件数、BLOCKED inventoryを独立突合 | 実装済み範囲はN+1なし・page化・PDF表示正常。未解除G2を性能PASSやS10全体PASSへ混ぜない |

---

## 8. MOD-08: 客先工数・雇用勤怠・36協定・休暇・月次締め

- 実装済み画面: `/my/timesheet`, `/my/attendance`, `/work-record`, `/work-record/attendance`, `/my/leave`, `/leave`, `/monthly-closing`
- 実装済みAPI: `/api/my/timesheet/**`, `/api/my/attendance/**`, `/api/work-records/**`, `/api/work-records/attendance/**`, `/api/my/leave/**`, `/api/leave/**`, `/api/monthly-closing/**`
- 実装済みDB: `t_work_record`, `t_work_record_daily`, `t_employee_attendance`, `t_employee_attendance_break`, `t_attendance_month`, `t_leave_request`, `t_leave_ledger`, `m_system_config`（締め済み月JSON）

| テストID | 次元 | 実行条件・操作 | DB断言 | 期待結果 |
|---|---|---|---|---|
| MOD08-01 | N,D,U | 紐付済み要員が本人契約へ日次開始/終了/休憩を保存し月提出 | daily行とactual_hours集計、status=`提出済`が一致 | カレンダーと月合計を更新、提出後のPDF導線を表示 |
| MOD08-02 | A,S,D | 未紐付要員、他要員contractId/workRecordIdをAPIへ差し込む | 全勤怠表更新0件 | 未紐付403、純不存在404、所有者不一致403を区別し他人情報を返さない |
| MOD08-03 | B,E,D | 月初/月末、契約開始/終了外、開始=終了、日跨ぎ、休憩0/1440/超過を試験 | 許可日だけ保存しworked hoursを正確に算出 | 不正時間・対象月外は4xx、負時間を作らない |
| MOD08-04 | E,D,U | 提出済/確定済の編集・削除・再提出、差戻し後の修正を実行 | ロック中0更新、差戻し後のみversion/行更新 | UIをread-onlyにし、直APIでも同じガード |
| MOD08-05 | A,S | 雇用勤怠管理を管理者/HR/マネージャーで操作し、営業/要員の管理API直送も試験 | 拒否時0更新。管理者/HR/managerも組織scope内だけ | SecurityConfigとattendance scopeの積集合どおり |
| MOD08-06 | N,D | 雇用勤怠の提出→承認→締め、差戻し→再提出、理由付きreopenを実行 | attendance month/status、break interval、approval action整合 | 許可遷移のみ成功し通知/履歴を表示 |
| MOD08-07 | B,D | 月の法定時間外を44:59/45:00/45:01に固定し、予兆/超過通知を計算 | 時間外・通知・followup集合を分単位の独立計算と突合 | 45時間ちょうどと超過を混同せず、本人/上長/HRへの段階通知が規則どおり |
| MOD08-08 | B,D | 単月の時間外+休日労働を99:59/100:00にし、休日労働0/1分を差し替える | 月100時間未満判定の算入時間とalertを突合 | 99:59は上限内、100:00は違反。休日労働を除外して見逃さない |
| MOD08-09 | B,D | 2/3/4/5/6か月それぞれで時間外+休日労働平均79:59/80:00/80:01を作る | 各windowの分子/月数/端数と最大平均を独立計算 | 80:00以内を許可し80:01を超過として、window欠落や月選択ずれを起こさない |
| MOD08-10 | B,D | 年間時間外359:59/360:00/360:01を特別条項なしで計算 | 年度/36協定期間、累計分、warningをfixtureと突合 | 360時間ちょうどと超過を仕様どおり区別し、暦年と協定期間を混同しない |
| MOD08-11 | B,D | 特別条項ありで年間719:59/720:00/720:01、45時間超の月が6回/7回を計算し休日労働も組み込む | 年累計、超過月数、特別条項適用区分、通知集合を突合 | 720時間・6回の境界を固定し、7回目又は上限超過を必ず検知 |
| MOD08-12 | N,E,D | 休暇申請、残高不足、期間重複、承認/却下/取消を実行 | `t_leave_request` と `t_leave_ledger` の増減がtransaction一致 | 負残高や二重控除を作らず本人/管理画面に同期表示 |
| MOD08-13 | N,C,D,X | attendance provider mock/freee同期を同一月・同一payloadで初回/再送し、cursorを再取得 | source、provider record key、cursor、取込/照合件数を突合し重複日次0件 | 成功時に1回だけ反映し、締め済み雇用勤怠や客先工数を上書きしない |
| MOD08-14 | E,C,D,X | provider 401→refresh成功/再401、429、500、timeout、途中応答後retryを注入 | cursorは成功commit時だけ前進し、重複日次0、失敗/再試行状態と秘密maskを突合 | 401 refreshは規定回数だけ、retry可能/不可を区別し、timeout後も冪等に回復 |
| MOD08-15 | N,B,E,D,U | 雇用勤怠と客先工数の差を479/480/481分で表示し、理由なし/あり確認、再通知を実行 | `attendance.discrepancy.confirmed` の本人/月/reason/actorだけ更新し、双方の工数・請求額は不変 | 480分境界を固定し、未確認超過だけ通知。確認操作で請求工数を自動修正しない |
| MOD08-16 | N,E,D | 締めsummaryに未入力/未確定/未請求/未払BPを各1件作り `/confirm` 申請後、未解消/全解消で最終承認 | 申請時はapproval requestのみ。未解消時又は最終承認前はclosing JSON不変、条件充足後の最終Adapter適用でだけ月record追加 | 期限超過請求だけでは阻害しない等ready条件を画面と一致させ、「confirm API直後に締まる」と誤認しない |
| MOD08-17 | C,D | 締め最終承認と勤怠保存/請求取消を同時実行し、同一申請approve二重送信、破損JSONも試験 | config row lockとapproval CASで片方だけ成立、target適用1回、破損時fail-closed | 締め済み月への後勝ち更新と二重target適用を許さず全rollback |
| MOD08-18 | A,D | 締め/reopen申請と最終承認を管理者・マネージャー、営業・HR・要員で直送 | 非許可はconfig/approval/action更新0件。許可者もroute participant一致を確認 | 5ロールとpermission actionの積で判定し、ControllerからServiceへ直接迂回しない |
| MOD08-19 | C,P,U | 有効な要員254件（無効member200を除外）を段階並列で日次保存/提出し、管理grid・差異・警告を狭幅/keyboardで操作 | 成功/拒否manifest、row数、deadlock、p95、SQL回数、pool待ち、DOM時間を保存 | 無効アカウントは認証拒否、二重日次なし、一覧page化、500/想定外4xxなし |

---

## 9. MOD-09: 請求・売掛金・入金消込・督促・S16 JP PINT

- 実装済み画面: `/invoice`, `/reconciliation`
- 実装済みAPI: `/api/invoices/**`, `/api/reconciliation/**`
- 実装済みDB: `t_invoice`, `t_invoice_item`, `t_invoice_payment`, `t_bank_deposit`, `t_mail_delivery`
- S16 JP PINT: 現時点ではM-PASS前。予定ルート/テーブルを実装済みとして扱わない。

| テストID | 次元 | 実行条件・操作 | DB/外部断言 | 期待結果 |
|---|---|---|---|---|
| MOD09-01 | N,D,U | 確定工数を持つ1顧客×1月で請求生成 | invoice 1件、item集合、subtotal、税切捨て、totalを独立計算と突合 | `/invoice` に未送付で表示しPDF金額も一致 |
| MOD09-02 | B,D | 精算下限/上限ちょうど、1時間不足/超過、月途中単価改定、税率0/10%を試験 | item控除/超過、適用単価、税の円単位を突合 | 境界の丸めを一貫させ二重按分しない |
| MOD09-03 | E,C,D | 同一顧客×月の二重生成、工数なし、検収状態を生成直前に変更 | 重複invoice 0件、失敗時itemも0件 | 409/業務エラーへ正規化し部分請求なし |
| MOD09-04 | A,S | sales01/02と組織scopeでlist/detail/PDF/payment/aging/reminderを直送 | 担当外は更新/ファイル/メール0件 | 一覧・帳票・明細の全経路で同じ404 scope |
| MOD09-05 | N,B,E,D | 0/一部/残額ちょうど/1円超過の入金を追加し削除 | payment合計、balance、status=`未送付又は送付済/一部入金/入金済`、paid_date整合 | 超過/0円は拒否、全削除後は元状態に応じ未送付又は送付済へ再計算 |
| MOD09-06 | N,D | 銀行入金fetch→候補score→手動apply | deposit=`消込済`、matched invoice/payment id、invoice balanceが同一transactionで更新 | 名義/金額/日付根拠を表示し自動確定を装わない |
| MOD09-07 | C,D | 同一depositを2セッションで別invoiceへ同時apply、同一要求再送 | depositは1invoiceだけ、payment 1件 | 一方成功、他方409。二重消込/二重入金なし |
| MOD09-08 | N,E,D | 期限超過invoiceへ有効な請求contactで督促、宛先なし、mail失敗、bulk一部scope外を試験 | mail deliveryの成功/失敗、invoice本体不変、bulk失敗時方針を証跡化 | 宛先PIIを権限どおりmaskし、安全に再送 |
| MOD09-09 | B,D,U | aging 0/1/30/31/60/61/90/91日、基準日指定、detail/Excel/PDFを確認 | bucket合計=未回収総額、export合計とAPI一致 | 境界・日本語font・CSV/Excel injection対策が正しい |
| MOD09-10 | P,U | 300人データで月次invoice page、aging、PDF、消込候補を計測 | 件数/合計SQL突合、p95/SQL回数/生成時間/heap保存 | 基準内、N+1なし、一覧・modal・downloadが安定 |
| MOD09-11 | D,A,X | **BLOCKED(M-PASS/S16)** route/migration/provider/permission inventoryと顧客別PDF/JP PINT preferenceを照合 | 実装後のmethod+path、保存先、provider adapter、action keyだけを確定inventoryへmapping | 不足0まで後続を実行せず、予定route/tableを実在扱いしない。PDF経路は継続可能 |
| MOD09-12 | N,B,E,A | **BLOCKED(M-PASS/S16)** legal entity/customerのparticipant ID・scheme・provider・verified dateを登録し、未検証/期限切れ/別tenantを送信 | participant照会結果とversionを確定保存先へ突合し、拒否時送信job/外部call 0 | directory検証済みparticipantだけ送信可能。権限は管理者又は会計担当permission groupで強制 |
| MOD09-13 | N,B,D,X | **BLOCKED(M-PASS/S16)** 既存invoice/item/tax snapshotからgolden XMLを生成し、1円端数、0/10%税、適格/非登録、code list、UTF-8を比較 | canonical値、rounding、税区分、snapshot hash、JP PINT versionをfixture/公式validatorと突合 | 請求正本を再計算上書きせずgolden XML一致。実装時確認した版を保存し自動upgradeしない |
| MOD09-14 | E,D,X | **BLOCKED(M-PASS/S16)** 必須ID欠落、総額不一致、未知code、schema/business rule不適合、warningを送信前validate | error/warning code、対象field、job/外部call 0件を確定契約と突合 | errorは送信不可、warningの扱いは明示承認規則どおりで、validator未通過XMLをdownload/sendしない |
| MOD09-15 | C,E,D,X | **BLOCKED(M-PASS/S16)** 同一invoice/versionを同/異idempotency keyでprovider送信し、queued→sent→delivered/rejected/failedのstatus照会を再送 | message ID/provider ID/payload hash/versionは1組、job claimとstatus historyを突合 | 同じbusiness payloadは外部送信1回。timeout/response喪失後もprovider IDでreconcileし二重送信しない |
| MOD09-16 | A,C,E,D | **BLOCKED(M-PASS/S16)** webhookの署名なし/改竄、delivered後sent、同event重複、cancelled後deliveredを実行 | 署名検証、provider event ID一意、受信順序history、invoice正本不変を突合 | 偽造は拒否、重複は冪等、逆順はstatus後退させず監査可能 |
| MOD09-17 | A,C,E,D,X | **BLOCKED(M-PASS/S16)** inbound XMLへXXE、巨大/invalid schema、同message ID、同supplier invoice no/hashを投入し、正常文書をarchive→候補match→人手review | 原本XML/PDF hash、scan/schema状態、候補link、review actorを確定保存先へ突合 | 外部entityを展開せず重複archiveなし。受信だけで発注/検収/支払済へ自動遷移しない |
| MOD09-18 | N,C,D,X,U | **BLOCKED(M-PASS/S16)** 送信済invoiceを訂正/取消し、旧messageを表示/downloadしながら再送を競合実行 | 新旧message/version/link、XML/validation/receipt/webhook artifactをappend-onlyで突合 | 訂正/取消は新履歴を作り旧messageを上書きしない。UIで各版とprovider結果を追跡可能 |

---

## 10. MOD-10: BP会社・外部要員在庫・取込・S12/S13

- 実装済み画面: `/bp-company`, `/bp-company/{id}`, `/bp-availability/list`, `/bp-availability-ingestion`, `/bp-availability-ingestion/review/{jobId}`
- 実装済みAPI: `/api/bp-companies/**`, `/api/bp-availabilities/**`, `/api/bp-availability-ingestions/**`
- 実装済みDB: `m_bp_company`, `t_bp_contact`, `t_bp_bank_account`, `t_bp_terms`, `t_bp_availability`, `t_bp_availability_ingestion`, `t_engineer_bp_affiliation`
- S12 capacity planning、S13外部ポータル: M-PASS前。未定義の`外部BP`ロールを使用しない。

| テストID | 次元 | 実行条件・操作 | DB/外部断言 | 期待結果 |
|---|---|---|---|---|
| MOD10-01 | N,D,U | 管理者/営業がBP会社、契約条件、contactを登録・更新 | master/terms/contactの有効期間とversion一致 | 一覧/detailへ再読込後も正しく表示 |
| MOD10-02 | B,E,D | 必須欠落、重複法人番号、validTo<validFrom、未知statusを送信 | 不正時全関連表0更新 | 4xx項目エラーでtransactionを部分保存しない |
| MOD10-03 | A,D | 銀行口座を登録し承認前後、権限なしpermission groupでも表示/更新 | 口座番号は暗号化、responseはmask、approval actor/time記録 | 原文をDB/DOM/logへ露出せず、承認操作を制限 |
| MOD10-04 | N,D | メール本文paste/許可file upload→parse→review補正→confirm | ingestion status遷移、confirm時availability 1件、raw/parsedの保持契約一致 | review前後を比較でき、confirm後一覧へ1件追加 |
| MOD10-05 | E,C,D | parse失敗、必須initial/company欠落、confirm二重送信、reparse中confirmを実行 | 失敗時availability 0件、二重confirmでも1件 | 状態機械どおり4xx/409、再処理可能 |
| MOD10-06 | N,C,D | availabilityをengineerへ昇格し同じIDを再送/同時送信 | engineer 1件、promoted_engineer_id、BP affiliation 1件 | 初回だけ生成、再送/競合は409で二重要員なし |
| MOD10-07 | A,S | BP会社画面（管理者/営業/manager、HR/要員）とavailability画面（menu定義全ロール）を直URL/APIで比較 | 拒否操作0更新。適用scope有無をinventoryへ明記 | V70/V45の実権限に一致し、名称だけで権限を推測しない |
| MOD10-08 | P,U | 300人データでBP会社/在庫/取込jobを検索・page・reviewし大きい本文も試験 | page total、p95、SQL回数、payload/DOM node数を保存 | 上限内、N+1なし、review差分とerror行が操作可能 |
| MOD10-09 | D,A | **BLOCKED(M-PASS/S12)** position/allocation/scenarioのroute・migration・owner/action inventoryを生成 | method+path、物理保存先、FK、version、owner/share規則を実装後inventoryへmapping | 不足0まで後続S12ケースを実行せず、予定route/tableを実在扱いしない |
| MOD10-10 | N,B,D,U | **BLOCKED(M-PASS/S12)** positionへrole、must/nice skill、rate、開始/終了、location、FTEを登録しopen→candidate→filled/hold/cancelを遷移 | position version/statusとproposal/contract link、filled countを確定保存先へ突合 | 許可遷移だけ成功し、候補/契約の増減で充足数が自動整合 |
| MOD10-11 | B,E,C,D | **BLOCKED(M-PASS/S12)** allocation FTEを0/50+50/100/60+50/100超で登録し、超過理由/承認なし・ありを同時送信 | 月/期間別FTE合計、exception approval、versionを独立計算 | 0/100境界を固定し、100超は理由と承認規則なしでは拒否、競合でも超過をすり抜けない |
| MOD10-12 | B,C,D | **BLOCKED(M-PASS/S12)** allocationの開始=終了、隣接、1日重複、open-ended、月跨ぎ、同時期間追加を実行 | 半開/包含の確定契約、overlap検知、rollbackをinventoryへ突合 | 許可境界だけ保存し、同じ期間を月次集計で二重計上しない |
| MOD10-13 | N,E,D | **BLOCKED(M-PASS/S12)** plan allocationとactual contractを別々に変更し、休暇・退職予定・契約終了/更新・availabilityを反映 | plan/actualのsource IDと月次seriesを独立突合 | actual変更でplan正本を上書きせず、availability要因を対象月だけに反映 |
| MOD10-14 | A,S,C,D | **BLOCKED(M-PASS/S12)** managerがscenarioを作成/共有し同条件を再計算、別owner/別組織からIDOR、同時編集を実行 | scenario owner/share/versionと派生KPIのみ更新し、real contract/proposal/allocation更新0 | scenario間・本番データを隔離し、閲覧者が自動提案/契約を発生させない |
| MOD10-15 | N,S,D | **BLOCKED(M-PASS/S12)** skill/role/location別の需要・供給・不足・余剰・bench costとmatching説明を算出 | position/allocation/availabilityの期待集合とKPIを独立計算 | scope内の不足を再現し、matchingは理由付き候補提示のみで人手承認まで状態を変えない |
| MOD10-16 | C,P,U | **BLOCKED(M-PASS/S12)** 300人×24か月×複数scenarioを25→300 VUで再計算しfilter/drilldownを操作 | 件数、series hash、p95、SQL回数、heap、cache key/invalidationを実測 | 同一inputは決定的、scenario cross-hit/N+1/古い系列なし、24か月UIがpage/scroll可能 |
| MOD10-17 | D,A | **BLOCKED(M-PASS/S13)** 独立host/security chain/identity store/DTO/route inventoryを内部`sys_user`系と比較 | 外部組織/user link、cookie名、CSRF、permission、method+pathを実装後inventoryへmapping | 内部5ロールや内部session/menu/APIを外部主体へ再利用せず、不足0まで後続を実行しない |
| MOD10-18 | B,E,C,D | **BLOCKED(M-PASS/S13)** invite tokenのhash保存、期限直前/直後、一度使用後再利用、指定email不一致、同時acceptを実行 | 原文token非保存、used/expired/revoked状態と外部identity生成数を突合 | 有効な指定宛先の初回だけ成功し、再利用/期限切れ/競合は外部account 0件 |
| MOD10-19 | A,C,D,X | **BLOCKED(M-PASS/S13)** 外部専用cookieでlogin→TOTP→terms同意→session更新し、内部cookie/CSRF流用、recovery、contact無効化を試験 | MFA、terms version/consent、session revoke、CSRF resultを確定保存先へ突合 | MFA/現行terms同意前は業務不可。独立cookie+CSRFを強制し、contact無効化で即時失効 |
| MOD10-20 | A,S,D | **BLOCKED(M-PASS/S13)** customer A/B、BP A/Bで全portal endpoint×HTTP methodのpath/body/query/file IDを総当たり差替え | 他tenant/orgの返却・更新・download・存在確認が0件。owner keyをserver-sideで突合 | GETだけでなくPOST/PUT/DELETE/downloadも403/404へ正規化し、連番IDから存在を漏らさない |
| MOD10-21 | N,E,C,D,U | **BLOCKED(M-PASS/S13)** customerが見積/注文/契約/work report/検収/invoiceを閲覧し、検収accept/rejectを二重送信 | external action、comment/attachment、internal target version、notificationを業務キーで突合 | 検収は一度だけ反映し、公開allow-list外の原価/支払済状態/内部memoを返さない |
| MOD10-22 | N,A,C,D,U | **BLOCKED(M-PASS/S13)** BPがavailability更新/停止、注文条件閲覧、invoice/report提出、銀行口座変更申請を実行 | review前後version、source org、approval requestを突合し、内部金額/支払status更新0 | 自社分だけ操作し、銀行変更は承認前にactive化せず、BPが支払状態を改変できない |
| MOD10-23 | E,A,D,X | **BLOCKED(M-PASS/S13)** DTOへ原価/role/tenant/paid等の余剰fieldを混入し、拡張子偽装・infected/unscanned fileをupload/download | allow-list bind結果、quarantine/scan/hash、孤児blob、access auditを突合 | mass assignmentを拒否/無視し、CLEANかつACL合格fileだけdownload、storage key/PII非表示 |
| MOD10-24 | B,C,P,U | **BLOCKED(M-PASS/S13)** login/invite/download/upload/acceptanceを閾値直前/直後と300主体で並列実行し、通知失敗も注入 | rate-limit key、request/action一意、audit/notification/outbox、p95を実装契約へ突合 | tenant間でlimitを共有せず、二重accept/download漏洩/500なし。失敗通知を業務commitと混同しない |

---

## 11. MOD-11: BP支払・S15会計仕訳/全銀FB

- 実装済み画面: BP支払は `/invoice` 内のタブ/操作として提供（独立したBP支払ページは存在しない）
- 実装済みAPI: `/api/work-records/{id}/bp-payments`, `/api/invoices/bp-payments/**`
- 実装済みDB: `t_bp_payment`, `t_work_record`, `m_bp_company`, `t_bp_terms`
- S15会計仕訳/全銀FB: M-PASS前。財務主体は管理者又は確定した会計担当permission groupで検証する。

| テストID | 次元 | 実行条件・操作 | DB/外部断言 | 期待結果 |
|---|---|---|---|---|
| MOD11-01 | N,D,U | 確定工数からBP支払を作成し親/子layer、支払先、条件snapshotを保存 | `t_bp_payment` のwork_record、layer_order、amount、terms snapshot一致 | invoice画面のBP支払タブに未払として表示 |
| MOD11-02 | B,E,D | amount=0/1/上限/負数、同一layer order、存在しないworkRecord/BP会社を送信 | 許可境界のみ保存、不正時関連行0件 | 4xxで拒否し親子合計や順序を壊さない |
| MOD11-03 | N,D | 親子layerを追加/更新/削除し、親合計・cost center・snapshotの不変/更新規則を確認 | 対象行version更新、論理削除、他layer不変 | 階層と表示合計が一致し削除後も監査可能 |
| MOD11-04 | A,S | 管理者/会計担当permission groupと非許可主体で一覧、作成、layer変更、支払済更新を直送 | 拒否時0更新。組織/sales scope適用有無をinventoryどおり突合 | 未定義`経理`ロールを使わずaction単位で許可 |
| MOD11-05 | C,D | 同一支払を2セッションで編集/支払済化、二重作成、同一version更新 | version CASで片方だけ成功、支払行/approval request重複0件 | stale更新409、二重支払なし |
| MOD11-06 | E,D | 締め済み月の作成/更新/削除、承認失敗、transaction途中例外を注入 | `t_bp_payment` とclosing/approval関連が全不変 | 月次締めガードを迂回せず全rollback |
| MOD11-07 | N,D | **BLOCKED(M-PASS/S15)** route/format inventory確定後、請求・入金・BP支払から仕訳を生成 | 仕訳物理保存先、借方/貸方、税、業務キーを確定migrationと突合 | 借方合計=貸方合計、円単位・勘定科目規則が一致 |
| MOD11-08 | B,E,X | **BLOCKED(M-PASS/S15)** 0円、取消、月跨ぎ、文字コード、CSV injection、同一月再出力を検証 | 出力hash/履歴/冪等規則を実装契約に従って断言 | 不正対象を除外又は4xx、再出力を追跡可能 |
| MOD11-09 | A,C,D | **BLOCKED(M-PASS/S15)** FB出力を会計担当2セッションで同時実行し、非許可主体も直送 | 二重振込防止キー、承認、監査、失敗時記録を確定スキーマと突合 | 一方のみ確定し二重FBなし、権限なしはdownload 0件 |
| MOD11-10 | P,U,X | **BLOCKED(M-PASS/S15)** 300人分の仕訳/FBを生成し件数・合計・p95・ファイル検証 | source件数との完全突合、全銀validator、memory/生成時間を保存 | 欠落/重複0、基準内、UIに対象件数/合計/再実行状態を表示 |

---

## 12. MOD-12: S14 要員セルフサービスポータル v2

現時点で、S14固有のプロフィール/資格/経費画面・API・DBはM-PASS前である。既存の `/my/timesheet`, `/my/attendance`, `/my/leave`, `/api/profile/**` はMOD-08/MOD-01の対象であり、S14 v2実装済みの根拠にはしない。以下はすべて `BLOCKED(M-PASS/S14)` とする。

| テストID | 次元 | 実行条件・操作 | M-PASS後の断言 | 期待結果 |
|---|---|---|---|---|
| MOD12-01 | D,A | **BLOCKED(M-PASS/S14)** route/migration/entity/permission/UI inventoryを生成し相互照合 | 全method+path、物理表、FK、owner key、action keyが対応 | 不足0になるまで他ケースを実行しない |
| MOD12-02 | N,U | **BLOCKED(M-PASS/S14)** 要員が本人プロフィールを表示し、営業側の許可された表示と比較 | owner user/engineer linkと表示項目を物理保存先に突合 | 本人値が再読込後も一致し、編集可能/参照のみを区別 |
| MOD12-03 | N,D | **BLOCKED(M-PASS/S14)** skill/資格を追加・更新・期限切れ化し既存engineer skillとの同期を実行 | source of truth、同期方向、履歴行を実装契約と突合 | 二重skillや古い資格を作らず営業画面へ規定タイミングで反映 |
| MOD12-04 | B,E | **BLOCKED(M-PASS/S14)** 空skill、重複、資格期限=取得日/以前、金額0/上限超過等を送信 | 不正時全更新0件 | 4xx項目エラーで入力を保持 |
| MOD12-05 | A,S | **BLOCKED(M-PASS/S14)** member001がmember002のIDをpath/bodyへ差し込み、営業/HR/managerも直接API実行 | 他人のプロフィール/経費/添付に読書き0件 | 本人所有と管理actionを分離しIDORを403/404へ正規化 |
| MOD12-06 | N,D,X | **BLOCKED(M-PASS/S14)** 経費を申請し許可形式の領収書をupload、管理側で承認/却下 | expense、file metadata、approval、通知が同一業務キーで関連 | 申請中→承認/却下を表示し、金額/ファイルを再読込可能 |
| MOD12-07 | E,X,D | **BLOCKED(M-PASS/S14)** 空file、上限超過、拡張子偽装、scan infected/unavailable、storage失敗を注入 | expense確定0件又は仕様化したdraftのみ、孤児blob/metadata 0 | download不能、秘密path非表示、安全に再送 |
| MOD12-08 | C,D | **BLOCKED(M-PASS/S14)** 保存/申請を二重クリックし同一request key、異なる金額で同時更新 | 業務レコード/approval各1件、version競合を記録 | 二重申請・二重精算なし、stale側へ409 |
| MOD12-09 | A,D | **BLOCKED(M-PASS/S14)** 管理者/会計担当permission groupで経費管理、5ロールの許可/拒否表を全実行 | 拒否操作0更新、監査result一致 | `経理`等の新ロールを暗黙追加しない |
| MOD12-10 | C,P,U | **BLOCKED(M-PASS/S14)** 有効要員254件を段階並列でプロフィール表示/保存/申請しモバイル操作 | manifest成功/拒否、p95、SQL回数、upload帯域、DOM時間を保存 | 無効member200拒否、scope漏洩/二重保存/500なし、基準内 |

---

## 13. MOD-13: S17 AIフィードバック学習

現時点でS17固有の学習画面・API・DBはM-PASS前である。既存AI matchingはMOD-05、提案文生成はMOD-06で検証する。以下はすべて `BLOCKED(M-PASS/S17)` とし、学習済みモデルや永続ログの存在を仮定しない。

| テストID | 次元 | 実行条件・操作 | M-PASS後の断言 | 期待結果 |
|---|---|---|---|---|
| MOD13-01 | D,A | **BLOCKED(M-PASS/S17)** route/model-job/migration/permission/provider inventoryを照合 | input snapshot、model version、job、auditの物理対応が全て存在 | manifest不足0まで実行開始しない |
| MOD13-02 | N,D | **BLOCKED(M-PASS/S17)** 成約/見送りfixtureを学習入力としてdry-run→確定 | 対象件数、除外理由、feature version、model versionを保存先と突合 | UI preview件数と確定入力が一致 |
| MOD13-03 | B | **BLOCKED(M-PASS/S17)** 0件、1件、閾値直前/直後、同一engineer/projectの重複結果を実行 | dedupe/weight規則を期待計算と突合 | 0除算やNaNなし、境界が決定的 |
| MOD13-04 | E,D | **BLOCKED(M-PASS/S17)** 欠損score、不明status、将来日、PII混入、provider timeout/不正responseを投入 | model current pointer不変、失敗job/auditのみ規定どおり記録 | 4xx/5xxを区別し旧モデルを継続利用 |
| MOD13-05 | A | **BLOCKED(M-PASS/S17)** 管理者/AI管理permission groupと営業/HR/manager/要員でpreview/start/promote/rollbackを直送 | 非許可時job/model更新0件 | action単位で最小権限、model情報の過剰開示なし |
| MOD13-06 | S,D | **BLOCKED(M-PASS/S17)** sales01/02由来データ、組織異動前後、削除/匿名化対象を入力母集団で確認 | 許可された集約/匿名化規則とsource ID集合を突合 | 担当外PIIをprompt/log/model metadataへ混ぜない |
| MOD13-07 | C,D | **BLOCKED(M-PASS/S17)** 同一idempotency key再送、2管理者同時start、promoteとrollback競合 | active job/current modelが最大1、version履歴欠落0 | 一方だけ開始/昇格し、競合409又は冪等応答 |
| MOD13-08 | E,D | **BLOCKED(M-PASS/S17)** 学習完了直前、metadata保存、pointer更新の各地点で故障注入 | current modelは旧版又は完全な新版のどちらか、孤児をcleanup可能 | 半端なモデルを配信しない |
| MOD13-09 | N,U,D | **BLOCKED(M-PASS/S17)** 新旧モデルの評価指標、差分、承認、rollback履歴を画面操作 | 指標の母数、version、actor/timeをDB/監査と突合 | 「精度向上」の固定文言でなく測定値と閾値を表示 |
| MOD13-10 | P,U | **BLOCKED(M-PASS/S17)** 300人規模の提案結果でpreview/train/evaluateを計測し、進捗更新/取消を操作 | p95/総時間/memory/provider call数、結果再現性を保存 | timeout/重複callなし、進捗が停止しても復旧可能 |

---

## 14. MOD-14: 組織・管理会計・営業歩合・ダッシュボード

- 実装済み画面: `/`, `/organization`, `/management-accounting`, `/sales-performance`, `/system-config`
- 実装済みAPI: `/api/dashboard/**`, `/api/organizations/**`, `/api/management-accounting/**`, `/api/sales-performance/**`, `/api/system-configs/**`
- 実装済みDB: `m_organization_unit`, `t_user_organization`, `m_cost_center`, `t_management_budget`, `t_monthly_accounting_dimension`, `m_system_config`（営業歩合snapshot表は存在せず都度計算）

| テストID | 次元 | 実行条件・操作 | DB/外部断言 | 期待結果 |
|---|---|---|---|---|
| MOD14-01 | N,A,U | 管理者/営業/HR/managerで`/`、要員で`/`へアクセス | DB不変 | 4管理ロールはdashboard、要員は`/my/timesheet`へ転送し「全ロールdashboard」と誤認しない |
| MOD14-02 | N,D | 稼働/Bench/退場予定、確定工数あり/なしのfixtureでKPIを表示 | 利用率、売上、粗利、bench数を共通計算Service/独立SQLと突合 | 金額は円、0分母の表示規則、対象月が一致 |
| MOD14-03 | S,A,D | sales01/02、manager組織、異動前後でdashboard/profit/forecastを比較 | scope keyごとの期待ID/金額集合を突合 | 他営業/他組織の件数・金額・ラベルを漏らさない |
| MOD14-04 | C,D | dashboard cache warm後に担当営業、組織所属、scope設定、契約を変更 | 関連cacheをinvalidateし、別scope keyを上書きしない | TTL待ちなしで次表示に反映、cross-user cache hitなし |
| MOD14-05 | N,B,D | 既定歩合（粗利/売上、0/15/100%）、契約override、負粗利、未帰属、更新契約を計算 | floor(base×rate/100)、負値0、更新成約除外、未帰属commission 0を突合 | sales performance合計と契約明細が一致 |
| MOD14-06 | E,A,D | commission rate -0.01/100.01、unknown key、営業からsystem config更新を送信 | `m_system_config` 0更新、scope cache不変 | 管理者だけ更新、schema validationで400 |
| MOD14-07 | N,B,D | 組織作成、親子付替、user所属のvalid_from/to境界、merge/status変更を実行 | cycleなし、履歴期間非重複、current所属一意 | 過去月集計を保持し現在組織だけ更新 |
| MOD14-08 | C,E,D | 同じuserを別組織へ同日同時transfer、組織を同時merge | 一方のみcurrent、version/CAS整合、cycle/重複0 | stale側409、全関連transaction rollback |
| MOD14-09 | N,S,D | 月次管理会計summary/drilldown/exportを全社/組織/cost center/customer/project/salesで絞込 | summary行+detail行のrevenue/cost/gross profitが同一母集団 | exportと画面合計一致、scope外detailなし |
| MOD14-10 | B,E,D | 予算JSON/CSVで0、負数、200/201行、列不足、不正日付、stale versionを取込 | 200行まで全件commit、1行でも不正なら方針どおり全rollback | CSV経路もBean Validation同等、injection無害化 |
| MOD14-11 | C,D | 月次締めsnapshotと組織異動/予算更新を同時実行 | snapshotのasOf所属・source一意、同月重複0 | 締め時点の管理会計を再現できる |
| MOD14-12 | P,U | 300人KPI、25営業performance、組織drilldown/exportをcold/warmで計測 | p95/SQL回数/cache hit/payload/CSV件数を保存 | 基準内、N+1なし、グラフ/表/keyboard/狭幅が正常 |

---

## 15. MOD-15: 承認・見積・注文・検収・文書保管

- 実装済み画面: `/approval`, `/approval/inbox`, `/approval/requests`, `/approval/routes`, `/quotation`, `/sales-order`, `/acceptance`, `/document/list`, `/document/detail/{id}`
- 実装済みAPI: `/api/approval/**`, `/api/quotations/**`, `/api/sales-orders/**`, `/api/acceptances/**`, `/api/documents/**`
- 実装済みDB: `m_approval_route`, `m_approval_route_step`, `t_approval_request`, `t_approval_action`, `t_approval_participant`, `t_quotation`, `t_sales_order`, `t_sales_order_line`, `t_acceptance`, `t_document`, `t_document_version`, `t_document_hash_claim`, `t_document_access_log`, `t_document_disposal_request`

| テストID | 次元 | 実行条件・操作 | DB/外部断言 | 期待結果 |
|---|---|---|---|---|
| MOD15-01 | N,D,U | 見積を作成しPDF preview/download | `t_quotation` の番号、顧客、案件、要員、単価/精算、status=`下書き`一致 | 日本語font、円、改頁、ファイル名、再読込が正しい |
| MOD15-02 | B,E,D | 単価0/負数、精算min=max/min>max、顧客と案件不一致、要員/案件欠落で提出を試験 | 不正時quotation/contract 0更新 | 4xx/409で項目を示し部分保存なし |
| MOD15-03 | N,E,D | `下書き→提出済→受注/失注`、終端編集/削除、受注から契約ドラフト生成 | 許可statusだけ更新、受注時contract source一意 | 不正遷移拒否、受注は契約確認導線へ |
| MOD15-04 | N,D | 2段階routeで申請→第1承認→最終承認 | request current_step/status/version、action/participant、通知が各段階一致 | 次承認者だけinbox表示、最終時だけtarget Adapter適用 |
| MOD15-05 | A,S,D | 非承認者、代理期限外、別組織/別営業scope、要員がapprove/reject/returnを直送 | request/action/target更新0件、拒否監査 | 403/404へ正規化し承認者候補や対象内容を漏らさない |
| MOD15-06 | C,D | 同一stepを2承認者/2tabで同時approve、approveとrejectを競合 | version/CASでaction 1件、target適用1回 | 一方だけ成功、他方409、二重通知/二重適用なし |
| MOD15-07 | E,D | 申請中にtarget versionを別更新して最終承認、Adapter途中故障を注入 | conflict status又は全rollback、古いpayloadをtargetへ適用しない | conflictを画面表示し再申請可能 |
| MOD15-08 | N,D | 受注済見積から注文を作成し複数line、原本document、注文請PDFを登録 | order/lines/source link、document/version/hash、status履歴一致 | 条件差分を表示し原本/注文請をdownload可能 |
| MOD15-09 | E,C,D | 同一原本hash二重upload、上限超過/scan拒否、注文作成二重送信 | document/hash claim/order業務キー一意、失敗時孤児blob 0 | 409/4xxで拒否し二重注文なし |
| MOD15-10 | N,E,D | 注文status全許可辺、条件差あり/なしの契約化、契約化後取消を実行 | 差ありは承認済request必須、contract lineごと一意 | 承認迂回を拒否し、許可時だけ契約生成 |
| MOD15-11 | N,B,D | 確定工数の検収提出→検収済、差戻し理由→再提出、検収不要契約を比較 | acceptance status/version/contact/document、work month一致 | 請求可能条件と画面表示が同期 |
| MOD15-12 | E,C,D | 未確定工数、理由なし差戻し、請求済検収取消、同時検収を実行 | 不正時acceptance/invoice不変、同時操作は1件だけ成功 | 409/4xx、二重検収/請求後取消なし |
| MOD15-13 | N,E,D,X | 文書登録→版追加→確定→download。scan未完了、同hash、stale versionも試験 | version連番、sha256、scan status、access log、storage key整合 | CLEAN版だけdownload、重複/競合を拒否しアクセス監査を残す |
| MOD15-14 | A,C,D,P,U | retention前後、legal hold、申請者自身の廃棄承認、同時承認/実行を試験し、300人規模のinbox/文書一覧も計測 | hold中/期限前/自己承認は0更新。承認済だけ物理blob処理し台帳/監査保持。p95/件数保存 | 職務分離とscopeを守り、二重廃棄なし、一覧page/keyboard/狭幅も基準内 |

---

## 16. 完了判定と証跡

各テストIDは、入力fixture、実行主体、HTTP request/response、画面capture、実行前後SQL、監査correlation、所要時間を1組で保存する。`DBデータ反映先`欄の記載だけではDB検証済みとしない。

モジュール完了条件は次のすべてである。

1. 当該コミットの `R/T/V/A/S/W/X` inventoryがレビュー済みで、設計カバレッジが100%である。
2. `BLOCKED`/`NOT_RUN`を分母へ残した実行カバレッジが100%である。従って全体Exit時にはM-PASS未解除ケースが0件である。
3. 合格率が100%（FAIL 0件）である。
4. 適用可能な強制次元 `N/B/E/A/S/C/D/P/U` が全て少なくとも1件PASSしている。
5. 300人性能結果は件数、p95、SQL回数、エラー率を数値で満たし、目視の「速い」で判定していない。

本書のテストID数は機械集計し、重複0件をCIで確認する。テストID数そのものは品質ゲートではなく、インベントリ分母に対する設計・実行証跡が品質ゲートである。
