# Requirements — フロント共通基盤の残バグ修正(frontend-common-hardening)

`common.js` と共通APIに残る3件の不具合修正。対象コード: `static/js/common.js` /
`controller/api/AutocompleteApiController.java` / `config/SecurityConfig.java`(1行追加のみ)。

## R1. jQuery ajax セッション切れ誤判定の修正

現状: `common.js` のグローバル `ajaxSetup complete` ハンドラーの判定が

```js
if (url.indexOf('/api/') !== -1 || url.indexOf('/login') === -1) {
```

となっており、第2条件(`/login` を含まないURL全部)のせいで、**HTML を正常に返すあらゆる
200 レスポンス**が「セッション切れ」と誤判定され、Toast + 強制ログイン遷移が発火する。
現時点で jQuery から HTML を取得するページがないため顕在化していないが、
HTML フラグメントを ajax 取得する機能を追加した瞬間に全画面が壊れる地雷である。

受入基準:
1. セッション切れ判定(HTML が返ってきた場合の検知)は **URL が `/api/` を含むリクエストに限定**される。
2. `/api/**` へのリクエストがログインページHTML(200)で返った場合は従来どおり
   Toast「セッションが切れました…」+ 1.5秒後 `/login` 遷移が動作する。
3. 401 ハンドリング(既存)は変更しない。

## R2. Toast メッセージの HTML エスケープ

現状: `SES.toast._show()` がメッセージをテンプレート文字列で `innerHTML` に直挿入している。
Toast にはサーバー側エラーメッセージ(`ApiResult.message`)がそのまま流れ、
メッセージにユーザー入力が混入した場合の XSS 面が残っている
(通知ドロップダウンは `SES.escapeHtml` 済みだが Toast だけ未対策)。

受入基準:
1. `SES.toast._show()` はメッセージを `SES.escapeHtml()` を通してから HTML に埋め込む。
2. 既存の呼び出し箇所(全 `Toast.success/error/warning/info`、`static/js/` 全域を grep して確認)に
   HTML タグを意図的に渡している箇所が**ない**ことを確認する。もし存在した場合はその箇所を
   プレーンテキスト化して本修正と両立させる。
3. `<script>alert(1)</script>` を含むメッセージを Toast 表示してもスクリプトが実行されず、
   文字列として表示される。

## R3. オートコンプリートのユーザー名列挙の制限

現状: `GET /api/autocomplete/users` が認証済みであれば誰でも全ログインID一覧を返す。
ユーザー管理モジュールは `hasRole("管理者")` の硬い境界で守っているのに、
アカウント名の列挙だけ全ロールに開いており、総当たり攻撃の下準備を助けてしまう。

受入基準:
1. `GET /api/autocomplete/users` は `管理者` ロールのみ利用できる(他ロールは 403)。
   実装は `SecurityConfig` の管理者 `requestMatchers` 群への追加、
   またはメソッドの `@PreAuthorize("hasRole('管理者')")` のどちらでもよい(両方でもよい)。
2. 非管理者の画面でコンソールエラーや 403 Toast が**出ない**こと:
   `SES.autocomplete.loadDatalist` は対象 `<datalist>` が存在しないページでは fetch しない実装に
   なっているため、`user-list` datalist を含むテンプレートが管理者専用ページ以外に存在しないことを
   grep で確認し、存在する場合はそのテンプレートから除去する。
3. engineers / customers / projects のオートコンプリートは従来どおり全ロールで動作する。

## R4. datalist 生成のエスケープ強化(軽微)

現状: `SES.autocomplete.loadDatalist` が `"` のみ `&quot;` に置換する独自エスケープを使っている。

受入基準:
1. `SES.escapeHtml()` を使う実装に置き換え、`<` `>` `&` `'` を含む名称でも
   datalist が壊れない。
