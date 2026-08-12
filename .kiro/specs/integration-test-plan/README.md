# SES Manager Pro 日本標準 結合テスト（ITa・ITb）全体計画書

本ディレクトリ (`.kiro/specs/integration-test-plan/`) は、SES Manager Pro の**全15モジュール内の詳細機能結合（ITa）** および **モジュール間クロス連携結合（ITb）** を完全網羅した、超高密度・画面UI操作ベースの結合テスト計画仕様書群です。

---

## 1. 結合テスト構成と仕様ドキュメント体系

本結合テスト計画は、以下の 4 つの専門仕様ドキュメントに分かれて定義されています。

```text
.kiro/specs/integration-test-plan/
├── README.md                      # 本書（基本方針・モジュール一覧・300人データ・S10〜S17定義）
├── module-test-matrix.md           # 【ITa】全15モジュール単体・内部インターフェース結合テスト詳細マトリクス
├── inter-module-integration.md     # 【ITb】全モジュール間（X↔Y）クロス連携・画面＆データ結合マトリクス
├── e2e-business-scenarios.md       # 【ITb E2E】300人規模 7大業務ライフサイクルシナリオ（UI操作＆DB落盤）
└── schedule-and-resources.md       # 4週間（20営業日）詳細スケジュール、体制、エントリー/エグジット基準
```

---

## 2. システム全 15 モジュール定義一覧

SES Manager Pro の全機能を以下の 15 モジュールに分解し、モジュール内およびモジュール間の結合テストを設計します。

| モジュールID | モジュール名称 | 主要画面URL | 対象 DB テーブル |
|---|---|---|---|
| **MOD-01** | **認証・アカウント・権限・MFA・監査** | `/login`, `/user/list`, `/mfa`, `/audit-log/list` | `sys_user`, `t_role_menu`, `m_menu`, `t_audit_log` |
| **MOD-02** | **採用・候補者管理 (Recruiting)** | `/candidate/list` | `t_candidate`, `t_candidate_history` |
| **MOD-03** | **エンジニア・職歴・担当営業マスタ** | `/engineer/list`, `/engineer/detail/{id}` | `t_engineer`, `t_engineer_sales`, `t_engineer_career` |
| **MOD-04** | **顧客・CRM・商談・コンタクト** | `/customer/list`, `/crm/list` | `t_customer`, `t_customer_contact`, `t_lead`, `t_opportunity` |
| **MOD-05** | **SES案件・要件スキル・AIマッチング** | `/project/list`, `/project/detail/{id}` | `t_project`, `t_project_skill`, `t_ai_match_score` |
| **MOD-06** | **提案Kanban・メールテンプレート** | `/proposal/kanban`, `/email-template/list` | `t_proposal`, `t_proposal_history`, `t_email_template` |
| **MOD-07** | **契約・単価改定・S10コンプライアンス・署名** | `/contract/list`, `/compliance/dispatch-ledger` | `t_contract`, `t_contract_price_history`, `t_dispatch_compliance` |
| **MOD-08** | **勤怠タイムシート・S11承認・36協定・月次締め** | `/my/timesheet`, `/attendance/list`, `/monthly-closing/list` | `t_work_record`, `t_monthly_closing`, `t_leave_balance` |
| **MOD-09** | **請求・売掛金消込・S16 JP PINT** | `/invoice/list`, `/reconciliation/list`, `/invoice/jp-pint` | `t_invoice`, `t_invoice_payment`, `t_jp_pint_export_log` |
| **MOD-10** | **BPパートナー・S12キャパシティ・S13外部ポータル** | `/bp-availability/list`, `/staffing/capacity-planning`, `/portal/customer-bp` | `t_bp_company`, `t_bp_availability`, `t_staffing_capacity` |
| **MOD-11** | **S15 会計連携・全銀FBデータ・BP支払** | `/accounting/export`, `/bp-payment/list` | `t_accounting_journal`, `t_fb_transfer_log`, `t_bp_payment` |
| **MOD-12** | **S14 要員セルフサービスポータル v2** | `/portal/engineer-self` | `t_engineer_self_profile`, `t_engineer_expense` |
| **MOD-13** | **S17 AIフィードバック学習** | `/ai/feedback-learning` | `t_ai_feedback_log`, `t_ai_model_config` |
| **MOD-14** | **組織・管理会計・営業歩合・ダッシュボード** | `/`, `/sales-performance`, `/management-accounting` | `m_organization`, `m_system_config`, `t_sales_commission_snapshot` |
| **MOD-15** | **多段階承認・見積・注文・検収・文書保管** | `/approval/list`, `/quotation/list`, `/sales-order/list`, `/document/list` | `t_approval_request`, `t_quotation`, `t_sales_order`, `t_document_archive` |

---

## 3. 300人規模実データ基盤と S10〜S17 並行開発の前提

1. **300人規模実データ (`V100__seed_r3_scale_300.sql`)**:
   - 255名 要員 / 25名 担当営業 / 10名 マネージャー / 8名 HR / 2名 管理者。
   - 25営業間のマルチテナントデータスコープ (`DataScopeService`) 相互遮断を全画面操作で検証。
2. **S10〜S17 並行開発の包含**:
   - S10 派遣請負コンプライアンス、S11 勤怠・36協定、S12 キャパシティ、S13 外部ポータル、S14 要員ポータルv2、S15 会計仕訳・FBデータ、S16 JP PINT デジタルインボイス、S17 AI学習を各モジュールへ統合。
3. **freee 連携の分離**:
   - 改修中につき `payroll-management` (freee API) はモックにて隔離、保留とする。

---
