package com.ses.privacy.production.provider;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2 provider の H2 test 用の行作成（共有 testdb。呼出し側の test は {@code @Transactional} で rollback する）。
 *
 * <p>ID は既存の seed・他 test と衝突しない大きな値を明示し、tenant は {@code nf07-f2-} で始める。
 */
final class PrivacyProviderH2Fixture {

    static final String TENANT = "nf07-f2-a";
    static final String OTHER_TENANT = "nf07-f2-b";
    static final long BASE_ID = 970_000_000L;

    private final JdbcTemplate jdbc;

    PrivacyProviderH2Fixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long engineer(long id, String tenant, String fullName, String photoUrl, boolean deleted) {
        jdbc.update("INSERT INTO t_engineer (id, tenant_id, full_name, employment_type, phone, photo_url, remarks, "
                        + "expected_unit_price, deleted_flag) VALUES (?, ?, ?, '正社員', '090-0000-0000', ?, 'note', 650000, ?)",
                id, tenant, fullName, photoUrl, deleted ? 1 : 0);
        return id;
    }

    long candidate(long id, String tenant, String name, boolean deleted) {
        jdbc.update("INSERT INTO t_candidate (id, tenant_id, name, contact_email, current_stage, deleted_flag) "
                + "VALUES (?, ?, ?, 'c@example.invalid', '応募受付', ?)", id, tenant, name, deleted ? 1 : 0);
        return id;
    }

    long candidateActivity(long id, long candidateId, String reason) {
        jdbc.update("INSERT INTO t_candidate_activity (id, candidate_id, stage, reason, changed_by, remarks) "
                + "VALUES (?, ?, '書類選考', ?, NULL, 'activity')", id, candidateId, reason);
        return id;
    }

    long sysUser(long id, String tenant, String username, String password) {
        jdbc.update("INSERT INTO sys_user (id, tenant_id, username, password, real_name, role, email, status, "
                        + "failed_count, locked_until) VALUES (?, ?, ?, ?, 'NF07 user', '営業', 'u@example.invalid', 1, 3, "
                        + "CURRENT_TIMESTAMP)", id, tenant, username, password);
        return id;
    }

    long document(long id, String tenant, String type, boolean deleted) {
        jdbc.update("INSERT INTO t_document (id, tenant_id, document_type, title, direction, amount, "
                        + "counterparty_name_snapshot, deleted_flag) VALUES (?, ?, ?, 'doc', 'IN', 1000, 'third party', ?)",
                id, tenant, type, deleted ? 1 : 0);
        return id;
    }

    long version(long id, String tenant, long documentId, int versionNo, String storageKey, boolean deleted) {
        jdbc.update("INSERT INTO t_document_version (id, tenant_id, document_id, version_no, storage_key, original_name, "
                        + "sha256, source_type, business_key, version_discriminator, deleted_flag) "
                        + "VALUES (?, ?, ?, ?, ?, 'original.pdf', ?, 'NF07_TEST', ?, ?, ?)",
                id, tenant, documentId, versionNo, storageKey, "a".repeat(64), "nf07-f2:" + id, "v" + versionNo,
                deleted ? 1 : 0);
        return id;
    }

