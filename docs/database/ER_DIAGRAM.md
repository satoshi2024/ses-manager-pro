# SES Manager Pro データベース設計書 & ER図（Entity Relationship Architecture）

本ドキュメントは、システムエンジニアリングサービス（SES）統合管理プラットフォーム「**SES Manager Pro**」のデータベース構造、エンティティ間リレーションシップ（ER図）、各ドメインのテーブル定義、およびデータ整合性制約を定義した公式データベース仕様書です。

---

## 1. データベースアーキテクチャ概要

* **RDBMS**: MySQL 8.0+ / InnoDB ストレージエンジン
* **文字コード / 照合順序**: `utf8mb4` / `utf8mb4_unicode_ci`
* **論理削除 (Soft Delete)**: `deleted_flag TINYINT DEFAULT 0` （MyBatis-Plus Global Logic Delete）
* **共通監査カラム**:
  * `created_at DATETIME DEFAULT CURRENT_TIMESTAMP` (作成日時)
  * `updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP` (更新日時)
  * `created_by VARCHAR(50)` / `updated_by VARCHAR(50)` (作成者/更新者)

---

## 2. 全社エンティティ相関図 (High-Level ER Matrix)

```mermaid
erDiagram
    sys_user ||--o{ t_engineer_sales : "担当営業割当"
    sys_user ||--o{ t_contract : "担当営業"
    sys_user ||--o{ t_approval_request : "申請/承認"
    
    m_organization_unit ||--o{ sys_user : "所属"
    m_organization_unit ||--o{ m_cost_center : "コスト配賦"

    m_customer ||--o{ t_opportunity : "商談"
    m_customer ||--o{ t_project : "発注元"
    m_customer ||--o{ t_contract : "契約先"
    m_customer ||--o{ t_invoice : "請求先"

    t_engineer ||--o{ t_engineer_skill : "保有スキル"
    t_engineer ||--o{ t_engineer_career : "職務経歴"
    t_engineer ||--o{ t_proposal : "提案候補"
    t_engineer ||--o{ t_contract : "アサイン"
    t_engineer ||--o{ t_work_record : "月次勤怠"
    t_engineer ||--o{ t_expense_request : "経費申請"

    t_bp_company ||--o{ t_bp_availability : "空き要員"
    t_bp_company ||--o{ t_contract : "仕入先"
    t_bp_company ||--o{ t_bp_payment : "支払先"

    t_project ||--o{ t_proposal : "提案先案件"
    t_proposal ||--o{ t_quotation : "見積作成"
    t_proposal ||--o{ t_contract : "成約契約"

    t_contract ||--o{ t_contract_document : "電子契約"
    t_contract ||--o{ t_work_record : "稼働工数"
    t_contract ||--o{ t_acceptance : "検収"
    t_contract ||--o{ t_invoice_item : "請求明細"
    t_contract ||--o{ t_bp_payment : "BP支払"

    t_invoice ||--o{ t_invoice_item : "内訳明細"
    t_invoice ||--o{ t_reconciliation : "入金消込"
    t_bank_deposit ||--o{ t_reconciliation : "入金元"

    t_monthly_closing ||--o{ t_monthly_accounting_dimension : "確定P/L"
```

---

## 3. ドメイン別詳細ER図

### 3.1 認証・権限・組織・監査ドメイン (Auth, RBAC, Org & Audit)

