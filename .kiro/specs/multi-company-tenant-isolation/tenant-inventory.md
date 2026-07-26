# tenant / legal entity 将来互換 inventory

## 1. 目的、決定、適用範囲

本書は `multi-company-tenant-isolation` のT001成果物であり、2026-07-26時点の読み取り専用棚卸しである。

- G0の発注者決定により、現在の正式な配備方式は**顧客ごとの独立DB**である。
- 独立DBのDB境界、既存認証、既存データスコープ、ファイル参照検証はデータ隔離の一部として維持する。
- 共有DB SaaSの全表`tenant_id`化、`TenantContext`、tenant interceptor、tenant単位backup/restore、共有DB用UNIQUE/FKは現在実装しない。
- V59は作成せず、従来の予約を取消して永久欠番として保持する。今回のT001ではDDL、既存行backfill、V1/H2変更、MySQL smoke assertを作成・実行しない。将来共有DBを再開する場合もV59を補完・再利用せず、その時点のFlyway最新番号`latest + 1`から新しいmigrationを採番する。
- 共有DB方式とtenant実装は、SaaS販売方式、契約・法務・セキュリティ・移行・運用条件が正式承認され、発注者がG0再開を明示した時だけ新規に再計画する。現在の実装taskを自動継続しない。
- 後続の新規テーブルは、`tenant_id`対象、global共有、tenant overrideの分類を設計時に必ず記録する。分類未確認のまま共有DB向けDDLを作らない。

### 1.1 要件ID

| ID | 将来の確認対象 |
|---|---|
| R1 | tenantとlegal entityのモデル、既存行のdefault tenant移行 |
| R2 | request、SQL、UNIQUE/FK、cache、ShedLock、file、外部キーの強制分離 |
| R3 | tenant解決、platform境界、停止、tenant単位export/restore、全体PITR |
| R4 | 独立DBとの後方互換、feature flag、段階migration |
| R5 | A/B同名データ、漏洩防止、context欠落、reconciliation、既存回帰 |

### 1.2 将来テストID

| ID | 内容 |
|---|---|
| TEN-001 | 独立DBモードと共有DBモードの起動・既存回帰。A/B同一username、顧客名、契約番号の境界確認 |
| TEN-002 | tenant context未解決、host不一致、停止tenant、既存sessionの更新拒否 |
| TEN-003 | list/detail/count、custom SQL、集計、annotation SQLのA/B漏洩ゼロ |
| TEN-004 | tenantを含むUNIQUE/FK、別tenant ID参照、404/403と件数非開示 |
| TEN-005 | scheduler、async、thread reuse、cache、ShedLockのtenant混線ゼロ |
| TEN-006 | file download、未知file fail-closed、Exportのscope一致 |
| TEN-007 | 通知、Webhook、Mail delivery、監査ログのrecipient/tenant境界 |
| TEN-008 | freee、CloudSign、外部ID、冪等キー、相関IDのtenant/legal entity境界 |
| TEN-009 | tenant単位backup/restore、全体PITR、復元後count/金額reconciliation |
| TEN-010 | 既存DBの件数、請求金額、工数・支払合計の移行前後一致 |

## 2. データ表 inventory

「将来tenant_id」は、現在追加する列ではなく、共有DB方式を再開した時に必要な境界の分類である。`分類`はglobal seed、tenant override、またはtenant所属のどれかを将来のDDLレビューで確定する。

