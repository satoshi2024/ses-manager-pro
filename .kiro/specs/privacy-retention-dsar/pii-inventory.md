# PII inventory（read-only discovery）

更新日: 2026-08-27（Asia/Tokyo）  
対象base: `origin/main@0333b0a4afadef42639bad27e1ae443758f9804f`  
承認入力: `<APPROVED_SCOPE>` / `<OWNER>` / `<BASE_COMMIT>` / `<BASE_BRANCH>`（すべて未置換）

## 読み方

`CONFIRMED_TECHNICAL` はschema/code/configに存在することだけを示す。法定保存年数、privacy owner、purposeの法的根拠、DSARの応答期限が未承認の場合は `PROVISIONAL` または `UNKNOWN` とし、dry-runの候補から除外する。値そのもの、氏名、email、電話、住所、resume本文、token、secret、raw promptはこのファイルに記録しない。

owner欄は実装上の責務候補とDG-07の未決定を分ける。`機能owner候補` は法的なPrivacy ownerの確定を意味しない。

## 1. DB table/column inventory

| ID / storage | PII・機微要素（column） | 機能owner候補 / purpose | trigger / retentionの現状 | hold / disposition / DSAR provider | policy / 根拠 |
|---|---|---|---|---|---|
| DB-001 `sys_user` | `username`, `real_name`, `email`, `password`、`failed_count`, `locked_until` | Identity/管理者候補。login、権限、通知、actor識別 | account lifecycle。期間未承認 | security/audit hold。passwordは復元・export不可、他はaccount closure policy未定。`InternalUserProvider` 未実装 | UNKNOWN。V1、enterprise-identity-security |
| DB-002 `m_customer` | `contact_person`, `contact_email`, `contact_phone`, `address`, `remarks` | Customer/営業候補。顧客連絡、契約/請求 | customer lifecycle。契約/請求linkがある間はactive business blocker候補 | 契約・請求・auditとのlinkを先に評価。`CustomerProvider` 未実装 | UNKNOWN。V1、platform scope |
| DB-003 `t_customer_contact` / `t_lead` | contact `name/email/phone`、lead `company_name/contact_*`、自由記述/normalized contact keys | CRM/営業候補。contact・lead管理、検索key | CRM lifecycle、source cost。retention未確定 | scope外customer/leadをredact。`CrmProvider` 未実装 | UNKNOWN。V73/V74_2、auditのCRM boundary |
| DB-004 `t_engineer` | `full_name`, `full_name_kana`, `initial_name`, `gender`, `birth_date`, `nationality`, `nearest_station`, `phone`, `resume_summary`, `photo_url`, `remarks` | HR/Engineer候補。要員・提案・self-service | active/退職、契約/給与/勤怠linkをtriggerにする必要あり。期間未承認 | active contract、payroll、audit、legal documentがblocker候補。`EngineerProvider` 未実装 | UNKNOWN。V1、enterprise identity/AI allow-list |
| DB-005 `t_engineer_career`, `t_engineer_skill`, `m_skill_tag` | career `project_name/client_industry/description/tech_stack`、skill relation | HR/Engineer候補。経歴・matching | engineer lifecycle。retention未確定 | skill sheet/document link、AI payload二次コピーを評価。`EngineerCareerProvider` 未実装 | UNKNOWN。V1、recruiting/AI |
| DB-006 `t_project`, `t_project_position` | `project_name`, `description`, `work_location`, `remarks`, `skills_json`, `location`、creator link | Project/営業候補。案件/募集枠 | project close、contract/quotation linkがtrigger候補。期間未承認 | active proposal/contract/customer linkがblocker候補。`ProjectProvider` 未実装 | UNKNOWN。V1/V103、platform scope |
| DB-007 `t_proposal`, `t_proposal_history` | `skill_sheet_path`, `proposal_email_text`, `match_reason`, `remarks`, history `remarks`、engineer/project/actor link | Proposal/営業候補。提案、選考、メール原稿 | proposal closed/contract convertedがtrigger候補。法定文書化された場合は文書policyに従う | `skill_sheet_path` はfile providerとdocument linkを両方評価。`ProposalProvider` 未実装 | PROVISIONAL/UNKNOWN。V1、legal-document-ledger-archive S04-NOTE-1 |
| DB-008 `t_candidate`, `t_candidate_activity` | `name`, `contact_email`, `contact_phone`, `skill_summary`, `remarks`, activity `reason/remarks` | Recruiting/HR候補。応募、選考、ステージ履歴 | retention期間は未確定。rejected candidateもsoft-deleteのみ | 採用/法務/HR責任者の決定までUNKNOWN。`CandidateProvider` 未実装 | UNKNOWN。recruiting-pipeline設計の明記 |
| DB-009 `t_resume_ingestion` | `original_file_name`, `stored_file_name`, `extracted_text`, `parsed_json`, `review_note`, `error_message`, `candidate_id/converted_engineer_id` | Recruiting/HR候補。resume取込、レビュー、engineer変換 | `app.resume.retention-days=30` は現行cleanup技術設定。確定/却下と`updated_at`基準だが、法的policyではない | 現行jobは却下rowのlogic delete、確定rowの`extracted_text` clear。原本fileのprovider参照が別にある。`ResumeProvider` 未実装 | PROVISIONAL。V43、recruiting retention未確定、audit round8 |
| DB-010 `t_bp_availability`, `t_bp_availability_ingestion` | initial/skills/company/remarks、原本名/保存名、`extracted_text`, `parsed_json`, `review_note` | BP/HR候補。外部要員在庫・availability email取込 | resume retention設定を共有する技術実装。BP/外部人材の法的保持は未承認 | 現行cleanupは却下jobをlogic delete、extracted textをclear。`BpAvailabilityProvider` 未実装 | PROVISIONAL/UNKNOWN。V45、platform file rules |
| DB-011 `m_bp_company`, `t_bp_contact` | company `address/representative`、contact `name/email/phone`、corporate/invoice identifiers | BP/営業候補。協力会社・担当者管理 | BP lifecycle、契約/支払/税務linkがtrigger候補 | bank/payment/document/auditとのlinkをhold。`BpProvider` 未実装 | UNKNOWN。V70、database-backup-recovery |
| DB-012 `t_bp_bank_account`, `t_freee_connection` | bank `encrypted_account_number`, `account_holder`、freee `access_token_encrypted`, `refresh_token_encrypted` | Finance/Payroll候補。支払・会計連携 | account revoke/connection lifecycle。法定・security retention未確定 | secretはDSAR export/redaction不可、revoke/backup/secret-manager確認が必須。`FinanceProvider` 未実装 | UNKNOWN。V21/V70、enterprise-identity-security |
| DB-013 `t_contract`, `t_work_record`, `t_work_record_daily` | job/work details、`work_location`, `payment_method`, `remarks`、日次時刻/勤怠 | Contract/Payroll/HR候補。契約、稼働、請求、給与 | 契約終了/締め/支払・法定文書のtrigger候補。具体的policyは文書種別ごとに未統合 | active business、法定保存、invoice/payment、auditがblocker候補。`ContractWorkProvider` 未実装 | PROVISIONAL/UNKNOWN。V1/V32、legal-document-ledger-archive |
| DB-014 `t_invoice`, `t_invoice_item`, `t_bp_payment`, `t_bank_deposit` | invoice item description（要員/案件名）、payee company、remarks、金融/入金reference | Billing/Finance候補。請求・支払・消込 | document/archive種別の法定保存が関係。DSARだけで削除不可 | 法定文書、tax/audit、active receivableがblocker。`AccountingProvider` 未実装 | PROVISIONAL。V5/V28/V52、database-backup-recovery |
| DB-015 `t_mail_delivery`, `m_email_template` | `recipient`, `subject`, `body`, `error_message`、template body | Notification/Mail候補。提案/通知送信 | queue/sent/failedの保持期間未定。本文は第三者混在を想定 | sent mail/audit/legal holdを先に評価し、第三者redact。`MailProvider` 未実装 | UNKNOWN。V26、audit MI-03 |
| DB-016 `t_notification`, `t_notification_read`, `t_task` | `title`, `message`, `link_url`, dedupe、task title/description | Notification/Task候補。業務通知・ToDo | read/close lifecycle。保持未確定 | recipient/scopeとauditを分離。`NotificationProvider` 未実装 | UNKNOWN。V4/V68、platform scope |
| DB-017 `t_document`, `t_document_version`, `t_document_link` | title/counterparty snapshot、`original_name`, `storage_key`, `external_id`, `change_reason`、linked target IDs | Legal document/Compliance候補。原本、版、業務link | `m_document_type.retention_years` と `retention_until` は技術上存在。`NULL`は未確定で候補外 | `legal_hold_flag=1`、retention未確定、original/hash/version整合、backupがblocker。`DocumentProvider` 未実装 | PROVISIONAL。V67、legal-document-ledger-archive |
| DB-018 `t_document_access_log`, `t_document_disposal_request`, `t_document_delivery` | `user_id`, `ip_hash`, action、disposal reason/approver、delivery snapshot/hash | Legal document/Audit候補。閲覧、廃棄申請、帳票delivery | append-only/access/disposal evidenceの期間未確定 | audit/legal originalは無条件削除不可。`AuditDocumentProvider` 未実装 | UNKNOWN/PROVISIONAL。V67/V84、NF-07 review |
| DB-019 `t_audit_log` | `username`, `uri`, method/status、application/success、timestamp | Security/Audit候補。API操作証跡 | append-only。専用法定/監査保持値未承認 | immutable auditとしてBLOCKED候補。`AuditProvider` 未実装 | UNKNOWN。V11/V25/ApiAuditFilter、audit specs |
| DB-020 `t_portal_user`, `t_portal_invitation`, `t_portal_terms_consent` | email/display name、invitation email/token hash、terms/IP hash | Portal/Identity候補。外部identity、招待、同意 | session/invitation/terms lifecycle。保持未確定 | external user identity、同意証跡、token hashを分離。`PortalProvider` 未実装 | UNKNOWN。V104 |
| DB-021 `t_portal_session`, `t_portal_access_log` | token hash、email、user agent、IP hash、target/action | Portal/Security候補。session/access監査 | session expiry/revoke、access audit。法的保持未定 | portal access audit/active incidentがblocker。`PortalAuditProvider` 未実装 | UNKNOWN。V104_1/V104_3、enterprise identity |
| DB-022 `t_user_external_identity`, `t_user_mfa`, `t_mfa_recovery_code` | OIDC `subject/email_snapshot`、encrypted TOTP、recovery hash | Identity/Security候補。login/MFA/link | account closure/revoke。法的保持未定 | secret/hashはexport不可、security incident/audit hold。`IdentityProvider` 未実装 | UNKNOWN。V63、enterprise-identity-security |
| DB-023 `t_user_session`, `t_file_security_metadata`, `t_break_glass_incident`, `t_mfa_attempt_guard` | session/IP/source hash、user agent、file owner/status/rejection、incident/correlation/reason | Security/File/SRE候補。session、malware scan、break-glass証跡 | expiry/revoke/incident close。retention未確定 | security/audit evidenceをhold。unknown scan/referenceはfail-closed。`SecurityEvidenceProvider` 未実装 | UNKNOWN。V63/V65、enterprise identity |
| DB-024 `t_one_on_one_request`, `t_survey_response` | employee note/private note ref、回答comment、confidential visibility | HR/Employee experience候補。相談、survey | case/campaign close。private/confidential retention未確定 | confidential note、健康/相談推知情報、同意を本人scopeから分離。`EmployeeSensitiveProvider` 未実装 | UNKNOWN。V105、enterprise identity |
| DB-025 `t_expense_request`, `t_expense_accounting_job` | expense purpose/remarks、receipt link、payload hash/error/correlation | Payroll/Accounting候補。経費申請・連携 | monthly closing/payment/accounting retention候補 | receipt/legal document、monthly closing、external job/auditがblocker。`ExpenseProvider` 未実装 | UNKNOWN。V105、monthly closing rules |
| DB-026 `t_compliance_*`, `t_employee_attendance`, `t_leave_*`, `t_overtime_*` | worker snapshot、person/contact fields、complaint/training/career notes、attendance/leave/overtime | Compliance/HR候補。派遣法、勤怠、休暇、時間外 | law/contract/dispatch close trigger候補。保持未確定 | compliance ledger、legal document、audit/active caseがblocker。`ComplianceProvider` 未実装 | UNKNOWN。V83/V84/V102、post-acceptance unresolved |
| DB-027 `t_lifecycle_*`, `t_approval_*`, `t_integration_*`, `t_webhook/outbox` | task/evidence/comment、approval payload、external mapping/event payload | Lifecycle/Approval/Integration候補。workflow、外部連携 | state close/retention未確定 | approval/audit/correlation、failed retry、external copyをhold。provider未登録 | UNKNOWN。V75/V79/V106/V109 |
| DB-028 `t_ai_log`（legacy） | `request_params`, `response_text`, request type/actor | AI/Platform候補。旧AI call log | `app.resume.retention-days=30` という技術設定・legacy raw。新規raw保存停止方針 | raw PII/third-party/legacy不明はUNKNOWN。`AiLegacyProvider` 未実装、外部sendしない | PROVISIONAL。V1、G10 allowlist/review ledger |
| DB-029 `m_ai_artifact_version`, `t_ai_recommendation_run/item/feedback/outcome/evaluation` | redacted summary、explanation/value/metrics、actor/target IDs | AI/Platform候補。再現性・推薦・評価 | redacted 730日、raw prompt 0日、metrics 90日等はG10 technical policy | allow-list外、legacy二重記録、target scope不明はblocked/unknown。`AiRunProvider` 未実装 | PROVISIONAL。V108、g10 allowlist |
| DB-030 `m_integration_connection` 等 | encrypted secret ref/token reference、external IDs、payload/event hashes | Integration/Finance候補。freee/外部会計 | revoke/connection lifecycle。retention未確定 | secret manager、external provider revoke、auditがblocker。`IntegrationProvider` 未実装 | UNKNOWN。V106、database-backup-recovery |

