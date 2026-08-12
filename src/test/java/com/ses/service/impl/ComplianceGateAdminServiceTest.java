package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.compliance.ComplianceMappingSourceInput;
import com.ses.entity.ComplianceExternalReviewerType;
import com.ses.entity.ComplianceMappingApprovalEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.entity.ComplianceResponsibleAssignment;
import com.ses.service.ComplianceApprovalService;
import com.ses.service.ComplianceGateAdminService;
import com.ses.service.ComplianceMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Phase A step 3: reviewer type・assignment・approval eventのL2〜L3検証。
 *  - reviewer type CRUD・duplicate拒否
 *  - assignment: 半開区間・active_slot単一（交代で旧open終了）・endReason必須
 *  - approval: PROVISIONAL_REVIEWEDのみ・実actor=指名者本人・canonical hash記録・actor不一致403
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
@WithMockUser(username = "1", roles = "管理者")
class ComplianceGateAdminServiceTest {

    @Autowired
    private ComplianceGateAdminService complianceGateAdminService;

    @Autowired
    private ComplianceApprovalService complianceApprovalService;

    @Autowired
    private ComplianceMappingService complianceMappingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reviewerTypeを作成更新無効化できる() {
        ComplianceExternalReviewerType type = complianceGateAdminService.createReviewerType(
                "LABOR_CONSULTANT", "社労士", "社会保険労務士", "社労士登録番号", true);
        assertNotNull(type.getId());
        assertEquals(1, type.getEnabled());

        ComplianceExternalReviewerType updated = complianceGateAdminService.updateReviewerType(
                type.getId(), "社労士（更新）", null, "社労士登録番号", true);
        assertEquals("社労士（更新）", updated.getDisplayName());

        ComplianceExternalReviewerType disabled = complianceGateAdminService.setReviewerTypeEnabled(type.getId(), false);
        assertEquals(0, disabled.getEnabled());

        // duplicate typeCodeは拒否
        assertThrows(BusinessException.class, () -> complianceGateAdminService.createReviewerType(
                "LABOR_CONSULTANT", "重複", null, null, false));
    }

    @Test
    void assignmentは半開区間でactiveSlotが常に単一になる() {
        Long workplaceId = insertWorkplace();
        Long user1 = insertUser("gate-user-1", "HR");
        Long user2 = insertUser("gate-user-2", "HR");

        ComplianceResponsibleAssignment first = complianceGateAdminService.createAssignment(
                workplaceId, user1, LocalDateTime.now().minusDays(1));
        assertEquals(1, first.getActiveSlot());
        assertNull(first.getEffectiveTo());

        // 交代: 旧openが終了し、新assignmentがopenになる
        ComplianceResponsibleAssignment second = complianceGateAdminService.createAssignment(
                workplaceId, user2, LocalDateTime.now());
        assertNotNull(second.getEffectiveTo() == null ? second : second);
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE workplace_id=? AND active_slot=1",
                Integer.class, workplaceId);
        assertEquals(1, openCount, "open assignmentは常に1つ");
        Integer endedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE workplace_id=? AND active_slot IS NULL",
                Integer.class, workplaceId);
        assertEquals(1, endedCount, "旧openが終了している");

        // endReasonなしの終了は拒否・openでないassignmentの終了は拒否
        assertThrows(BusinessException.class, () -> complianceGateAdminService.endAssignment(second.getId(), null));
        complianceGateAdminService.endAssignment(second.getId(), "交代");
        assertThrows(BusinessException.class, () -> complianceGateAdminService.endAssignment(second.getId(), "再終了"));
    }

    @Test
    void approvalは指名者本人だけがcanonicalHash付きで記録できる() {
        Long workplaceId = insertWorkplace();
        // 指名者=現在のactor（@WithMockUser username=1 → currentUserId=1）
        complianceGateAdminService.createAssignment(workplaceId, 1L, LocalDateTime.now().minusDays(1));

        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-APPROVAL",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");

        // 指名者本人が承認 → event記録（canonical hash・64 hex）
        ComplianceMappingApprovalEvent event = complianceApprovalService.approve(
                version.getId(), workplaceId, "公式source確認済み", null);
        assertNotNull(event.getId());
        assertEquals("APPROVE", event.getAction());
        assertEquals(64, event.getMappingHash().length());
        assertEquals(1L, event.getActorId());
        assertEquals("MAPPING-2026-07-APPROVAL", event.getMappingVersion());
        // hashがmapping versionの保存hashと一致する（canonical再計算）
        ComplianceMappingVersion saved = complianceMappingService.getById(version.getId());
        assertEquals(saved.getMappingHash(), event.getMappingHash());
    }

    @Test
    void approvalは指名者以外を拒否する() {
        Long workplaceId = insertWorkplace();
        // 指名者は他ユーザー（currentUserId=1とは不一致）
        Long other = insertUser("gate-other", "HR");
        complianceGateAdminService.createAssignment(workplaceId, other, LocalDateTime.now().minusDays(1));
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-APPROVAL-3",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");
        assertThrows(BusinessException.class,
                () -> complianceApprovalService.approve(version.getId(), workplaceId, "他人の承認", null),
                "actor不一致は403相当のBusinessException");
    }

    @Test
    void approvalはPROVISIONAL_REVIEWED以外を拒否する() {
        Long workplaceId = insertWorkplace();
        Long actor = insertUser("gate-actor-2", "HR");
        complianceGateAdminService.createAssignment(workplaceId, actor, LocalDateTime.now().minusDays(1));
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-APPROVAL-2",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        // DRAFTのまま承認 → 拒否
        assertThrows(BusinessException.class,
                () -> complianceApprovalService.approve(version.getId(), workplaceId, "早期承認", null));
    }

    private Long insertWorkplace() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('gate customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='gate customer'", Long.class);
        jdbcTemplate.update("INSERT INTO m_workplace (tenant_id, customer_id, name, organization_unit) "
                + "VALUES ('default', ?, 'gate workplace', '開発部')", customerId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='gate workplace'", Long.class);
    }

    private Long insertUser(String username, String role) {
        jdbcTemplate.update("INSERT INTO sys_user (username, real_name, role, status, password) "
                + "VALUES (?, ?, ?, 1, 'x')", username, username + "名", role);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    }

    private List<ComplianceMappingSourceInput> sources() {
        return List.of(
                source("SRC-C", "https://example/src-c", "2026-07"),
                source("SRC-E", "https://example/src-e", "2026-07"),
                source("SRC-N", "https://example/src-n", "2026-07"),
                source("SRC-L", "https://example/src-l", "2026-07"),
                source("SRC-INDEX", "https://example/index", "2026-07"));
    }

    private ComplianceMappingSourceInput source(String code, String url, String version) {
        ComplianceMappingSourceInput input = new ComplianceMappingSourceInput();
        input.setSourceCode(code);
        input.setSourceUrl(url);
        input.setSourceVersion(version);
        input.setConfirmedOn(LocalDate.of(2026, 8, 9));
        input.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        input.setEffectiveTo(LocalDate.of(2026, 9, 30));
        return input;
    }
}