| 表 | 将来tenant_id | 現在の扱い / 将来の対応 | owner | risk | 要件 / テスト |
|---|---|---|---|---|---|
| `sys_user` | 要 | 現在は顧客別DB。共有DB再開時にtenant付きusernameとplatform境界を設計 | 認証/基盤担当 | 高 | R2/R3/R4, TEN-001/002/004 |
| `m_customer` | 要 | 顧客企業はtenant所属。customer IDの参照をtenant検証 | 顧客業務担当 | 高 | R1/R2, TEN-003/004 |
| `t_engineer` | 要 | tenant所属。関連career、skill、accountも同一境界 | 要員業務担当 | 高 | R2/R5, TEN-003/004/010 |
| `t_engineer_career` | 親に従う | engineer経由でtenant検証 | 要員業務担当 | 中 | R2, TEN-003/004 |
| `m_skill_tag` | 分類 | global seedまたはtenant overrideを将来決定 | マスタ担当 | 中 | R1/R2, TEN-003/004 |
| `t_engineer_skill` | 要 | engineer/skillの複合参照をtenant検証 | 要員業務担当 | 高 | R2, TEN-003/004 |
| `t_project` | 要 | customer、engineer、proposalとの境界を統一 | 案件業務担当 | 高 | R1/R2, TEN-003/004 |
| `t_project_skill` | 要 | project経由でtenant検証 | 案件業務担当 | 中 | R2, TEN-003/004 |
| `t_proposal` | 要 | engineer、project、customerと同一tenant | 営業業務担当 | 高 | R2/R5, TEN-003/004 |
| `t_proposal_history` | 親に従う | proposal削除・履歴参照時にtenantを継承 | 営業業務担当 | 中 | R2/R5, TEN-003/004 |
| `t_contract` | 要 | customer、engineer、legal entity、sales repを境界化 | 契約/請求担当 | 高 | R1/R2/R5, TEN-003/004/010 |
| `t_ai_log` | 要 | PIIとprompt/resultをtenant scopeで保管 | AI/セキュリティ担当 | 高 | R2/R5, TEN-003/007 |
| `m_email_template` | 分類 | global templateとtenant overrideを分離 | 通知担当 | 中 | R1/R2, TEN-007 |
| `m_system_config` | 分類 | global設定とtenant/legal entity設定を分離 | 基盤担当 | 高 | R1/R2, TEN-008 |
| `m_menu` | global/override | seedはglobal、tenant権限overrideの要否を将来決定 | 権限担当 | 中 | R2/R3, TEN-002/003 |
| `t_role_menu` | 要/分類 | tenant role mappingまたはglobal seed+override | 権限担当 | 高 | R2/R3, TEN-002/004 |
| `t_notification` | 要 | recipient、menu、dedupe keyをtenant境界化 | 通知担当 | 高 | R2/R5, TEN-007 |
| `t_notification_read` | 親に従う | notificationとuserのtenantを同時検証 | 通知担当 | 中 | R2/R5, TEN-007 |
| `t_work_record` | 要 | contract、engineer、work monthをtenant境界化 | 勤怠/請求担当 | 高 | R2/R5, TEN-003/004/010 |
| `t_invoice` | 要 | customer、contract、legal entity、paymentを境界化 | 請求担当 | 高 | R1/R2/R5, TEN-003/004/010 |
| `t_invoice_item` | 親に従う | invoice/work recordと同一tenant | 請求担当 | 高 | R2/R5, TEN-003/004/010 |
| `t_bp_payment` | 要 | work record、BP、legal entityを境界化 | BP/請求担当 | 高 | R1/R2/R5, TEN-003/004/010 |
| `t_sales_activity` | 要 | customer、engineer、userのtenant検証 | CRM担当 | 中 | R2, TEN-003/004 |
| `t_audit_log` | 要 | tenant eventとplatform eventを分離 | 監査/セキュリティ担当 | 高 | R2/R3, TEN-007 |
| `t_engineer_sales` | 親に従う | engineerとsales userの同一tenantを検証 | 営業/要員担当 | 高 | R2/R5, TEN-003/004 |
| `t_candidate` | 要 | candidateとconversion先engineerを境界化 | 採用担当 | 高 | R2/R5, TEN-003/004 |
| `t_candidate_activity` | 親に従う | candidate履歴のtenant継承 | 採用担当 | 中 | R2, TEN-003/004 |
| `m_contract_template` | 分類 | global templateとtenant overrideを分離 | 契約/文書担当 | 中 | R1/R2, TEN-006 |
| `t_contract_document` | 要 | contract、原本、signed PDFをtenant scope化 | 文書/契約担当 | 高 | R2/R3, TEN-006/009 |
| `t_freee_connection` | 要 | 現在はglobal単一接続。将来tenant/legal entity単位へ再設計 | 外部連携担当 | 高 | R1/R2, TEN-008 |
| `t_freee_employee_link` | 親に従う | engineerとfreee employeeのtenant境界 | 外部連携担当 | 高 | R2, TEN-008 |
| `t_mail_delivery` | 要 | invoice、recipient、provider結果をtenant境界化 | 通知/請求担当 | 中 | R2/R5, TEN-007/008 |
| `t_invoice_payment` | 要 | invoice、bank deposit、external payment IDを境界化 | 請求担当 | 高 | R1/R2/R5, TEN-004/008/010 |
| `t_quotation` | 要 | customer、project、proposal、created_byの暗黙参照を明示化 | 見積担当 | 高 | R2/R5, TEN-003/004 |
| `t_engineer_account_link` | 親に従う | sys_userとengineerの同一tenantを検証 | 認証/要員担当 | 高 | R2/R3, TEN-002/004 |
| `t_work_record_daily` | 親に従う | work recordとcontractのtenantを継承 | 勤怠担当 | 中 | R2/R5, TEN-003/010 |
| `t_contract_price_history` | 親に従う | contract価格履歴のtenant継承 | 契約/請求担当 | 中 | R2/R5, TEN-003/010 |
| `t_resume_ingestion` | 要 | stored file、PII抽出結果をtenant scope化 | 文書/採用担当 | 高 | R2/R3, TEN-006/007 |
| `t_project_ingestion` | 要 | stored fileとproject import結果をtenant scope化 | 案件担当 | 高 | R2, TEN-006 |
| `t_bp_availability` | 要 | BP availabilityとBP会社をtenant scope化 | BP担当 | 高 | R2/R5, TEN-003/004 |
| `t_bp_availability_ingestion` | 親に従う | stored fileとavailabilityを同一tenant | BP担当 | 高 | R2, TEN-006 |
| `t_bank_deposit` | 要 | freee ID、invoice/paymentをlegal entity境界化 | 請求/外部連携担当 | 高 | R1/R2/R5, TEN-008/009/010 |
| `t_engineer_followup` | 要 | engineer、担当者、通知をtenant scope化 | 要員担当 | 中 | R2/R5, TEN-003/007 |
| `shedlock` | 対象外/設計変更 | 業務Entityなし。将来はtenant loopまたはtenant suffixでジョブ競合を防止 | 運用/基盤担当 | 高 | R2/R3, TEN-005 |

