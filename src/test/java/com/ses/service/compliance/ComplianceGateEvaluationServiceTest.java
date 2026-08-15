package com.ses.service.compliance;

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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R23-P1-01 §4-8/9/12・§6: gate評価のL3テスト。
 * APPROVED adoptionのみ採用（§G2-VERIFY-09）・REVOKE後/expired後はgate拒否（§4-12）・
 * mapping/policy snapshot不一致拒否・required verification欠落fail-closed。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "1", roles = "管理者")
class ComplianceGateEvaluationServiceTest {

    @Autowired
    private ComplianceMappingService complianceMappingService;
    @Autowired
    private ComplianceGateAdminService complianceGateAdminService;
    @Autowired
    private ComplianceExternalReviewVerificationService verificationService;
    @Autowired
    private ComplianceExternalReviewAdoptionService adoptionService;
    @Autowired
    private ComplianceGateEvaluationService gateEvaluationService;
    @Autowired
    private com.ses.service.compliance.ComplianceReviewerFingerprintService fingerprintService;
    @Autowired
    private com.ses.mapper.ComplianceExternalReviewerSubjectMapper subjectMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Long reviewerTypeId;
    private Long subjectId;
    private Long[] evidenceIds = new Long[2];

    private Long reviewerTypeId() {
        if (reviewerTypeId == null) {
            reviewerTypeId = complianceGateAdminService.createReviewerType(
                    "GATE_LABOR", "社労士", "gate test", "登録番号", true).getId();
        }
        return reviewerTypeId;
    }

    private Long subjectId() {
        if (subjectId == null) {
            ComplianceExternalReviewerSubject subject = new ComplianceExternalReviewerSubject();
            subject.setTenantId("default");
            subject.setSubjectCode("GATE-SUBJECT-1");
            subject.setDisplayName("gate 山田");
            subject.setOrganizationName("gate 組織");
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
                + "VALUES ('default', 900002, 1, 'ev/k', 'evidence.pdf', 'application/pdf', 10, ?, "
                + "'UPLOAD', 'gate-ev-1', '1', 'CLEAN', 1)", sha);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_document_version WHERE business_key = 'gate-ev-1'", Long.class);
        evidenceIds[0] = 900002L;
        evidenceIds[1] = versionId;
        return evidenceIds;
    }

    private ComplianceMappingVersion setupMapping(String code, String versionName) {
        ComplianceMappingVersion v = complianceMappingService.create(
                code, versionName, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                List.of(source("SRC-C"), source("SRC-E"), source("SRC-N"),
                        source("SRC-L"), source("SRC-INDEX")));
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(v.getId(), "GRP-1", "グループ1", 1);
        complianceGateAdminService.addRequirementType(group.getId(), reviewerTypeId());
        complianceMappingService.transition(v.getId(), "PROVISIONAL_REVIEWED");
        return complianceMappingService.getById(v.getId());
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

    private ComplianceExternalReviewEvent submit(ComplianceMappingVersion v, String credential) {
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.listRequirementGroups(v.getId()).get(0);
        return complianceGateAdminService.recordExternalReview(
                v.getId(), group.getId(), reviewerTypeId(),
                "gate 山田", "gate 組織", credential,
                "SUBMITTED", LocalDateTime.now(), null, null, null, null);
    }

    private ComplianceExternalReviewerVerificationEvent verify(ComplianceMappingVersion v,
                                                               ComplianceExternalReviewEvent review,
                                                               String kind, String idemKey) {
        return verify(v, review, kind, idemKey, reviewerTypeId());
    }

    private ComplianceExternalReviewerVerificationEvent verify(ComplianceMappingVersion v,
                                                               ComplianceExternalReviewEvent review,
                                                               String kind, String idemKey, Long typeId) {
        return verificationService.record(
                review.getId(), subjectId(), typeId, kind, "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-GATE-1",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), idemKey);
    }

    private ComplianceExternalReviewAdoptionEvent approveFull(ComplianceMappingVersion v,
                                                              ComplianceExternalReviewEvent review,
                                                              String idemKey) {
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY", idemKey + "-ID");
        ComplianceExternalReviewerVerificationEvent qual = verify(v, review, "QUALIFICATION", idemKey + "-Q");
        ComplianceExternalReviewerVerificationEvent active = verify(v, review, "ACTIVE_STATUS", idemKey + "-A");
        ComplianceExternalReviewerVerificationEvent authorship = verify(v, review, "REVIEW_AUTHORSHIP", idemKey + "-AU");
        return adoptionService.approve(review.getId(), identity.getId(), qual.getId(), active.getId(),
                authorship.getId(), evidenceIds()[0], evidenceIds()[1], idemKey + "-ADOPT");
    }

    @Test
    void APPROVEDadoptionはgate採用される() {
        ComplianceMappingVersion v = setupMapping("GATE-MAP1", "GATE-V1");
        ComplianceExternalReviewEvent review = submit(v, "REG-GATE-1");
        ComplianceExternalReviewAdoptionEvent approved = approveFull(v, review, "GATE-KEY1");

        ComplianceExternalReviewAdoptionEvent adopted = gateEvaluationService.adopt(
                "default", review.getReviewChainId(), v, LocalDate.now(), true, true);
        assertNotNull(adopted);
        assertEquals(approved.getId(), adopted.getId());
    }

