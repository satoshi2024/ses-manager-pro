# Design: ログイン・CRUD操作の不具合修正

## 1. ログイン失敗理由の切り分け（Requirement 1）

### 1.1 現状
`SecurityConfig.java` の `formLogin().failureUrl("/login?error")` は例外の種類を問わず一律 `/login?error` に飛ばす。`login.html:115-118` は `${param.error}` のみを見て固定文言を出す。

### 1.2 方針
- `AuthenticationFailureHandler` をカスタム実装し、例外の型で振り分ける。
  - `AuthenticationServiceException`（内部に `DataAccessException` 等を持つ場合が多い）→ `/login?error=system`
  - それ以外（`BadCredentialsException`, `UsernameNotFoundException` 等）→ `/login?error=credentials`
- `login.html` で `param.error` の値によって文言を出し分ける（`error=system` の時のみシステムエラー文言、それ以外は従来文言）。
- カスタムハンドラ内で `log.warn`/`log.error` にて原因例外をログ出力する。

### 1.3 対象ファイル
- 新規: `com.ses.config.LoginFailureHandler`（`SimpleUrlAuthenticationFailureHandler` 継承）
- 変更: `SecurityConfig.java`（`.failureHandler(loginFailureHandler)` に置き換え）
- 変更: `login.html`

## 2. ログイン成功時のフィードバック（Requirement 2）

### 2.1 方針
`defaultSuccessUrl("/dashboard", true)` はそのまま利用し、クエリパラメータ経由で成功を伝える案は URL 汚染になるため避け、**セッション1回限りの flash 相当**として Spring Security の `AuthenticationSuccessHandler` を使わず、シンプルに `/dashboard?login=success` へのリダイレクトにして dashboard 側で JS 判定 + `history.replaceState` で URL からパラメータを消す方式にする（サーバー側のセッション属性管理より軽量で確実）。

### 2.2 実装詳細
- `SecurityConfig`: `.defaultSuccessUrl("/dashboard?login=success", true)`
- `dashboard/index.html` もしくは共通 `base.html` の初期化 JS で:
  ```js
  const params = new URLSearchParams(location.search);
  if (params.get('login') === 'success') {
      Toast.success('ログインしました');
      params.delete('login');
      const newUrl = location.pathname + (params.toString() ? '?' + params.toString() : '');
      history.replaceState(null, '', newUrl);
  }
  ```
- 配置場所は `common.js` の `DOMContentLoaded` 内（dashboard 限定にしたい場合は `dashboard.js` でも可。汎用性を考え `common.js` に置く）。

## 3. CRUD操作の弾窗と通知の一貫性（Requirement 3）

### 3.1 現状の問題点
- 各モジュール（`engineer.js`, `customer.js`, `project.js`, `contract.js`, `proposal-kanban.js`, `email-template.js`）が個別に `$.ajax` の `success`/`error` を書いており、`error` コールバックが「通信エラーが発生しました」のみで具体的な理由を出せていない。
- バックエンドが 500 の場合、Spring Boot のデフォルトエラーページ（HTML）が返る可能性があり、`$.ajax` の `success` 扱いにならず `error` に落ちるが、パース不能な HTML がそのまま握りつぶされる。

### 3.2 方針
1. **バックエンド**: `GlobalControllerAdvice` / `GlobalExceptionHandler` を確認し、`/api/**` 配下の全例外（`Exception.class` を含む）が必ず `ApiResult.error(code, message)` の JSON を返すことを保証する（未捕捉の `Exception` 用ハンドラを追加）。
2. **フロントエンド共通化**: `common.js` の `SES.api` を jQuery ベースの各モジュールと统一するのは大改修になるため、本specでは最小差分で以下を徹底する:
   - 各モジュールの `error:` コールバックで `jqXHR.responseJSON?.message` があればそれを `Toast.error` に渡し、無ければ「通信エラーが発生しました（ステータス: N）」のようにステータスコードを含める。
   - モーダルを閉じる処理は成功時のみのままで良い（既存仕様通り）が、失敗時に必ず `Toast.error` が呼ばれることをテストする。
3. 6画面で同一パターンになるよう、共通の `handleAjaxError(jqXHR)` ヘルパーを `common.js` に追加し、各モジュールの `error:` から呼び出す形にリファクタする。

### 3.3 対象ファイル
- `common.js`（`SES.util.handleAjaxError` 追加）
- `engineer.js`, `customer.js`, `project.js`, `contract.js`, `proposal-kanban.js`, `email-template.js`（各 `error:` コールバックを共通ヘルパー呼び出しに変更）
- `GlobalExceptionHandler.java`（未捕捉例外のフォールバックハンドラ確認・追加）

## 4. セッション切れ誤検知の修正（Requirement 4）

### 4.1 現状
```js
if (xhr.status === 200 && contentType.indexOf('text/html') !== -1) {
    const url = this.url || '';
    if (url.indexOf('/api/') !== -1 || url.indexOf('/login') === -1) {
        // セッション切れ扱い
```
`url.indexOf('/login') === -1` は「/login という文字列を含まない」がほぼ全URLで真になるため、`/api/` を含まないリクエストでも常に発火してしまう（OR条件が意図と逆）。

### 4.2 方針
本来の意図は「`/api/` へのリクエストなのに HTML(ログインページ)が返ってきた」を検知すること。条件を以下に修正する:
```js
if (xhr.status === 200 && contentType.indexOf('text/html') !== -1) {
    const url = this.url || '';
    if (url.indexOf('/api/') !== -1) {
        // セッション切れ扱い
    }
}
```

## 5. パスワードのハッシュ化（Requirement 5）

### 5.1 方針
- `SecurityConfig.passwordEncoder()` を `new BCryptPasswordEncoder()` に変更。
- `sql/002_init_master_data.sql` の admin パスワードを事前計算した BCrypt ハッシュ（`admin123` に対応するもの）に置き換える。
- 既存 DB を使っているユーザー向けに、マイグレーション用の `UPDATE sys_user SET password = '<hash>' WHERE username = 'admin'` を別スクリプト（`sql/003_migrate_password_hash.sql`）として用意する。

## 6. 開発環境の再現性（Requirement 6）

- `README.md` に「MySQL 起動 → `sql/001`, `sql/002`(, `003`) 実行 → `mvn spring-boot:run`」の手順を明記。
- 余力があれば `docker-compose.yml`（MySQL 8 + 初期化スクリプトマウント）を追加。
- ルートに `.gitignore` を追加/更新し `target/` を除外。既に追跡済みの `target/**` は `git rm -r --cached target` で追跡解除する。