現行migrationの根拠は `src/main/resources/db/migration/V1__create_tables.sql:40-419` とV4〜V58の各`CREATE TABLE`である。V1冒頭の「全14テーブル」は実際の16表と一致しないため、実装前に台帳だけを同期する。既存表は44表、`@TableName`付きEntity/Mapperは43組、`shedlock`はEntity/Mapperなしである。

## 3. Entity、Mapper、annotation SQL

### 3.1 EntityとMapperの対応

| Entity | Mapper | 表 |
|---|---|---|
| `SysUser` | `SysUserMapper` | `sys_user` |
| `Customer` | `CustomerMapper` | `m_customer` |
| `Engineer` | `EngineerMapper` | `t_engineer` |
| `EngineerCareer` | `EngineerCareerMapper` | `t_engineer_career` |
| `SkillTag` | `SkillTagMapper` | `m_skill_tag` |
| `EngineerSkill` | `EngineerSkillMapper` | `t_engineer_skill` |
| `Project` | `ProjectMapper` | `t_project` |
| `ProjectSkill` | `ProjectSkillMapper` | `t_project_skill` |
| `Proposal` | `ProposalMapper` | `t_proposal` |
| `ProposalHistory` | `ProposalHistoryMapper` | `t_proposal_history` |
| `Contract` | `ContractMapper` | `t_contract` |
| `AiLog` | `AiLogMapper` | `t_ai_log` |
| `EmailTemplate` | `EmailTemplateMapper` | `m_email_template` |
| `SystemConfig` | `SystemConfigMapper` | `m_system_config` |
| `Menu` | `MenuMapper` | `m_menu` |
| `RoleMenu` | `RoleMenuMapper` | `t_role_menu` |
| `Notification` | `NotificationMapper` | `t_notification` |
| `NotificationRead` | `NotificationReadMapper` | `t_notification_read` |
| `WorkRecord` | `WorkRecordMapper` | `t_work_record` |
| `Invoice` | `InvoiceMapper` | `t_invoice` |
| `InvoiceItem` | `InvoiceItemMapper` | `t_invoice_item` |
| `BpPayment` | `BpPaymentMapper` | `t_bp_payment` |
| `SalesActivity` | `SalesActivityMapper` | `t_sales_activity` |
| `AuditLog` | `AuditLogMapper` | `t_audit_log` |
| `EngineerSales` | `EngineerSalesMapper` | `t_engineer_sales` |
| `Candidate` | `CandidateMapper` | `t_candidate` |
| `CandidateActivity` | `CandidateActivityMapper` | `t_candidate_activity` |
| `ContractTemplate` | `ContractTemplateMapper` | `m_contract_template` |
| `ContractDocument` | `ContractDocumentMapper` | `t_contract_document` |
| `FreeeConnection` | `FreeeConnectionMapper` | `t_freee_connection` |
| `FreeeEmployeeLink` | `FreeeEmployeeLinkMapper` | `t_freee_employee_link` |
| `MailDelivery` | `MailDeliveryMapper` | `t_mail_delivery` |
| `InvoicePayment` | `InvoicePaymentMapper` | `t_invoice_payment` |
| `Quotation` | `QuotationMapper` | `t_quotation` |
| `EngineerAccountLink` | `EngineerAccountLinkMapper` | `t_engineer_account_link` |
| `WorkRecordDaily` | `WorkRecordDailyMapper` | `t_work_record_daily` |
| `ContractPriceHistory` | `ContractPriceHistoryMapper` | `t_contract_price_history` |
| `ResumeIngestion` | `ResumeIngestionMapper` | `t_resume_ingestion` |
| `ProjectIngestion` | `ProjectIngestionMapper` | `t_project_ingestion` |
| `BpAvailability` | `BpAvailabilityMapper` | `t_bp_availability` |
| `BpAvailabilityIngestion` | `BpAvailabilityIngestionMapper` | `t_bp_availability_ingestion` |
| `BankDeposit` | `BankDepositMapper` | `t_bank_deposit` |
| `EngineerFollowup` | `EngineerFollowupMapper` | `t_engineer_followup` |