```mermaid
erDiagram
    sys_user {
        BIGINT id PK "ユーザーID"
        VARCHAR username UK "ログインID"
        VARCHAR password "パスワード(BCrypt)"
        VARCHAR real_name "氏名"
        ENUM role "権限ロール(管理者/営業/HR/マネージャー/要員)"
        VARCHAR email "メールアドレス"
        TINYINT status "1:有効 0:無効"
        INT failed_count "連続認証失敗回数"
        DATETIME locked_until "アカウントロック解除時刻"
        BIGINT organization_id FK "所属部署ID"
    }

    m_menu {
        BIGINT id PK "メニューID"
        VARCHAR menu_key UK "メニュー識別子"
        VARCHAR menu_name "メニュー表示名"
        VARCHAR path_prefix "画面パス接頭辞"
        VARCHAR api_prefix "APIパス接頭辞"
        INT sort_order "表示順"
    }

    t_role_menu {
        BIGINT id PK "ID"
        VARCHAR role "ロール名"
        BIGINT menu_id FK "メニューID"
    }

    m_organization_unit {
        BIGINT id PK "部署ID"
        VARCHAR unit_code UK "部署コード"
        VARCHAR unit_name "部署名"
        BIGINT parent_id FK "親組織ID"
        BIGINT manager_user_id FK "部門責任者ID"
        INT sort_order "ソート順"
    }

    t_audit_log {
        BIGINT id PK "ログID"
        VARCHAR user_name "実行ユーザー名"
        VARCHAR client_ip "クライアントIP"
        VARCHAR request_uri "アクセスAPIパス"
        VARCHAR http_method "HTTPメソッド"
        VARCHAR action_type "操作種別(INSERT/UPDATE/DELETE)"
        TEXT before_data "変更前JSON"
        TEXT after_data "変更後JSON"
        DATETIME created_at "実行日時"
    }

    sys_user ||--o{ t_role_menu : "権限照合"
    m_menu ||--o{ t_role_menu : "メニューマッピング"
    m_organization_unit ||--o{ sys_user : "所属メンバー"
```

---

### 3.2 要員・スキル・採用・BPドメイン (Engineer, Skill, Candidate & BP)

```mermaid
erDiagram
    t_engineer {
        BIGINT id PK "要員ID"
        VARCHAR engineer_code UK "要員コード"
        VARCHAR name "氏名（漢字）"
        VARCHAR kana_name "氏名（カナ）"
        VARCHAR initial "イニシャル表記"
        ENUM employment_type "所属区分(自社正社員/契約/フリーランス/BP)"
        DECIMAL cost_price "原価月額（円）"
        DECIMAL target_unit_price "目標単価（円）"
        VARCHAR nearest_station "最寄駅"
        VARCHAR railway_company "通勤路線"
        TINYINT status "1:稼働中 2:待機中 0:退職"
        BIGINT user_id FK "ログインアカウントID"
    }

    m_skill_tag {
        BIGINT id PK "スキルID"
        VARCHAR category "カテゴリ(言語/FW/DB/クラウド/ツール)"
        VARCHAR skill_name UK "スキル名"
    }

    t_engineer_skill {
        BIGINT id PK "ID"
        BIGINT engineer_id FK "要員ID"
        BIGINT skill_id FK "スキルID"
        INT experience_months "実務経験月数"
        INT skill_level "習熟度(1-5)"
    }

    t_engineer_career {
        BIGINT id PK "職歴ID"
        BIGINT engineer_id FK "要員ID"
        DATE start_date "開始年月"
        DATE end_date "終了年月"
        VARCHAR project_name "参画プロジェクト名"
        VARCHAR role "担当役割(PM/SE/PG)"
        TEXT technical_skills "使用技術スタック"
        TEXT job_description "業務内容・担当工程"
    }

    t_engineer_sales {
        BIGINT id PK "割当ID"
        BIGINT engineer_id FK "要員ID"
        BIGINT sales_user_id FK "担当営業ユーザーID"
        TINYINT primary_flag "主担当フラグ(1:主 0:副)"
        DATETIME assigned_at "割当日時"
        DATETIME released_at "解除日時(NULL=現在有効)"
    }

    t_candidate {
        BIGINT id PK "候補者ID"
        VARCHAR name "氏名"
        VARCHAR email "メールアドレス"
        VARCHAR phone "電話番号"
        VARCHAR applied_position "応募職種"
        VARCHAR selection_stage "選考ステージ(書類/一次/最終/内定/入社)"
        DECIMAL desired_salary "希望年収/単価"
        BIGINT converted_engineer_id FK "要員化時ID"
    }

    t_bp_company {
        BIGINT id PK "BP企業ID"
        VARCHAR company_code UK "BPコード"
        VARCHAR company_name "企業名"
        VARCHAR corporate_number "法人番号"
        VARCHAR invoice_number "適格請求書登録番号(T番号)"
        VARCHAR payment_terms "支払サイト"
        DATE basic_contract_date "基本契約締結日"
        DATE nda_date "NDA締結日"
    }

    t_bp_availability {
        BIGINT id PK "在庫ID"
        BIGINT bp_company_id FK "BP企業ID"
        VARCHAR engineer_name "要員名/イニシャル"
        VARCHAR skill_summary "主要スキル要約"
        DECIMAL desired_price "希望仕入単価"
        DATE available_from "稼働可能開始日"
        VARCHAR work_location "希望勤務地/リモート率"
        TINYINT status "1:公開中 0:終了"
    }

    t_engineer ||--o{ t_engineer_skill : "保有スキル"
    m_skill_tag ||--o{ t_engineer_skill : "タグ"
    t_engineer ||--o{ t_engineer_career : "職歴タイムライン"
    t_engineer ||--o{ t_engineer_sales : "営業担当履歴"
    t_bp_company ||--o{ t_bp_availability : "所属空き要員"
```

