package com.ses.service.changerequest.impl;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.dto.document.DocumentRegisterRequest;
import com.ses.entity.Document;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.DocumentService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.changerequest.EngineerChangeRequestService;
import com.ses.service.changerequest.EngineerChangeRequestService.SkillSheetConfirmResult;
import com.ses.service.changerequest.EngineerChangeRequestService.SkillSheetPreview;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RB-PRERUN 1: 要員本人のスキルシート確定（{@code POST /api/my/profile/skill-sheet/confirm}）が初回だけ404になる不具合を、
 * 実MySQL 8（既定のREPEATABLE READ）の空DB（V1〜LATEST）で再現し、修正後は初回で確定できることを確かめる。
 *
 * <ul>
 *   <li>再現: 修正前の {@code confirmSkillSheet} と同じ順序（外側transactionで先に読む → {@code registerGenerated}
 *       （NOT_SUPPORTED。文書行を別接続でcommit）→ {@code DocumentService.link}）では、linkの文書存在確認（非lock読み）が
 *       外側のread viewから新しい文書を見られず404 {@code error.document.notFound} になる。同じ要求を新しいtransactionで
 *       再送すると、冪等登録が既存文書を返し、そのread viewは文書のcommit後に作られるため成功する（現場の「再送すると通る」）。</li>
 *   <li>修正後: {@code confirmSkillSheet} はtransactionを持たず、linkと確認日時の記録を文書のcommit後に始める
 *       transactionで行うため、初回でENGINEER linkと確認版が記録される。</li>
 * </ul>
 *
 * <p>H2はREPEATABLE READのread viewを再現しないため、fastでは {@code EngineerChangeRequestSkillSheetLockOrderTest} が
 * transactionの開始順を固定する。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class SkillSheetConfirmFirstAttemptMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_rb_prerun_skill_sheet_confirm")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    private static final String TENANT = "default";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DocumentService documentService;
    @Autowired private EngineerChangeRequestService changeRequestService;
    @Autowired private SysUserMapper sysUserMapper;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId(TENANT);
        SysUser admin = sysUserMapper.selectByUsername("admin");
        assertNotNull(admin, "V2のadminが存在すること");
        LoginUser principal = new LoginUser(admin, List.of(new SimpleGrantedAuthority("ROLE_管理者")), TENANT);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "N/A", principal.getAuthorities()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 外側transactionで先に読むと後からcommitした文書をlinkが見られず初回だけ404になる() {
        long engineerId = createEngineer("確定再現-" + UUID.randomUUID());
        String businessKey = "SKILL_SHEET-REPRO:" + engineerId;
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        BusinessException notFound = assertThrows(BusinessException.class,
                () -> outer.executeWithoutResult(status -> legacyConfirmOrder(engineerId, businessKey)));

        assertEquals(404, notFound.getCode());
        assertEquals("error.document.notFound", notFound.getMessageKey());
        // 文書は別接続でcommit済みで、linkは外側のrollbackで残らない
        assertEquals(1, documentCount(businessKey), "registerGeneratedの文書行はcommit済みであること");
        assertEquals(0, engineerLinkCount(engineerId), "初回はENGINEER linkが作られないこと");

        // 同じ要求の再送は、冪等登録が既存文書を返し、文書のcommit後に作られたread viewで読むため成功する
        outer.executeWithoutResult(status -> legacyConfirmOrder(engineerId, businessKey));

        assertEquals(1, documentCount(businessKey), "再送で文書を重複登録しないこと");
        assertEquals(1, engineerLinkCount(engineerId), "再送でENGINEER linkが作られること");
    }

    @Test
    void スキルシート確定は初回でENGINEER_linkと確認版を記録する() {
        long engineerId = createEngineer("確定初回-" + UUID.randomUUID());
        SkillSheetPreview preview = changeRequestService.skillSheetPreview(engineerId);
        assertTrue(!preview.current(), "確定前は確認済みの版が無いこと");

        SkillSheetConfirmResult result = changeRequestService.confirmSkillSheet(engineerId, preview.fingerprint());

        assertEquals(preview.fingerprint(), result.fingerprint());
        assertEquals(1, documentCount("SKILL_SHEET:" + engineerId), "SKILL_SHEET文書が1件登録されること");
        assertEquals(1, engineerLinkCount(engineerId), "初回の確定でENGINEER linkが作られること");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link "
                        + "WHERE tenant_id = ? AND target_type = 'ENGINEER' AND target_id = ? "
                        + "AND skill_sheet_confirmed_version = ? AND skill_sheet_confirmed_at IS NOT NULL",
                Integer.class, TENANT, engineerId, preview.fingerprint()), "確認版と確認日時が記録されること");
        assertTrue(changeRequestService.skillSheetPreview(engineerId).current(), "確定後のpreviewは確認済みの版と一致すること");

        // fingerprint stale → 409 の既存の意味は変わらない
        BusinessException stale = assertThrows(BusinessException.class,
                () -> changeRequestService.confirmSkillSheet(engineerId, "stale-" + preview.fingerprint()));
        assertEquals(409, stale.getCode());
        assertEquals("error.changeRequest.skillSheetStale", stale.getMessageKey());
    }

    /** 修正前のconfirmSkillSheetと同じ順序（呼出元のtransactionの中で実行する）。 */
    private void legacyConfirmOrder(long engineerId, String businessKey) {
        // 外側transactionの最初の非lock読みがREPEATABLE READのread viewを作る（修正前はpreviewの読取）
        jdbc.queryForObject("SELECT COUNT(*) FROM t_engineer WHERE id = ?", Long.class, engineerId);
        Document document = documentService.registerGenerated(DocumentRegisterRequest.builder()
                .documentType("SKILL_SHEET")
                .title("スキルシート 再現")
                .sourceType("GENERATED")
                .direction("INTERNAL")
                .counterpartyType("INTERNAL")
                .transactionDate(LocalDate.of(2026, 11, 17))
                .businessKey(businessKey)
                .versionDiscriminator("fp-repro")
                .originalName("skill-sheet-" + engineerId + ".pdf")
                .contentType("application/pdf")
                .build(), new ByteArrayInputStream(minimalPdf()));
        documentService.link(document.getId(), "ENGINEER", engineerId);
    }

    private long createEngineer(String fullName) {
        Long userId = sysUserMapper.selectByUsername("admin").getId();
        jdbc.update("INSERT INTO t_engineer (tenant_id, full_name, employment_type, status, created_by) "
                + "VALUES (?, ?, '正社員', 'Bench', ?)", TENANT, fullName, userId);
        return jdbc.queryForObject("SELECT id FROM t_engineer WHERE full_name = ?", Long.class, fullName);
    }

    private int documentCount(String businessKey) {
        return jdbc.queryForObject("SELECT COUNT(DISTINCT d.id) FROM t_document d JOIN t_document_version v "
                        + "ON v.document_id = d.id WHERE d.tenant_id = ? AND v.business_key = ?",
                Integer.class, TENANT, businessKey);
    }

    private int engineerLinkCount(long engineerId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM t_document_link "
                        + "WHERE tenant_id = ? AND target_type = 'ENGINEER' AND target_id = ? AND deleted_flag = 0",
                Integer.class, TENANT, engineerId);
    }

    private static byte[] minimalPdf() {
        return ("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n")
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    }
}