全Mapperの`BaseMapper`経由SQLも将来のinterceptor対象である。Entityに列を追加する実装は今回行わない。

### 3.2 annotation SQL

以下は`rg "@(Select|Update|Delete|Insert)" src/main/java/com/ses/mapper`で確認したannotation SQLの入口である。共有DBを再開する場合、各SQLへtenant条件を明示するか、安全な共通条件へ置換し、TEN-003で検証する。

| Mapper | 主なSQL / 行 | owner | 対応方法 | risk | 要件 / テスト |
|---|---|---|---|---|---|
| `SysUserMapper` | username検索、lock更新、重複確認 `:26,33,42-60,65-78` | 認証担当 | tenant解決後検索、platform経路分離 | 高 | R2/R3/R4, TEN-001/002/004 |
| `ContractMapper` | 期間、採番、更新lock、一覧join `:23-134` | 契約担当 | tenant/legal entity条件と採番範囲を再設計 | 高 | R1/R2, TEN-003/004 |
| `WorkRecordMapper` | lock、月次集計、請求集計 `:14-104` | 勤怠/請求担当 | contract経由条件と集計母集団を確認 | 高 | R2/R5, TEN-003/010 |
| `InvoiceMapper` | 採番、一覧、請求集計 `:16-91` | 請求担当 | legal entityとtenant条件を付加 | 高 | R1/R2/R5, TEN-003/004/010 |
| `BpPaymentMapper` | 支払集計、work record参照 `:15-74` | BP/請求担当 | tenantとlegal entityを同時検証 | 高 | R1/R2, TEN-003/004 |
| `NotificationMapper` | visibility、既読insert、role join `:15-52` | 通知担当 | recipientとmenu/roleのtenant境界 | 高 | R2/R5, TEN-007 |
| `EngineerSalesMapper` | engineer/sales join、current assignment `:22-74` | 営業/要員担当 | engineerとsys_userを同一tenantに限定 | 高 | R2, TEN-003/004 |
| `EngineerSkillMapper` | engineer/skill join `:15-32` | 要員担当 | parent tenantの存在を検証 | 中 | R2, TEN-003/004 |
| `EngineerMapper` | photo、lock、削除対象一覧 `:18-26` | 要員担当 | file scopeとtenant条件を統一 | 高 | R2/R3, TEN-003/006 |
| `EngineerAccountLinkMapper` | user/engineer link `:12-15` | 認証/要員担当 | cross-tenant link拒否 | 高 | R2/R3, TEN-002/004 |
| `ProjectMapper` | project/custom join `:20-56` | 案件担当 | customer/engineer tenant条件を確認 | 高 | R2, TEN-003/004 |
| `ProjectSkillMapper` | project/skill join `:13-18` | 案件担当 | parent tenant条件を確認 | 中 | R2, TEN-003 |
| `ProposalMapper` | join、lock、file path、script query `:17-60` | 営業担当 | fileとproposalのtenant scopeを統一 | 高 | R2/R3, TEN-003/006 |
| `QuotationMapper` | 採番、lock `:13,16` | 見積担当 | tenant単位採番、参照検証 | 高 | R2/R5, TEN-003/004 |
| `InvoiceItemMapper` | invoice/work record join `:14-31` | 請求担当 | parent tenant条件を確認 | 中 | R2, TEN-003/010 |
| `RoleMenuMapper` | role/menu keys `:22-28` | 権限担当 | global seedとtenant overrideを分類 | 中 | R2/R3, TEN-002/003 |
| `SystemConfigMapper` | config lock `:14` | 基盤担当 | global/tenant/legal entity keyを分離 | 高 | R1/R2, TEN-008 |
| `FreeeConnectionMapper` | latest connection `:10` | 外部連携担当 | tenant/legal entity単位へ再設計 | 高 | R1/R2, TEN-008 |
| `FreeeEmployeeLinkMapper` | delete、employee link `:11-14` | 外部連携担当 | tenant境界と冪等性を付加 | 高 | R2, TEN-008 |
| `ResumeIngestionMapper` | stored file一覧 `:19-20` | 文書/採用担当 | file scopeとtenantを同時検証 | 高 | R2/R3, TEN-006 |
| `ProjectIngestionMapper` | stored file一覧 `:16` | 案件担当 | file scopeとtenantを同時検証 | 高 | R2/R3, TEN-006 |
| `BpAvailabilityIngestionMapper` | stored file一覧 `:19-20` | BP担当 | file scopeとtenantを同時検証 | 高 | R2/R3, TEN-006 |

