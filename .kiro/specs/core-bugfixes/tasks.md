# Implementation Plan

本タスクリストは `requirements.md` / `design.md` の内容を段階的に実装するための作業手順である。各タスクは独立して着手可能だが、番号順に進めることを推奨する（前提: タスク0の環境復旧が全ての土台）。

---

- [ ] 0. 開発環境の復旧（MySQL起動・初期データ投入）
  - **Objective**: ローカルでログイン・CRUD操作を実際に検証できる状態にする。すべての後続タスクの前提。
  - **実装ガイダンス**:
    - MySQL を起動する（未インストールなら導入、または Docker で `mysql:8` コンテナを起動）。
    - `sql/001_create_tables.sql` → `sql/002_init_master_data.sql` の順に実行し、`ses_manager_db` を構築する。
    - `mvn spring-boot:run`（または既存の起動手順）でアプリを起動し、`admin / admin123` でログインできることを確認する。
  - **テスト要件**: 手動確認のみ。
  - **Demo**: ブラウザで `/login` にアクセスし、admin/admin123 でログイン後 `/dashboard` に遷移できる。

---

- [ ] 1. ログイン失敗理由の切り分け（Requirement 1）
  - **Objective**: DB未接続等のシステムエラーとID/パスワード誤りを画面上で区別できるようにする。
  - **実装ガイダンス**:
    - `com.ses.config.LoginFailureHandler`（`SimpleUrlAuthenticationFailureHandler` 継承）を新規作成し、`AuthenticationException` の型に応じて `/login?error=system` または `/login?error=credentials` にリダイレクトする（design.md 1.2節）。
    - `SecurityConfig.java` の `formLogin()` で `.failureUrl(...)` を `.failureHandler(loginFailureHandler)` に置き換える。
    - `login.html` の `${param.error}` 判定を `error=system` かどうかで文言を出し分ける。
    - ハンドラ内で原因例外を `log.warn`（credentials）/ `log.error`（system）で使い分けてログ出力する。
  - **テスト要件**: 可能であれば `LoginFailureHandlerTest` で各例外型に対するリダイレクト先をユニットテスト。難しい場合は手動確認で可。
  - **Demo**: MySQLを停止した状態でログインを試みると「システムに接続できません」等のシステムエラー文言が出る。MySQL起動状態で誤ったパスワードを入れると従来通りの「ユーザーIDまたはパスワードが正しくありません」が出る。

---

- [ ] 2. ログイン成功時のトースト表示（Requirement 2）
  - **Objective**: ログイン成功をユーザーが画面上で確認できるようにする。
  - **実装ガイダンス**:
    - `SecurityConfig.java`: `.defaultSuccessUrl("/dashboard?login=success", true)` に変更。
    - `common.js` の `DOMContentLoaded` ハンドラに、URLパラメータ `login=success` を検知して `Toast.success('ログインしました')` を表示し、`history.replaceState` でURLからパラメータを除去する処理を追加する（design.md 2.2節のコード例参照）。
  - **テスト要件**: 手動確認。
  - **Demo**: ログイン→ダッシュボード遷移直後に「ログインしました」トーストが表示される。ブラウザをリロードしても再表示されない。

---

- [ ] 3. セッション切れ誤検知の修正（Requirement 4）
  - **Objective**: 通常のページ遷移でセッション切れが誤検知されないようにする。
  - **実装ガイダンス**:
    - `common.js` 288〜300行付近の `$.ajaxSetup({ complete: ... })` 内の条件式を design.md 4.2節の通り修正する（`url.indexOf('/api/') !== -1` のみを条件にする）。
  - **テスト要件**: 手動確認（DevToolsのNetworkタブで通常ページ遷移時に誤ったトーストが出ないことを確認）。
  - **Demo**: 任意の画面遷移・一覧読み込みで「セッションが切れました」の誤トーストが出ない。意図的にセッションを切った状態でAPIを呼ぶと正しく検知されログイン画面に遷移する。

---

