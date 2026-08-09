# Defect Catalog — 300人規模 E2E（2026-08-09）

自動検出（第1ラウンド `e2e-issues.json` 156件 / 第2ラウンド
`round2/round2-issues.json` 26件）を実体ごとに集約した最終カタログ。
状態: **FIXED**（修正済み・回帰検証済み）／**確認済み（仕様通り）**。

## 凡例

- P0: 即時対応必須（ログイン不能・データ破壊など）— 該当なし
- P1: 主要業務を阻害（表示不能・操作不能・権限不整合）
- P2: 一部機能を阻害（JSエラー・404・データ不整合）
- P3: 軽微（表記・権限データの不整合など）

## 検出方法

- Playwright（Chromium）で全5ロール × 全メニューをデスクトップ1440×900 / モバイル390×844で巡回
- HTTPステータス / console error / page error / 横方向はみ出し / サイドバー可視性を自動取得
- 代表的なCRUD（顧客登録・ToDo作成・候補者ステージ変更・要員タイムシート）と
  同時ログイン（10 + 34アカウント）を追加実施
- スクリーンショット: `.kiro/specs/scale-300-e2e/evidence/`

第2ラウンドの追加検証（`ops/e2e/scale-300/round2-deep.mjs`）:
- 既知障害の完全スタックトレース取得（ファイル・行まで特定）
- 要員・顧客・案件・契約・ToDo・候補者のページング（最終ページまで）
- 要員名検索・横断検索、主要モーダル（要員/顧客/契約/ToDo）
- 404/403/エラーページ、API権限マトリクス、モバイルドロワー
- V2初期要員3名（s300.member253〜255）のログインとタイムシート表示

---

## D-001 [確認済み・仕様通り] 「承認ワークフロー」の403は /approval/routes のみ

- 対象: 営業 / HR / マネージャー
- 自動ID（第1ラウンド）: E2E-027/028, 047/048, 070/071, 117/118, 129/130, 144/145
- 事象: 第1ラウンドは `/approval/routes` への直接遷移で HTTP 403 を検出。
- 第2ラウンド結果:
  - `/approval/inbox` / `/approval/requests` は営業/HR/マネージャーとも **200** で描画OK
  - `/approval/routes` は `ApprovalPageController.routes()` の
    `@PreAuthorize("hasRole('管理者')")` により管理者専用（仕様）
  - サイドバーでは `sec:authorize="hasRole('管理者')"` により
    `/approval/routes` リンク自体が非管理者に非表示
- 結論: 403は正しい権限制御。第1ラウンドのロール×全メニュー巡回データ
  （`role-pages.tsv`）が管理者専用ルートを全ロールへ割り当てていたための検出で、
  UI上の不整合ではない。カタログからはクローズ扱いとする。

## D-002 [P1] 提案カンバンで `renderKanbanCard is not defined`

- 対象: 全ロール（デスクトップ・モバイル）
- 自動ID: E2E-001〜006, 020〜025, 040〜045, 063〜068, 091〜096, 110〜115, 122〜127, 137〜142
- 事象: `/proposal/kanban` の読み込みで1ページあたり6回
  `ReferenceError: renderKanbanCard is not defined` が発生。カード描画処理が動いていない。
- 原因（確定）: `src/main/resources/static/js/modules/proposal-kanban.js:174` が
  `renderKanbanCard(item, colBody)` を呼ぶが、同ファイルの実装は
  `createKanbanCard(item)`（約249行目）に改名済みで、`renderKanbanCard` が存在しない。
- 修正: 呼び出しを `colBody.append(createKanbanCard(item))` に変更。
- 状態: **FIXED**（第3ラウンド E2E でカンバン描画エラー0件）
- 期待: カンバンカードが描画され、ステータス変更操作が可能であること。

## D-003 [P1] 契約一覧で `Cannot read properties of undefined (reading '11')`

- 対象: 全ロール（デスクトップ・モバイル）、URLは `/contract/gantt`
- 自動ID: E2E-007, 026, 046, 069, 097, 116, 128, 143
- 事象: `/contract/gantt` で `TypeError: Cannot read properties of undefined (reading '11')`
  が発生し、ガントチャートが描画されない。
- 原因（確定）: `src/main/resources/static/js/modules/contract-gantt.js:75` が
  Frappe Gantt に `language: 'ja'` を渡すが、バンドル済み
  `lib/frappe-gantt/frappe-gantt.min.js` に `ja` ロケールが無いため、
  `format()` が `r[s][+i[1]]`（`r['ja']` は undefined）を評価して例外になる。
  スタック: `Object.format → get_date_info → make_dates → render → new Gantt`