---

### 3.3 顧客・商機・案件・提案・契約ドメイン (CRM, Project, Proposal & Contract)

```mermaid
erDiagram
    m_customer {
        BIGINT id PK "顧客ID"
        VARCHAR customer_code UK "顧客コード"
        VARCHAR customer_name "企業名"
        VARCHAR corporate_number "法人番号"
        VARCHAR invoice_number "適格請求書登録番号"
        VARCHAR payment_terms "支払サイト（例: 月末締め翌月末払い）"
        BIGINT sales_user_id FK "主担当営業ID"
    }

    t_opportunity {
        BIGINT id PK "商機ID"
        BIGINT customer_id FK "顧客ID"
        VARCHAR opportunity_name "商機名"
        VARCHAR stage "ステージ(アプローチ/提案/内定/成約/失注)"
        VARCHAR probability "確度ランク(A/B/C/D)"
        DECIMAL expected_revenue "想定月額売上"
        DATE expected_start_date "受注予定日"
    }

    t_project {
        BIGINT id PK "案件ID"
        BIGINT customer_id FK "顧客ID"
        VARCHAR project_code UK "案件コード"
        VARCHAR project_name "案件名"
        VARCHAR job_title "募集職種"
        DECIMAL min_unit_price "下限単価"
        DECIMAL max_unit_price "上限単価"
        INT settlement_min_hours "精算基準下限時間(例: 140h)"
        INT settlement_max_hours "精算基準上限時間(例: 180h)"
        VARCHAR status "ステータス(募集中/提案中/成約済/クローズ)"
    }

    t_proposal {
        BIGINT id PK "提案ID"
        BIGINT project_id FK "案件ID"
        BIGINT engineer_id FK "要員ID"
        VARCHAR status "ステータス(提案中/書類通過/面談調整/面談済/成約/失注)"
        DECIMAL proposed_price "提案月額単価"
        DATE interview_date "面談日時"
        BIGINT proposed_by FK "提案営業ユーザーID"
    }

    t_quotation {
        BIGINT id PK "見積ID"
        VARCHAR quotation_number UK "見積番号"
        BIGINT customer_id FK "顧客ID"
        BIGINT project_id FK "案件ID"
        BIGINT engineer_id FK "要員ID"
        DECIMAL monthly_price "月額単価"
        DECIMAL overtime_unit_price "超過単価"
        DECIMAL deduction_unit_price "控除単価"
        VARCHAR status "ステータス(下書き/申請中/承認済/発行済)"
        DATE valid_until "有効期限"
    }

    t_contract {
        BIGINT id PK "契約ID"
        VARCHAR contract_number UK "契約番号"
        BIGINT customer_id FK "顧客ID"
        BIGINT engineer_id FK "要員ID"
        BIGINT bp_company_id FK "BP企業ID(自社時NULL)"
        ENUM contract_type "契約形態(準委任/派遣/請負)"
        DATE start_date "契約開始日"
        DATE end_date "契約終了日"
        DECIMAL unit_price "売上月額単価"
        DECIMAL cost_price "仕入月額原価"
        INT min_hours "精算下限時間(140h)"
        INT max_hours "精算上限時間(180h)"
        BIGINT sales_user_id FK "担当営業ID"
        ENUM commission_base_type "歩合基準(売上/粗利)"
        DECIMAL commission_rate "歩合率(%)"
        TINYINT status "1:契約中 2:満了間近 3:更新済 0:終了"
    }

    t_contract_document {
        BIGINT id PK "文書ID"
        BIGINT contract_id FK "契約ID"
        VARCHAR document_title "契約書タイトル"
        VARCHAR cloudsign_document_id "CloudSignドキュメントID"
        VARCHAR sign_status "署名ステータス(下書き/先方確認中/締結済)"
        VARCHAR storage_file_path "締結済PDF保存パス"
    }

    m_customer ||--o{ t_opportunity : "商談"
    m_customer ||--o{ t_project : "案件発注"
    t_project ||--o{ t_proposal : "提案候補"
    t_proposal ||--o{ t_quotation : "見積書"
    t_proposal ||--o{ t_contract : "成約契約"
    t_contract ||--o{ t_contract_document : "電子署名"
```