## 4. 自動生成SQLとcustom query

### 4.1 wrapper / service query

| 入口 | 確認箇所 | owner | 将来の対応 | risk | 要件 / テスト |
|---|---|---|---|---|---|
| Customer CRUD | `CustomerApiController.java:46-199` | 顧客業務担当 | list/detail/update/deleteすべてtenant scope | 高 | R2/R5, TEN-003/004 |
| Engineer CRUD | `EngineerApiController.java:30-96` | 要員業務担当 | customer/sales rep/fileを同一tenantで検証 | 高 | R2/R5, TEN-003/004/006 |
| Contract CRUD | `ContractApiController.java:41-86` | 契約担当 | proposal/engineer/customer/legal entity境界 | 高 | R1/R2, TEN-003/004 |
| Export | `ExportApiController.java:95-342` | Export担当 | scopeとbulk queryを同一母集団にする | 高 | R2/R5, TEN-003/006 |
| CSV | `CsvApiController.java:72-236` | Export担当 | `inSql`、`apply`、batch queryを個別検証 | 高 | R2/R5, TEN-003/006 |
| Notification generation | `NotificationGenerateService.java:90-260` | 通知担当 | dedupe key、recipient、日次生成をtenant scope | 高 | R2/R5, TEN-005/007 |
| Dashboard | `DashboardServiceImpl.java:60-70` | KPI担当 | cache keyと集計母集団にtenant | 高 | R2/R5, TEN-003/005 |
| Sales performance | `SalesPerformanceServiceImpl.java` | 営業分析担当 | contract/work record集計をtenant scope | 高 | R2/R5, TEN-003/010 |
| Invoice service | `InvoiceServiceImpl.java:194-625` | 請求担当 | invoice/payment/billingの一貫したscope | 高 | R1/R2/R5, TEN-003/004/010 |
| Rule matching | `RuleMatchingServiceImpl.java:65-212` | AI/要員担当 | candidate/engineer/project候補をtenant限定 | 中 | R2/R5, TEN-003 |
| File scope validation | `FileScopeValidationService.java:43-87` | 文書/セキュリティ担当 | unknown referenceをfail-closedへ変更 | 高 | R2/R3, TEN-006 |

