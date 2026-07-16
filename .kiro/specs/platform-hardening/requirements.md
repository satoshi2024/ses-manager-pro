# Requirements Document — 横断的品質強化(P8)

## Introduction

全モジュール共通の品質欠損を解消する: 入力バリデーション不在(`@Valid` ゼロ)、ファイルアップロード未実装
(`skill_sheet_path` / `photo_url` が宙に浮いている)、CSV 入出力なし、`created_by` の手動任せ、
メール送信未実装(テンプレートのみ存在)、セキュリティの緩い箇所、`m_system_config` 未使用、テスト空白地帯。
他フェーズと並行で進めてよいが、R1(バリデーション)と R4(created_by 自動設定)は早期実施を推奨。

## Requirements

### Requirement 1: 入力バリデーション

#### Acceptance Criteria
1. THE 全 API SHALL Bean Validation(`@Valid` + 制約注釈)で必須・長さ・範囲を検証し、違反時は `GlobalExceptionHandler` 経由で日本語メッセージの `ApiResult`(code=400)を返す。
2. 最低限の対象: Engineer(氏名必須・単価0以上)、Project(案件名/顧客必須・単価下限≦上限・終了≧開始)、Customer(会社名必須)、Contract(P2 で実装済みの分を注釈化)、SysUser(username 必須・4〜50文字)。

### Requirement 2: ファイルアップロード

#### Acceptance Criteria
1. THE システム SHALL スキルシート(pdf/xlsx/docx, 10MB 以下)と顔写真(png/jpg, 2MB 以下)のアップロードを提供し、拡張子・サイズ・Content-Type を検証する。
2. 保存先はローカルディレクトリ(`app.upload.base-path`、既定 `./uploads`)とし、保存ファイル名は UUID 化して元名は別途保持する。
3. THE ファイル SHALL 認証済みユーザーのみダウンロード可能な配信エンドポイント経由で取得する(静的公開しない)。
4. 要員詳細画面から写真・スキルシートの登録/差し替え/ダウンロードができる。

### Requirement 3: CSV 入出力

#### Acceptance Criteria
1. THE システム SHALL 要員・案件の CSV エクスポート(UTF-8 BOM 付き、Excel で文字化けしない)を提供する。
2. THE システム SHALL 要員 CSV インポート(ヘッダー検証・行単位バリデーション)を提供し、結果レポート(成功N件/失敗行と理由)を返す。失敗行があっても成功行は取り込む。

### Requirement 4: 監査情報の自動設定

#### Acceptance Criteria
1. THE システム SHALL `created_by` を持つ全エンティティの INSERT 時にログインユーザー ID を自動設定する(コントローラー個別実装の廃止)。
2. THE システム SHALL API 経由の更新系操作(POST/PUT/DELETE)を操作ログ(実行者・メソッド・パス・結果コード)としてアプリケーションログに記録する。

### Requirement 5: メール送信

#### Acceptance Criteria
1. THE システム SHALL SMTP(環境変数で設定)によるメール送信サービスを提供し、`m_email_template` のテンプレート(`{{変数}}` 置換)で本文を組み立てられる。
2. 送信は非同期とし、失敗はログ + 通知(P3 導入済みの場合)で可視化する。
3. SMTP 未設定時はドライラン(ログ出力のみ)として動作し、画面操作を妨げない。
4. 最初の適用箇所: 提案メール(カンバン/マッチングから顧客担当者宛に送信)。

### Requirement 6: セキュリティ強化

#### Acceptance Criteria
1. THE システム SHALL `/api/**` の CSRF 無効化をやめ、CookieCsrfTokenRepository + JS の `X-XSRF-TOKEN` ヘッダー送信方式に移行する。
2. THE システム SHALL ログイン失敗5回で30分アカウントロックする(`sys_user` に failed_count / locked_until 追加)。
3. THE システム SHALL ユーザー作成・パスワード変更時にパスワードポリシー(8文字以上・英数混在)を検証する。
4. DB 接続情報は環境変数(`DB_URL` / `DB_USERNAME` / `DB_PASSWORD`)から注入し、`application.yml` にはデフォルト値のみ残す。

### Requirement 7: システム設定画面

#### Acceptance Criteria
1. THE システム SHALL `m_system_config` の管理画面(管理者のみ、キー・値・説明の一覧編集)を提供する。
2. THE システム SHALL 型付きアクセサ(`getInt` / `getString` / `getBigDecimal`、デフォルト値付き、キャッシュ+更新時リフレッシュ)を提供する。
3. ハードコード値を設定化する: 通知の契約終了予告日数(30)/提案停滞日数(7)/Bench警告日数(30)、消費税率(0.10)、会社名・振込先(請求書用)。

### Requirement 8: テスト拡充

#### Acceptance Criteria
1. Engineer / Project / Customer / Proposal / Contract / User の各 API に最低限の `@WebMvcTest` または H2 統合テスト(一覧・登録・バリデーションエラー)を追加する。
2. `mvn test` が MySQL なしで全件グリーンであること。

### Requirement 9: 本番HTTPSと認証Cookie

#### Acceptance Criteria

1. THE production deployment SHALL honor `X-Forwarded-Proto` from the trusted reverse proxy and SHALL not generate HTTP redirects after an HTTPS login.
2. THE production application SHALL redirect HTTP requests to HTTPS and SHALL emit HSTS on secure responses.
3. THE `JSESSIONID` and `XSRF-TOKEN` cookies SHALL use `Secure`; the session cookie SHALL remain `HttpOnly`, and both cookies SHALL use an explicit `SameSite` policy.
4. THE deployment SHALL rotate any shared or exposed administrator credentials before release and SHALL verify that the previous credential no longer authenticates.
