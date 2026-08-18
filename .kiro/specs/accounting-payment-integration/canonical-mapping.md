# Canonical Mapping & freee API Spike Specification

## 1. 基本原則 (G4 / G9 Gate)

1. **System of Record (SoR)**:
   - 会計確定後の総勘定元帳・支払確定: **freee会計**
   - SES業務明細・請求工数・承認前データ・送信Job・照合: **SES Manager Pro (本システム)**
   - 総勘定元帳は自作しない。
2. **連携方式**:
   - freee Public API (OAuth 2.0 Authorization Code Flow)
   - API未対応・プラン制限時は CSV エクスポート/インポートへのフォールバック
   - DB transaction 外で非同期 Job (Outbox パターン) による送信
3. **未確認項目 (本番 Release Gate)**:
   - 実 freee 契約プラン (Standard / Professional / Enterprise)
   - 本番 `company_id`
   - 本番 Client ID / Client Secret / 接続先 URL
   - 本番環境での勘定科目/税区分/部門/取引先の実 ID マッピング

---

## 2. 外部マスタ 10種別 正規識別子 & 検証エンドポイント仕様

| No | マッピング種別 (`object_type`) | 正規識別子型 | freee API エンドポイント / ソース | 存在検証・照合ルール | freee 送信時ペイロード適用先 (JSON型) | 確認状態 (Release Gate) |
|---|---|---|---|---|---|---|
| 1 | `CUSTOMER_PARTNER` | `id` (Numeric String) | `GET /api/1/partners/{id}?company_id={company_id}` | `partner.id == external_id` かつ事業所一致 | deal `partner_id` (Number) | **未確認 (Release Gate)** |
| 2 | `BP_PARTNER` | `id` (Numeric String) | `GET /api/1/partners/{id}?company_id={company_id}` | `partner.id == external_id` かつ事業所一致 | deal `partner_id` (Number) | **未確認 (Release Gate)** |
| 3 | `ACCOUNT_SALES` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | **未確認 (Release Gate)** |
| 4 | `ACCOUNT_PURCHASE` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | **未確認 (Release Gate)** |
| 5 | `ACCOUNT_EXPENSE` | `id` (Numeric String) | `GET /api/1/account_items?company_id={company_id}` | 一覧走査で `account_item.id == external_id` | deal details `account_item_id` (Number) | **未確認 (Release Gate)** |
| 6 | `TAX_SALES_10` | `tax_code` (Numeric Integer, 例: `34`) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 34` (Number) | **未確認 (Release Gate)** |
| 7 | `TAX_PURCHASE_10` | `tax_code` (Numeric Integer, 例: `21`) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 21` (Number) | **未確認 (Release Gate)** |
| 8 | `TAX_EXPENSE_10` | `tax_code` (Numeric Integer, 例: `21`) | `GET /api/1/taxes/companies/{company_id}` | 一覧走査で `code == external_id` (Integer) | deal details `tax_code: 21` (Number) | **未確認 (Release Gate)** |
| 9 | `SECTION` | `id` (Numeric String) | `GET /api/1/sections?company_id={company_id}` | 一覧走査で `section.id == external_id` | deal details `section_id` (Number) | **未確認 (Release Gate)** |
| 10 | `COST_CENTER` | `id` (Numeric String) | `GET /api/1/sections?company_id={company_id}` | G4 決定: SECTION へ写像して照合 | deal details `section_id` (Number) | **未確認 (Release Gate)** |

- **フェイルクローズ**: 上記10種別以外の未知の `object_type` は `return false`（検証失敗）。一覧取得が 200 OK であっても該当 ID / code が存在しない場合は `false`。
- **Canonical Snapshot 仕様**:
  `m_external_mapping.payload_snapshot` には生の外部レスポンス全体ではなく、以下の allow-list された標準 JSON のみを保存する。
  ```json
  {
    "objectType": "CUSTOMER_PARTNER",
    "externalId": "101",
    "externalCode": "CUST-001",
    "name": "株式会社クライアントA",
    "companyId": 99001,
    "verifiedAt": "2026-08-18T10:00:00Z"
  }
  ```

---

## 3. freee 業務連携 API 仕様

| 業務機能 | freee API エンドポイント | HTTP Method | 主な用途 |
|---|---|---|---|
| 売上連携 | `/api/1/deals` | POST | 承認済請求書の取引（収入・未決済）登録 |
| 売上取消 | `/api/1/deals/{id}` | DELETE | 取消請求書の取引削除 |
| 仕入連携 | `/api/1/deals` | POST | 承認済BP支払の取引（支出・未決済）登録 |
| 経費連携 | `/api/1/deals` | POST | 承認済要員経費の取引（支出・未決済）登録 |
| 支払照合 | `/api/1/deals/{id}` | GET | 個別取引の決済状況・決済日・金額同期 |
| 月次照合 | `/api/1/deals` | GET | 指定月（start_issue_date〜end_issue_date）の取引全件取得 (Pagination) |

### 共通ヘッダ & エラーハンドリング仕様
- 認証: `Authorization: Bearer <access_token>`
- 相関: `X-Freee-Request-ID` (レスポンスヘッダから取得し Job に記録)
- 冪等性: 送信時に `payload_snapshot` の UTF-8 SHA-256 ハッシュを `payload_hash` として記録
- レート制限 (429): `Retry-After` ヘッダに基づく Exponential Backoff + Jitter
- 認証失効 (401): `token_version` と 3段階リースによる multi-node 直列化 Token Refresh (1回のみ)。連続 401 は FAILED
- 入力エラー (400 / 422): リトライせず FAILED（人手修正待ち）
- プラン制限 (403): リトライせず FAILED（CSV フォールバックを案内）