---

### 3.4 勤怠・検収・請求・入金消込・経費ドメイン (Billing, AR & Expenses)

```mermaid
erDiagram
    t_work_record {
        BIGINT id PK "勤怠ID"
        BIGINT contract_id FK "契約ID"
        BIGINT engineer_id FK "要員ID"
        VARCHAR work_month "稼働年月(YYYY-MM)"
        DECIMAL actual_hours "実稼働工数(h)"
        DECIMAL over_hours "超過時間(h)"
        DECIMAL deduction_hours "控除不足時間(h)"
        DECIMAL billing_amount "請求確定金額（円）"
        DECIMAL payment_amount "BP支払金額（円）"
        TINYINT status "0:未提出 1:提出済 2:承認済"
        VARCHAR evidence_file_path "客先勤務表エビデンスパス"
    }

    t_acceptance {
        BIGINT id PK "検収ID"
        BIGINT contract_id FK "契約ID"
        VARCHAR work_month "検収年月"
        DECIMAL accepted_hours "客先確定検収時間(h)"
        DECIMAL variance_hours "申告工数との差異(h)"
        VARCHAR acceptance_status "検収ステータス(未確定/確定済)"
        DATE confirmed_at "確定日時"
    }

    t_invoice {
        BIGINT id PK "請求書ID"
        VARCHAR invoice_number UK "請求書番号"
        BIGINT customer_id FK "顧客ID"
        VARCHAR billing_month "請求対象月"
        DATE issue_date "発行日"
        DATE due_date "支払期日"
        DECIMAL subtotal_amount "税抜合計額"
        DECIMAL tax_amount "消費税額(10%)"
        DECIMAL total_amount "税込合計額"
        VARCHAR status "ステータス(未発行/発行済/送付済/入金完了)"
        VARCHAR pdf_file_path "請求書PDF保存パス"
    }

    t_invoice_item {
        BIGINT id PK "明細ID"
        BIGINT invoice_id FK "請求書ID"
        BIGINT contract_id FK "契約ID"
        VARCHAR item_name "品名（要員稼働費）"
        DECIMAL unit_price "基本単価"
        DECIMAL adjustment_amount "過不足精算額"
        DECIMAL amount "明細合計金額"
    }

    t_bank_deposit {
        BIGINT id PK "入金ID"
        DATE deposit_date "入金日"
        VARCHAR payer_name "振込人名義（カナ）"
        DECIMAL amount "入金金額"
        VARCHAR bank_account "入金口座番号"
        TINYINT reconciled_flag "消込済フラグ(1:消込済 0:未消込)"
    }

    t_reconciliation {
        BIGINT id PK "消込ID"
        BIGINT invoice_id FK "請求書ID"
        BIGINT deposit_id FK "入金ID"
        DECIMAL reconciled_amount "消込金額"
        DECIMAL fee_amount "振込手数料額"
        DATETIME reconciled_at "消込完了日時"
    }

    t_bp_payment {
        BIGINT id PK "支払ID"
        BIGINT bp_company_id FK "BP企業ID"
        BIGINT contract_id FK "契約ID"
        VARCHAR work_month "対象年月"
        DECIMAL payment_amount "仕入支払金額"
        DECIMAL tax_amount "消費税額"
        DATE payment_due_date "支払期日"
        VARCHAR status "ステータス(未確定/確定済/振込完了)"
    }

    t_expense_request {
        BIGINT id PK "経費申請ID"
        BIGINT user_id FK "申請ユーザーID"
        DATE expense_date "利用日"
        VARCHAR account_title "勘定科目（旅費交通費等）"
        DECIMAL amount "税込金額"
        VARCHAR payee "支払先"
        VARCHAR invoice_number "インボイス事業者番号"
        VARCHAR receipt_file_path "領収書画像パス"
        VARCHAR status "ステータス(申請中/一次承認/経理承認/精算済/差戻し)"
    }

    t_work_record ||--o{ t_invoice_item : "精算反映"
    t_invoice ||--o{ t_invoice_item : "請求明細"
    t_invoice ||--o{ t_reconciliation : "消込先"
    t_bank_deposit ||--o{ t_reconciliation : "消込元"
```

