# Implementation Plan — フロント共通基盤の残バグ修正(frontend-common-hardening)

3タスクは互いに独立。上から順に実施を推奨。編集対象は design.md 冒頭のリストに限定する。

- [ ] 1. セッション切れ誤判定の修正
  - **Objective**: R1。HTML 200 レスポンス全般を「セッション切れ」と誤判定する条件式を修正する。
  - **実装ガイダンス**: design.md 1章。`url.indexOf('/api/') !== -1` のみに限定。コメントも更新。
  - **テスト要件**: フロント自動テストなし。デモで代替。
  - **Demo**: (a) ログイン後、開発者ツールで `$.get('/engineer/list')`(HTMLページ)を実行 → セッション切れToastが**出ない**。(b) 別タブでログアウト後、勤怠画面で保存操作 → 従来どおり「セッションが切れました」Toast + /login 遷移。

- [ ] 2. Toast / datalist の HTML エスケープ
  - **Objective**: R2, R4。共通UIコンポーネントの XSS 面を閉じる。
  - **実装ガイダンス**: design.md 2章・4章。`_show` に `SES.escapeHtml` 適用。事前に `static/js/` 全域を grep し、Toast に HTML を渡す呼び出しがないことを確認(あれば当該箇所をプレーンテキスト化)。datalist の独自エスケープを `SES.escapeHtml` に置換。
  - **テスト要件**: フロント自動テストなし。デモで代替。
  - **Demo**: コンソールで `Toast.error('<img src=x onerror=alert(1)>')` → alert が発火せずタグが文字列表示される。要員名に `<b>test</b>` を含む要員を登録 → オートコンプリート候補が壊れない。

- [ ] 3. `/api/autocomplete/users` の管理者制限
  - **Objective**: R3。全ロールへのログインID列挙を止める。
  - **実装ガイダンス**: design.md 3章。SecurityConfig の管理者 requestMatchers に追加 + `@PreAuthorize` の二層。`templates/` を grep して `user-list` datalist が非管理者ページに残っていないか確認・除去。
  - **テスト要件**: MockMvc — 営業ロールで users → 403(ApiResult JSON)、管理者 → 200、営業で engineers → 200。既存テストの `@WithMockUser(roles=...)` パターンを踏襲。
  - **Demo**: 営業ロールでログインし各画面を巡回 → 403 Toast やコンソールエラーが出ない。`mvn test` 全件グリーン。
