package com.ses.service.impl;

import com.ses.dto.compliance.ComplianceMappingSourceInput;
import com.ses.entity.ComplianceMappingApprovalEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.mapper.ComplianceMappingVersionMapper;
import com.ses.service.ComplianceApprovalService;
import com.ses.service.ComplianceGateAdminService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private ComplianceGateAdminService complianceGateAdminService;

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
    void DRAFTからPROVISIONAL_REVIEWEDへはsourceCompletenessとpolicy非空を検証して遷移する() {
        // source不足（SRC-INDEX欠落）→ 400
        List<ComplianceMappingSourceInput> incomplete = new java.util.ArrayList<>(allSources());
        incomplete.removeIf(s -> "SRC-INDEX".equals(s.getSourceCode()));
        ComplianceMappingVersion incompleteVersion = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-1",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), incomplete);
        setupPolicy(incompleteVersion.getId());
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(incompleteVersion.getId(), "PROVISIONAL_REVIEWED"));

        // policy未設定（Requirement Groupなし） → 400 (P2-N1)
        ComplianceMappingVersion noPolicyVersion = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-NOPOLICY",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.transition(noPolicyVersion.getId(), "PROVISIONAL_REVIEWED"),
                "policy未設定のままPROVISIONAL化は不可");

        // source完全＋policy設定あり → 遷移成功・freeze（status変更でhash不変）
        ComplianceMappingVersion complete = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-TEST-2",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        setupPolicy(complete.getId());
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
        setupPolicy(version.getId());
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
        setupPolicy(version.getId());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");

        // 実actor（指名者=1）が承認
        ComplianceMappingApprovalEvent approval = complianceApprovalService.approve(
                version.getId(), workplaceId, "一次source確認済み", null);
        assertEquals(64, approval.getMappingHash().length());

        // §3.2 event順序: SUBMITTED review → verification（IDENTITY/AUTHORSHIP）→ APPROVED adoption
        setupExternalReviewGate(version.getId());

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
        setupPolicy(version.getId());
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
        setupPolicy(v1.getId());
        complianceMappingService.transition(v1.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app1 = complianceApprovalService.approve(v1.getId(), workplaceId, "v1確認", null);
        setupExternalReviewGate(v1.getId());
        complianceMappingService.transition(v1.getId(), "ACTIVE", app1.getId());

        // Version 2 (future保留版)
        ComplianceMappingVersion v2 = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-10-V2",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31), allSources());
        setupPolicy(v2.getId());
        complianceMappingService.transition(v2.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app2 = complianceApprovalService.approve(v2.getId(), workplaceId, "v2確認", null);
        setupExternalReviewGate(v2.getId());
        ComplianceMappingVersion v2Active = complianceMappingService.transition(v2.getId(), "ACTIVE", app2.getId());
        assertEquals("PROVISIONAL_REVIEWED", v2Active.getStatus(), "active_slotがNULLのためstatus=PROVISIONAL_REVIEWEDを維持");
        assertEquals(1, v2Active.getFutureSlot(), "既存ACTIVEがあるためfuture_slot=1");

        // Version 3: 2件目のfutureはcreate時点で拒否される（N1）
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.create(
                        "G2-MAPPING", "MAPPING-2027-01-V3",
                        LocalDate.of(2027, 1, 1), LocalDate.of(2027, 3, 31), allSources()),
                "2件目future候補はcreate時点で拒否される");

        // Promote: v2をactive_slot=1へ昇格、v1はSUPERSEDED化
        ComplianceMappingVersion promoted = complianceMappingService.promoteFutureToActive(v2.getId());
        assertEquals("ACTIVE", promoted.getStatus());
        assertEquals(1, promoted.getActiveSlot());
        assertNull(promoted.getFutureSlot());

        ComplianceMappingVersion oldV1 = complianceMappingService.getById(v1.getId());
        assertEquals("SUPERSEDED", oldV1.getStatus());
        assertNull(oldV1.getActiveSlot());
        assertEquals(v1.getEffectiveTo(), oldV1.getEffectiveTo(), "P1-N1: 旧ACTIVEのeffective_toは不変（決定性保全）");
        assertEquals(v1.getMappingHash(), oldV1.getMappingHash(), "P1-N1: mapping_hashは不変");
    }

    @Test
    void promoteFutureToActiveは承認REVOKE済みの場合昇格を拒否する() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('promote revoke customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='promote revoke customer'", Long.class);
        jdbcTemplate.update("INSERT INTO m_workplace (tenant_id, customer_id, name, organization_unit) "
                + "VALUES ('default', ?, 'promote revoke workplace', '開発部')", customerId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='promote revoke workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_compliance_responsible_assignment "
                + "(tenant_id, workplace_id, user_id, role_code, effective_from, active_slot, assigned_by) "
                + "VALUES ('default', ?, 1, 'COMPLIANCE_RESPONSIBLE', '2026-08-01 00:00:00.000000', 1, 1)",
                workplaceId);

        // Active version
        ComplianceMappingVersion v1 = complianceMappingService.create(
                "G2-MAPPING-PRO", "MAPPING-2026-07-P1",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        setupPolicy(v1.getId());
        complianceMappingService.transition(v1.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app1 = complianceApprovalService.approve(v1.getId(), workplaceId, "v1確認", null);
        setupExternalReviewGate(v1.getId());
        complianceMappingService.transition(v1.getId(), "ACTIVE", app1.getId());

        // Future candidate version
        ComplianceMappingVersion v2 = complianceMappingService.create(
                "G2-MAPPING-PRO", "MAPPING-2026-10-P2",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31), allSources());
        setupPolicy(v2.getId());
        complianceMappingService.transition(v2.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app2 = complianceApprovalService.approve(v2.getId(), workplaceId, "v2確認", null);
        setupExternalReviewGate(v2.getId());
        complianceMappingService.transition(v2.getId(), "ACTIVE", app2.getId());

        // 予約後に承認がREVOKEされる（後続REVOKE挿入） (P3-N1)
        jdbcTemplate.update("INSERT INTO t_compliance_mapping_approval_event "
                + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, assignment_id, "
                + "workplace_id_snapshot, actor_id, actor_display_name_snapshot, actor_role_snapshot, action, "
                + "event_chain_id, target_event_id, occurred_at, reason, operation_id, correlation_id, idempotency_key) VALUES "
                + "('default', ?, 'MAPPING-2026-10-P2', ?, ?, ?, ?, 1, '1名', '管理者', 'REVOKE', "
                + "'chain-2', ?, CURRENT_TIMESTAMP, '予約後取消', 'op-2', 'corr-2', 'idempotency-revoke-2')",
                v2.getId(), app2.getMappingHash(), app2.getReviewPolicyHash(),
                app2.getAssignmentId(), app2.getWorkplaceIdSnapshot(), app2.getId());

        // 昇格試行は承認REVOKEにより拒否される
        assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.promoteFutureToActive(v2.getId()),
                "承認REVOKE済みのfuture版は昇格拒否される");
    }

    @Test
    void createはeffectiveToがnullの無期限マスタを許可しcanonicalizerのhashが安定して出力される() {
        ComplianceMappingVersion indefinite = complianceMappingService.create(
                "G2-MAPPING-INDEFINITE", "MAPPING-2026-10",
                LocalDate.of(2026, 10, 1), null, allSources());

        assertNotNull(indefinite.getId());
        assertNull(indefinite.getEffectiveTo());
        assertEquals(64, indefinite.getMappingHash().length());
    }

    @Test
    void createは将来候補asOf未満でfuture_slotを1予約し同一マスタコードの2件目を409で拒否する() {
        // effectiveFrom > now (e.g. 2099-01-01) -> future candidate
        LocalDate futureFrom = LocalDate.of(2099, 1, 1);
        ComplianceMappingVersion futureCandidate = complianceMappingService.create(
                "G2-MAPPING-FUTURE-RESERVE", "MAPPING-2099-01",
                futureFrom, null, allSources());

        assertNotNull(futureCandidate.getId());
        assertEquals(1, futureCandidate.getFutureSlot(), "作成時asOf<effectiveFromによりfuture_slot=1が予約される");

        // 2件目の将来候補作成は 409 futureSlotAlreadyExists で拒否
        com.ses.common.exception.BusinessException ex = assertThrows(com.ses.common.exception.BusinessException.class,
                () -> complianceMappingService.create(
                        "G2-MAPPING-FUTURE-RESERVE", "MAPPING-2099-02",
                        futureFrom, null, allSources()));
        assertEquals(409, ex.getCode());
    }

    @Test
    void promoteFutureToActiveは単一のoperationIdとcorrelationIdを記録しhash不一致時に拒否する() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('uuid customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='uuid customer'", Long.class);
        jdbcTemplate.update("INSERT INTO m_workplace (tenant_id, customer_id, name, organization_unit) "
                + "VALUES ('default', ?, 'uuid workplace', '開発部')", customerId);
        Long workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='uuid workplace'", Long.class);
        jdbcTemplate.update("INSERT INTO t_compliance_responsible_assignment "
                + "(tenant_id, workplace_id, user_id, role_code, effective_from, active_slot, assigned_by) "
                + "VALUES ('default', ?, 1, 'COMPLIANCE_RESPONSIBLE', '2026-08-01 00:00:00.000000', 1, 1)",
                workplaceId);

        // Version 1 (現在版)
        ComplianceMappingVersion v1 = complianceMappingService.create(
                "G2-MAPPING-UUID", "MAPPING-2026-07-UUID1",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), allSources());
        setupPolicy(v1.getId());
        complianceMappingService.transition(v1.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app1 = complianceApprovalService.approve(v1.getId(), workplaceId, "v1確認", null);
        setupExternalReviewGate(v1.getId());
        complianceMappingService.transition(v1.getId(), "ACTIVE", app1.getId());

        // Version 2 (future候補)
        ComplianceMappingVersion v2 = complianceMappingService.create(
                "G2-MAPPING-UUID", "MAPPING-2026-10-UUID2",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31), allSources());
        setupPolicy(v2.getId());
        complianceMappingService.transition(v2.getId(), "PROVISIONAL_REVIEWED");
        ComplianceMappingApprovalEvent app2 = complianceApprovalService.approve(v2.getId(), workplaceId, "v2確認", null);
        setupExternalReviewGate(v2.getId());
        complianceMappingService.transition(v2.getId(), "ACTIVE", app2.getId());

        // Promote昇格
        complianceMappingService.promoteFutureToActive(v2.getId());

        // status eventのoperation_id, correlation_idを取得
        List<java.util.Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT operation_id, correlation_id FROM t_compliance_mapping_status_event WHERE mapping_id IN (?, ?) ORDER BY id DESC LIMIT 2",
                v1.getId(), v2.getId());
        assertEquals(2, events.size());
        assertEquals(events.get(0).get("operation_id"), events.get(1).get("operation_id"), "旧SUPERSEDEDと新ACTIVEでoperation_idが一致");
        assertEquals(events.get(0).get("correlation_id"), events.get(1).get("correlation_id"), "旧SUPERSEDEDと新ACTIVEでcorrelation_idが一致");
    }

    /**
     * §4-3（P0-FIX-3）: policyは各group最低1type必須のため、group作成と同時にtypeを追加する。
     */
    private void setupPolicy(Long mappingId) {
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(mappingId, "GRP-1", "グループ1", 1);
        Integer typeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_compliance_external_reviewer_type WHERE type_code='LABOR_CONSULTANT'", Integer.class);
        if (typeCount == null || typeCount == 0) {
            complianceGateAdminService.createReviewerType(
                    "LABOR_CONSULTANT", "社労士", "社会保険労務士", "社労士登録番号", true);
        }
        Long reviewerTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_compliance_external_reviewer_type WHERE type_code='LABOR_CONSULTANT'", Long.class);
        complianceGateAdminService.addRequirementType(group.getId(), reviewerTypeId);
    }

    private List<ComplianceMappingSourceInput> allSources() {
        return List.of(
                source("SRC-C", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722622.pdf", "2026-07"),
                source("SRC-E", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722631.pdf", "2026-07"),
                source("SRC-N", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722633.pdf", "2026-07"),
                source("SRC-L", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/content/contents/002722641.pdf", "2026-07"),
                source("SRC-INDEX", "https://jsite.mhlw.go.jp/hokkaido-roudoukyoku/hourei_seido_tetsuzuki/roudousha_haken/newpage_00448.html", "2026-07"));
    }

    /**
     * §3.2 event順序のテストセットアップ: SUBMITTED review → verification（IDENTITY/AUTHORSHIP）→ APPROVED adoption。
     * review/verification/adoption eventを直接INSERTし、gate採用条件（§3.2 K3）を満たすfixtureを作る。
     */
    private void setupExternalReviewGate(Long mappingId) {
        com.ses.entity.ComplianceMappingVersion version = versionMapper.selectById(mappingId);
        // fixture: reviewer type・subject（存在しなければ作成）
        Integer typeCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_compliance_external_reviewer_type WHERE type_code='LABOR_CONSULTANT'", Integer.class);
        if (typeCount == null || typeCount == 0) {
            jdbcTemplate.update("INSERT INTO m_compliance_external_reviewer_type "
                    + "(tenant_id, type_code, display_name, credential_label, enabled) VALUES ('default','LABOR_CONSULTANT','社労士','社労士登録番号',1)");
        }
        Integer subjectCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_compliance_external_reviewer_subject WHERE subject_code='SUBJ-TEST'", Integer.class);
        if (subjectCount == null || subjectCount == 0) {
            jdbcTemplate.update("INSERT INTO t_compliance_external_reviewer_subject "
                    + "(tenant_id, subject_code, display_name, organization_name, person_fingerprint_snapshot, fingerprint_key_version, created_by) "
                    + "VALUES ('default','SUBJ-TEST','確認対象者','確認組織', REPEAT('c',64), 'v1', 1)");
        }
        String reviewChainId = "CHAIN-" + mappingId;
        Long reviewerTypeId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_compliance_external_reviewer_type WHERE type_code='LABOR_CONSULTANT'", Long.class);
        Long subjectId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_compliance_external_reviewer_subject WHERE subject_code='SUBJ-TEST'", Long.class);
        Long groupId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_compliance_mapping_review_requirement_group WHERE mapping_id=? AND tenant_id='default'",
                Long.class, mappingId);

        // SUBMITTED review event
        jdbcTemplate.update("INSERT INTO t_compliance_external_review_event "
                + "(tenant_id, mapping_id, mapping_version, mapping_hash, review_policy_hash, requirement_group_id, "
                + "requirement_group_code_snapshot, reviewer_type_id, reviewer_type_code_snapshot, reviewer_type_name_snapshot, "
                + "reviewer_name_snapshot, organization_snapshot, reviewer_identity_hash, action, review_chain_id, "
                + "reviewed_at, recorded_at, recorded_by, operation_id, correlation_id, idempotency_key) VALUES "
                + "('default', ?, ?, ?, ?, ?, 'G1', ?, 'LABOR_CONSULTANT', '社労士', '確認対象者', '確認組織', "
                + "REPEAT('d',64), 'SUBMITTED', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 1, ?, ?, ?)",
                mappingId, version.getMappingVersion(), version.getMappingHash(), version.getReviewPolicyHash(),
                groupId, reviewerTypeId, reviewChainId, "op-sub-" + mappingId, "corr-sub-" + mappingId,
                "review-sub-" + mappingId);
        Long reviewEventId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_compliance_external_review_event WHERE review_chain_id=? AND action='SUBMITTED'",
                Long.class, reviewChainId);
        // verification: IDENTITY（VERIFIED）
        jdbcTemplate.update("INSERT INTO t_compliance_external_reviewer_verification_event "
                + "(tenant_id, reviewer_type_id, reviewer_type_code_snapshot, reviewer_type_name_snapshot, "
                + "reviewer_subject_id, person_fingerprint_snapshot, qualification_fingerprint_snapshot, "
                + "fingerprint_key_version, verification_kind, result, method_code, authority_source_code, "
                + "authority_source_name, checked_at, checked_by, submitted_review_event_id, operation_id, correlation_id, idempotency_key) VALUES "
                + "('default', ?, 'LABOR_CONSULTANT', '社労士', ?, ?, ?, 'v1', "
                + "'IDENTITY', 'VERIFIED', 'MANUAL_OFFICIAL_SOURCE', 'OFFICIAL_1', '公式確認source', CURRENT_TIMESTAMP, 1, "
                + "?, ?, ?, ?)",
                reviewerTypeId, subjectId, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                reviewEventId, "op-ver-i-" + mappingId, "corr-ver-i-" + mappingId, "ver-i-" + mappingId);
        Long identityVerificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_compliance_external_reviewer_verification_event "
                        + "WHERE submitted_review_event_id=? AND verification_kind='IDENTITY'",
                Long.class, reviewEventId);
        // verification: REVIEW_AUTHORSHIP（VERIFIED・binding必須）
        jdbcTemplate.update("INSERT INTO t_compliance_external_reviewer_verification_event "
                + "(tenant_id, reviewer_type_id, reviewer_type_code_snapshot, reviewer_type_name_snapshot, "
                + "reviewer_subject_id, person_fingerprint_snapshot, qualification_fingerprint_snapshot, "
                + "fingerprint_key_version, verification_kind, result, method_code, authority_source_code, "
                + "authority_source_name, checked_at, checked_by, review_policy_version, review_policy_hash, "
                + "mapping_id, mapping_version, mapping_hash, external_review_event_id, external_review_chain_id, "
                + "submitted_review_event_id, operation_id, correlation_id, idempotency_key) VALUES "
                + "('default', ?, 'LABOR_CONSULTANT', '社労士', ?, ?, ?, 'v1', "
                + "'REVIEW_AUTHORSHIP', 'VERIFIED', 'MANUAL_INTERNAL_AUTHORSHIP_CONFIRM', 'INTERNAL', '内部確認', "
                + "CURRENT_TIMESTAMP, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                reviewerTypeId, subjectId, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                version.getMappingVersion(), version.getReviewPolicyHash(), mappingId, version.getMappingVersion(),
                version.getMappingHash(), reviewEventId, reviewChainId, reviewEventId,
                "op-ver-a-" + mappingId, "corr-ver-a-" + mappingId, "ver-a-" + mappingId);
        Long authorshipVerificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_compliance_external_reviewer_verification_event "
                        + "WHERE submitted_review_event_id=? AND verification_kind='REVIEW_AUTHORSHIP'",
                Long.class, reviewEventId);
        // verification: QUALIFICATION（VERIFIED・frozen policyがcredential_required=trueのため必須）
        jdbcTemplate.update("INSERT INTO t_compliance_external_reviewer_verification_event "
                + "(tenant_id, reviewer_type_id, reviewer_type_code_snapshot, reviewer_type_name_snapshot, "
                + "reviewer_subject_id, person_fingerprint_snapshot, qualification_fingerprint_snapshot, "
                + "fingerprint_key_version, verification_kind, result, method_code, authority_source_code, "
                + "authority_source_name, checked_at, checked_by, submitted_review_event_id, operation_id, correlation_id, idempotency_key) VALUES "
                + "('default', ?, 'LABOR_CONSULTANT', '社労士', ?, ?, ?, 'v1', "
                + "'QUALIFICATION', 'VERIFIED', 'MANUAL_REGISTRY', 'REGISTRY_1', '社労士名簿', CURRENT_TIMESTAMP, 1, "
                + "?, ?, ?, ?)",
                reviewerTypeId, subjectId, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                reviewEventId, "op-ver-q-" + mappingId, "corr-ver-q-" + mappingId, "ver-q-" + mappingId);
        Long qualificationVerificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_compliance_external_reviewer_verification_event "
                        + "WHERE submitted_review_event_id=? AND verification_kind='QUALIFICATION'",
                Long.class, reviewEventId);
        // verification: ACTIVE_STATUS（VERIFIED・業務停止なし）
        jdbcTemplate.update("INSERT INTO t_compliance_external_reviewer_verification_event "
                + "(tenant_id, reviewer_type_id, reviewer_type_code_snapshot, reviewer_type_name_snapshot, "
                + "reviewer_subject_id, person_fingerprint_snapshot, qualification_fingerprint_snapshot, "
                + "fingerprint_key_version, verification_kind, result, method_code, authority_source_code, "
                + "authority_source_name, checked_at, checked_by, submitted_review_event_id, operation_id, correlation_id, idempotency_key) VALUES "
                + "('default', ?, 'LABOR_CONSULTANT', '社労士', ?, ?, ?, 'v1', "
                + "'ACTIVE_STATUS', 'VERIFIED', 'MANUAL_REGISTRY', 'REGISTRY_1', '社労士名簿', CURRENT_TIMESTAMP, 1, "
                + "?, ?, ?, ?)",
                reviewerTypeId, subjectId, "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                reviewEventId, "op-ver-s-" + mappingId, "corr-ver-s-" + mappingId, "ver-s-" + mappingId);
        Long activeStatusVerificationId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_compliance_external_reviewer_verification_event "
                        + "WHERE submitted_review_event_id=? AND verification_kind='ACTIVE_STATUS'",
                Long.class, reviewEventId);
        // APPROVED adoption（identity/qualification/active-status/authorship参照・mapping/policy/evidence snapshot）
        jdbcTemplate.update("INSERT INTO t_compliance_external_review_adoption_event "
                + "(tenant_id, action, review_chain_id, submitted_review_event_id, identity_verification_event_id, "
                + "qualification_verification_event_id, active_status_verification_event_id, "
                + "authorship_verification_event_id, mapping_id, mapping_version, mapping_hash, review_policy_version, "
                + "review_policy_hash, adopted_at, adopted_by, operation_id, correlation_id, idempotency_key) VALUES "
                + "('default', 'APPROVED', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, 1, ?, ?, ?)",
                reviewChainId, reviewEventId, identityVerificationId, qualificationVerificationId,
                activeStatusVerificationId, authorshipVerificationId,
                mappingId, version.getMappingVersion(), version.getMappingHash(),
                version.getMappingVersion(), version.getReviewPolicyHash(),
                "op-adopt-" + mappingId, "corr-adopt-" + mappingId, "adopt-" + mappingId);
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