---

### 3.5 ワークフロー・決算・システム設定ドメイン (Workflow, Closing & System)

```mermaid
erDiagram
    t_approval_request {
        BIGINT id PK "申請ID"
        VARCHAR request_type "申請種別(勤怠/休暇/経費/見積/契約/変更申請)"
        BIGINT target_id "対象エンティティID"
        BIGINT applicant_user_id FK "申請者ID"
        VARCHAR status "ステータス(承認待ち/承認済/差戻し/却下)"
        DATETIME submitted_at "申請日時"
    }

    t_approval_route {
        BIGINT id PK "ルートID"
        VARCHAR request_type "対象申請種別"
        INT step_number "承認ステップ順序"
        VARCHAR approver_role "承認役職/ロール"
    }

    t_approval_action {
        BIGINT id PK "履歴ID"
        BIGINT request_id FK "申請ID"
        BIGINT approver_user_id FK "承認者ID"
        VARCHAR action "アクション(承認/差戻し/却下)"
        TEXT comment "承認/差戻しコメント"
        DATETIME action_at "処理日時"
    }

    t_monthly_closing {
        BIGINT id PK "締めID"
        VARCHAR closing_month UK "締め年月(YYYY-MM)"
        TINYINT is_closed "1:ロック中 0:解除中"
        DATETIME closed_at "締め実行日時"
        BIGINT closed_by FK "締め実行者ID"
        TEXT unlock_reason "ロック解除理由（例外時のみ）"
    }

    m_system_config {
        BIGINT id PK "ID"
        VARCHAR config_key UK "設定キー"
        VARCHAR config_value "設定値"
        VARCHAR description "説明"
    }

    t_approval_request ||--o{ t_approval_action : "承認履歴"
```

---

## 4. データベース整合性・制約ポリシー

1. **外部キー制約 (FK) & カスケード保護**:
   * マスタデータ（顧客、要員、契約）は、誤削除によるトランザクション孤立を防ぐため `RESTRICT` を基本とし、MyBatis-Plus の論理削除（`deleted_flag = 1`）によって運用します。
2. **ユニーク制約 (UK) & 重複防止**:
   * `sys_user.username`、`m_customer.customer_code`、`t_engineer.engineer_code`、`t_contract.contract_number`、`t_invoice.invoice_number`、`t_monthly_closing.closing_month` など業務キーに対して一意性制約を定義。
3. **トランザクション整合性 & 月次締めガード**:
   * 月次締めが完了した月度（`t_monthly_closing.is_closed = 1`）に対する `t_work_record`、`t_acceptance`、`t_invoice`、`t_bp_payment` への更新操作は、サービス層の `MonthlyClosingService.assertOpenForUpdate()` により確実に例外遮断されます。