`inSql`、`apply`、`last`を使う箇所はinterceptorだけに依存せずSQL単位で検証する。現行でunknown file referenceを許可するfallbackがあるため、共有DB再開時のTEN-006で明示的に扱う。

## 5. UNIQUE、FK、暗黙参照

| 現行制約・参照 | 根拠 | 将来の改造要求 | owner | risk | 要件 / テスト |
|---|---|---|---|---|---|
| `sys_user.username` unique | `V1__create_tables.sql` | `(tenant_id, username, deleted_flag相当)`を検討し、platform userを別境界にする | 認証/DB担当 | 高 | R2/R3, TEN-001/004 |
| skill、engineer-skill、project-skillのunique | V1 | tenant所属かglobal masterかを分類し、cross-tenant重複を許容/拒否 | マスタ/DB担当 | 中 | R1/R2, TEN-003/004 |
| contract、invoice、quotationの業務番号unique | V1、V18、V29 | 業務番号をtenantまたはlegal entity単位にするか決定 | 契約/請求/DB担当 | 高 | R1/R2, TEN-001/004 |
| work record、invoice item、BP paymentの複合unique | V5、V10 | tenant/legal entityを含めた複合制約へ再設計 | 請求/DB担当 | 高 | R1/R2/R5, TEN-004/010 |
| notification dedupe/read unique | V4 | dedupe keyとrecipientをtenant境界化 | 通知/DB担当 | 高 | R2/R5, TEN-007 |
| engineer-sales、candidate、contract documentのFK | V14、V16 | 親子が同一tenantであることをDBまたはserviceで拒否 | 業務/DB担当 | 高 | R2, TEN-004 |
| freee、employee link、bank depositのFK/外部ID | V21、V52 | external ID、legal entity、tenantをキーに含め冪等化 | 外部連携/DB担当 | 高 | R1/R2, TEN-008/009 |
| quotationのcustomer/project/engineer/proposal/created_by | V29 | 現行FKなしのため、参照存在とtenant一致をserviceで必須化 | 見積/DB担当 | 高 | R2/R5, TEN-003/004 |
| ingestion、mail delivery、audit、notification recipientの暗黙参照 | V43-V45、V28、V4 | tenant scopeを保存し、孤児・cross-tenant参照を拒否 | 基盤/各業務担当 | 高 | R2/R3, TEN-004/006/007 |

## 6. Scheduler、Async、Cache、ShedLock

