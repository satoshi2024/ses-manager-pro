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

## 2. freee 会計 API エンドポイント仕様 (Official Spike)

| 業務機能 | freee API エンドポイント | HTTP Method | 主な用途 |
|---|---|---|---|
| 売上連携 | `/api/1/deals` | POST | 承認済請求書の取引（収入）登録 |
| 売上取消 | `/api/1/deals/{id}` | DELETE | 取消請求書の取引削除または差額伝票 |
| 仕入連携 | `/api/1/deals` | POST | 承認済BP支払の取引（支出・未決済）登録 |
| 経費連携 | `/api/1/deals` | POST | 承認済要員経費の取引（支出・未決済）登録 |
| 支払照合 | `/api/1/deals` / `/api/1/payments` | GET | 決済済ステータス・支払日の同期 |
| マスタ検証 | `/api/1/account_items` | GET | 勘定科目の存在・有効性検証 |
| マスタ検証 | `/api/1/taxes/companies/{company_id}` | GET | 税区分の存在・有効性検証 |
| マスタ検証 | `/api/1/sections` | GET | 部門の存在・有効性検証 |
| 取引先検証 | `/api/1/partners` | GET | 取引先の存在・有効性検証 |

### 共通ヘッダ & エラーハンドリング仕様
- 認証: `Authorization: Bearer <access_token>`
- 相関: `X-Freee-Request-ID` (レスポンスヘッダから取得し Job に記録)
- 冪等性: 送信時に `payload_hash` を算出し、同一 payload の二重送信を防止
- レート制限 (429): `Retry-After` ヘッダに基づく Exponential Backoff + Jitter
- 認証失効 (401): 1回のみ Token Refresh を実行しリトライ。連続 401 は FAILED（要再認可）
- 入力エラー (400 / 422): リトライせず FAILED（人手修正待ち）
- プラン制限 (403): リトライせず FAILED（CSV フォールバックを案内）

---

## 3. Canonical Object Mapping 表

### 3.1 取引先 (Partner) Mapping
| 内部エンティティ | 内部キー | freee フィールド | mapping object_type | 確認状態 |
|---|---|---|---|---|
| `t_customer` (顧客) | `customer.id` / `customer.code` | `partner_id` / `partner_code` | `CUSTOMER_PARTNER` | **未確認 (Release Gate)** |
| `t_bp_company` (BP企業) | `bp_company.id` / `bp_company.code` | `partner_id` / `partner_code` | `BP_PARTNER` | **未確認 (Release Gate)** |
| `t_engineer` (自社要員/個人) | `engineer.id` / `engineer.code` | `partner_id` / `partner_code` | `ENGINEER_PARTNER` | **未確認 (Release Gate)** |

### 3.2 勘定科目 (Account Item) Mapping
| 内部業務分類 | デフォルト勘定科目名 | freee `account_item_id` | mapping object_type | 確認状態 |
|---|---|---|---|---|
| 売上（SES契約） | 売上高 (Sales) | 動的設定 (`account_item_id`) | `ACCOUNT_SALES` | **未確認 (Release Gate)** |
| 仕入（BP再委託） | 外注費 (Subcontract Expense) | 動的設定 (`account_item_id`) | `ACCOUNT_OUTSOURCING` | **未確認 (Release Gate)** |
| 経費（交通費） | 旅費交通費 (Travel Expense) | 動的設定 (`account_item_id`) | `ACCOUNT_TRAVEL_EXPENSE` | **未確認 (Release Gate)** |
| 経費（通信費/その他） | 雑費 / 通信費 | 動的設定 (`account_item_id`) | `ACCOUNT_MISC_EXPENSE` | **未確認 (Release Gate)** |

### 3.3 税区分 (Tax Code) Mapping
| 内部税率区分 | freee `tax_code` (例) | 税率 | mapping object_type | 確認状態 |
|---|---|---|---|---|
| 課税売上 10% | `21` (課対仕入10% / 課税売上10%) | 10% | `TAX_SALES_10` | **未確認 (Release Gate)** |
| 課税仕入 10% | `108` (課税仕入10% 簡易/原則) | 10% | `TAX_PURCHASE_10` | **未確認 (Release Gate)** |
| 非課税 / 不課税 | `non_tax` / `out_of_scope` | 0% | `TAX_EXEMPT` | **未確認 (Release Gate)** |

### 3.4 部門 & Cost Center Mapping
| 内部部門 / Cost Center | freee `section_id` | mapping object_type | 確認状態 |
|---|---|---|---|
| `t_cost_center` / `sys_dept` | 動的設定 (`section_id`) | `SECTION` | **未確認 (Release Gate)** |

---

## 4. 契約プラン & フォールバック方針

1. **freee API 対応プラン**:
   - 取引 API (`/api/1/deals`)、取引先 API (`/api/1/partners`) は Standard 以上のプランで利用可能。
   - スタータープラン等で API 制限がある場合、本システムは `CsvAccountingExportProvider` を用いて、同等の Canonical DTO から freee インポート形式の CSV を生成・ダウンロード可能とする。
2. **マッピング未検証時のフェイルセーフ**:
   - `m_external_mapping.verified_at IS NULL` の項目が存在する場合、API 送信前バリデーションで即時中断し、外部へ不完全な取引を送信しない（R1.3 遵守）。