- 修正: vendored `frappe-gantt.min.js` の月名マップへ `ja`（1月〜12月）を追加し、
  `language: 'ja'` を維持したまま描画できるようにした。
- 状態: **FIXED**（第3ラウンド E2E でガントページエラー0件）
- 期待: サポート済み言語（`en`）を使う、または日本語ロケールを同梱する。
- 補足: `/contract/list` 自体は第2ラウンドで252件・13ページのページングが正常。

## D-004 [P2] 要員詳細で `loadAccountLink is not defined`

- 対象: 全ロール（デスクトップ・モバイル）
- 自動ID: E2E-018/019, 039, 059, 082, 108/109, 121, 133, 148
- 事象: `/engineer/detail?id=1001` などで `loadAccountLink is not defined` が発生。
- 原因（確定）: `src/main/resources/static/js/modules/engineer-account-link.js:6` の
  `document.addEventListener('DOMContentLoaded', loadAccountLink)` が、
  8行目の `window.loadAccountLink = function ...` より先に実行されるため
  スクリプト読み込み時に ReferenceError になる。
- 影響: アカウント連携カードが常に空（例: id=1 はDB上
  `s300.member253` と連携済みだが、UIには `—` のまま）。
- 修正: `addEventListener` を `window.loadAccountLink` の定義後に移動。
- 状態: **FIXED**（id=1 で `#397` 表示を確認）
- 期待: アカウント連携表示が定義済み関数で描画されること。

## D-005 [P2] メニュー権限データに未実装ルートが存在（404）

- 対象: `/skill-tag` `/search` `/tasks` `/saved-views` `/batch-operations`
- 自動ID: E2E-008〜017, 029〜038, 049〜058, 072〜081, 098〜107 ほか
- 事象: `m_menu` / `t_role_menu` に存在するが、対応するページコントローラが無く
  直接URLが404になる。サイドバーには表示されていない（ユーザー影響は小さい）。
- 修正: V101 で `search` / `tasks` / `skill-tag` / `saved-views` /
  `batch-operations` の m_menu 行と t_role_menu 付与を撤去。
  API は t_permission_group_action（V66_1 / V74）で継続利用可能。
- 状態: **FIXED**（ページUIは spec どおり次フェーズで再登録）
- 期待: 実装予定ルートはメニューデータから外す、またはページを実装する。

## D-006 [P2] 要員ロールの権限データに参照不可ルートが存在（403）

- 対象: 要員ロールの `/search` `/tasks` `/saved-views` `/batch-operations`
- 自動ID: E2E-083〜090, 149〜156
- 事象: `t_role_menu` で要員に付与されているが、SecurityConfig が拒否して403。
  サイドバーには表示されないためUI上の事故はないが、権限データが不整合。
- 修正: V101 で全ロール（要員含む）の付与を削除。
- 状態: **FIXED**
- 期待: 要員には付与しない。

## D-007 [P2] マネージャーの案件一覧でリソース404

- 対象: マネージャー（デスクトップ・モバイル）
- 自動ID: E2E-062, 136
- 事象: `/project/list` で console に404リソースロードエラーが1件発生。
- 原因（確定）: favicon 未設定のためブラウザが `/favicon.ico` を自動要求し404。
  さらに favicon.svg 追加後は、SecurityConfig の permitAll に含まれておらず
  要員ロールで `/favicon.svg` が403になっていた。
- 修正: `static/favicon.svg` を追加し、base / login / error の各HTMLに
  `<link rel="icon">` を宣言。SecurityConfig の静的リソース permitAll へ
  `/favicon.svg` `/favicon.ico` を追加。
- 状態: **FIXED**（要員の `/my/timesheet` でも4xx/consoleエラーなし）
- 期待: 静的リソース/APIの参照先がすべて200であること。

## D-008 [P3] 権限データと実装の乖離（メニュー非表示だがDB付与）

- `search` / `tasks` / `saved-views` / `batch-operations` / `skill-tag` が
  `t_role_menu` に残っており、`GlobalControllerAdvice` の許可集合と
  SecurityConfig / ページコントローラ実装が一致していない。
- UI上の表示はされていないが、今後メニュー表示条件を変えると404/403事故の温床になる。
- 修正: V101 で未実装5ルートを m_menu / t_role_menu から削除。
- 状態: **FIXED**

