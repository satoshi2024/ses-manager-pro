# Design — フロント共通基盤の残バグ修正(frontend-common-hardening)

マイグレーション不要。編集対象(これ以外に触れないこと):

- `src/main/resources/static/js/common.js`
- `src/main/java/com/ses/controller/api/AutocompleteApiController.java`
- `src/main/java/com/ses/config/SecurityConfig.java`(管理者 requestMatchers への1エントリ追加のみ)
- 必要なら `templates/` 内の `user-list` datalist 除去(R3-2 の grep 結果次第)
- テスト: `web/` 配下に新規 or 既存の `ApiCoverageIntegrationTest` 系へ追加

## 1. セッション切れ判定の修正(R1)

`common.js` 末尾の `$.ajaxSetup({ complete: ... })` 内:

```js
// 修正前
if (url.indexOf('/api/') !== -1 || url.indexOf('/login') === -1) {

// 修正後: APIリクエストに限定する
if (url.indexOf('/api/') !== -1) {
```

`this.url` が `settings.url` である点・401 分岐は現状のまま。コメントも実態に合わせて更新する。

## 2. Toast のエスケープ(R2)

`SES.toast._show()` の生成 HTML 中 `<span>${message}</span>` を
`<span>${SES.escapeHtml(message)}</span>` に変更する。

事前確認: `grep -rn "Toast\.\(success\|error\|warning\|info\)" src/main/resources/static/js/`
および `SES.toast.` 呼び出しを全数確認し、HTML を渡している呼び出しがないことを確かめる
(現状の想定はすべてプレーンテキスト。もし `<br>` 等を渡す箇所が見つかったら、その箇所を
複数行テキストではなく短文に直す方向で対応し、`_show` に「HTMLを許すオプション」は**作らない**)。

## 3. `/api/autocomplete/users` の管理者制限(R3)

二層で守る(このリポジトリの流儀に合わせる):

1. `SecurityConfig.securityFilterChain` の管理者 `requestMatchers(...)` ブロックに
   `"/api/autocomplete/users"` を追加する(既存の `/api/users/**` 等と同じ並び)。
2. `AutocompleteApiController.getUsers()` に `@PreAuthorize("hasRole('管理者')")` を付ける
   (`@EnableMethodSecurity` は有効化済み)。

フロント側の確認: `grep -rn "user-list" src/main/resources/templates/` を実行し、
`<datalist id="user-list">` が管理者専用画面(`user/list.html` 等)以外に存在しないことを確認。
存在した場合はそのテンプレートから datalist と参照 `list="user-list"` 属性を除去する
(`SES.autocomplete.loadDatalist` は datalist 不在なら fetch 自体をしないため、
これで非管理者ページでの 403 Toast は発生しない)。

## 4. datalist エスケープ(R4)

`loadDatalist` 内:

```js
// 修正前
const safeName = name.replace(/"/g, '&quot;');
// 修正後
const safeName = SES.escapeHtml(name);
```

## 5. テスト設計

- サーバー側(R3): MockMvc テストを追加 — 営業ロールで `GET /api/autocomplete/users` → 403(JSON)、
  管理者ロール → 200。engineers は営業ロールでも 200。
  既存の `web/ApiCoverageIntegrationTest` / `config/AuditLogSecurityTest` のロール付きリクエストの
  書き方(`@WithMockUser(roles = "管理者")` 等)を踏襲する。
- フロント側(R1/R2/R4)は自動テスト基盤がないため、Demo 手順(tasks.md)による手動確認とし、
  修正差分を最小に保つことで回帰リスクを抑える。
