package com.ses.order;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Acceptance;
import com.ses.entity.SalesOrder;
import com.ses.mapper.SalesOrderMapper;
import com.ses.service.AcceptanceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R09-P1-02定向テスト: 検収書原本（ACCEPTANCE）の文書台帳登録・download（R3.1）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@Sql(scripts = "/sql/engineer-schema-h2.sql")
class AcceptanceDocumentTest {

    @Autowired AcceptanceService acceptanceService;
    @Autowired JdbcTemplate jdbcTemplate;

    private long contractId;

    @BeforeEach
    void setUp() {
        // 文書台帳登録はcreated_byをSecurityUtils.currentUserId()で自動fillするため、
        // 認証コンテキストを設定する（未設定だとt_document_version.created_by NOT NULL違反）
        var principal = org.springframework.security.core.userdetails.User
                .withUsername("1").password("").authorities("ROLE_管理者").build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        principal, null, principal.getAuthorities()));
        String suffix = "-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", "AD顧客" + suffix);
        long customerId = jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, "AD顧客" + suffix);
        jdbcTemplate.update("INSERT INTO t_engineer (full_name, employment_type, status) VALUES (?, '正社員', 'Bench')", "AD要員" + suffix);
        long engineerId = jdbcTemplate.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, "AD要員" + suffix);
        jdbcTemplate.update("INSERT INTO t_project (project_name, customer_id, status) VALUES (?, ?, '募集中')", "AD案件" + suffix, customerId);
        long projectId = jdbcTemplate.queryForObject("SELECT id FROM t_project WHERE project_name = ?", Long.class, "AD案件" + suffix);
        jdbcTemplate.update(
                "INSERT INTO t_contract (contract_no, engineer_id, project_id, customer_id, start_date,"
                        + " selling_price, cost_price, status, acceptance_required)"
                        + " VALUES (?, ?, ?, ?, '2026-01-01', 600000, 300000, '稼動中', 1)",
                "AD-C-" + suffix, engineerId, projectId, customerId);
        contractId = jdbcTemplate.queryForObject("SELECT id FROM t_contract WHERE contract_no = ?", Long.class, "AD-C-" + suffix);
        jdbcTemplate.update(
                "INSERT INTO t_work_record (contract_id, work_month, actual_hours, billing_amount, status)"
                        + " VALUES (?, '2026-07', 160.00, 600000, '確定')",
                contractId);
    }

    private Acceptance accepted() {
        Acceptance submitted = acceptanceService.submit(contractId, "2026-07");
        return acceptanceService.accept(submitted.getId(), null);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile("file", "acceptance.pdf", "application/pdf",
                "fake-pdf-content".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("検収済acceptanceに検収書原本を登録するとdocument_idが設定され、downloadできる")
    void uploadAndDownloadAcceptanceDocument() throws Exception {
        Acceptance accepted = accepted();
        assertNull(accepted.getDocumentId());

        Acceptance withDoc = acceptanceService.uploadDocument(accepted.getId(), pdf());
        assertNotNull(withDoc.getDocumentId(), "document_idが設定される（R3.1）");

        Long docCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_document WHERE id = ? AND document_type = 'ACCEPTANCE'",
                Long.class, withDoc.getDocumentId());
        assertEquals(1L, docCount, "ACCEPTANCE文書が文書台帳へ登録される");
        Long linkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_document_link WHERE document_id = ? AND target_type = 'CONTRACT' AND target_id = ?",
                Long.class, withDoc.getDocumentId(), contractId);
        assertEquals(1L, linkCount, "検収書は契約のscope（CONTRACT）へリンクされる");

        try (InputStream in = acceptanceService.downloadDocument(withDoc.getId())) {
            assertNotNull(in);
            byte[] bytes = in.readAllBytes();
            assertEquals("fake-pdf-content", new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("検収書は1件まで（二重登録は拒否）")
    void duplicateAcceptanceDocumentRejected() {
        Acceptance withDoc = acceptanceService.uploadDocument(accepted().getId(), pdf());
        assertThrows(BusinessException.class,
                () -> acceptanceService.uploadDocument(withDoc.getId(), pdf()));
    }
}
