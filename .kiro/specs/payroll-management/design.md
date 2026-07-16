# 給与情報外部連携 設計

## 構成

- `FreeeIntegrationService` が OAuth URL、callback、token refresh、freee API 呼出しを担当する。認可コードの `state` は HttpSession で一回限り検証する。
- `FreeeConnection` は会社 ID、暗号化済み refresh/access token、有効期限のみを保持する。AES-GCM の鍵は `FREEE_TOKEN_ENCRYPTION_KEY` から取得し、暗号文以外をログへ出力しない。
- `FreeeEmployeeLink` は engineer_id と freee_employee_id の一意マッピングを保持する。明細 DTO は API 応答の都度生成し永続化しない。
- `FreeePayrollApiController` は `/api/payroll/**`、`FreeeOAuthController` は `/integrations/freee/**` を提供し、メソッドセキュリティで管理者/HR に限定する。

## 外部 API

`FREEE_API_BASE_URL`（既定 `https://api.freee.co.jp`）を基底 URL とし、OAuth は `/oauth/authorize` と `/oauth/token`、従業員・給与明細は freee 人事労務 API の相対パスを設定で差し替え可能にする。レスポンスの金額は DTO にのみ保持し、エラーは HTTP ステータス別に `BusinessException` へ変換する。

## データベース

V21 で `t_freee_connection`、V21 で `t_freee_employee_link` を追加する（MySQL/H2 スキーマを同期）。token は AES-GCM の `BLOB`/Base64 暗号文、給与データ用テーブルは作成しない。

## 権限・監査

`payroll` メニューを管理者/HR に付与し、SecurityConfig の `/payroll/**` `/api/payroll/**` を `hasAnyRole("管理者","HR")` で保護する。給与取得 API のレスポンスは no-store とし、監査ログには freee employee/company ID や金額を記録しない。
