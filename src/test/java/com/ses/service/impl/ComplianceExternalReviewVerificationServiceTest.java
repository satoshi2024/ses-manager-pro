package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ComplianceExternalReviewEvent;
import com.ses.entity.ComplianceExternalReviewerSubject;
import com.ses.entity.ComplianceExternalReviewerVerificationEvent;
import com.ses.entity.ComplianceMappingVersion;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R23-P1-01 §3.3/§4/§6: verification serviceのL2〜L3テスト。
 * AUTHORSHIP binding（mapping/policy一致・§G2-VERIFY-12）・REVOKE後gate拒否（§4-12）・
 * idempotency replay/409（§3.6）・kind別result matrix（§3.9）。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "1", roles = "管理者")
class ComplianceExternalReviewVerificationServiceTest {

    @Autowired
    private ComplianceMappingService complianceMappingService;
    @Autowired
    private ComplianceGateAdminService complianceGateAdminService;
    @Autowired
    private ComplianceExternalReviewVerificationService verificationService;
    @Autowired
    private com.ses.service.compliance.ComplianceReviewerFingerprintService fingerprintService;
    @Autowired
    private com.ses.mapper.ComplianceExternalReviewerSubjectMapper subjectMapper;

    private Long reviewerTypeId;
    private Long subjectId;
    private Long[] evidenceIds = new Long[2];
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /** P0-5: exact CLEAN evidence versionを作成し{documentId, versionId}を返す。 */
    private Long[] evidenceIds() {
        if (evidenceIds[0] != null) {
            return evidenceIds;
        }
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        jdbcTemplate.update("INSERT INTO t_document_version "
                + "(tenant_id, document_id, version_no, storage_key, original_name, content_type, "
                + "size_bytes, sha256, source_type, business_key, version_discriminator, scan_status, created_by) "
                + "VALUES ('default', 920001, 1, 'ev/k', 'ver-ev.pdf', 'application/pdf', 10, ?, "
                + "'UPLOAD', 'verification-ev', '1', 'CLEAN', 1)", sha);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_document_version WHERE business_key = 'verification-ev'", Long.class);
        evidenceIds[0] = 920001L;
        evidenceIds[1] = versionId;
        return evidenceIds;
    }

    private Long reviewerTypeId() {
        if (reviewerTypeId == null) {
            reviewerTypeId = complianceGateAdminService.createReviewerType(
                    "VER_LABOR", "社労士", "verification test", "登録番号", true).getId();
        }
        return reviewerTypeId;
    }

    private Long subjectId() {
        if (subjectId == null) {
            ComplianceExternalReviewerSubject subject = new ComplianceExternalReviewerSubject();
            subject.setTenantId("default");
            subject.setSubjectCode("VER-SUBJECT-1");
            subject.setDisplayName("ver 山田");
            subject.setOrganizationName("ver 組織");
            subject.setPersonFingerprintSnapshot(fingerprintService.personFingerprint("default", subject));
            subject.setFingerprintKeyVersion("k1");
            subjectMapper.insert(subject);
            subjectId = subject.getId();
        }
        return subjectId;
    }

    private ComplianceMappingVersion setupMapping() {
        return setupMapping("VER-MAP", "VER-V1");
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

    private ComplianceExternalReviewEvent submit(ComplianceMappingVersion v) {
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.listRequirementGroups(v.getId()).get(0);
        return complianceGateAdminService.recordExternalReview(
                v.getId(), group.getId(), reviewerTypeId(),
                "ver 山田", "ver 組織", "REG-VER-1",
                "SUBMITTED", LocalDateTime.now(), null, null, null, null);
    }

    private ComplianceExternalReviewerVerificationEvent record(ComplianceMappingVersion v,
                                                               ComplianceExternalReviewEvent review,
                                                               String kind, String result,
                                                               String idemKey) {
        return verificationService.record(
                review.getId(), subjectId(), reviewerTypeId(), kind, result,
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-VER-1",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), idemKey);
    }

    @Test
    void AUTHORSHIPはmappingPolicy一致のbinding列をsnapshotする() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);

        ComplianceExternalReviewerVerificationEvent event =
                record(v, review, "REVIEW_AUTHORSHIP", "VERIFIED", "VER-IDEM-1");

        assertEquals("REVIEW_AUTHORSHIP", event.getVerificationKind());
        assertEquals(v.getId(), event.getMappingId());
        assertEquals(v.getMappingVersion(), event.getMappingVersion());
        assertEquals(v.getMappingHash(), event.getMappingHash());
        // §3.6: review_policy_versionの正本はmapping_version
        assertEquals(v.getMappingVersion(), event.getReviewPolicyVersion());
        assertEquals(v.getReviewPolicyHash(), event.getReviewPolicyHash());
        assertEquals(review.getId(), event.getExternalReviewEventId());
        assertEquals(review.getReviewChainId(), event.getExternalReviewChainId());
        assertEquals(subjectId(), event.getReviewerSubjectId());
        assertNotNull(event.getPersonFingerprintSnapshot());
        assertNotNull(event.getQualificationFingerprintSnapshot());
    }