| 対象 | 現行根拠 | 将来の対応 | owner | risk | 要件 / テスト |
|---|---|---|---|---|---|
| Scheduler | `SesManagerApplication.java:14-16`、`ContractPriceSyncService.java:32-34`、`ResumeRetentionCleanupServiceImpl.java:37-39`、`RenewalEscalationScheduler.java:20-22`、`NotificationScheduler.java:14-17`、`FileCleanupScheduler.java:20-23`、`ContractRenewalScheduler.java:19-22` | 共有DBではtenant一覧loopまたはtenant suffix。context欠落はfail-closed | 運用/基盤担当 | 高 | R2/R3, TEN-002/005 |
| Async | `AsyncConfig.java:20-37`、各Ingestion/Mail/Webhook serviceの`@Async` | TaskDecoratorでtenant/user/locale伝播、finally解除、contextなし拒否 | 基盤担当 | 高 | R2/R5, TEN-002/005 |
| Cache | `CacheConfig.java:31-75`、`DashboardServiceImpl.java:63-65`、`UtilizationForecastServiceImpl.java:59-63` | cache keyへtenant/legal entityを追加し、既存data scope/user keyと併用 | 基盤/KPI担当 | 高 | R2/R5, TEN-003/005 |
| ShedLock | `SchedulerLockConfig.java:24-35`、`V58__shedlock.sql:8-13` | tenant suffixまたは1ジョブの全tenant loopを選択。重複実行を検証 | 運用/DB担当 | 高 | R2/R3, TEN-005 |

現在は顧客ごとにDBが分かれるため、これらの共有DB対応を追加しない。既存のジョブ、Async、Cacheを削除・簡略化してはならない。

## 7. ファイル、Export、通知、Webhook

| 対象 | 現行根拠 | 将来の対応 | owner | risk | 要件 / テスト |
|---|---|---|---|---|---|
| Upload base path | `UploadProperties.java:12-24`、`application.yml:157-159` | 現在は顧客別DB/運用単位。共有DBでは`tenant/{tenantId}/{kind}/{uuid}`へ変更 | ファイル/運用担当 | 高 | R2/R3, TEN-006/009 |
| Generic download | `FileStorageServiceImpl.java:33-68,75-124`、`FileApiController.java:18-24,42-58` | DB参照付きfileのみ許可、unknown file fail-closed | ファイル/セキュリティ担当 | 高 | R2/R3, TEN-006 |
| Contract PDF / signed PDF | `ContractDocumentServiceImpl.java:30-31,66-84,174-202` | contract tenant/legal entityとpathを一致検証 | 文書/契約担当 | 高 | R1/R2, TEN-006/009 |
| Ingestion files | `V43`、`V44`、`V45`、各IngestionMapper | PIIを含むため保存key、download、retentionをtenant scope | 文書/運用担当 | 高 | R2/R3, TEN-006/009 |
| Export / CSV | `ExportApiController.java:95-342`、`CsvApiController.java:72-236` | 画面検索と同一scope、bulk上限、出力監査、tenant単位exportを将来追加 | Export/監査担当 | 高 | R2/R5, TEN-003/006/009 |
| Notification | `NotificationMapper.java:15-52`、`NotificationServiceImpl.java:137-150`、`NotificationGenerateService.java:196-260` | recipient、menu、dedupe、リンク先のtenant一致 | 通知担当 | 高 | R2/R5, TEN-005/007 |
| Webhook / Mail | `WebhookNotifier.java:34-61`、`MailServiceImpl.java:24-26,102-135` | endpoint、相関ID、recipient、retryをtenant/legal entity境界化 | 外部通知担当 | 高 | R2/R3/R5, TEN-007/008 |

## 8. 外部接続

| 接続 | 現行根拠 | 現在の扱い | 将来の対応 | owner | risk | 要件 / テスト |
|---|---|---|---|---|---|---|
| freee | `FreeeIntegrationServiceImpl.java:51-64,92-99,120-139,273-276`、`V21` | 接続はglobalのlatest一件を取得する構造 | tenant/legal entityごとの接続、external ID、冪等キー、相関ID、CSV fallbackを再設計 | 外部連携/会計担当 | 高 | R1/R2/R3, TEN-008/009 |
| CloudSign | `CloudSignClientImpl.java:16-32`、`AppConfig.java:37-46` | 共通RestTemplate、CloudSign client設定 | tenant/legal entity別token、文書scope、署名結果、retryを設計 | 電子契約担当 | 高 | R1/R2, TEN-006/008 |
| Webhook | `WebhookNotifier.java:34-61` | system config由来の外部通知 | tenant別endpoint、秘密情報、送信履歴、retry、相関IDを分離 | 外部通知担当 | 高 | R2/R3, TEN-007/008 |
| SMTP | `MailServiceImpl.java:24-26,102-135`、`application.yml:102-113` | 環境共通SMTP | sender、recipient、template、delivery結果をtenant scope化 | 通知担当 | 中 | R2/R3, TEN-007 |
| AI | `application.yml:173-180`、`AiConfig.java` | `mock` provider。実AI送信なし | G10承認前はPII送信禁止。実装時はtenant/legal entityとDPA境界を確認 | AI/セキュリティ担当 | 高 | R2/R3, TEN-008 |

