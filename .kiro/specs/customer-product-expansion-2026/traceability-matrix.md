# 顧客課題→実装→効果 トレーサビリティ

| 顧客課題/現状 | 対応spec | 主に接続する既存資産 | 実装後の効果 | 効果確認指標 |
|---|---|---|---|---|
| 顧客ごとのDB分離かSaaS共有DBか未確定 | multi-company-tenant-isolation | BaseEntity/MyBatis/Security/全mapper | tenant境界を後付けせず、販売形態に合う分離 | cross-tenant漏洩0、移行差分0 |
| 部門/上長/原価部門がなく全社集計のみ | organization-management-accounting | SysUser/DataScope/Dashboard/MonthlyClosing | 部門責任者の管理と過去月を変えない部門損益 | 全社=部門合計、異動後過去不変 |
| form loginと固定role中心 | enterprise-identity-security | SecurityConfig/SysUser/Menu/File | SSO/MFA、session/権限/PII統制 | 管理者MFA100%、権限経路差0 |
| PDF/fileが機能ごと・local path中心 | legal-document-ledger-archive | FileStorage/PDF/CloudSign/Audit | 原本、版、hash、検索、保存/廃棄証跡 | hash検証100%、検索/export成功 |
| ToDoが通知一覧、横断検索/保存viewなし | productivity-search-saved-view | Notification/各一覧/DataScope | 探索時間と手作業を削減、担当/期限を管理 | task期限遵守、検索到達時間 |
| BP会社名が在庫/支払の自由文字列 | bp-company-master-procurement-compliance | BpAvailability/BpPayment/Engineer | BPを一意管理し取適法/フリーランス確認を支援 | 現役自由入力0、期限違反警告 |
| 高リスク操作を権限保持者が即時確定 | approval-workflow-internal-control | Quotation/Contract/Invoice/Bp/Closing | 申請者と承認者を分離し差分/代理/SLAを追跡 | 単独確定0、二重適用0 |
| 顧客担当者1名、商機前段がない | crm-contact-opportunity | Customer/SalesActivity/Project/Quotation | 決裁/現場/請求担当と商機funnelを管理 | 次action漏れ、転換率/失注理由 |
| 見積と契約間の注文/検収がメール | order-acceptance-workflow | Quotation/Contract/WorkRecord/Invoice | PO/注文請/検収を請求根拠へ接続 | 未検収請求0、検収日数 |
| complianceが4種のrule警告中心 | dispatch-outsourcing-compliance-ledger | Contract/LaborCompliance/Document | 派遣台帳、明示書、抵触日、指示経路を追跡 | 必須項目欠落0、期限超過0 |
| 客先工数はあるが雇用勤怠/休暇なし | attendance-leave-overtime-compliance | WorkRecord/MyTimesheet/Freee/Org | 勤怠と請求工数を分離し36協定を監視 | 45/360等警告精度、差異確認率 |
| 案件requiredCountのみ、兼務/将来需給なし | staffing-capacity-planning | Project/Proposal/Contract/Analytics | position/FTE/将来不足/シナリオを可視化 | 過配賦0、充足率/bench予測 |
| 顧客/BPとの文書往復がメール | external-customer-bp-portal | Archive/Acceptance/BP/Identity | 顧客検収、BP提出/支払参照を外部self-service化 | メール添付件数、検収lead time |
| 要員self-serviceは勤怠中心 | engineer-self-service-portal-v2 | MyTimesheet/Engineer/Payroll/Followup | profile/skill/給与/経費/1on1を本人起点化 | 転記件数、申請処理日数 |
| freeeは給与/入金読取中心 | accounting-payment-integration | Freee/Invoice/BpPayment/Closing | 売上/仕入/経費/支払を冪等連携し月次照合 | 二重伝票0、照合差異/未送信 |
| PDF請求中心、構造化invoiceなし | jp-pint-digital-invoice | Invoice/Archive/Accounting | provider経由でJP PINT送受信 | validator合格、delivery/reject率 |
| AI既定mock、採否/成果/版評価なし | ai-feedback-learning | Ai*/Proposal/Contract/Matching | 推薦→採否→面談→成約を版別評価 | 採用/面談/成約率、PII漏洩0 |

## 期待効果の測定ルール

1. 変更前の4週間または直近1締め月をbaselineとして記録する。
2. 機能公開後1か月/3か月で同じ定義を再計測する。
3. 「画面ができた」ではなく、転記件数、漏れ、処理日数、差異、違反、二重登録等の業務結果を測る。
4. KPI改善がない場合、機能追加ではなく利用導線、権限、入力負荷、運用ルールを先に調査する。

