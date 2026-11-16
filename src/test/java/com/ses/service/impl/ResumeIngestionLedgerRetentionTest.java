package com.ses.service.impl;

import com.ses.service.document.DocumentEligibilityService;
import com.ses.service.document.ResumeIngestionLedgerRetentionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 履歴・スキルシート取込の原本 purge に合わせて、文書台帳の副本（RESUME_INGESTION）を処分対象にする（2026-11-16 依頼者決定）。
 *
 * <p>原本の purge 後、副本は CONFIRMED・保存期限＝当日になり、既存の廃棄候補作成（{@code DocumentRetentionJob} と同じ
 * {@code registerRetentionDisposalCandidate}）が受け付ける。legal hold 中の副本は変えない。他 tenant の副本に触れない。
 * 再実行しても重ねて変更せず、廃棄候補も重複しない。hold の解除後の実行で拾い直す。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ResumeIngestionLedgerRetentionTest {

    private static final String TENANT = "nf07-rr-a";
    private static final String OTHER_TENANT = "nf07-rr-b";
    private static final long BASE = 971_000_000L;
    private static final LocalDateTime OLD = LocalDateTime.of(2020, 1, 1, 0, 0);

    @Autowired
    private ResumeRetentionCleanupServiceImpl cleanup;
    @Autowired
    private ResumeIngestionLedgerRetentionService ledgerRetention;
    @Autowired
    private DocumentEligibilityService documentEligibilityService;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 原本のpurge後に副本は保存期限が当日になり既存の廃棄候補作成が受け付ける() {
        long copy = resumeWithCopy(1, TENANT, OLD, "DRAFT", false);
        LocalDate today = LocalDate.now();

        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));

        assertThat(jdbc.queryForObject("SELECT stored_file_name FROM t_resume_ingestion WHERE id = ?", String.class,
                BASE + 1)).as("原本は purge 済み").isNull();
        Map<String, Object> document = document(copy);
        assertThat(document.get("STATUS")).isEqualTo("CONFIRMED");
        assertThat(retentionUntil(copy)).isEqualTo(today);
        assertThat(documentEligibilityService.registerRetentionDisposalCandidate(TENANT, copy, today))
                .as("DocumentRetentionJob と同じ経路で廃棄候補になる").isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ? "
                + "AND status = 'PENDING' AND requested_by = -1", Long.class, copy)).isEqualTo(1L);
    }

    @Test
    void legal_hold中の副本と他tenantの副本と未期限の原本の副本は変えない() {
        long held = resumeWithCopy(11, TENANT, OLD, "DRAFT", true);
        long other = resumeWithCopy(12, OTHER_TENANT, OLD, "DRAFT", false);
        long notExpired = resumeWithCopy(13, TENANT, LocalDateTime.now(), "DRAFT", false);

        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));

        assertThat(document(held).get("STATUS")).isEqualTo("DRAFT");
        assertThat(retentionUntil(held)).isNull();
        assertThat(document(other).get("STATUS")).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT stored_file_name FROM t_resume_ingestion WHERE id = ?", String.class,
                BASE + 12)).as("他 tenant の原本は purge しない").isNotNull();
        assertThat(document(notExpired).get("STATUS")).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT stored_file_name FROM t_resume_ingestion WHERE id = ?", String.class,
                BASE + 13)).as("期限前の原本は purge しない").isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id IN (?, ?, ?)",
                Long.class, held, other, notExpired)).isZero();
    }

    @Test
    void 再実行しても重ねて変えず廃棄候補も重複せずhold解除後の実行で拾い直す() {
        long copy = resumeWithCopy(21, TENANT, OLD, "DRAFT", false);
        long confirmedWithoutRetention = resumeWithCopy(22, TENANT, OLD, "CONFIRMED", false);
        long held = resumeWithCopy(23, TENANT, OLD, "DRAFT", true);
        long cancelled = resumeWithCopy(24, TENANT, OLD, "CANCELLED", false);
        LocalDate today = LocalDate.now();

        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));
        assertThat(retentionUntil(confirmedWithoutRetention)).as("確定済みで保存期限が無い副本も当日に合わせる")
                .isEqualTo(today);
        assertThat(document(cancelled).get("STATUS")).as("取消済みの副本は状態を変えず保存期限だけを合わせる")
                .isEqualTo("CANCELLED");
        assertThat(retentionUntil(cancelled)).isEqualTo(today);
        assertThat(documentEligibilityService.registerRetentionDisposalCandidate(TENANT, copy, today)).isTrue();
        Object version = document(copy).get("VERSION");
        Object confirmedVersion = document(confirmedWithoutRetention).get("VERSION");

        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));
        assertThat(ledgerRetention.alignPurgedCopies(TENANT, today).aligned()).as("合わせ済みの副本は照会に出ない")
                .isZero();
        assertThat(document(copy).get("VERSION")).isEqualTo(version);
        assertThat(document(confirmedWithoutRetention).get("VERSION")).isEqualTo(confirmedVersion);
        assertThat(documentEligibilityService.registerRetentionDisposalCandidate(TENANT, copy, today))
                .as("廃棄候補は重複しない").isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ?",
                Long.class, copy)).isEqualTo(1L);
        assertThat(document(held).get("STATUS")).isEqualTo("DRAFT");

        jdbc.update("UPDATE t_document SET legal_hold_flag = 0 WHERE id = ?", held);
        cleanup.cleanupTenant(TENANT, LocalDateTime.now().minusDays(30));
        assertThat(document(held).get("STATUS")).as("hold 解除後の実行で拾い直す").isEqualTo("CONFIRMED");
        assertThat(retentionUntil(held)).isEqualTo(today);
    }

    /** 原本を持つ履歴取込ジョブと、文書台帳の副本（link 付き）を作る。 */
    private long resumeWithCopy(int offset, String tenant, LocalDateTime updatedAt, String documentStatus, boolean held) {
        long jobId = BASE + offset;
        long documentId = BASE + 100 + offset;
        jdbc.update("INSERT INTO t_resume_ingestion (id, tenant_id, original_file_name, stored_file_name, file_ext, "
                        + "status, extracted_text, version, created_at, updated_at, deleted_flag) "
                        + "VALUES (?, ?, 'resume.pdf', ?, 'pdf', '確定済', 'text', 0, ?, ?, 0)",
                jobId, tenant, "nf07-rr-" + jobId + ".pdf", updatedAt, updatedAt);
        jdbc.update("INSERT INTO t_document (id, tenant_id, document_type, title, direction, status, legal_hold_flag, "
                        + "version, deleted_flag) VALUES (?, ?, 'RESUME_INGESTION', 'resume.pdf', 'INCOMING', ?, ?, 1, 0)",
                documentId, tenant, documentStatus, held ? 1 : 0);
        jdbc.update("INSERT INTO t_document_link (id, tenant_id, document_id, target_type, target_id, deleted_flag) "
                + "VALUES (?, ?, ?, 'RESUME_INGESTION', ?, 0)", documentId, tenant, documentId, jobId);
        return documentId;
    }

    private Map<String, Object> document(long id) {
        return jdbc.queryForMap("SELECT status, version FROM t_document WHERE id = ?", id);
    }

    private LocalDate retentionUntil(long id) {
        return jdbc.queryForObject("SELECT retention_until FROM t_document WHERE id = ?", LocalDate.class, id);
    }
}