## D-009 [P2] スキルシート/案件メール取込のレビュー画面が404（データなし）

- 対象: HR `/resume-ingestion/review/1`（第3ラウンド E2E で検出）
- 事象: 取込ジョブのテーブルにシードが無く、レビューURLが404になる。
  一覧画面も空のため開発中に「空虚感」が出る原因になっていた。
- 修正: シード生成器へ `t_resume_ingestion` 6件（要確認2/確定済1/却下1/失敗1/
  取込待ち1）と `t_project_ingestion` 5件（要確認2/確定済1/失敗1/取込待ち1）を追加。
  レビュー画面で表示される parsed_json も日本語の実データを設定。
- 状態: **FIXED**（HRレビュー/マネージャー案件取込レビューとも200）

## D-010 [P3] 取込レビュー画面のボタン文言が中国語

- 対象: `resume-ingestion.js` / `project-ingestion.js` / `bp-availability-ingestion.js`
- 事象: 「删」「経歴删除」が日本語UIに混在。`resume-ingestion.js` は「業為・役割」の
  誤字もあった。
- 修正: 「削除」「経歴削除」「業界・役割」へ統一。
- 状態: **FIXED**（`rg` で該当文言0件）

## D-011 [P2] 案件詳細リンクが404（案件詳細ページ未実装）

- 対象: `project-ingestion.js` の「案件詳細」リンクと確定フロー遷移先
- 事象: 取込案件を確定済みにすると `/project/detail/{id}` へ遷移するが、
  対応するページコントローラが存在せず404になる（既存コードのリンク切れ）。
  今回のシード追加で確定済み案件が表示されるようになり顕在化した。
- 修正:
  - `ProjectPageController` に `/project/detail?id={id}` を追加
  - `templates/project/detail.html` と `static/js/modules/project-detail.js` を新設
  - `project-ingestion.js` のリンク3箇所を `/project/detail?id=` 形式へ統一
- 状態: **FIXED**（id=5001 で全項目表示、存在しないIDは「案件が見つかりません」）
- 補足: マネージャーは組織データスコープのためアクセス可能な案件5100で検証。
  権限外案件（5001）はAPIが404を返し、画面は「案件が見つかりません」を表示。

## レビュー指摘対応（2026-08-10）

- 取込シードの変換先を実データへ整合:
  - `t_resume_ingestion` id=3 は「高橋 博之」→ 要員1029（名前・単価・経験年数が一致）
  - `t_project_ingestion` id=3 は案件5001「公共機関向け申請システム開発（Phase3）」と
    案件名・単価・勤務地・期間・スキルが一致
- `project-detail.js` のフィールド名を `Project` エンティティ（unitPriceMin /
  unitPriceMax / remoteType / commercialFlow）へ修正し、単価とリモート表示を確認

---

## 追加検証（合格）

- 顧客新規登録（管理者）: 成功
- ToDo作成 → 完了（管理者）: 成功
- 候補者ステージ変更（HR）: 成功
- 要員マイ勤怠表示（モバイル）: 成功
- 同時ログイン: 10/10（第1ラウンド）＋34/34（第2ラウンド）成功（deadlock/500なし）
- 全ページの横方向はみ出し: 自動検出0件
- ページング: 要員255件/26P、顧客38件/4P、案件103件/11P、契約252件/13P、
  ToDo100件/5P、候補者45件/5P — 最終ページまで遷移OK
- 検索: 要員名「田中」・横断検索「田中」が該当者を表示
- モーダル: 要員11項目・顧客5項目・契約16項目・ToDo5項目を開いて確認
- モバイル: サイドバードロワー開閉、モーダルスクロール、横はみ出しなし
- V2初期要員: s300.member253〜255 がマイタイムシートへログイン・表示OK
- API権限: 管理者以外の `/api/users` `/api/role-menus` は403 JSON、
  要員の `/api/engineers` `/api/customers` は403 JSON（いずれも正常）

## 第2ラウンドで再確認されたOPEN問題

- D-002（カンバン）・D-003（ガント）・D-004（アカウント連携）は
  第2ラウンドでも再現し、原因ファイル・行まで特定済み。
- D-005 / D-006（未実装ルートの404 / 要員403）は第2ラウンドでも再現。
- 契約一覧・ガントは別機能であり、一覧側は正常、ガント側のみ障害（D-003修正）。

## 修正ラウンド（第3ラウンド）の実績

