# Design — BP会社マスタ・発注コンプライアンス

## 1. DDL（予約V66）

- `m_bp_company(id, tenant_id, legal_name, name_kana, entity_type, corporate_number,
  invoice_registration_number, capital_band, employee_band, address, representative, status,
  primary_sales_user_id, compliance_applicability, applicability_checked_by/at, applicability_note, version)`。
- `t_bp_contact(bp_company_id, name, department, role, email, phone, primary_flag)`。
- `t_bp_bank_account(bp_company_id, encrypted_bank/branch/account, masked_label, valid_from/to, approval_status)`。
- `t_bp_terms(bp_company_id, effective_from/to, closing_day, payment_month_offset, payment_day,
  fee_bearer, payment_method, max_payment_days, version)`。
- `t_engineer_bp_affiliation(engineer_id, bp_company_id, valid_from/to)`。
- `t_bp_evaluation(bp_company_id, period, scores..., comment, evaluated_by)`。
- `t_bp_price_negotiation(bp_company_id, requested_at, responded_at, status, requested_amount,
  agreed_amount, summary, document_id)`。
- `BpAvailability.bp_company_id`, `BpPayment.bp_company_id`、表示snapshot列。

## 2. 移行

- 正規化: 法人格、全半角、空白を除く。ただし同一候補を自動mergeしない。
- `DISTINCT bp_company/payee_company_name`から仮BPを生成し、exact normalizationだけ自動link。
- 複数候補/空値は`migration_exception` CSVと管理画面で人が解決。
- 2リリースは旧文字列read fallback、writeはID必須。完了後fallback削除を別taskにする。

## 3. Service/API/UI

- `BpCompanyService`, `BpComplianceService`, `BpTermsResolver`。
- `/bp-company`, `/api/bp-companies`。detail tabs: 基本/連絡先/口座/条件/文書/要員/評価/価格協議/支払。
- bank DTOは末尾のみ返し、復号値を通常APIで返さない。
- Autocomplete/ingestionは候補score+理由を返し、confirm時にID必須。

## 4. 法令rule

- `ProcurementComplianceFinding(code,severity,message,field,sourceUrl)`を都度導出。
- ruleはconfig version付き。適用対象は人が確定、システムは不足/期限/不整合だけを検査。
- sourceはmaster roadmapのresearch-sourcesをUI helpへ静的link。

## 5. テスト

重複候補、affiliation期間、terms resolver、snapshot、60日境界、具体日、fee bearer、bank masking、
migration reconciliation、data scope/tenant、取引停止選択除外。

