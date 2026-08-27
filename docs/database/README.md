# データベース設計書・テーブル定義書・ER図 概要

本ディレクトリには、**SES Manager Pro** のデータベース設計に関する公式技術ドキュメントおよび定義書が格納されています。

---

## 📁 格納ファイル一覧

1. **[ER_DIAGRAM.md](./ER_DIAGRAM.md)**:
   * 全社エンティティ相関図（High-Level ER Matrix）
   * ドメイン別 Mermaid ER図（認証・権限、要員・スキル・BP、顧客・商談・案件、契約・電子署名、勤怠・検収・請求・入金、ワークフロー・決算）
   * データベース整合性・制約ポリシー

2. **[SES_Manager_Pro_テーブル定義書_ER図.xlsx](./SES_Manager_Pro_テーブル定義書_ER図.xlsx)**:
   * 全 8 シート構成の公式 Excel データベース設計書（標準ビュー・倍率100%・文字欠けゼロ）
   * 全 45+ テーブルの詳細物理定義（カラム名、論理名、データ型、桁数、PK/FK、NULL許可、デフォルト値、インデックス、備考・コード定義）
   * ドメイン別テーブル定義マトリクスおよびエンティティ相関一覧

---

## 🗄️ 収録主要テーブル一覧（抜粋）

| テーブル物理名 | テーブル論理名 | 業務ドメイン | 主要カラム・キー |
|:---|:---|:---|:---|
| `sys_user` | ユーザーアカウントマスタ | 認証・権限 | `id`(PK), `username`(UK), `role`, `organization_id`(FK) |
| `m_menu` | メニューマスタ | 認証・権限 | `id`(PK), `menu_key`(UK), `path_prefix`, `api_prefix` |
| `t_role_menu` | ロール別メニュー権限マッピング | 認証・権限 | `id`(PK), `role`, `menu_id`(FK) |
| `m_organization_unit` | 組織・部署マスタ | 組織・管理会計 | `id`(PK), `unit_code`(UK), `parent_id`(FK), `manager_user_id`(FK) |
| `t_engineer` | 要員台帳マスタ | 要員・スキル | `id`(PK), `engineer_code`(UK), `employment_type`, `cost_price` |
| `t_engineer_skill` | 要員保有スキル | 要員・スキル | `id`(PK), `engineer_id`(FK), `skill_id`(FK), `experience_months` |
| `t_engineer_sales` | 要員担当営業履歴 | 要員・営業 | `id`(PK), `engineer_id`(FK), `sales_user_id`(FK), `primary_flag` |
| `t_candidate` | 採用候補者マスタ | 採用管理 | `id`(PK), `name`, `selection_stage`, `desired_salary` |
| `t_bp_company` | BP企業マスタ | パートナー管理 | `id`(PK), `company_code`(UK), `invoice_number`, `payment_terms` |
| `t_bp_availability` | BP外部要員空き在庫 | パートナー調達 | `id`(PK), `bp_company_id`(FK), `desired_price`, `available_from` |
| `m_customer` | 顧客マスタ | 顧客CRM | `id`(PK), `customer_code`(UK), `invoice_number`, `sales_user_id`(FK) |
| `t_opportunity` | CRM商機 | 顧客CRM | `id`(PK), `customer_id`(FK), `stage`, `probability`, `expected_revenue` |
| `t_project` | 案件マスタ | 案件管理 | `id`(PK), `project_code`(UK), `customer_id`(FK), `min_hours`, `max_hours` |
| `t_proposal` | 提案カンバンレコード | 提案管理 | `id`(PK), `project_id`(FK), `engineer_id`(FK), `proposed_price`, `status` |
| `t_quotation` | 見積書マスタ | 見積管理 | `id`(PK), `quotation_number`(UK), `customer_id`(FK), `monthly_price` |
| `t_contract` | SES契約マスタ | 契約管理 | `id`(PK), `contract_number`(UK), `unit_price`, `cost_price`, `sales_user_id`(FK) |
| `t_contract_document`| 電子契約書・署名連携 | 契約管理 | `id`(PK), `contract_id`(FK), `cloudsign_document_id`, `sign_status` |
| `t_work_record` | 勤怠・月次工数レコード | 勤怠・精算 | `id`(PK), `contract_id`(FK), `engineer_id`(FK), `actual_hours`, `billing_amount` |
| `t_acceptance` | 月次検収レコード | 検収管理 | `id`(PK), `contract_id`(FK), `accepted_hours`, `acceptance_status` |
| `t_invoice` | 請求書マスタ | 請求管理 | `id`(PK), `invoice_number`(UK), `customer_id`(FK), `total_amount`, `due_date` |
| `t_invoice_item` | 請求書明細 | 請求管理 | `id`(PK), `invoice_id`(FK), `contract_id`(FK), `unit_price`, `amount` |
| `t_bank_deposit` | 銀行入金明細 | 売掛金管理 | `id`(PK), `deposit_date`, `payer_name`, `amount`, `reconciled_flag` |
| `t_reconciliation` | 入金消込レコード | 売掛金管理 | `id`(PK), `invoice_id`(FK), `deposit_id`(FK), `reconciled_amount`, `fee_amount` |
| `t_bp_payment` | BP仕入支払レコード | 支払管理 | `id`(PK), `bp_company_id`(FK), `contract_id`(FK), `payment_amount` |
| `t_expense_request` | 経費精算申請 | 経費管理 | `id`(PK), `user_id`(FK), `expense_date`, `account_title`, `amount`, `status` |
| `t_monthly_closing` | 月次締めレコード | 月次決算 | `id`(PK), `closing_month`(UK), `is_closed`, `closed_at`, `closed_by`(FK) |
| `t_approval_request`| 承認ワークフロー申請 | ワークフロー | `id`(PK), `request_type`, `applicant_user_id`(FK), `status` |
| `t_audit_log` | 操作監査ログ | ガバナンス | `id`(PK), `user_name`, `request_uri`, `http_method`, `action_type`, `created_at` |
| `m_system_config` | システム共通設定 | システム設定 | `id`(PK), `config_key`(UK), `config_value`, `description` |
