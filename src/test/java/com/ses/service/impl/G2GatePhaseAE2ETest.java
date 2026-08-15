package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ComplianceExternalReviewAdoptionEvent;
import com.ses.entity.ComplianceExternalReviewEvent;
import com.ses.entity.ComplianceExternalReviewerSubject;
import com.ses.entity.ComplianceExternalReviewerVerificationEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.service.ComplianceExternalReviewAdoptionService;
import com.ses.service.ComplianceExternalReviewVerificationService;
import com.ses.service.ComplianceGateAdminService;
import com.ses.service.ComplianceMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Phase A（R25契約A・S10_TECHNICAL_ACCEPTANCE）: G2 gateのTEST/DEVELOPMENT fixtureによる
 * 完全E2Eパス検証。mapping→policy→subject→external review(SUBMITTED)→verification
 * （IDENTITY/QUALIFICATION/ACTIVE_STATUS/REVIEW_AUTHORSHIP）→adoption(APPROVED)→ACTIVE化。
 * 架空専門家（TEST/DEVELOPMENT fixture）は本テスト内限定であり、seed/正式証跡へ流用しない（§6・R25 §2）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "1", roles = "管理者")
class G2GatePhaseAE2ETest {

    @Autowired
    private ComplianceMappingService complianceMappingService;
    @Autowired
    private ComplianceGateAdminService complianceGateAdminService;
    @Autowired
    private ComplianceExternalReviewVerificationService verificationService;
    @Autowired
    private ComplianceExternalReviewAdoptionService adoptionService;
    @Autowired
    private com.ses.service.ComplianceApprovalService approvalService;
    @Autowired
    private com.ses.service.compliance.ComplianceReviewerFingerprintService fingerprintService;
    @Autowired
    private com.ses.mapper.ComplianceExternalReviewerSubjectMapper subjectMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Long reviewerTypeId;
    private Long subjectId;
    private Long[] evidenceIds = new Long[2];
    private Long workplaceId;

