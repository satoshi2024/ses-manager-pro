package com.ses.service.impl;

import com.ses.dto.compliance.ComplianceMappingSourceInput;
import com.ses.entity.ComplianceMappingApprovalEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.mapper.ComplianceMappingVersionMapper;
import com.ses.service.ComplianceApprovalService;
import com.ses.service.ComplianceMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 mapping version service（Phase A step 3）のL2〜L3検証。
 *  - create: canonicalizerでmapping_hash計算・DRAFT登録
 *  - DRAFT→PROVISIONAL_REVIEWED: source completeness必須・freeze
 *  - ACTIVE化は証跡gateで保留
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
@org.springframework.security.test.context.support.WithMockUser(username = "1", roles = "管理者")
class ComplianceMappingServiceImplTest {

    @Autowired
    private ComplianceMappingService complianceMappingService;

    @Autowired
    private ComplianceMappingVersionMapper versionMapper;

    @Autowired
    private ComplianceApprovalService complianceApprovalService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void createはcanonicalizerでhashを計算しDRAFTで登録する() {
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                allSources());

        assertNotNull(version.getId());
        assertEquals("DRAFT", version.getStatus());
        assertEquals(64, version.getMappingHash().length());
        assertEquals(version.getMappingHash(), versionMapper.selectById(version.getId()).getMappingHash());
    }

    @Test
    void DRAFTからPROVISIONAL_REVIEWEDへはsourceCompletenessを検証して遷移する() {
        // source不足（SRC-INDEX欠落）→ 400
        List<ComplianceMappingSourceInput> incomplete = new java.util.ArrayList<>(allSources());
        incomplete.removeIf(s -> "SRC-INDEX".equals(s.getSourceCode()));
        ComplianceMappingVersion incompleteVersion = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-1",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), incomplete);
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(incompleteVersion.getId(), "PROVISIONAL_REVIEWED"));

        // source完全 → 遷移成功・freeze（status変更でhash不変）
        ComplianceMappingVersion complete = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-2",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        String draftHash = complete.getMappingHash();
        ComplianceMappingVersion reviewed = complianceMappingService.transition(complete.getId(), "PROVISIONAL_REVIEWED");
        assertEquals("PROVISIONAL_REVIEWED", reviewed.getStatus());
        assertEquals(draftHash, reviewed.getMappingHash(), "freeze: hash不変");

        // PROVISIONAL_REVIEWED→PROVISIONAL_REVIEWEDは不正遷移
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(reviewed.getId(), "PROVISIONAL_REVIEWED"));
    }

    @Test
    void ACTIVE化は承認eventが無ければ証跡gateで保留される() {
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-3",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");
        // 承認eventなし → approvalRequiredで保留
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(version.getId(), "ACTIVE"));
    }

    @Test
    void ACTIVE化は承認eventとopenAssignmentとhash一致で成立しstatusEventが記録される() {
        // workplace + open assignment（指名者=currentUserId 1）
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('active customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='active customer'", Long.class);
        jdbcTemplate.update("INSERT INTO m_workplace (tenant_id, customer_id, name, organization_unit) "
                + "VALUES ('default', ?, 'active workplace', '開発部')", customerId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='active workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_compliance_responsible_assignment "
                + "(tenant_id, workplace_id, user_id, role_code, effective_from, active_slot, assigned_by) "
                + "VALUES ('default', ?, 1, 'COMPLIANCE_RESPONSIBLE', '2026-08-01 00:00:00.000000', 1, 1)",
                workplaceId);

        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-4",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");

        // 実actor（指名者=1）が承認
        ComplianceMappingApprovalEvent approval = complianceApprovalService.approve(
                version.getId(), workplaceId, "一次source確認済み", null);
        assertEquals(64, approval.getMappingHash().length());

        // ACTIVE化成立: active_slot=1・activatedAt・status event記録
        ComplianceMappingVersion active = complianceMappingService.transition(version.getId(), "ACTIVE", approval.getId());
        assertEquals("ACTIVE", active.getStatus());
        assertEquals(1, active.getActiveSlot());
        assertNotNull(active.getActivatedAt());
        Integer eventCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_compliance_mapping_status_event WHERE mapping_id=? AND after_status='ACTIVE'",
                Integer.class, version.getId());
        assertEquals(1, eventCount, "status event（append-only）が記録される");
    }

    @Test
    void ACTIVE化はREVOKE済みの承認やapprovalEventId未指定を拒否する() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('revoke customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='revoke customer'", Long.class);
        jdbcTemplate.update("INSERT INTO m_workplace (tenant_id, customer_id, name, organization_unit) "
                + "VALUES ('default', ?, 'revoke workplace', '開発部')", customerId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='revoke workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_compliance_responsible_assignment "
                + "(tenant_id, workplace_id, user_id, role_code, effective_from, active_slot, assigned_by) "
                + "VALUES ('default', ?, 1, 'COMPLIANCE_RESPONSIBLE', '2026-08-01 00:00:00.000000', 1, 1)",
                workplaceId);

        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-REVOKE-TEST",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");

        ComplianceMappingApprovalEvent approval = complianceApprovalService.approve(
                version.getId(), workplaceId, "一次source確認済み", null);

        // approvalEventId未指定は拒否
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(version.getId(), "ACTIVE", null));

        // 後続REVOKEイベントを挿入
        jdbcTemplate.update("INSERT INTO t_compliance_mapping_approval_event "
                + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                + "event_chain_id, target_event_id, occurred_at, reason, operation_id, correlation_id, idempotency_key) VALUES "
                + "('default', ?, 'MAPPING-2026-07-REVOKE-TEST', ?, ?, ?, ?, 1, '1名', '管理者', 'REVOKE', "
                + "'chain-1', ?, CURRENT_TIMESTAMP, '取消', 'op-1', 'corr-1', 'idempotency-revoke-1')",
                version.getId(), approval.getMappingHash(), approval.getReviewPolicyHash(),
                approval.getAssignmentId(), approval.getWorkplaceIdSnapshot(), approval.getId());

        // 取消済みの承認イベントでのACTIVE化は拒否
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(version.getId(), "ACTIVE", approval.getId()));
    }

    @Test
    void futureSlotの昇格経路と単一slot制約を検証する() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('future customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='future customer'", Long.class);
        jdbcTemplate.update("INSERT INTO m_workplace (tenant_id, customer_id, name, organization_unit) "
                + "VALUES ('default', ?, 'future workplace', '開発部')", customerId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='future workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_compliance_responsible_assignment "
                + "(tenant_id, workplace_id, user_id, role_code, effective_from, active_slot, assigned_by) "
                + "VALUES ('default', ?, 1, 'COMPLIANCE_RESPONSIBLE', '2026-08-01 00:00:00.000000', 1, 1)",
                workplaceId);

        // Version 1 (現在版)
        ComplianceMappingVersion v1 = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-V1",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        complianceMappingService.transition(v1.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app1 = complianceApprovalService.approve(v1.getId(), workplaceId, "v1確認", null);
        complianceMappingService.transition(v1.getId(), "ACTIVE", app1.getId());

        // Version 2 (future保留版)
        ComplianceMappingVersion v2 = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-10-V2",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31), allSources());
        complianceMappingService.transition(v2.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app2 = complianceApprovalService.approve(v2.getId(), workplaceId, "v2確認", null);
        ComplianceMappingVersion v2Active = complianceMappingService.transition(v2.getId(), "ACTIVE", app2.getId());
        assertEquals(1, v2Active.getFutureSlot(), "既存ACTIVEがあるためfuture_slot=1");

        // Version 3: 2件目のfutureは拒否される
        ComplianceMappingVersion v3 = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2027-01-V3",
                LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31), allSources());
        complianceMappingService.transition(v3.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app3 = complianceApprovalService.approve(v3.getId(), workplaceId, "v3確認", null);
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(v3.getId(), "ACTIVE", app3.getId()), "2件目future候補は拒否");

        // Promote: v2をactive_slot=1へ昇格、v1はSUPERSEDED化
        ComplianceMappingVersion promoted = complianceMappingService.promoteFutureToActive(v2.getId());
        assertEquals("ACTIVE", promoted.getStatus());
        assertEquals(1, promoted.getActiveSlot());
        assertNull(promoted.getFutureSlot());

        ComplianceMappingVersion oldV1 = complianceMappingService.getById(v1.getId());
        assertEquals("SUPERSEDED", oldV1.getStatus());
        assertNull(oldV1.getActiveSlot());
    }

    private List<ComplianceMappingSourceInput> allSources() {
        return List.of(
                source("SRC-C", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722622.pdf", "2026-07"),
                source("SRC-E", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722631.pdf", "2026-07"),
                source("SRC-N", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722633.pdf", "2026-07"),
                source("SRC-L", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722641.pdf", "2026-07"),
                source("SRC-INDEX", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/hourei_seido_tetsuzuki/roudousha_haken/newpage_00448.html", "2026-07"));
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