    @Test
    void AUTHORSHIPはmappingHash不一致で拒否する() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);

        assertThrows(BusinessException.class, () -> verificationService.record(
                review.getId(), subjectId(), reviewerTypeId(), "REVIEW_AUTHORSHIP", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-VER-1",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), "00" + v.getMappingHash().substring(2),
                review.getId(), review.getReviewChainId(), "VER-IDEM-2"));
    }

    @Test
    void AUTHORSHIPはbinding欠落で拒否する() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);

        assertThrows(BusinessException.class, () -> verificationService.record(
                review.getId(), subjectId(), reviewerTypeId(), "REVIEW_AUTHORSHIP", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-VER-1",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], null, null, null, null, null,
                null, null, "VER-IDEM-3"));
    }

    @Test
    void 同一idempotencyKeyの同一内容は重複記録されず409になる() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);
        record(v, review, "IDENTITY", "VERIFIED", "VER-IDEM-4");

        assertThrows(BusinessException.class,
                () -> record(v, review, "IDENTITY", "VERIFIED", "VER-IDEM-4"));
    }

    @Test
    void revoke後は新規verificationで再確認可能() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);
        ComplianceExternalReviewerVerificationEvent event =
                record(v, review, "QUALIFICATION", "VERIFIED", "VER-IDEM-5");

        ComplianceExternalReviewerVerificationEvent revoked = verificationService.revoke(
                event.getId(), "失効", 1L, "VER-REVOKE-5");
        assertEquals("REVOKED", revoked.getResult());
        assertEquals(event.getId(), revoked.getRevokedVerificationEventId());

        // 新しいverification（別key）は記録可能
        ComplianceExternalReviewerVerificationEvent re =
                record(v, review, "QUALIFICATION", "VERIFIED", "VER-IDEM-6");
        assertEquals("VERIFIED", re.getResult());
    }

    @Test
    void kind別resultはCHECKmatrixの全combinationを許容する() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);
        int seq = 0;
        for (String kind : List.of("IDENTITY", "QUALIFICATION", "ACTIVE_STATUS", "REVIEW_AUTHORSHIP")) {
            for (String result : List.of("VERIFIED", "FAILED", "INCONCLUSIVE")) {
                ComplianceExternalReviewerVerificationEvent event =
                        record(v, review, kind, result, "VER-MATRIX-" + (++seq));
                assertEquals(kind, event.getVerificationKind());
                assertEquals(result, event.getResult());
            }
        }
    }

    @Test
    void IDENTITYのregistrationIdentifierは暗号化されmaskedのみ露出する() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);

        ComplianceExternalReviewerVerificationEvent event =
                record(v, review, "IDENTITY", "VERIFIED", "VER-IDEM-7");

        assertNotNull(event.getRegistrationIdentifierEncrypted());
        assertNotNull(event.getRegistrationIdentifierKeyVersion());
        assertNotNull(event.getRegistrationIdentifierCipherFormat());
        assertNotNull(event.getRegistrationIdentifierMaskedSnapshot());
        // maskedは末尾4桁のみ露出（full値を含まない）
        assertEquals("****ER-1", event.getRegistrationIdentifierMaskedSnapshot());
    }

    // ===== R23-P1-01 P0-6: cross境界拒否 =====

    @Test
    void 別chainのexternalReviewIdを渡すとcrossChain混在として拒否される() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);
        // 別のSUBMITTED review（同一mapping・別chain・別credential）
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.listRequirementGroups(v.getId()).get(0);
        ComplianceExternalReviewEvent other = complianceGateAdminService.recordExternalReview(
                v.getId(), group.getId(), reviewerTypeId(),
                "ver 山田", "ver 組織", "REG-VER-OTHER",
                "SUBMITTED", LocalDateTime.now(), null, null, null, null);
        assertNotNull(other.getId());
        assertNotEquals(review.getReviewChainId(), other.getReviewChainId());

        assertThrows(BusinessException.class, () -> verificationService.record(
                review.getId(), subjectId(), reviewerTypeId(), "IDENTITY", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-VER-1",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                other.getId(), other.getReviewChainId(), "VER-CROSS-1"));
    }

    @Test
    void 別mappingIdを渡すと拒否される() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);
        ComplianceMappingVersion other = setupMapping("VER-MAP-OTHER", "VER-V1B");

        assertThrows(BusinessException.class, () -> verificationService.record(
                review.getId(), subjectId(), reviewerTypeId(), "IDENTITY", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-VER-1",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], other.getMappingVersion(), other.getReviewPolicyHash(),
                other.getId(), other.getMappingVersion(), other.getMappingHash(),
                review.getId(), review.getReviewChainId(), "VER-CROSS-2"));
    }

    @Test
    void 別reviewerTypeを渡すとtype不一致として拒否される() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);
        Long otherType = complianceGateAdminService.createReviewerType(
                "VER_OTHER", "別資格", "verification other", "登録番号", true).getId();

        assertThrows(BusinessException.class, () -> verificationService.record(
                review.getId(), subjectId(), otherType, "IDENTITY", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-VER-1",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), "VER-CROSS-3"));
    }

    @Test
    void AUTHORSHIP以外はmaxAge未設定でfailClosed拒否される() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);

        assertThrows(BusinessException.class, () -> verificationService.record(
                review.getId(), subjectId(), reviewerTypeId(), "IDENTITY", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-VER-1",
                LocalDateTime.now(), LocalDateTime.now(), null, LocalDateTime.now().plusYears(1),
                1L, evidenceIds()[0], evidenceIds()[1], v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), "VER-MAXAGE-1"));
    }

    @Test
    void evidenceNULLはfailClosed拒否される() {
        ComplianceMappingVersion v = setupMapping();
        ComplianceExternalReviewEvent review = submit(v);

        assertThrows(BusinessException.class, () -> verificationService.record(
                review.getId(), subjectId(), reviewerTypeId(), "IDENTITY", "VERIFIED",
                "MANUAL_PUBLIC_SOURCE", "PUBLIC_REGISTRY", "公的登録",
                "https://example/registry", "REG-VER-1",
                LocalDateTime.now(), LocalDateTime.now(), 365, LocalDateTime.now().plusYears(1),
                1L, null, null, v.getMappingVersion(), v.getReviewPolicyHash(),
                v.getId(), v.getMappingVersion(), v.getMappingHash(),
                review.getId(), review.getReviewChainId(), "VER-EVID-1"));
    }
}