## 9. Backup、Restore、PITR

| 対象 | 現行根拠 | 現在の扱い | 将来の対応 | owner | risk | 要件 / テスト |
|---|---|---|---|---|---|---|
| Full backup | `ops/backup/backup-full.sh:4-14` | mysqldump、uploads tar、resticを全体単位で実行 | 独立DBでは顧客DB単位を運用境界とする。共有DB移行後はtenant exportを追加 | 運用/DB担当 | 高 | R3/R4, TEN-009/010 |
| Restore | `ops/backup/restore.sh:3-10`、`restore-drill.sh` | 全体restore手順 | 共有DBではtenant単位restore、参照整合、秘密情報分離を追加 | 運用/DB担当 | 高 | R3/R4, TEN-009 |
| PITR/binlog | `archive-binlog.sh:3-5`、`snapshot-binlog.sh:3-6` | binlog archive/snapshot | 全体PITRを維持し、共有DB時はtenant境界の復元・reconciliation手順を別設計 | 運用/DB担当 | 高 | R3/R5, TEN-009/010 |
| Uploads | `ops/backup/backup-full.sh:4-14` | DBとuploads tarを別取得 | DB参照とfile pathの一致、復元後download scopeを検証 | ファイル/運用担当 | 高 | R2/R3, TEN-006/009 |

現在、tenant単位backup/restoreは実装しない。独立DB方式での顧客DB単位運用と全体PITRを混同せず、共有DB再開時の追加要件として扱う。

## 10. 現在明確に実施しない内容

今回のT001では次を実施しない。いずれも延期中であり、実装済みとは表示しない。

- V59作成、V1変更、H2 schema変更、Flyway実行。
- 全業務表への`tenant_id`追加、既存行backfill、複合UNIQUE/FK変更。
- `TenantContext`、`TenantResolver`、`TenantContextFilter`、MyBatis tenant interceptor。
- shared DB用のusername解決、host/subdomain解決、platform-admin経路。
- tenant単位Scheduler loop、Async TaskDecorator、tenant cache key、tenant ShedLock。
- tenant prefix file storage、tenant Export、tenant通知/Webhook、tenant単位backup/restore。
- cross-tenant integration test、移行reconciliation、MySQL migration smoke assert。

独立DBの顧客境界、既存の権限・データスコープ、ファイル参照検証、監査・通知の既存機能は維持する。共有DB方式を再開する場合は、TEN-001〜TEN-010を実装計画へ組み込み、requirements R1〜R5とのtraceを再確認する。

## 11. 環境とDemo記録

- V59: `src/main/resources/db/migration`に`V59*`は存在しない。V59は永久欠番として保持し、現行の最大migration番号はV58である。将来は当時のFlyway最新番号`latest + 1`から採番し、V59を追加してはならない。
- MySQL: `MySQL84` serviceはRunning、`localhost:3306`は接続可能である。
- Docker: CLIは存在するがDocker daemonへ接続できず、Testcontainers MySQL smokeは現環境では実行不可。CIまたはDocker daemon起動環境で実行する。
- T001 Demo: decision-logにG0決定、本文書に44表・Entity/Mapper・annotation SQL・custom query・制約・非HTTP経路・外部連携・backup/restoreを記録し、関連specの現行独立DB/延期範囲を同期した。