- 実施日時: 2026-08-09
- 変更:
  - `proposal-kanban.js` : カード描画関数呼び出しを修正（D-002）
  - `frappe-gantt.min.js` : 日本語月名ロケールを追加（D-003）
  - `engineer-account-link.js` : イベント登録順を修正（D-004）
  - `V101__remove_unimplemented_menu_routes.sql` : 未実装5ルートの
    m_menu / t_role_menu を撤去（D-005 / D-006 / D-008）
  - `TodoPageController` : `/tasks` を `/todo` へリダイレクト（互換入口）
- 検証（空DBからマイグレーション適用後の `ses_manager_ui_test_300`）:
  - 第2ラウンド深掘りE2E再実行: **0問題 / 54チェック全てOK**
  - 要員詳細 id=1 のアカウント連携カードが `#397` を表示
  - 営業ロールで `/api/search` `/api/tasks/page` `/api/skill-tags`
    `/api/saved-views` が200（メニュー撤去後もAPIは action permission で利用可）
  - 未実装ルートが `m_menu` / `t_role_menu` に存在しないことをSQLで確認
  - 全54 JSファイル `node --check` 成功
  - 34/34 同時ログイン成功

## 修正ラウンド（第4ラウンド）の実績

- 実施日時: 2026-08-10
- 変更:
  - `SecurityConfig` : `/favicon.svg` `/favicon.ico` を permitAll へ追加（D-007）
  - `static/favicon.svg` 追加 + base/login/error に icon 宣言（D-007）
  - `generate-seed.mjs` : スキルシート取込6件 / 案件メール取込5件を追加（D-009）
  - `resume-ingestion.js` / `project-ingestion.js` / `bp-availability-ingestion.js` :
    中国語ボタン文言を日本語へ修正（D-010）
  - `run-e2e.mjs` : `/approval/routes` を管理者専用EXTRA_PAGESへ移動し、
    取込レビューURLを追加
- 検証（再構築DB）:
  - 第1ラウンド全メニューE2E再実行: **0問題**
  - 第2ラウンド深掘りE2E再実行: **0問題 / 54チェック全てOK**
  - 要員 `/my/timesheet` で favicon 含め4xx/consoleエラーなし
  - HR `/resume-ingestion/review/1` / マネージャー `/project-ingestion/review/1` が200
  - `/approval/routes` は管理者200 / 営業403（意図通り）

## 修正ラウンド（第5ラウンド）の実績

- 実施日時: 2026-08-10（コードレビュー指摘対応）
- 変更:
  - `ProjectPageController` + `project/detail.html` + `project-detail.js` を追加
  - `project-ingestion.js` の詳細リンクを `?id=` 形式へ修正（D-011）
  - 取込シードの変換先（要員1029 / 案件5001）と parsed_json を整合
  - `resume-ingestion.js` のインデント修正（add-skillテンプレート）
- 検証:
  - `/project/detail?id=5001`: 200、案件名/単価/勤務地/リモート/期間を表示
  - マネージャーはデータスコープ内の `/project/detail?id=5100` で200を確認
  - `/project/detail?id=9999`: 「案件が見つかりません」を表示（4xx/consoleなし）
  - 要員1029 / 案件5001 の名前が parsed_json と一致

## UI設計メモ（障害ではない改善候補）

- 契約モーダルは可視項目16個と多く、モバイル390×844ではモーダル本文が
  約831pxまで伸びる。スクロール自体は正常だが、入力項目のグルーピングや
  2段組化を検討すると操作性が上がる。
- 要員詳細のアカウント連携カードは、DB連携済み（例: 田中 太郎 → s300.member253）
  でもD-004のJSエラーにより常に未連携表示になる。修正後は
  連携済み表示と候補者選択UIの両方を再確認すること。
- 未実装ルート（skill-tag/search/tasks/saved-views/batch-operations）は
  サイドバー非表示だが、URL直打ちで404/403の統一エラーページになる。
  エラーページ自体のレイアウトは正常。開発中のメニューは権限データから
  除外しておくと事故を防げる。
- 横断検索の結果はカテゴリ別に要員/顧客/案件などをまとめて表示できており、
  300人規模でもレスポンス・表示とも正常。

## 未完了の自動テスト

`mvn test` は Testcontainers のMySQLマイグレーション検証（Flyway系）が遅く、
20分の実行時間制限内に完了しなかった。途中経過ではコンテナ起動・マイグレーション成功を確認。
最終gateとしてはCI（Docker有効）で再実行が必要。