    @Test
    void REVOKE後のadoptionはgate採用されない() {
        ComplianceMappingVersion v = setupMapping("GATE-MAP2", "GATE-V2");
        ComplianceExternalReviewEvent review = submit(v, "REG-GATE-2");
        ComplianceExternalReviewAdoptionEvent approved = approveFull(v, review, "GATE-KEY2");
        adoptionService.revoke(approved.getId(), "失効", "GATE-REVOKE2");

        assertThrows(BusinessException.class, () -> gateEvaluationService.adopt(
                "default", review.getReviewChainId(), v, LocalDate.now(), true, true));
    }

    @Test
    void 初回adoptionがREJECTEDならgate採用されない() {
        ComplianceMappingVersion v = setupMapping("GATE-MAP3", "GATE-V3");
        ComplianceExternalReviewEvent review = submit(v, "REG-GATE-3");
        adoptionService.reject(review.getId(), "証跡不備", "GATE-REJECT3");

        assertThrows(BusinessException.class, () -> gateEvaluationService.adopt(
                "default", review.getReviewChainId(), v, LocalDate.now(), true, true));
    }

    @Test
    void verificationがREVOKEDされるとgate採用されない() {
        ComplianceMappingVersion v = setupMapping("GATE-MAP4", "GATE-V4");
        ComplianceExternalReviewEvent review = submit(v, "REG-GATE-4");
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY", "GATE-K4-ID");
        ComplianceExternalReviewerVerificationEvent qual = verify(v, review, "QUALIFICATION", "GATE-K4-Q");
        ComplianceExternalReviewerVerificationEvent active = verify(v, review, "ACTIVE_STATUS", "GATE-K4-A");
        ComplianceExternalReviewerVerificationEvent authorship = verify(v, review, "REVIEW_AUTHORSHIP", "GATE-K4-AU");
        ComplianceExternalReviewAdoptionEvent approved = adoptionService.approve(
                review.getId(), identity.getId(), qual.getId(), active.getId(),
                authorship.getId(), evidenceIds()[0], evidenceIds()[1], "GATE-K4-ADOPT");
        // QUALIFICATION verificationだけrevoke（frozen flag=trueのため必須）
        verificationService.revoke(qual.getId(), "資格失効", 1L, "GATE-K4-VREV");

        assertThrows(BusinessException.class, () -> gateEvaluationService.adopt(
                "default", review.getReviewChainId(), v, LocalDate.now(), true, true));
        assertNotNull(approved);
    }

    @Test
    void mappingHashが一致しないadoptionはgate採用されない() {
        ComplianceMappingVersion v = setupMapping("GATE-MAP5", "GATE-V5");
        ComplianceExternalReviewEvent review = submit(v, "REG-GATE-5");
        approveFull(v, review, "GATE-KEY5");

        // 同一chainだが別mapping（hash不一致）を渡す
        ComplianceMappingVersion other = setupMapping("GATE-MAP5B", "GATE-V5B");
        assertThrows(BusinessException.class, () -> gateEvaluationService.adopt(
                "default", review.getReviewChainId(), other, LocalDate.now(), true, true));
    }

    @Test
    void frozenFlagがfalseのtypeはqualificationVerification不要でgate採用される() {
        ComplianceMappingVersion v = complianceMappingService.create(
                "GATE-MAP6", "GATE-V6", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                List.of(source("SRC-C"), source("SRC-E"), source("SRC-N"),
                        source("SRC-L"), source("SRC-INDEX")));
        // credentialRequired=falseのtype（qualification/activeStatus不要）
        Long optionalType = complianceGateAdminService.createReviewerType(
                "GATE_OPTIONAL", "任意資格", "gate optional", "任意", false).getId();
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(v.getId(), "GRP-1", "グループ1", 1);
        complianceGateAdminService.addRequirementType(group.getId(), optionalType);
        complianceMappingService.transition(v.getId(), "PROVISIONAL_REVIEWED");
        v = complianceMappingService.getById(v.getId());

        ComplianceExternalReviewEvent review = submitOptional(v, optionalType);
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY", "GATE-K6-ID", optionalType);
        ComplianceExternalReviewerVerificationEvent authorship = verify(v, review, "REVIEW_AUTHORSHIP", "GATE-K6-AU", optionalType);
        ComplianceExternalReviewAdoptionEvent approved = adoptionService.approve(
                review.getId(), identity.getId(), null, null, authorship.getId(),
                evidenceIds()[0], evidenceIds()[1], "GATE-K6-ADOPT");

        ComplianceExternalReviewAdoptionEvent adopted = gateEvaluationService.adopt(
                "default", review.getReviewChainId(), v, LocalDate.now(), false, false);
        assertEquals(approved.getId(), adopted.getId());
    }

    private ComplianceExternalReviewEvent submitOptional(ComplianceMappingVersion v, Long typeId) {
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.listRequirementGroups(v.getId()).get(0);
        return complianceGateAdminService.recordExternalReview(
                v.getId(), group.getId(), typeId,
                "gate 山田", "gate 組織", "OPT-1",
                "SUBMITTED", LocalDateTime.now(), null, null, null, null);
    }
}
