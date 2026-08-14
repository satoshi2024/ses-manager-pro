package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ComplianceExternalReviewAdoptionEvent;
import com.ses.entity.ComplianceExternalReviewEvent;
import com.ses.entity.ComplianceExternalReviewerVerificationEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.service.ComplianceExternalReviewAdoptionService;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R23-P1-01 §3.4/§4: adoption serviceのL2テスト。
 * gateはAPPROVED adoptionのみ採用・REJECTED/REVOKEDは不採用・初回adoption限定・
 * REVOKEはAPPROVEDのみtarget・idempotency契約（§3.6）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "1", roles = "管理者")
class ComplianceExternalReviewAdoptionServiceTest {

    @Autowired
    private ComplianceMappingService complianceMappingService;
    @Autowired
    private ComplianceGateAdminService complianceGateAdminService;
    @Autowired
    private com.ses.service.ComplianceExternalReviewVerificationService verificationService;
    @Autowired
    private ComplianceExternalReviewAdoptionService adoptionService;
    @Autowired
    private com.ses.service.compliance.ComplianceReviewerFingerprintService fingerprintService;
    @Autowired
    private com.ses.mapper.ComplianceExternalReviewerSubjectMapper subjectMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private Long insertedReviewerTypeId;
    private Long insertedSubjectId;
    private Long[] insertedEvidenceIds = new Long[2];

    /** exact CLEAN evidence（document_id, version_id）を作成する（§4-5/6: server-side解決）。 */
    private Long[] evidenceIds() {
        if (insertedEvidenceIds[0] != null) {
            return insertedEvidenceIds;
        }
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        jdbcTemplate.update("INSERT INTO t_document_version "
                + "(tenant_id, document_id, version_no, storage_key, original_name, content_type, "
                + "size_bytes, sha256, source_type, business_key, version_discriminator, scan_status, created_by) "
                + "VALUES ('default', 900001, 1, 'ev/k', 'evidence.pdf', 'application/pdf', 10, ?, "
                + "'UPLOAD', 'adopt-ev-1', '1', 'CLEAN', 1)", sha);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_document_version WHERE business_key = 'adopt-ev-1'", Long.class);
        insertedEvidenceIds[0] = 900001L;
        insertedEvidenceIds[1] = versionId;
        return insertedEvidenceIds;
    }

    private Long reviewerTypeId() {
        if (insertedReviewerTypeId != null) {
            return insertedReviewerTypeId;
        }
        insertedReviewerTypeId = complianceGateAdminService.createReviewerType(
                "ADOPT_LABOR", "社労士", "adoption test", "登録番号", true).getId();
        return insertedReviewerTypeId;
    }

    private Long subjectId() {
        if (insertedSubjectId != null) {
            return insertedSubjectId;
        }
        insertedSubjectId = insertSubject();
        return insertedSubjectId;
    }

    private Long insertSubject() {
        // subjectはmapper経由で直接INSERTする（R23-P1-01 §9: person-stable正本）
        return insertSubjectRow("ADOPT-SUBJECT-1", "adopt 山田", "adopt 組織");
    }

    private Long insertSubjectRow(String code, String name, String org) {
        com.ses.entity.ComplianceExternalReviewerSubject subject =
                new com.ses.entity.ComplianceExternalReviewerSubject();
        subject.setTenantId("default");
        subject.setSubjectCode(code);
        subject.setDisplayName(name);
        subject.setOrganizationName(org);
        subject.setPersonFingerprintSnapshot(fingerprintService.personFingerprint("default",
                subject));
        subject.setFingerprintKeyVersion("k1");
        subjectMapper.insert(subject);
        return subject.getId();
    }

    private ComplianceMappingVersion setupMappingWithPolicy(String code, String versionName) {
        ComplianceMappingVersion v = complianceMappingService.create(
                code, versionName, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                List.of(source("SRC-C", "https://example/src-c", "2026-07"),
                        source("SRC-E", "https://example/src-e", "2026-07"),
                        source("SRC-N", "https://example/src-n", "2026-07"),
                        source("SRC-L", "https://example/src-l", "2026-07"),
                        source("SRC-INDEX", "https://example/index", "2026-07")));
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(v.getId(), "GRP-1", "グループ1", 1);
        complianceGateAdminService.addRequirementType(group.getId(), reviewerTypeId());
        complianceMappingService.transition(v.getId(), "PROVISIONAL_REVIEWED");
        return complianceMappingService.getById(v.getId());
    }

