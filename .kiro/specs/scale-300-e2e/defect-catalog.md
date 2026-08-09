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
- 第2ラウンド結果: マネージャーで `/project/list` を再検証したが404リソースは
  **再現せず**（0件）。初回検出時はトランザクション性の高い状態だった可能性が高い。
- 状態: 再現待ち（現時点ではOPENのまま追跡、UI上は影響なし）。
- 期待: 静的リソース/APIの参照先がすべて200であること。

## D-008 [P3] 権限データと実装の乖離（メニュー非表示だがDB付与）

- `search` / `tasks` / `saved-views` / `batch-operations` / `skill-tag` が
  `t_role_menu` に残っており、`GlobalControllerAdvice` の許可集合と
  SecurityConfig / ページコントローラ実装が一致していない。
- UI上の表示はされていないが、今後メニュー表示条件を変えると404/403事故の温床になる。
- 修正: V101 で未実装5ルートを m_menu / t_role_menu から削除。
- 状態: **FIXED**

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