- [ ] 4. バックエンド: 未捕捉例外のJSONフォールバック確認・追加（Requirement 3 前提）
  - **Objective**: `/api/**` へのリクエストがどんな例外でも必ず `ApiResult` のJSONを返すようにする。
  - **実装ガイダンス**:
    - `GlobalExceptionHandler` の既存 `@ExceptionHandler` 一覧を確認し、`BusinessException` 以外の汎用 `Exception.class` 用ハンドラが `/api/**` に対して機能しているか確認する。
    - 不足していれば `@ExceptionHandler(Exception.class)` を追加し、500系の `ApiResult.error(500, "サーバーエラーが発生しました")` を返すようにする（スタックトレースは `log.error` でサーバー側にのみ出力）。
  - **テスト要件**: `GlobalExceptionHandlerTest` または既存コントローラーテストに、意図的に例外を投げるケースを追加し、レスポンスがJSONであることを検証する。
  - **Demo**: 適当な削除APIで存在しないIDを指定した際、ブラウザNetworkタブのレスポンスがHTMLではなくJSON（`ApiResult`形式）で返る。

---

- [ ] 5. フロントエンド共通エラーハンドラの追加（Requirement 3）
  - **Objective**: 各画面のAjaxエラー処理を共通化し、失敗時に必ず具体的なメッセージが出るようにする。
  - **実装ガイダンス**:
    - `common.js` の `SES.util` に `handleAjaxError: function(jqXHR)` を追加する（design.md 3.2節）。`jqXHR.responseJSON?.message` があればそれを、無ければ `通信エラーが発生しました（ステータス: ${jqXHR.status}）` を `Toast.error` に渡す。
  - **テスト要件**: 手動確認（後続タスクで各モジュールから呼び出して確認）。
  - **Demo**: なし（ヘルパー追加のみ、タスク6で結線）。

---

- [ ] 6. 各モジュールのエラーハンドリングをタスク5のヘルパーに統一（Requirement 3）
  - **Objective**: engineer / customer / project / contract / proposal-kanban / email-template の全モジュールで、保存・削除失敗時の挙動を統一する。
  - **実装ガイダンス**:
    - 対象ファイル: `engineer.js`, `customer.js`, `project.js`, `contract.js`, `proposal-kanban.js`, `email-template.js`。
    - 各ファイル内の保存(`saveXxx`)・削除(`deleteXxx`)関数の `error:` コールバックを `SES.util.handleAjaxError(jqXHR)` 呼び出しに置き換える。
    - 削除確認の `Swal.fire` はそのまま維持し、`error:` 部分のみ変更する。
  - **テスト要件**: 手動での回帰確認（下記Demo）。
  - **Demo**: 6画面それぞれで「存在しないIDの削除」や「必須項目を空にした保存」を試し、モーダルが残ったまま具体的なエラーメッセージがトースト表示されることを確認する。正常な保存・削除では従来通りモーダルが閉じ、成功トーストが出て一覧が更新される。

---

- [ ] 7. パスワードのBCrypt化（Requirement 5）
  - **Objective**: パスワードを平文比較からBCryptハッシュ比較に切り替える。
  - **実装ガイダンス**:
    - `SecurityConfig.passwordEncoder()` を `new BCryptPasswordEncoder()` に変更。
    - `admin123` のBCryptハッシュ値を生成し、`sql/002_init_master_data.sql` の初期データを更新する。既存DBを持つ開発者向けに `sql/003_migrate_password_hash.sql`（`UPDATE sys_user SET password = '<hash>' WHERE username = 'admin'`）を新規作成する。
  - **テスト要件**: `CustomUserDetailsServiceTest` があれば更新、なければ手動確認。
  - **Demo**: DBを作り直す（`001`→`002`→`003`の順で実行、または`002`のみでも新ハッシュが入っていることを確認）→ admin/admin123 でログインできる。DB上の `sys_user.password` カラムがBCrypt形式（`$2a$...`）になっている。

---

- [ ] 8. 開発環境ドキュメント整備とgit衛生（Requirement 6）
  - **Objective**: DB未起動を「バグ」と誤認しないよう手順を明文化し、ビルド成果物をリポジトリから除外する。
  - **実装ガイダンス**:
    - `README.md` に「MySQL起動 → sql/001, 002, 003実行 → `mvn spring-boot:run`」の手順を追記する。
    - ルートに `.gitignore` を作成/更新し `target/` を追加する。
    - `git rm -r --cached target` で既存の追跡を解除する（ユーザーに確認の上でコミット）。
  - **テスト要件**: 不要。
  - **Demo**: `git status` で `target/` 配下の変更が表示されなくなる。README通りの手順で新規に環境構築できる。