    private com.ses.dto.compliance.ComplianceMappingSourceInput source(String code, String url, String version) {
        com.ses.dto.compliance.ComplianceMappingSourceInput input =
                new com.ses.dto.compliance.ComplianceMappingSourceInput();
        input.setSourceCode(code);
        input.setSourceUrl(url);
        input.setSourceVersion(version);
        input.setConfirmedOn(LocalDate.of(2026, 8, 9));
        input.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        input.setEffectiveTo(LocalDate.of(2026, 9, 30));
        return input;
    }

    private ComplianceExternalReviewEvent submitReview(ComplianceMappingVersion v) {
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.listRequirementGroups(v.getId()).get(0);
        return complianceGateAdminService.recordExternalReview(
                v.getId(), group.getId(), reviewerTypeId(),
                "adopt 山田", "adopt 組織", "REG-12345",
                "SUBMITTED", LocalDateTime.now(), null, null, null, null);
    }

    /** 別credential番号のSUBMITTED（同一chainの再Reviewではなく新規chain・§3.2 K2）。 */
    private ComplianceExternalReviewEvent submitReviewWithCredential(ComplianceMappingVersion v, String credential) {
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.listRequirementGroups(v.getId()).get(0);
        return complianceGateAdminService.recordExternalReview(
                v.getId(), group.getId(), reviewerTypeId(),
                "adopt 山田", "adopt 組織", credential,
                "SUBMITTED", LocalDateTime.now(), null, null, null, null);
    }