## 2. file/object inventory

| ID / provider | object / 参照元 | PII・第三者情報 | trigger / retentionの現状 | hold / disposition / DSAR provider | 根拠 |
|---|---|---|---|---|---|
| FILE-001 `ResumeIngestionFileReferenceProvider` | `t_resume_ingestion.stored_file_name`（upload base） | resume原本、元ファイル名 | `ResumeRetentionCleanupServiceImpl` はextracted textと却下jobを別処理。原本保持は30日設定だけでは確定しない | candidate/engineer/third-party混在、document link/backupはblocker。`ResumeFileProvider` 未実装 | V43、FileReferenceProvider |
| FILE-002 `EngineerFileReferenceProvider` | `t_engineer.photo_url` | 顔写真/biometric-like image | engineer lifecycle。retention未確定 | active contract、本人確認、file scope、backupがblocker。`EngineerPhotoProvider` 未実装 | V1、FileScopeValidationService |
| FILE-003 `ProposalFileReferenceProvider` | `t_proposal.skill_sheet_path` | skill sheet、経歴・連絡先・第三者情報の可能性 | proposal/document typeで技術的保持が分かれる。unclassified skillsheet mappingはprovisional | client submission/legal document、third-party redaction、hash/versionがblocker。`ProposalFileProvider` 未実装 | V1/V67、legal-document-ledger-archive S04-NOTE-1 |
| FILE-004 `ProjectIngestionFileReferenceProvider` | `t_project_ingestion.stored_file_name` | EML原本、sender/recipient、本文・添付 | project email retention未確定（unclassified） | legal hold、mail participant third-party、document archive linkがblocker。`ProjectEmailFileProvider` 未実装 | V44、legal-document-ledger-archive |
| FILE-005 `BpAvailabilityFileReferenceProvider` | `t_bp_availability_ingestion.stored_file_name` | BP availability email/attachment、個人・会社情報 | resume技術設定を共有するが法的保持未確定 | external worker/third-party、BP contract/payment、backupがblocker。`BpAvailabilityFileProvider` 未実装 | V45、recruiting/backup |
| FILE-006 `DocumentArchiveFileReferenceProvider` | `t_document_version.storage_key` + basename | legal original/version PDF、契約・請求・receipt等 | document type policy/retention_until。NULLはunknown | legal hold、hash/version、backup same-time、disposal approvalが必須。`DocumentFileProvider` 未実装 | V67、legal-document-ledger-archive |
| FILE-007 `FileSecurityMetadataReferenceProvider` | `t_file_security_metadata.stored_name`（quarantine/published/rejected） | scan対象file、拒否理由、owner link | scan state/cleanup safety window。retention未確定 | scan未完/unknown referenceはfail-closed。`SecurityFileProvider` 未実装 | V63、FileScopeValidationService |
| FILE-008 upload dirs / backup / replica | upload base、`quarantine`、`published`、DB/upload backup、restore target、read replica | DBに登録されない孤児、binary二次コピー、同時点snapshot | `FileCleanupServiceImpl` の孤児cleanupは既存物理削除経路。backup retention/restore state未確定 | backup/replica/unknown referenceは必ずhold/unknown。`BackupBinaryProvider` 未実装 | database-backup-recovery、platform invariants |