    /** 実actor（管理者=username 1）を指名したassignment付きworkplaceを作成（証跡1 fixture・TEST/DEVELOPMENT）。 */
    private Long workplaceId() {
        if (workplaceId != null) {
            return workplaceId;
        }
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('phasea customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='phasea customer'", Long.class);
        jdbcTemplate.update("INSERT INTO m_workplace (tenant_id, customer_id, name, organization_unit) "
                + "VALUES ('default', ?, 'phasea workplace', '開発部')", customerId);
        workplaceId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='phasea workplace'", Long.class);
        // 被指名actor=現在のユーザー（@WithMockUser username=1 → currentUserId=1）
        complianceGateAdminService.createAssignment(workplaceId, 1L, LocalDateTime.now().minusDays(1));
        return workplaceId;
    }

    private Long reviewerTypeId() {
        if (reviewerTypeId == null) {
            reviewerTypeId = complianceGateAdminService.createReviewerType(
                    "PHASEA_LABOR", "社労士", "Phase A fixture", "登録番号", true).getId();
        }
        return reviewerTypeId;
    }

    private Long subjectId() {
        if (subjectId == null) {
            ComplianceExternalReviewerSubject subject = new ComplianceExternalReviewerSubject();
            subject.setTenantId("default");
            subject.setSubjectCode("PHASEA-SUBJECT-1");
            subject.setDisplayName("PhaseA 山田");
            subject.setOrganizationName("PhaseA 組織");
            subject.setPersonFingerprintSnapshot(fingerprintService.personFingerprint("default", subject));
            subject.setFingerprintKeyVersion("k1");
            subjectMapper.insert(subject);
            subjectId = subject.getId();
        }
        return subjectId;
    }

    private Long[] evidenceIds() {
        if (evidenceIds[0] != null) {
            return evidenceIds;
        }
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        jdbcTemplate.update("INSERT INTO t_document_version "
                + "(tenant_id, document_id, version_no, storage_key, original_name, content_type, "
                + "size_bytes, sha256, source_type, business_key, version_discriminator, scan_status, created_by) "
                + "VALUES ('default', 930001, 1, 'ev/k', 'phasea-ev.pdf', 'application/pdf', 10, ?, "
                + "'UPLOAD', 'phasea-ev', '1', 'CLEAN', 1)", sha);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_document_version WHERE business_key = 'phasea-ev'", Long.class);
        evidenceIds[0] = 930001L;
        evidenceIds[1] = versionId;
        return evidenceIds;
    }

    private com.ses.dto.compliance.ComplianceMappingSourceInput source(String code) {
        com.ses.dto.compliance.ComplianceMappingSourceInput input =
                new com.ses.dto.compliance.ComplianceMappingSourceInput();
        input.setSourceCode(code);
        input.setSourceUrl("https://example/" + code);
        input.setSourceVersion("2026-07");
        input.setConfirmedOn(LocalDate.of(2026, 8, 9));
        input.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        input.setEffectiveTo(LocalDate.of(2026, 9, 30));
        return input;
    }

    @Test
    void PhaseAfixtureでmappingからACTIVEまでの完全パスが成立する() {
        // 1. mapping作成＋policy freeze（§4-3: 最低1group・最低1type）
        ComplianceMappingVersion v = complianceMappingService.create(
                "PHASEA-MAP", "PHASEA-V1", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                List.of(source("SRC-C"), source("SRC-E"), source("SRC-N"),
                        source("SRC-L"), source("SRC-INDEX")));
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(v.getId(), "GRP-1", "グループ1", 1);
        complianceGateAdminService.addRequirementType(group.getId(), reviewerTypeId());
        // dynamic policy設定（frozen flags・source/method・max_age・§8）
        complianceGateAdminService.updateReviewerTypeDynamic(reviewerTypeId(), 1, 1, null, null, 365, null, null);
        complianceMappingService.transition(v.getId(), "PROVISIONAL_REVIEWED");
        v = complianceMappingService.getById(v.getId());
        assertEquals("PROVISIONAL_REVIEWED", v.getStatus());

        // 2. internal approval（証跡2 fixture・実actor本人がexact CLEAN evidence付きで承認）
        Long workplace = workplaceId();
        com.ses.entity.ComplianceMappingApprovalEvent approval = approvalService.approve(
                v.getId(), workplace, "PhaseA fixture承認", evidenceIds()[0], evidenceIds()[1]);
        assertNotNull(approval.getId());

        // 3. subject作成＋資格association（P0-4）
        Long subject = subjectId();
        assertNotNull(subject);
        Long qualificationId = complianceGateAdminService.addQualification(subject, reviewerTypeId(), "****1234", "登録番号").getId();
        assertNotNull(qualificationId);

        // 4. external review SUBMITTED（§3.2 step 1）
        ComplianceExternalReviewEvent review = complianceGateAdminService.recordExternalReview(
                v.getId(), group.getId(), reviewerTypeId(),
                "PhaseA 山田", "PhaseA 組織", "REG-PHASEA-1",
                "SUBMITTED", LocalDateTime.now(), null, null, null, null);
        assertNotNull(review.getReviewChainId());

        // 5. verification 4 kind（§3.3・IDENTITY/AUTHORSHIP常時・QUALIFICATION/ACTIVE_STATUSはfrozen flag=true）
        ComplianceExternalReviewerVerificationEvent identity = verificationService.record(
                review.getId(), subject, reviewerTypeId(), "IDENTITY", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-PHASEA-1",
                LocalDateTime.of(2026, 8, 14, 10, 0, 0), LocalDateTime.of(2026, 8, 14, 10, 0, 0),
                365, LocalDateTime.of(2027, 8, 14, 10, 0, 0),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), "PHASEA-ID");
        ComplianceExternalReviewerVerificationEvent qual = verificationService.record(
                review.getId(), subject, reviewerTypeId(), "QUALIFICATION", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-PHASEA-1",
                LocalDateTime.of(2026, 8, 14, 10, 0, 0), LocalDateTime.of(2026, 8, 14, 10, 0, 0),
                365, LocalDateTime.of(2027, 8, 14, 10, 0, 0),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), "PHASEA-Q");
        ComplianceExternalReviewerVerificationEvent active = verificationService.record(
                review.getId(), subject, reviewerTypeId(), "ACTIVE_STATUS", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-PHASEA-1",
                LocalDateTime.of(2026, 8, 14, 10, 0, 0), LocalDateTime.of(2026, 8, 14, 10, 0, 0),
                365, LocalDateTime.of(2027, 8, 14, 10, 0, 0),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), "PHASEA-A");
        ComplianceExternalReviewerVerificationEvent authorship = verificationService.record(
                review.getId(), subject, reviewerTypeId(), "REVIEW_AUTHORSHIP", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-PHASEA-1",
                LocalDateTime.of(2026, 8, 14, 10, 0, 0), LocalDateTime.of(2026, 8, 14, 10, 0, 0),
                365, LocalDateTime.of(2027, 8, 14, 10, 0, 0),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), "PHASEA-AU");

        // 6. adoption APPROVED（§3.2 step 3・frozen flag=trueのため4 verification必須）
        ComplianceExternalReviewAdoptionEvent approved = adoptionService.approve(
                review.getId(), identity.getId(), qual.getId(), active.getId(), authorship.getId(),
                evidenceIds()[0], evidenceIds()[1], "PHASEA-ADOPT");
        assertEquals("APPROVED", approved.getAction());

        // 7. ACTIVE化（§4-8: gate評価通過・approval event id必須）
        ComplianceMappingVersion activeVersion = complianceMappingService.transition(v.getId(), "ACTIVE", approval.getId());
        assertEquals("ACTIVE", activeVersion.getStatus());
        assertEquals(1, activeVersion.getActiveSlot());
    }
}
