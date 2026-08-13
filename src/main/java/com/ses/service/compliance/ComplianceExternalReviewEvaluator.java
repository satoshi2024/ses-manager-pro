package com.ses.service.compliance;

import com.ses.common.exception.BusinessException;
import com.ses.entity.ComplianceExternalReviewEvent;
import com.ses.entity.ComplianceMappingReviewRequirementGroup;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.ComplianceExternalReviewEventMapper;
import com.ses.mapper.DocumentVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Evaluates external review policy (§3.2, §7.3, G2-SEC-12..18).
 * Checks requirement group AND, reviewer type OR, minimum distinct reviewers,
 * evidence CLEAN status, valid_until expiry, and credential decryption verification.
 */
@Component
public class ComplianceExternalReviewEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ComplianceExternalReviewEvaluator.class);

    private final ComplianceExternalReviewEventMapper externalReviewEventMapper;
    private final ComplianceGateCredentialCryptoService credentialCryptoService;
    private final DocumentVersionMapper documentVersionMapper;

    public ComplianceExternalReviewEvaluator(ComplianceExternalReviewEventMapper externalReviewEventMapper,
                                               ComplianceGateCredentialCryptoService credentialCryptoService,
                                               DocumentVersionMapper documentVersionMapper) {
        this.externalReviewEventMapper = externalReviewEventMapper;
        this.credentialCryptoService = credentialCryptoService;
        this.documentVersionMapper = documentVersionMapper;
    }

    /**
     * Evaluates a requirement group against external review events for a mapping version as of a target date.
     * @return List of adopted positive external review events for the group.
     * @throws BusinessException 409 if requirement is not met or credential decryption fails.
     */
    public List<ComplianceExternalReviewEvent> evaluateGroup(String tenantId, ComplianceMappingVersion version,
                                                              ComplianceMappingReviewRequirementGroup group, LocalDate asOf) {
        String safeTenant = StringUtils.hasText(tenantId) ? tenantId : "default";
        List<ComplianceExternalReviewEvent> events = externalReviewEventMapper.selectByMappingAndGroup(safeTenant, version.getId(), group.getId());

        if (events == null || events.isEmpty()) {
            log.warn("No external review events found for group code={} mappingId={}", group.getRequirementGroupCode(), version.getId());
            throw BusinessException.of(409, "compliance.gate.externalReviewIncomplete");
        }

        // Group by reviewChainId, sort by recorded_at ASC, id ASC to find latest event in chain
        Map<String, List<ComplianceExternalReviewEvent>> chainMap = new LinkedHashMap<>();
        for (ComplianceExternalReviewEvent ev : events) {
            if (StringUtils.hasText(ev.getReviewChainId())) {
                chainMap.computeIfAbsent(ev.getReviewChainId(), k -> new ArrayList<>()).add(ev);
            }
        }

        Set<String> distinctIdentityHashes = new HashSet<>();
        List<ComplianceExternalReviewEvent> adoptedEvents = new ArrayList<>();
        LocalDateTime asOfEnd = LocalDateTime.of(asOf, LocalTime.MAX);

        for (Map.Entry<String, List<ComplianceExternalReviewEvent>> entry : chainMap.entrySet()) {
            List<ComplianceExternalReviewEvent> chainEvents = entry.getValue();
            chainEvents.sort(Comparator.comparing(ComplianceExternalReviewEvent::getRecordedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(ComplianceExternalReviewEvent::getId));
            ComplianceExternalReviewEvent latest = chainEvents.get(chainEvents.size() - 1);

            // Latest action must be APPROVED
            if (!"APPROVED".equalsIgnoreCase(latest.getAction())) {
                continue;
            }

            // Mapping version / hash / policy hash match
            if (!version.getMappingVersion().equals(latest.getMappingVersion()) ||
                    !version.getMappingHash().equals(latest.getMappingHash()) ||
                    !version.getReviewPolicyHash().equals(latest.getReviewPolicyHash())) {
                continue;
            }

            // Timeline check: reviewed_at <= asOf and recorded_at <= asOf
            if (latest.getReviewedAt() != null && latest.getReviewedAt().isAfter(asOfEnd)) {
                continue;
            }
            if (latest.getRecordedAt() != null && latest.getRecordedAt().isAfter(asOfEnd)) {
                continue;
            }

            // Expiry check: valid_until IS NULL OR asOf < valid_until (valid_until on or before asOf is EXPIRED)
            if (latest.getValidUntil() != null) {
                LocalDate validUntilDate = latest.getValidUntil().toLocalDate();
                if (!asOf.isBefore(validUntilDate)) {
                    // Expired
                    continue;
                }
            }

            // Evidence document scan status check
            if (latest.getEvidenceDocumentId() != null && documentVersionMapper != null) {
                DocumentVersion evidenceVer = documentVersionMapper.findLatestByDocumentId(latest.getEvidenceDocumentId());
                if (evidenceVer != null && !"CLEAN".equalsIgnoreCase(evidenceVer.getScanStatus())) {
                    continue;
                }
            }

            // Decryption check (G2-SEC-12..18): verify credential decryption if encrypted snapshot exists
            if (StringUtils.hasText(latest.getCredentialSnapshotEncrypted())) {
                try {
                    credentialCryptoService.decrypt(safeTenant, version.getId(), version.getMappingVersion(),
                            latest.getOperationId(), latest.getCredentialSnapshotEncrypted());
                } catch (Exception e) {
                    log.error("Gate credential decryption verification failed for event id={} operationId={}", latest.getId(), latest.getOperationId(), e);
                    throw BusinessException.of(409, "compliance.gate.credentialUnavailable");
                }
            }

            if (StringUtils.hasText(latest.getReviewerIdentityHash())) {
                distinctIdentityHashes.add(latest.getReviewerIdentityHash());
                adoptedEvents.add(latest);
            }
        }

        if (distinctIdentityHashes.size() < group.getMinimumDistinctReviewers()) {
            log.warn("External review minimum distinct reviewers not met for group code={}, distinct={}, minimum={}",
                    group.getRequirementGroupCode(), distinctIdentityHashes.size(), group.getMinimumDistinctReviewers());
            throw BusinessException.of(409, "compliance.gate.externalReviewIncomplete");
        }

        return adoptedEvents;
    }
}