## 3. AI payload inventory

正本は `.kiro/specs/ai-feedback-learning/g10-allowlist.json`。この表の「許可」は外部送信の法的許可ではなく、G10が定義するpayload schema上のallow-listである。`ai.external-send-enabled=false`、provider=`mock` の現状を維持する。

| ID / payload | allow-list / prohibited field | retention / owner / purpose | hold / DSAR provider | policy / 根拠 |
|---|---|---|---|---|
| AI-001 `MATCHING`/`PROPOSAL_DRAFT` canonical input | 許可: skill、experience、unit price、availability、status、employment type、location grain、role/category等。`initialName`はsend-only | matching/proposal recommendation。raw promptは0日、redacted summary technical 730日 | actor/target scope、第三者混入、AI run/evaluation hold。`AiPayloadProvider` 未実装 | PROVISIONAL。G10 allowlist JSON |
| AI-002 `CHAT` input | 本文を自由連結しない。氏名/fullName、phone、photo、resumeSummary、remarks、career description、customer contact、bank、address、raw promptは禁止 | chatはmock/ruleのみ。実provider DPA/region/training opt-out/owner gate未完 | out-of-scope ID、prompt injection、raw log二重保存はblocked。外部providerを呼ばない | PROVISIONAL/BLOCKED external。G10、MI-04/05/06 |
| AI-003 `t_ai_recommendation_run.redacted_summary_json` | mask済summaryのみ。raw prompt/PII canary不可。`input_hash`は再現用 | redacted 730日、metrics 90日（G10 technical setting） | DSAR exportはsummaryも第三者redaction後。`AiRunProvider` 未実装 | PROVISIONAL。V108、G10 |
| AI-004 legacy `t_ai_log.request_params/response_text` | legacy raw/二重記録の可能性。新規保存停止、raw promptを復活させない | legacy `app.resume.retention-days=30` 技術設定。法的保持未承認 | legacy不明、audit/evaluation dependencyはUNKNOWN。`AiLegacyProvider` 未実装 | PROVISIONAL。V1、G10 review ledger R2-P2-04 |
| AI-005 provider boundary | `mock`/`rule` local only。Gemini等real providerはDPA、region、training opt-out、security/HR/product owner gate後のみ | `external-send-enabled=false` がkill switch | scope外provider呼出しはBLOCKED。providerにDSAR subjectを送らない | BLOCKED external。GATE-S17-G10-PROD |

