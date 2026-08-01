# Review Ledger — CRM複数担当者・商機管理 (S08)

## T048: F1. contact/lead/opportunity DDLと移行

| 項目 | 内容 |
|------|------|
| **Task** | T048 / F1. contact/lead/opportunity DDLと移行 |
| **Requirements** | R1.1〜R1.4 (担当者管理), R2.1 (商機DDL), R3.1 (リードDDL) |
| **Base commit** | `5bdfb34` (main) |
| **Branch** | `feature/crm-contact-opportunity` |
| **Migration** | V73 (`V73__crm_contact_lead_opportunity.sql`) |

### 変更ファイル

| ファイル | 変更内容 |
|----------|----------|
| `src/main/resources/db/migration/V73__crm_contact_lead_opportunity.sql` | 新規: t_customer_contact, t_lead, t_opportunity DDL、t_sales_activity拡張、t_project/t_quotation source列、既存contact移行、CRMメニュー |
| `src/main/resources/db/migration/V1__create_tables.sql` | baseline同期: CRM3テーブルDROP/CREATE追加、t_project.source_opportunity_id追加 |
| `src/main/resources/db/migration/V6__create_sales_activity.sql` | baseline同期: contact_id/opportunity_id/assignee_user_id追加 |
| `src/test/resources/sql/schema-crm-h2.sql` | 新規: H2互換DDL (JSON→CLOB, IF NOT EXISTS) |
| `src/test/resources/application-test.yml` | schema-locations追加: schema-crm-h2.sql |
| `src/main/java/com/ses/entity/CustomerContact.java` | 新規entity |
| `src/main/java/com/ses/entity/Lead.java` | 新規entity |
| `src/main/java/com/ses/entity/Opportunity.java` | 新規entity |
| `src/main/java/com/ses/entity/SalesActivity.java` | contactId/opportunityId/assigneeUserId追加 |
| `src/main/java/com/ses/entity/Project.java` | sourceOpportunityId追加 |
| `src/main/java/com/ses/entity/Quotation.java` | sourceOpportunityId追加 |
| `src/main/java/com/ses/mapper/CustomerContactMapper.java` | 新規mapper |
| `src/main/java/com/ses/mapper/LeadMapper.java` | 新規mapper |
| `src/main/java/com/ses/mapper/OpportunityMapper.java` | 新規mapper |
| `src/test/java/com/ses/crm/CustomerContactSchemaTest.java` | 新規定向テスト (11 test cases) |
| `.kiro/specs/crm-contact-opportunity/tasks.md` | F1チェック |

### テスト

| レベル | 内容 | 結果 |
|--------|------|------|
| L1 | H2 schema replay: 3テーブルCRUD | 構造検証済み（sandbox mvn不可、CI実行待ち） |
| L2 | primary一意(service CAS設計)、0件許容、期間重複検出/非検出、退職者除外 | テストコード作成済み |
| L2 | lead初期状態、opportunity初期状態 | テストコード作成済み |
| L3 | git diff --check | whitespace問題なし |

### Demo

- 既存顧客のcontact_person/email/phoneがV73移行でt_customer_contactの初回レコード(primary=1)として生成される
- 移行前後で担当者名/emailが一致（INSERT SELECT構文で直接マッピング）

### リスク・未検証事項

| # | 事項 | 理由 | 検証予定 |
|---|------|------|----------|
| 1 | mvn test実行 | sandbox INTEGRATIONS_ONLY制限 | CI (push後GitHub Actions) |
| 2 | Docker MySQL smoke (Testcontainers) | 同上 | CI |
| 3 | 移行の既存データ件数一致 | 実データ不在 | staging環境 |
| 4 | PII export mask | T050で画面/export実装時に検証 | T050 |

### Rollback

- V73を適用済みの場合: `flyway repair` 後に V73 を削除し、t_customer_contact/t_lead/t_opportunity を手動DROP、ALTER TABLE t_sales_activity DROP COLUMN contact_id/opportunity_id/assignee_user_id、ALTER TABLE t_project/t_quotation DROP COLUMN source_opportunity_id
- entity/mapper/testは削除のみで影響なし（他から参照されていない）
