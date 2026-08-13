package com.ses.service.compliance;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ComplianceExternalReviewAdoptionEvent;
import com.ses.entity.ComplianceExternalReviewerVerificationEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.mapper.ComplianceExternalReviewAdoptionEventMapper;
import com.ses.mapper.ComplianceExternalReviewerVerificationEventMapper;
import com.ses.mapper.ComplianceMappingVersionMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * R23-P1-01 §4-8/9/10: ACTIVE・future promote・formal generateで共通のgate評価service。
 *
 * <p>gate採用条件（§3.2 K3固定）:
 * <ol>
 *   <li>exact APPROVED adoption event（reducer正本=adopted_at, id）</li>
 *   <li>adoptionがREVOKEDされていない</li>
 *   <li>adoptionが参照する必要verification event（当該frozen policyが要求するverification set）がVERIFIED</li>
 *   <li>verificationがREVOKEDされていない</li>
 *   <li>verificationがasOf時点で有効（checked_at &lt;= asOf・valid_until/max_age考慮・§3.7）</li>
 *   <li>mapping/policy/evidence snapshotが完全一致</li>
 * </ol>
 *
 * <p>旧ComplianceExternalReviewEvaluator（self-declared hash・latest evidence・旧APPROVED直接採用）は
 * 本serviceのgate正本から除外される（§4-8）。distinct reviewer判定はreviewer_subject_idで行う（§G2-VERIFY-13）。
 */
@Component
public class ComplianceGateEvaluationService {

    private final ComplianceExternalReviewAdoptionEventMapper adoptionEventMapper;
    private final ComplianceExternalReviewerVerificationEventMapper verificationEventMapper;
    private final ComplianceMappingVersionMapper versionMapper;

    public ComplianceGateEvaluationService(
            ComplianceExternalReviewAdoptionEventMapper adoptionEventMapper,
            ComplianceExternalReviewerVerificationEventMapper verificationEventMapper,
            ComplianceMappingVersionMapper versionMapper) {
        this.adoptionEventMapper = adoptionEventMapper;
        this.verificationEventMapper = verificationEventMapper;
        this.versionMapper = versionMapper;
    }

    /**
     * 指定review_chain_idのAPPROVED adoptionをgate採用する。
     *
     * @return 採用されたAPPROVED adoption event
     * @throws BusinessException 採用条件を満たさない場合
     */
    public ComplianceExternalReviewAdoptionEvent adopt(String tenantId, String reviewChainId,
                                                       ComplianceMappingVersion version, LocalDate asOf,
                                                       boolean qualificationRequired, boolean activeStatusRequired) {
        if (reviewChainId == null || version == null || asOf == null) {
            throw BusinessException.of(400, "compliance.gate.invalidGateEvaluation");
        }
        List<ComplianceExternalReviewAdoptionEvent> chain =
                adoptionEventMapper.selectChain(tenantId, reviewChainId);
        if (chain.isEmpty()) {
            throw BusinessException.of(400, "compliance.gate.externalReviewIncomplete");
        }
        // reducer: adopted_at, id順の最新action
        ComplianceExternalReviewAdoptionEvent latest = chain.get(chain.size() - 1);
        if (!"APPROVED".equals(latest.getAction())) {
            throw BusinessException.of(400, "compliance.gate.externalReviewIncomplete");
        }
        // mapping/policy snapshot一致（§G2-VERIFY-12・§3.6: review_policy_version正本=mapping_version）
        if (!version.getMappingVersion().equals(latest.getMappingVersion())
                || !version.getMappingHash().equals(latest.getMappingHash())
                || !version.getMappingVersion().equals(latest.getReviewPolicyVersion())
                || !version.getReviewPolicyHash().equals(latest.getReviewPolicyHash())) {
            throw BusinessException.of(400, "compliance.gate.mappingHashMismatch");
        }
        // 必要verification（当該frozen policyが要求するset）の検証
        verifyRequired("IDENTITY", latest.getIdentityVerificationEventId(), tenantId, asOf);
        verifyRequired("REVIEW_AUTHORSHIP", latest.getAuthorshipVerificationEventId(), tenantId, asOf);
        if (qualificationRequired) {
            verifyRequired("QUALIFICATION", latest.getQualificationVerificationEventId(), tenantId, asOf);
        }
        if (activeStatusRequired) {
            verifyRequired("ACTIVE_STATUS", latest.getActiveStatusVerificationEventId(), tenantId, asOf);
        }
        return latest;
    }

    private void verifyRequired(String kind, Long verificationEventId, String tenantId, LocalDate asOf) {
        if (verificationEventId == null) {
            throw BusinessException.of(400, "compliance.gate.verificationRequired");
        }
        ComplianceExternalReviewerVerificationEvent event =
                verificationEventMapper.selectByTenantAndId(tenantId, verificationEventId);
        if (event == null || !kind.equals(event.getVerificationKind())) {
            throw BusinessException.of(400, "compliance.gate.verificationRequired");
        }
        if (!"VERIFIED".equals(event.getResult())) {
            throw BusinessException.of(400, "compliance.gate.verificationNotVerified");
        }
        // asOf有効（§3.7: checked_at <= asOf・effective expiry）
        if (event.getCheckedAt() != null && event.getCheckedAt().toLocalDate().isAfter(asOf)) {
            throw BusinessException.of(400, "compliance.gate.verificationExpired");
        }
        LocalDateTime effectiveExpiry = effectiveExpiry(event);
        if (effectiveExpiry != null && asOf.atStartOfDay().isAfter(effectiveExpiry)) {
            throw BusinessException.of(400, "compliance.gate.verificationExpired");
        }
    }

    /** §3.7: effective expiry = min(authority valid_until（存在時）, checked_at + frozen max_age) */
    private LocalDateTime effectiveExpiry(ComplianceExternalReviewerVerificationEvent event) {
        LocalDateTime authority = event.getValidUntil();
        if (event.getMaxAgeDaysSnapshot() != null && event.getCheckedAt() != null) {
            LocalDateTime byMaxAge = event.getCheckedAt().plusDays(event.getMaxAgeDaysSnapshot());
            authority = authority == null ? byMaxAge : authority.isBefore(byMaxAge) ? authority : byMaxAge;
        }
        return authority;
    }
}