## 4. audit / retention unresolved matters

- 法定文書specの未確定: proposal skill sheet、candidate resume、project email originalの分類/保持はcompliance ownerと外部専門家の承認待ち。candidate resumeは1年 provisional、proposal skill sheet/project emailは3年 provisionalという既存資料上の案を、承認済みpolicyと扱わない。
- recruiting-pipelineは candidate PIIのretentionを未確定としている。rejected candidate、activity、converted engineerへのlink、resume ingestionの原文/抽出text/parsed JSONを分離して決定する必要がある。
- auditは削除対象ではなく、保持期間・immutable性・DSAR export上の見せ方を外部/社内責任者が決める。`ApiAuditFilter` はURI/actor/statusを記録するが、業務payload全体を記録する仕組みではないため、これだけで全PIIの所在を証明しない。
- backup/recoveryはproduction DB、upload、replica、restore target、credential、測定済みRPO/RTOが未完了で、DSAR処分の復元後再検証gateを満たさない。
- enterprise identityはOIDC tenant/app、MFA、break-glass、90日exercise、責任者等の外部gateが未完了。MFA secret/tokenはDSAR export・処分の通常経路から除外する。
- AIはG10 allow-listを技術境界とし、real providerへの送信、DPA、region、training opt-out、owner gateが未完了。AI payloadの二次コピーはunknownとして残す。

## 5. scope外・未発見の扱い

このinventoryはschema/entity/provider/config/searchで確認できた静的所在であり、本番DB、object storage、mailbox、backup、replica、外部SaaSの実データを検索していない。未登録のfile/object、migration後の運用artifact、ログ基盤、APM、メール受信箱、ブラウザ/download cache、開発者端末は `UNKNOWN` のまま、providerを呼び出さず、人の範囲承認後に再inventoryする。