    void link(long id, String tenant, long documentId, String targetType, long targetId, boolean deleted) {
        jdbc.update("INSERT INTO t_document_link (id, tenant_id, document_id, target_type, target_id, deleted_flag) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, tenant, documentId, targetType, targetId, deleted ? 1 : 0);
    }

    long resume(long id, String tenant, String storedName, Long engineerId, Long candidateId, boolean deleted) {
        jdbc.update("INSERT INTO t_resume_ingestion (id, tenant_id, original_file_name, stored_file_name, file_ext, "
                        + "status, extracted_text, error_message, ai_provider, converted_engineer_id, candidate_id, "
                        + "deleted_flag) VALUES (?, ?, 'resume.pdf', ?, 'pdf', '確定済', 'resume text', 'error body', "
                        + "'mock', ?, ?, ?)",
                id, tenant, storedName, engineerId, candidateId, deleted ? 1 : 0);
        return id;
    }

    long bpAvailability(long id, String tenant) {
        jdbc.update("INSERT INTO t_bp_availability (id, tenant_id) VALUES (?, ?)", id, tenant);
        return id;
    }

    long bpIngestion(long id, String storedName, String fileExt, Long availabilityId, boolean deleted) {
        jdbc.update("INSERT INTO t_bp_availability_ingestion (id, original_file_name, stored_file_name, file_ext, "
                        + "status, extracted_text, error_message, converted_availability_id, deleted_flag) "
                        + "VALUES (?, 'bp.eml', ?, ?, '確定済', 'bp text', 'error body', ?, ?)",
                id, storedName, fileExt, availabilityId, deleted ? 1 : 0);
        return id;
    }

    long aiRun(long id, String tenant, String summaryJson, boolean deleted) {
        return aiRun(id, tenant, "MATCHING", summaryJson, null, deleted);
    }

    long aiRun(long id, String tenant, String useCase, String summaryJson, String parameterHash, boolean deleted) {
        long versionId = id;
        jdbc.update("INSERT INTO m_ai_artifact_version (id, use_case, provider, prompt_version, config_hash, status) "
                + "VALUES (?, 'NF07_TEST', 'mock', 'p1', ?, 'DRAFT')", versionId, "c".repeat(64));
        jdbc.update("INSERT INTO t_ai_recommendation_run (id, tenant_id, trace_id, use_case, artifact_version_id, "
                        + "actor_user_id, input_hash, parameter_hash, redacted_summary_json, status, cost_jpy, "
                        + "deleted_flag) VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, 'SUCCEEDED', 12, ?)",
                id, tenant, String.format("00000000-0000-0000-0000-%012d", id), useCase, versionId, "b".repeat(64),
                parameterHash, summaryJson, deleted ? 1 : 0);
        return id;
    }

    // ---------------------------------------------------------------- 文書の間接導出（契約・実績・BP支払・見積・資格・1on1）

    long customerProjectContract(long id, long engineerId) {
        jdbc.update("INSERT INTO m_customer (id, company_name) VALUES (?, 'NF07 customer')", id);
        jdbc.update("INSERT INTO t_project (id, project_name, customer_id) VALUES (?, 'NF07 project', ?)", id, id);
        jdbc.update("INSERT INTO t_contract (id, engineer_id, project_id, customer_id, start_date, selling_price, "
                + "cost_price) VALUES (?, ?, ?, ?, DATE '2026-04-01', 700000, 500000)", id, engineerId, id, id);
        return id;
    }

    long workRecord(long id, long contractId) {
        jdbc.update("INSERT INTO t_work_record (id, contract_id, work_month, actual_hours) VALUES (?, ?, '2026-04', 160.0)",
                id, contractId);
        return id;
    }

    long bpPayment(long id, long workRecordId) {
        jdbc.update("INSERT INTO t_bp_payment (id, work_record_id, amount) VALUES (?, ?, 400000)", id, workRecordId);
        return id;
    }

    long quotation(long id, long customerId, Long engineerId) {
        jdbc.update("INSERT INTO t_quotation (id, quotation_no, customer_id, title, unit_price, engineer_id) "
                + "VALUES (?, ?, ?, 'NF07 quotation', 700000, ?)", id, "NF07-Q-" + id, customerId, engineerId);
        return id;
    }

    long engineerCertification(long id, String tenant, long engineerId) {
        jdbc.update("INSERT INTO t_certification_continuity_group (tenant_id, engineer_id, certification_id, "
                + "continuity_group_id) VALUES (?, ?, ?, ?)", tenant, engineerId, id, id);
        jdbc.update("INSERT INTO t_engineer_certification (id, tenant_id, engineer_id, certification_id, "
                + "continuity_group_id, acquired_on) VALUES (?, ?, ?, ?, ?, DATE '2026-01-10')",
                id, tenant, engineerId, id, id);
        return id;
    }

    void privateNote(long id, long engineerId, long counterpartUserId, long documentId) {
        jdbc.update("INSERT INTO t_one_on_one_request (id, engineer_id, counterpart_user_id, candidate_dates_json, "
                        + "private_note_ref) VALUES (?, ?, ?, '[]', ?)", id, engineerId, counterpartUserId,
                Long.toString(documentId));
    }

    void aiItem(long id, String tenant, long runId, int rank, String targetType, long targetId) {
        jdbc.update("INSERT INTO t_ai_recommendation_item (id, tenant_id, run_id, rank_no, target_type, target_id) "
                + "VALUES (?, ?, ?, ?, ?, ?)", id, tenant, runId, rank, targetType, targetId);
    }

    // ---------------------------------------------------------------- 請求・電子請求書・注文（複数要員は和集合）

    long invoice(long id, long customerId) {
        jdbc.update("INSERT INTO t_invoice (id, invoice_no, customer_id, billing_month, subtotal, tax, total) "
                + "VALUES (?, ?, ?, '2026-04', 1000000, 100000, 1100000)", id, "NF07-INV-" + id, customerId);
        return id;
    }

    void invoiceItem(long id, long invoiceId, long workRecordId) {
        jdbc.update("INSERT INTO t_invoice_item (id, invoice_id, work_record_id, amount) VALUES (?, ?, ?, 500000)",
                id, invoiceId, workRecordId);
    }

    void digitalInvoice(long id, String tenant, long invoiceId, long xmlDocumentId) {
        jdbc.update("INSERT INTO t_digital_invoice (id, tenant_id, legal_entity_id, invoice_id, direction, profile, "
                        + "specification_version, message_id, xml_document_id, status, actor_type, confirmation_source) "
                        + "VALUES (?, ?, 1, ?, 'SEND', 'Standard', '1.0', ?, ?, 'QUEUED', 'SYSTEM', 'SCHEDULER_POLL')",
                id, tenant, invoiceId, "nf07-f2-msg-" + id, xmlDocumentId);
    }

    long salesOrder(long id, long customerId) {
        jdbc.update("INSERT INTO t_sales_order (id, order_no, customer_id, order_date) VALUES (?, ?, ?, DATE '2026-04-01')",
                id, "NF07-SO-" + id, customerId);
        return id;
    }

    void salesOrderLine(long id, long orderId, int lineNo, long engineerId) {
        jdbc.update("INSERT INTO t_sales_order_line (id, order_id, line_no, engineer_id, unit_price) "
                + "VALUES (?, ?, ?, ?, 700000)", id, orderId, lineNo, engineerId);
    }

    /** 書込み0の確認用（test 側は全列を読む）。 */
    List<Map<String, Object>> snapshot(String table, long fromId) {
        return jdbc.queryForList("SELECT * FROM " + table + " WHERE id >= ? ORDER BY id", fromId);
    }

    /** 返した列が allow-list と完全に一致する（allow-list 外の列を読まない）。 */
    static void assertOnlyAllowListed(List<PrivacyRecord> records, ProviderTable... tables) {
        for (PrivacyRecord record : records) {
            ProviderTable table = java.util.Arrays.stream(tables).filter(t -> t.table().equals(record.table()))
                    .findFirst().orElseThrow(() -> new AssertionError("想定外の表: " + record.table()));
            assertThat(record.fields().keySet()).as(record.table()).containsExactlyElementsOf(table.columns());
        }
    }

    /** 他 tenant・他 subject の値が混ざらない。 */
    static void assertTenant(List<PrivacyRecord> records, String tenant) {
        for (PrivacyRecord record : records) {
            if (record.fields().containsKey("tenant_id")) {
                assertThat(record.fields().get("tenant_id")).as(record.table()).isEqualTo(tenant);
            }
        }
    }

    static Set<Long> rowIds(List<PrivacyRecord> records, String table) {
        return records.stream().filter(r -> r.table().equals(table)).map(PrivacyRecord::rowId)
                .collect(java.util.stream.Collectors.toSet());
    }

    static void authenticateAdmin(String tenant) {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setRole("管理者");
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_管理者"));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new LoginUser(user, authorities, tenant), null, authorities));
    }
}