    private ComplianceExternalReviewerVerificationEvent verify(ComplianceMappingVersion v,
                                                               ComplianceExternalReviewEvent review,
                                                               String kind) {
        return verificationService.record(
                review.getId(), subjectId(), reviewerTypeId(), kind, "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-12345",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), "ADOPT-VERIFY-" + kind + "-" + System.nanoTime());
    }

    @Test
    void approveはfrozenPolicyのverificationSetを検証してAPPROVEDadoptionを記録する() {
        ComplianceMappingVersion v = setupMappingWithPolicy("ADOPT-MAP", "ADOPT-V1");
        ComplianceExternalReviewEvent review = submitReview(v);
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY");
        ComplianceExternalReviewerVerificationEvent qual = verify(v, review, "QUALIFICATION");
        ComplianceExternalReviewerVerificationEvent active = verify(v, review, "ACTIVE_STATUS");
        ComplianceExternalReviewerVerificationEvent authorship = verify(v, review, "REVIEW_AUTHORSHIP");

        ComplianceExternalReviewAdoptionEvent approved = adoptionService.approve(
                review.getId(), identity.getId(), qual.getId(), active.getId(),
                authorship.getId(), evidenceIds()[0], evidenceIds()[1], "ADOPT-KEY-1");

        assertEquals("APPROVED", approved.getAction());
        assertEquals(review.getReviewChainId(), approved.getReviewChainId());
        assertEquals(v.getMappingHash(), approved.getMappingHash());
        assertEquals(v.getReviewPolicyHash(), approved.getReviewPolicyHash());
        assertNotNull(approved.getAdoptedAt());
    }

    @Test
    void approveはidentityまたはauthorshipVerification欠落で拒否する() {
        ComplianceMappingVersion v = setupMappingWithPolicy("ADOPT-MAP2", "ADOPT-V2");
        ComplianceExternalReviewEvent review = submitReview(v);
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY");

        assertThrows(BusinessException.class, () -> adoptionService.approve(
                review.getId(), identity.getId(), null, null, null,
                null, null, "ADOPT-KEY-2"));
    }

    @Test
    void 同一submittedReviewの2回目のapproveは409で拒否する() {
        ComplianceMappingVersion v = setupMappingWithPolicy("ADOPT-MAP3", "ADOPT-V3");
        ComplianceExternalReviewEvent review = submitReview(v);
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY");
        ComplianceExternalReviewerVerificationEvent qual = verify(v, review, "QUALIFICATION");
        ComplianceExternalReviewerVerificationEvent active = verify(v, review, "ACTIVE_STATUS");
        ComplianceExternalReviewerVerificationEvent authorship = verify(v, review, "REVIEW_AUTHORSHIP");

        adoptionService.approve(review.getId(), identity.getId(), qual.getId(), active.getId(),
                authorship.getId(), evidenceIds()[0], evidenceIds()[1], "ADOPT-KEY-3");

        BusinessException error = assertThrows(BusinessException.class, () -> adoptionService.approve(
                review.getId(), identity.getId(), qual.getId(), active.getId(),
                authorship.getId(), evidenceIds()[0], evidenceIds()[1], "ADOPT-KEY-3b"));
        assertEquals(409, error.getCode());
    }

    @Test
    void reject後にapproveは409で拒否しrejectは新しいchainで可能() {
        ComplianceMappingVersion v = setupMappingWithPolicy("ADOPT-MAP4", "ADOPT-V4");
        ComplianceExternalReviewEvent review = submitReview(v);
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY");
        ComplianceExternalReviewerVerificationEvent qual = verify(v, review, "QUALIFICATION");
        ComplianceExternalReviewerVerificationEvent active = verify(v, review, "ACTIVE_STATUS");
        ComplianceExternalReviewerVerificationEvent authorship = verify(v, review, "REVIEW_AUTHORSHIP");

        ComplianceExternalReviewAdoptionEvent rejected = adoptionService.reject(
                review.getId(), "証跡不備", "ADOPT-REJECT-1");
        assertEquals("REJECTED", rejected.getAction());

        // 初回adoption（REJECTED）後は同一chainのapproveを409で拒否（§3.2: 1 chainに初回adoption 1件）
        BusinessException error = assertThrows(BusinessException.class,
                () -> adoptionService.approve(review.getId(), identity.getId(), qual.getId(),
                        active.getId(), authorship.getId(),
                        evidenceIds()[0], evidenceIds()[1], "ADOPT-KEY-4"));
        assertEquals(409, error.getCode());
    }

    @Test
    void revokeはAPPROVEDのみtargetにできREJECTEDは拒否する() {
        ComplianceMappingVersion v = setupMappingWithPolicy("ADOPT-MAP5", "ADOPT-V5");
        ComplianceExternalReviewEvent review = submitReview(v);
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY");
        ComplianceExternalReviewerVerificationEvent qual = verify(v, review, "QUALIFICATION");
        ComplianceExternalReviewerVerificationEvent active = verify(v, review, "ACTIVE_STATUS");
        ComplianceExternalReviewerVerificationEvent authorship = verify(v, review, "REVIEW_AUTHORSHIP");

        ComplianceExternalReviewAdoptionEvent approved = adoptionService.approve(
                review.getId(), identity.getId(), qual.getId(), active.getId(),
                authorship.getId(), evidenceIds()[0], evidenceIds()[1], "ADOPT-KEY-5");
        ComplianceExternalReviewAdoptionEvent revoked = adoptionService.revoke(
                approved.getId(), "資格失効", "ADOPT-REVOKE-5");
        assertEquals("REVOKED", revoked.getAction());
        assertEquals(approved.getId(), revoked.getRevokedAdoptionEventId());

        // REJECTEDをtargetにするrevokeは拒否（新しいSUBMITTED chain・別credential）
        ComplianceExternalReviewEvent review2 = submitReviewWithCredential(v, "REG-99999");
        ComplianceExternalReviewAdoptionEvent rejected = adoptionService.reject(
                review2.getId(), "再審査", "ADOPT-REJECT-5");
        assertThrows(BusinessException.class,
                () -> adoptionService.revoke(rejected.getId(), "不正", "ADOPT-REVOKE-5b"));
    }

    @Test
    void listBySubmittedReviewはadoptedAt順に全Eventを返す() {
        ComplianceMappingVersion v = setupMappingWithPolicy("ADOPT-MAP6", "ADOPT-V6");
        ComplianceExternalReviewEvent review = submitReview(v);
        ComplianceExternalReviewerVerificationEvent identity = verify(v, review, "IDENTITY");
        ComplianceExternalReviewerVerificationEvent qual = verify(v, review, "QUALIFICATION");
        ComplianceExternalReviewerVerificationEvent active = verify(v, review, "ACTIVE_STATUS");
        ComplianceExternalReviewerVerificationEvent authorship = verify(v, review, "REVIEW_AUTHORSHIP");
        ComplianceExternalReviewAdoptionEvent approved = adoptionService.approve(
                review.getId(), identity.getId(), qual.getId(), active.getId(),
                authorship.getId(), evidenceIds()[0], evidenceIds()[1], "ADOPT-KEY-6");
        adoptionService.revoke(approved.getId(), "失効", "ADOPT-REVOKE-6");

        List<ComplianceExternalReviewAdoptionEvent> chain =
                adoptionService.listBySubmittedReview(review.getId());
        assertEquals(2, chain.size());
        assertEquals("APPROVED", chain.get(0).getAction());
        assertEquals("REVOKED", chain.get(1).getAction());
    }
}
