package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.dto.compliance.ComplianceDocumentDeliveryDto;
import com.ses.dto.compliance.ComplianceDocumentGenerateRequest;
import com.ses.entity.ComplianceFinding;
import com.ses.entity.Contract;
import com.ses.entity.ContractComplianceProfile;
import com.ses.entity.ContractComplianceSnapshot;
import com.ses.entity.CustomerContact;
import com.ses.entity.Document;
import com.ses.entity.DocumentDelivery;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.ComplianceFindingMapper;
import com.ses.mapper.ContractComplianceProfileMapper;
import com.ses.mapper.ContractComplianceSnapshotMapper;
import com.ses.mapper.ContractComplianceWorkerSnapshotMapper;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.DocumentAccessLogMapper;
import com.ses.mapper.DocumentDeliveryMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.ComplianceDocumentService;
import com.ses.service.ContractService;
import com.ses.service.DocumentService;
import com.ses.service.MenuCacheService;
import com.ses.service.SystemConfigService;
import com.ses.service.compliance.ComplianceAccessControl;
import com.ses.service.compliance.ComplianceDocumentGenerator;
import com.ses.service.compliance.ComplianceSnapshotWriter;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T064 B1: 法定帳票の生成・交付・受領確認・ダウンロード。
 *  - 生成: profile → snapshot（ComplianceSnapshotWriter、append-only＋CAS）→ 帳票PDF
 *    → DocumentService.registerGenerated（document archive）→ t_document_delivery記録。
 *  - 冪等: (contract_id, document_type, template_version, snapshot_hash)の既存交付行があれば再生成しない。
 *  - mask: PDFは生成roleのmaskLevel（管理者/HR=FULL、マネージャー=MASK）を適用（R4.2）。
 *  - 営業は生成・確認・ダウンロード不可（403、fail-closed）。一覧はcompliance権限ロールのみ。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ComplianceDocumentServiceImpl implements ComplianceDocumentService {

    private static final Set<String> DELIVERY_METHODS = Set.of("EMAIL", "PORTAL", "PAPER", "OTHER");

    private final ContractService contractService;
    private final ContractComplianceProfileMapper profileMapper;
    private final ContractComplianceSnapshotMapper snapshotMapper;
    private final ContractComplianceWorkerSnapshotMapper workerSnapshotMapper;
    private final ComplianceSnapshotWriter snapshotWriter;
    private final ComplianceDocumentGenerator documentGenerator;
    private final DocumentService documentService;
    private final DocumentDeliveryMapper deliveryMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentAccessLogMapper documentAccessLogMapper;
    private final CustomerContactMapper customerContactMapper;
    private final EngineerMapper engineerMapper;
    private final com.ses.mapper.CustomerMapper customerMapper;
    private final com.ses.mapper.ComplianceMappingVersionMapper mappingVersionMapper;
    private final com.ses.mapper.ComplianceResponsibleAssignmentMapper assignmentMapper;
    private final com.ses.mapper.ComplianceMappingApprovalEventMapper approvalEventMapper;
    private final com.ses.mapper.ComplianceMappingSourceMapper mappingSourceMapper;
    private final com.ses.mapper.ComplianceMappingReviewRequirementGroupMapper requirementGroupMapper;
    private final com.ses.mapper.ComplianceMappingReviewRequirementTypeMapper requirementTypeMapper;
    private final com.ses.service.compliance.ComplianceMappingCanonicalizer canonicalizer;
    private final com.ses.service.storage.DocumentStorage documentStorage;
    private final com.ses.mapper.SysUserMapper sysUserMapper;
    private final ComplianceFindingMapper findingMapper;
    private final SystemConfigService systemConfigService;
    private final DataScopeService dataScopeService;
    private final com.ses.service.compliance.ComplianceExternalReviewEvaluator externalReviewEvaluator;
    private final ObjectProvider<MenuCacheService> menuCacheServiceProvider;
    private final MessageSource messageSource;

    @Override
    public List<ComplianceDocumentDeliveryDto> list(Long contractId) {
        Contract contract = requireVisibleContract(contractId);
        requireAnyContractRole();
        return deliveryMapper.selectList(new LambdaQueryWrapper<DocumentDelivery>()
                        .eq(DocumentDelivery::getContractId, contractId)
                        .orderByDesc(DocumentDelivery::getId))
                .stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public ComplianceDocumentDeliveryDto generate(Long contractId, ComplianceDocumentGenerateRequest request) {
        Contract contract = requireVisibleContract(contractId);
        requireComplianceAccess();
        requireWritable();
        if (request == null || !ComplianceDocumentGenerator.DOCUMENT_TYPES.contains(request.getDocumentType())) {
            throw BusinessException.of(400, "contract.compliance.invalidDocumentType");
        }
        if (request.getDeliveryMethod() == null || !DELIVERY_METHODS.contains(request.getDeliveryMethod())) {
            throw BusinessException.of(400, "contract.compliance.invalidDeliveryMethod");
        }
        int templateVersion = templateVersion(request.getDocumentType());

        ContractComplianceProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<ContractComplianceProfile>()
                        .eq(ContractComplianceProfile::getContractId, contractId));
        if (profile == null || profile.getWorkplaceId() == null) {
            throw BusinessException.of(400, "contract.compliance.profileRequired");
        }
        Long workplaceId = profile.getWorkplaceId();
        ContractComplianceSnapshot snapshot = snapshotWriter.ensureSnapshot(contract, profile);

        LocalDateTime deliveredAt = LocalDateTime.now().withNano(0);
        LocalDate asOf = resolveAsOf();

        // 1. ACTIVE mapping version gate evaluation (§5, §6.4, R6.4, R8.1, S4-1)
        com.ses.entity.ComplianceMappingVersion activeMapping = mappingVersionMapper != null ? mappingVersionMapper.selectOne(
                new LambdaQueryWrapper<com.ses.entity.ComplianceMappingVersion>()
                        .eq(com.ses.entity.ComplianceMappingVersion::getTenantId, "default")
                        .eq(com.ses.entity.ComplianceMappingVersion::getMappingCode, "G2-MAPPING")
                        .eq(com.ses.entity.ComplianceMappingVersion::getStatus, "ACTIVE")
                        .eq(com.ses.entity.ComplianceMappingVersion::getActiveSlot, 1)
                        .last("LIMIT 1")) : null;
        if (activeMapping == null) {
            throw BusinessException.of(409, "compliance.gate.invalidTransition");
        }
        if ((activeMapping.getEffectiveFrom() != null && asOf.isBefore(activeMapping.getEffectiveFrom()))
                || (activeMapping.getEffectiveTo() != null && asOf.isAfter(activeMapping.getEffectiveTo()))) {
            throw BusinessException.of(409, "compliance.gate.invalidTransition");
        }

        // 2. Active responsible assignment for target workplace
        com.ses.entity.ComplianceResponsibleAssignment activeAssignment = assignmentMapper != null ? assignmentMapper.selectOne(
                new LambdaQueryWrapper<com.ses.entity.ComplianceResponsibleAssignment>()
                        .eq(com.ses.entity.ComplianceResponsibleAssignment::getTenantId, "default")
                        .eq(com.ses.entity.ComplianceResponsibleAssignment::getWorkplaceId, workplaceId)
                        .eq(com.ses.entity.ComplianceResponsibleAssignment::getActiveSlot, 1)
                        .last("LIMIT 1")) : null;
        if (activeAssignment == null) {
            throw BusinessException.of(409, "compliance.gate.invalidTransition");
        }
        LocalDate asgFrom = activeAssignment.getEffectiveFrom() == null ? null : activeAssignment.getEffectiveFrom().toLocalDate();
        LocalDate asgTo = activeAssignment.getEffectiveTo() == null ? null : activeAssignment.getEffectiveTo().toLocalDate();
        if ((asgFrom != null && asOf.isBefore(asgFrom)) || (asgTo != null && asOf.isAfter(asgTo))) {
            throw BusinessException.of(409, "compliance.gate.invalidTransition");
        }

        // 3. Valid unrevoked approval event (action = "APPROVE", countSubsequentRevokes == 0)
        com.ses.entity.ComplianceMappingApprovalEvent approvalEvent = null;
        if (approvalEventMapper != null) {
            List<com.ses.entity.ComplianceMappingApprovalEvent> approvals = approvalEventMapper.selectByMapping("default", activeMapping.getId(), "APPROVE");
            if (approvals != null) {
                for (com.ses.entity.ComplianceMappingApprovalEvent app : approvals) {
                    if (workplaceId.equals(app.getWorkplaceIdSnapshot())) {
                        long revokes = approvalEventMapper.countSubsequentRevokes("default", activeMapping.getId(), app.getId());
                        if (revokes == 0) {
                            approvalEvent = app;
                        }
                    }
                }
            }
        }
        if (approvalEvent == null) {
            throw BusinessException.of(409, "compliance.gate.approvalRevoked");
        }

        // 4. Re-verify DB mapping_hash and review_policy_hash match
        if (canonicalizer != null) {
            List<com.ses.entity.ComplianceMappingSource> sources = mappingSourceMapper != null ? mappingSourceMapper.selectList(
                    new LambdaQueryWrapper<com.ses.entity.ComplianceMappingSource>()
                            .eq(com.ses.entity.ComplianceMappingSource::getMappingId, activeMapping.getId())) : List.of();
            String recomputedMappingHash = canonicalizer.computeMappingHash(activeMapping, sources);
            if (!recomputedMappingHash.equals(activeMapping.getMappingHash())) {
                throw BusinessException.of(409, "compliance.gate.policyHashMismatch");
            }

            List<com.ses.entity.ComplianceMappingReviewRequirementGroup> groups = requirementGroupMapper != null ? requirementGroupMapper.selectList(
                    new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                            .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, "default")
                            .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getMappingId, activeMapping.getId())) : List.of();
            List<Long> groupIds = groups.stream().map(com.ses.entity.ComplianceMappingReviewRequirementGroup::getId).filter(java.util.Objects::nonNull).toList();
            List<com.ses.entity.ComplianceMappingReviewRequirementType> types = (!groupIds.isEmpty() && requirementTypeMapper != null) ? requirementTypeMapper.selectList(
                    new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                            .in(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, groupIds)) : List.of();
            String recomputedPolicyHash = canonicalizer.computeReviewPolicyHash(groups, types);
            if (!recomputedPolicyHash.equals(activeMapping.getReviewPolicyHash())) {
                throw BusinessException.of(409, "compliance.gate.policyHashMismatch");
            }
        }

        String engineerName = contract.getEngineerId() == null ? null
                : (engineerMapper.selectById(contract.getEngineerId()) == null ? null
                : engineerMapper.selectById(contract.getEngineerId()).getFullName());
        com.ses.entity.ContractComplianceWorkerSnapshot workerSnapshot = workerSnapshot(contract, deliveredAt);

        Long mappingVersionId = activeMapping.getId();
        String mappingVersionStr = activeMapping.getMappingVersion();
        String mappingHashStr = activeMapping.getMappingHash();
        String reviewPolicyHashStr = activeMapping.getReviewPolicyHash();

        String recipientHash = recipientHash(request.getRecipientContactId(), contract);
        String configHash = companyConfigHash();
        String fieldMaskHash = sha256Hex(maskLevel());

        String gateSnapshotHashStr = computeGateSnapshotHash(
                activeMapping, deliveredAt.toLocalDate(),
                workplaceId, activeAssignment, approvalEvent, deliveredAt);

        String renderInputHashStr = computeRenderInputHash(
                snapshot.getId(), snapshot.getSnapshotHash(),
                workerSnapshot == null ? null : workerSnapshot.getId(),
                workerSnapshot == null ? null : workerSnapshot.getSnapshotHash(),
                workplaceId, recipientHash, configHash,
                request.getDocumentType(), templateVersion, fieldMaskHash, deliveredAt);

        String businessKey = computeDeliveryBusinessKey(contractId, request.getDocumentType(), templateVersion,
                snapshot.getId(), snapshot.getSnapshotHash(),
                workerSnapshot == null ? null : workerSnapshot.getId(),
                workerSnapshot == null ? null : workerSnapshot.getSnapshotHash(),
                workplaceId, recipientHash, configHash,
                mappingVersionId, mappingVersionStr, mappingHashStr, reviewPolicyHashStr,
                approvalEvent.getId(), fieldMaskHash);

        String legacyIdempotencyKey = idempotencyKey(contractId, request.getDocumentType(), templateVersion,
                snapshot.getSnapshotHash());

        // Check if delivery already exists for businessKey or legacy idempotencyKey (R8.4, S4-6, P2-N-3)
        DocumentDelivery existing = deliveryMapper.selectOne(
                new LambdaQueryWrapper<DocumentDelivery>()
                        .eq(DocumentDelivery::getDeliveryBusinessKey, businessKey)
                        .or(w -> w.isNull(DocumentDelivery::getDeliveryBusinessKey)
                                .eq(DocumentDelivery::getIdempotencyKey, legacyIdempotencyKey))
                        .last("LIMIT 1"));
        if (existing != null && "READY".equals(existing.getGenerationState())) {
            return toDto(existing);
        }

        // 3 Renditions generation: FULL, MASK, LIMITED sharing single rendition_group_id (§9.1)
        String renditionGroupId = java.util.UUID.randomUUID().toString();

        byte[] fullPdf = documentGenerator.toPdf(
                documentGenerator.build(contract, snapshot, request.getDocumentType(), "FULL", engineerName, workerSnapshot),
                messageSource);
        String fullSha256 = sha256HexBytes(fullPdf);
        Document fullDoc = registerRendition(contract, request.getDocumentType(), templateVersion, snapshot.getSnapshotHash(), "FULL", fullPdf);

        byte[] maskPdf = documentGenerator.toPdf(
                documentGenerator.build(contract, snapshot, request.getDocumentType(), "MASK", engineerName, workerSnapshot),
                messageSource);
        String maskSha256 = sha256HexBytes(maskPdf);
        Document maskDoc = registerRendition(contract, request.getDocumentType(), templateVersion, snapshot.getSnapshotHash(), "MASK", maskPdf);

        byte[] limitedPdf = documentGenerator.toPdf(
                documentGenerator.build(contract, snapshot, request.getDocumentType(), "LIMITED", engineerName, workerSnapshot),
                messageSource);
        String limitedSha256 = sha256HexBytes(limitedPdf);
        Document limitedDoc = registerRendition(contract, request.getDocumentType(), templateVersion, snapshot.getSnapshotHash(), "LIMITED", limitedPdf);

        DocumentVersion fullVersion = documentVersionMapper.findLatestByDocumentId(fullDoc.getId());
        DocumentVersion maskVersion = documentVersionMapper.findLatestByDocumentId(maskDoc.getId());
        DocumentVersion limitedVersion = documentVersionMapper.findLatestByDocumentId(limitedDoc.getId());

        DocumentDelivery delivery = new DocumentDelivery();
        delivery.setTenantId("default");
        delivery.setContractId(contractId);
        delivery.setDocumentId(fullDoc.getId());
        delivery.setDocumentType(request.getDocumentType());
        delivery.setTemplateVersion(String.valueOf(templateVersion));
        delivery.setEffectiveFrom(snapshot.getDispatchFrom());
        delivery.setEffectiveTo(snapshot.getDispatchTo());
        delivery.setSnapshotHash(snapshot.getSnapshotHash());
        fillRecipient(delivery, request.getRecipientContactId(), contract);
        delivery.setDeliveryMethod(request.getDeliveryMethod());
        delivery.setDeliveryStatus("DELIVERED");
        delivery.setDeliveredAt(deliveredAt);
        // 新規deliveryはbusinessKeyをidempotency_keyとして使用（P2-N-3: legacyKeyはdelivery_business_key IS NULLの旧行の
        // フォールバック照合専用。legacyKeyをそのまま保存するとUNIQUE(tenant_id, idempotency_key)が別businessKeyと衝突する）
        delivery.setIdempotencyKey(businessKey);

        delivery.setMappingVersionId(mappingVersionId);
        delivery.setMappingVersion(mappingVersionStr);
        delivery.setMappingHash(mappingHashStr);
        delivery.setReviewPolicyHash(reviewPolicyHashStr);
        delivery.setGateEvaluatedAt(deliveredAt);
        delivery.setGateSnapshotHash(gateSnapshotHashStr);
        delivery.setProfileSnapshotId(snapshot.getId());
        delivery.setProfileSnapshotHash(snapshot.getSnapshotHash());
        delivery.setWorkerSnapshotId(workerSnapshot == null ? null : workerSnapshot.getId());
        delivery.setWorkerSnapshotHash(workerSnapshot == null ? null : workerSnapshot.getSnapshotHash());
        delivery.setWorkplaceId(workplaceId);
        delivery.setRenderInputHash(renderInputHashStr);
        delivery.setRecipientDisplaySnapshotHash(recipientHash);
        delivery.setCompanyConfigSnapshotHash(configHash);
        delivery.setFieldMaskPolicyHash(fieldMaskHash);
        delivery.setRenderEngineVersion("1.0.0");
        delivery.setRenditionGroupId(renditionGroupId);
        delivery.setFullDocumentVersionId(fullVersion != null ? fullVersion.getId() : null);
        delivery.setFullDocumentSha256(fullVersion != null ? fullVersion.getSha256() : fullSha256);
        delivery.setMaskDocumentVersionId(maskVersion != null ? maskVersion.getId() : null);
        delivery.setMaskDocumentSha256(maskVersion != null ? maskVersion.getSha256() : maskSha256);
        delivery.setLimitedDocumentVersionId(limitedVersion != null ? limitedVersion.getId() : null);
        delivery.setLimitedDocumentSha256(limitedVersion != null ? limitedVersion.getSha256() : limitedSha256);
        delivery.setDeliveryBusinessKey(businessKey);
        delivery.setGenerationState("READY");

        try {
            deliveryMapper.insert(delivery);
        } catch (DuplicateKeyException e) {
            DocumentDelivery concurrent = deliveryMapper.selectOne(
                    new LambdaQueryWrapper<DocumentDelivery>()
                            .eq(DocumentDelivery::getDeliveryBusinessKey, businessKey)
                            .last("LIMIT 1"));
            if (concurrent != null) {
                return toDto(concurrent);
            }
            throw e;
        }
        return toDto(delivery);
    }

    @Override
    public byte[] preview(Long contractId, ComplianceDocumentGenerateRequest request) {
        Contract contract = requireVisibleContract(contractId);
        requireComplianceAccess();
        requireWritable();
        if (request == null || !ComplianceDocumentGenerator.DOCUMENT_TYPES.contains(request.getDocumentType())) {
            throw BusinessException.of(400, "contract.compliance.invalidDocumentType");
        }

        ContractComplianceProfile profile = profileMapper.selectOne(
                new LambdaQueryWrapper<ContractComplianceProfile>()
                        .eq(ContractComplianceProfile::getContractId, contractId));
        if (profile == null) {
            throw BusinessException.of(400, "contract.compliance.profileRequired");
        }
        ContractComplianceSnapshot snapshot = snapshotWriter.ensureSnapshot(contract, profile);

        LocalDateTime deliveredAt = LocalDateTime.now().withNano(0);
        String engineerName = contract.getEngineerId() == null ? null
                : (engineerMapper.selectById(contract.getEngineerId()) == null ? null
                : engineerMapper.selectById(contract.getEngineerId()).getFullName());
        com.ses.entity.ContractComplianceWorkerSnapshot workerSnapshot = workerSnapshot(contract, deliveredAt);

        String viewerMask = maskLevel();
        ComplianceDocumentGenerator.Content content = documentGenerator.build(
                contract, snapshot, request.getDocumentType(), viewerMask, engineerName, workerSnapshot);
        return documentGenerator.toPdf(content, messageSource, "PREVIEW / 本番交付物ではありません");
    }

    @Override
    @Transactional
    public ComplianceDocumentDeliveryDto confirm(Long contractId, Long deliveryId, String note) {
        Contract contract = requireVisibleContract(contractId);
        requireComplianceAccess();
        requireWritable();
        DocumentDelivery delivery = deliveryMapper.selectById(deliveryId);
        if (delivery == null || !contractId.equals(delivery.getContractId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (delivery.getConfirmedAt() != null) {
            return toDto(delivery);
        }
        delivery.setConfirmedAt(LocalDateTime.now());
        delivery.setConfirmationNote(note);
        int rows = deliveryMapper.updateById(delivery);
        if (rows == 0) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }
        return toDto(delivery);
    }

    @Override
    public byte[] download(Long contractId, Long deliveryId) {
        Contract contract = requireVisibleContract(contractId);
        requireAnyContractRole();
        DocumentDelivery delivery = deliveryMapper.selectById(deliveryId);
        if (delivery == null || !contractId.equals(delivery.getContractId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        if (delivery.getGenerationState() != null && !"READY".equals(delivery.getGenerationState())) {
            throw BusinessException.of(409, "contract.compliance.versionConflict");
        }

        String viewerMask = maskLevel();
        Long versionId = null;
        String expectedSha256 = null;
        if ("LIMITED".equals(viewerMask) && delivery.getLimitedDocumentVersionId() != null) {
            versionId = delivery.getLimitedDocumentVersionId();
            expectedSha256 = delivery.getLimitedDocumentSha256();
        } else if ("MASK".equals(viewerMask) && delivery.getMaskDocumentVersionId() != null) {
            versionId = delivery.getMaskDocumentVersionId();
            expectedSha256 = delivery.getMaskDocumentSha256();
        } else if (delivery.getFullDocumentVersionId() != null) {
            versionId = delivery.getFullDocumentVersionId();
            expectedSha256 = delivery.getFullDocumentSha256();
        }

        DocumentVersion version = null;
        if (versionId != null) {
            version = documentVersionMapper.selectById(versionId);
        } else if (delivery.getDocumentId() != null) {
            version = documentVersionMapper.findLatestByDocumentId(delivery.getDocumentId());
        }

        if (version == null || !"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        recordAccessLog(delivery.getDocumentId() != null ? delivery.getDocumentId() : 0L, version.getId());

        // Return the stored immutable PDF rendition bytes! (S4-2, P2-N-1 fail-closed)
        try {
            byte[] pdfBytes = documentStorage.readAll(version.getStorageKey());
            if (pdfBytes == null || pdfBytes.length == 0) {
                throw BusinessException.of(404, "error.scope.notFound");
            }
            String actualSha256 = sha256HexBytes(pdfBytes);
            if (version.getSha256() != null && !version.getSha256().isBlank()) {
                if (!version.getSha256().equalsIgnoreCase(actualSha256)) {
                    log.error("Stored DocumentVersion SHA-256 mismatch for versionId={}: expected={}, actual={}",
                            version.getId(), version.getSha256(), actualSha256);
                    throw BusinessException.of(500, "error.file.readFailed");
                }
            }
            if (expectedSha256 != null && !expectedSha256.isBlank()) {
                if (!expectedSha256.equalsIgnoreCase(actualSha256)) {
                    log.error("Delivery rendition SHA-256 mismatch for versionId={}: expected={}, actual={}",
                            version.getId(), expectedSha256, actualSha256);
                    throw BusinessException.of(500, "error.file.readFailed");
                }
            }
            return pdfBytes;
        } catch (BusinessException be) {
            throw be;
        } catch (Exception e) {
            log.error("Failed to read document rendition bytes from storage for versionId={}", version.getId(), e);
            throw BusinessException.of(500, "error.file.readFailed");
        }
    }

    /**
     * 帳票の基準時点以前で確定した要員snapshotだけを読む（無ければnull）。
     * 交付後に作成されたworker snapshotを参照すると、過去帳票が現在値で変わるため、
     * snapshotAtが不明な行も安全側で帳票へ渡さない。
     */
    private com.ses.entity.ContractComplianceWorkerSnapshot workerSnapshot(Contract contract,
                                                                            LocalDateTime asOf) {
        if (contract.getEngineerId() == null || asOf == null) {
            return null;
        }
        List<com.ses.entity.ContractComplianceWorkerSnapshot> list = workerSnapshotMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ContractComplianceWorkerSnapshot>()
                        .eq(com.ses.entity.ContractComplianceWorkerSnapshot::getContractId, contract.getId())
                        .eq(com.ses.entity.ContractComplianceWorkerSnapshot::getWorkerId, contract.getEngineerId())
                        .le(com.ses.entity.ContractComplianceWorkerSnapshot::getSnapshotAt, asOf)
                        .isNotNull(com.ses.entity.ContractComplianceWorkerSnapshot::getSnapshotAt)
                        .orderByDesc(com.ses.entity.ContractComplianceWorkerSnapshot::getSnapshotAt)
                        .orderByDesc(com.ses.entity.ContractComplianceWorkerSnapshot::getSnapshotVersion)
                        .last("LIMIT 1"));
        return selectWorkerSnapshotAsOf(list, asOf);
    }

    static com.ses.entity.ContractComplianceWorkerSnapshot selectWorkerSnapshotAsOf(
            List<com.ses.entity.ContractComplianceWorkerSnapshot> snapshots, LocalDateTime asOf) {
        if (asOf == null) {
            return null;
        }
        return snapshots.stream()
                .filter(snapshot -> snapshot.getSnapshotAt() != null
                        && !snapshot.getSnapshotAt().isAfter(asOf))
                .max(Comparator.comparing(com.ses.entity.ContractComplianceWorkerSnapshot::getSnapshotAt)
                        .thenComparing(com.ses.entity.ContractComplianceWorkerSnapshot::getSnapshotVersion,
                                Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElse(null);
    }

    // ===== 共通 =====

    private Contract requireVisibleContract(Long contractId) {
        Contract contract = contractService.getById(contractId);
        if (contract == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedContract(contractId);
        return contract;
    }

    /** compliance menu権限の再チェック（管理者/HR=可、他はcompliance menu、fail-closed）。 */
    private void requireComplianceAccess() {
        String role = SecurityUtils.currentRole();
        MenuCacheService menuCacheService = null;
        try {
            menuCacheService = menuCacheServiceProvider.getIfAvailable();
        } catch (Exception ignored) {
            // fail-closed
        }
        if (!ComplianceAccessControl.canViewCompliance(role, menuCacheService)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
    }

    /** 契約メニュー配下の全ロール（4管理ロール＋営業）を許容する（一覧・download）。 */
    private void requireAnyContractRole() {
        String role = SecurityUtils.currentRole();
        if (!java.util.Set.of("管理者", "HR", "マネージャー", "営業").contains(role)) {
            throw BusinessException.of(403, "error.accessDenied");
        }
    }

    /** 営業は生成・確認不可（writeはfail-closed）。 */
    private void requireWritable() {
        if ("営業".equals(SecurityUtils.currentRole())) {
            throw BusinessException.of(403, "contract.compliance.writeDenied");
        }
    }

    /** download時にviewer roleで適用するmask（営業=LIMITED、マネージャー=MASK、管理者/HR=FULL）。 */
    private String maskLevel() {
        String role = SecurityUtils.currentRole();
        if ("営業".equals(role)) {
            return "LIMITED";
        }
        if ("マネージャー".equals(role)) {
            return "MASK";
        }
        return "FULL";
    }

    @org.springframework.beans.factory.annotation.Value("${spring.jackson.time-zone:#{null}}")
    private String deploymentTimezone;

    private LocalDate resolveAsOf() {
        return LocalDate.now(resolveDeploymentZoneId());
    }

    private java.time.ZoneId resolveDeploymentZoneId() {
        if (!org.springframework.util.StringUtils.hasText(deploymentTimezone)) {
            throw BusinessException.of(409, "compliance.gate.timezoneUnavailable");
        }
        try {
            return java.time.ZoneId.of(deploymentTimezone.trim());
        } catch (Exception e) {
            throw BusinessException.of(409, "compliance.gate.timezoneUnavailable");
        }
    }

    private int templateVersion(String documentType) {
        return systemConfigService.getInt("compliance.template." + documentType + ".version", 1);
    }

    private String idempotencyKey(Long contractId, String documentType, int templateVersion, String snapshotHash) {
        return "COMPLIANCE:" + contractId + ":" + documentType + ":v" + templateVersion + ":" + snapshotHash;
    }

    private void fillRecipient(DocumentDelivery delivery, Long recipientContactId, Contract contract) {
        if (recipientContactId == null) {
            return;
        }
        CustomerContact contact = customerContactMapper.selectById(recipientContactId);
        if (contact == null) {
            throw BusinessException.of(400, "contract.compliance.invalidContact");
        }
        if (contract.getCustomerId() != null && contact.getCustomerId() != null
                && !contract.getCustomerId().equals(contact.getCustomerId())) {
            throw BusinessException.of(400, "contract.compliance.contactCustomerMismatch");
        }
        delivery.setRecipientContactId(recipientContactId);
        delivery.setRecipientNameSnapshot(contact.getName());
        delivery.setRecipientEmailSnapshot(contact.getEmail());
    }

    private String customerName(Contract contract) {
        if (contract.getCustomerId() == null) {
            return null;
        }
        com.ses.entity.Customer customer = customerMapper.selectById(contract.getCustomerId());
        return customer == null ? null : customer.getCompanyName();
    }

    private void recordAccessLog(Long documentId, Long versionId) {
        try {
            com.ses.entity.DocumentAccessLog accessLog = new com.ses.entity.DocumentAccessLog();
            accessLog.setDocumentId(documentId);
            accessLog.setVersionId(versionId);
            accessLog.setAction("DOWNLOAD");
            accessLog.setUserId(SecurityUtils.currentUserId());
            accessLog.setOccurredAt(LocalDateTime.now());
            documentAccessLogMapper.insert(accessLog);
        } catch (Exception e) {
            log.warn("document access logの記録に失敗しました（documentId={}）", documentId, e);
        }
    }

    private Document registerRendition(Contract contract, String documentType, int templateVersion, String snapshotHash, String renditionLevel, byte[] pdf) {
        com.ses.dto.document.DocumentRegisterRequest registerRequest =
                com.ses.dto.document.DocumentRegisterRequest.builder()
                        .documentType(documentType)
                        .title(messageSource.getMessage("doc.title." + documentType,
                                null, documentType, org.springframework.context.i18n.LocaleContextHolder.getLocale()))
                        .counterpartyType("CUSTOMER")
                        .counterpartyId(contract.getCustomerId())
                        .counterpartyNameSnapshot(customerName(contract))
                        .transactionDate(java.time.LocalDate.now())
                        .sourceType("COMPLIANCE_DELIVERY_RENDITION")
                        .businessKey("COMPLIANCE:" + contract.getId() + ":" + documentType + ":" + renditionLevel)
                        .versionDiscriminator("v" + templateVersion + ":" + snapshotHash + ":" + renditionLevel)
                        .originalName(documentType + "-" + contract.getId() + "-" + renditionLevel + ".pdf")
                        .contentType("application/pdf")
                        .targetType("CONTRACT")
                        .targetId(contract.getId())
                        .build();
        return documentService.registerGenerated(registerRequest, new ByteArrayInputStream(pdf));
    }

    private String sha256HexBytes(byte[] data) {
        if (data == null) {
            return "";
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 calculation failed", e);
        }
    }

    private String sha256Hex(String text) {
        return sha256HexBytes(text == null ? new byte[0] : text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String recipientHash(Long contactId, Contract contract) {
        if (contactId == null) {
            return sha256Hex("NO_RECIPIENT");
        }
        CustomerContact contact = customerContactMapper.selectById(contactId);
        if (contact == null) {
            return sha256Hex("CONTACT_" + contactId);
        }
        return sha256Hex(contact.getName() + ":" + contact.getEmail());
    }

    private String companyConfigHash() {
        String companyName = systemConfigService.getString("company.name", "");
        String companyAddress = systemConfigService.getString("company.address", "");
        return sha256Hex(companyName + ":" + companyAddress);
    }

    private String computeGateSnapshotHash(com.ses.entity.ComplianceMappingVersion activeMapping, LocalDate asOf,
                                           Long workplaceId, com.ses.entity.ComplianceResponsibleAssignment assignment,
                                           com.ses.entity.ComplianceMappingApprovalEvent approvalEvent, LocalDateTime gateEvaluatedAt) {
        StringBuilder payload = new StringBuilder();
        payload.append("mapping_id=").append(activeMapping.getId()).append('\n');
        payload.append("mapping_version=").append(activeMapping.getMappingVersion()).append('\n');
        payload.append("mapping_hash=").append(activeMapping.getMappingHash()).append('\n');
        payload.append("review_policy_hash=").append(activeMapping.getReviewPolicyHash()).append('\n');
        payload.append("workplace_id=").append(workplaceId).append('\n');
        payload.append("assignment_id=").append(assignment.getId()).append('\n');
        payload.append("assignment_user_id=").append(assignment.getUserId()).append('\n');
        payload.append("assignment_effective_from=").append(assignment.getEffectiveFrom()).append('\n');
        payload.append("assignment_effective_to=").append(assignment.getEffectiveTo()).append('\n');
        payload.append("approval_event_id=").append(approvalEvent.getId()).append('\n');
        payload.append("approval_event_action=").append(approvalEvent.getAction()).append('\n');
        payload.append("approval_event_occurred_at=").append(approvalEvent.getOccurredAt()).append('\n');

        List<com.ses.entity.ComplianceMappingReviewRequirementGroup> groups = requirementGroupMapper.selectList(
                new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementGroup>()
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getTenantId, "default")
                        .eq(com.ses.entity.ComplianceMappingReviewRequirementGroup::getMappingId, activeMapping.getId()));
        List<Long> groupIds = groups.stream().map(com.ses.entity.ComplianceMappingReviewRequirementGroup::getId).toList();
        List<com.ses.entity.ComplianceMappingReviewRequirementType> types = groupIds.isEmpty() ? List.of() :
                requirementTypeMapper.selectList(
                        new LambdaQueryWrapper<com.ses.entity.ComplianceMappingReviewRequirementType>()
                                .eq(com.ses.entity.ComplianceMappingReviewRequirementType::getTenantId, "default")
                                .in(com.ses.entity.ComplianceMappingReviewRequirementType::getRequirementGroupId, groupIds));

        // E-3: NO_EXTERNAL_REVIEW センチネルは撤去し、freeze済みpolicyを満たす実在external review eventのみを
        // gate_snapshot_hashへ含める（type未設定グループは外部レビュー不要 — mapping側activateと同一判定）。
        if (externalReviewEvaluator != null && !groups.isEmpty()) {
            List<com.ses.entity.ComplianceExternalReviewEvent> allAdopted = new ArrayList<>();
            for (com.ses.entity.ComplianceMappingReviewRequirementGroup grp : groups) {
                boolean hasTypes = types.stream().anyMatch(t -> grp.getId().equals(t.getRequirementGroupId()));
                if (hasTypes) {
                    allAdopted.addAll(externalReviewEvaluator.evaluateGroup("default", activeMapping, grp, asOf));
                }
            }
            allAdopted.sort(Comparator.comparing(com.ses.entity.ComplianceExternalReviewEvent::getRequirementGroupCodeSnapshot, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(com.ses.entity.ComplianceExternalReviewEvent::getReviewerIdentityHash, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(com.ses.entity.ComplianceExternalReviewEvent::getId));
            for (com.ses.entity.ComplianceExternalReviewEvent ev : allAdopted) {
                payload.append("external_review_event=")
                        .append(ev.getRequirementGroupCodeSnapshot()).append(':')
                        .append(ev.getReviewerIdentityHash()).append(':')
                        .append(ev.getId()).append(':')
                        .append(ev.getReviewedAt()).append(':')
                        .append(ev.getValidUntil()).append('\n');
            }
        }

        payload.append("gate_evaluated_at=").append(gateEvaluatedAt).append('\n');
        return sha256Hex(payload.toString());
    }

    private String computeRenderInputHash(Long profileSnapshotId, String profileSnapshotHash,
                                         Long workerSnapshotId, String workerSnapshotHash,
                                         Long workplaceId, String recipientHash, String configHash,
                                         String documentType, int templateVersion, String fieldMaskHash,
                                         LocalDateTime deliveredAt) {
        StringBuilder payload = new StringBuilder();
        payload.append("profile_snapshot_id=").append(profileSnapshotId).append('\n');
        payload.append("profile_snapshot_hash=").append(profileSnapshotHash).append('\n');
        payload.append("worker_snapshot_id=").append(workerSnapshotId == null ? "NULL" : workerSnapshotId).append('\n');
        payload.append("worker_snapshot_hash=").append(workerSnapshotHash == null ? "ABSENT" : workerSnapshotHash).append('\n');
        payload.append("workplace_id=").append(workplaceId).append('\n');
        payload.append("recipient_display_hash=").append(recipientHash).append('\n');
        payload.append("company_config_hash=").append(configHash).append('\n');
        payload.append("document_type=").append(documentType).append('\n');
        payload.append("template_version=").append(templateVersion).append('\n');
        payload.append("field_mask_policy_hash=").append(fieldMaskHash).append('\n');
        payload.append("render_engine_version=1.0.0\n");
        payload.append("delivered_at=").append(deliveredAt).append('\n');
        return sha256Hex(payload.toString());
    }

    private String computeDeliveryBusinessKey(Long contractId, String documentType, int templateVersion,
                                               Long profileSnapshotId, String profileSnapshotHash,
                                               Long workerSnapshotId, String workerSnapshotHash,
                                               Long workplaceId, String recipientHash, String configHash,
                                               Long mappingVersionId, String mappingVersion,
                                               String mappingHash, String reviewPolicyHash,
                                               Long approvalEventId, String fieldMaskHash) {
        String workerHashOrAbsent = (workerSnapshotId != null && workerSnapshotHash != null)
                ? workerSnapshotHash : "ABSENT";
        String workerIdStr = workerSnapshotId != null ? String.valueOf(workerSnapshotId) : "NULL";
        String approvalIdStr = approvalEventId != null ? String.valueOf(approvalEventId) : "NULL";
        String payload = String.format("%s,%d,%s,%d,%d,%s,%s,%s,%d,%s,%s,%d,%s,%s,%s,%s,,,,,%s,1.0.0",
                "default", contractId, documentType, templateVersion,
                profileSnapshotId, profileSnapshotHash,
                workerIdStr, workerHashOrAbsent,
                workplaceId,
                recipientHash == null ? "" : recipientHash,
                configHash == null ? "" : configHash,
                mappingVersionId == null ? 0 : mappingVersionId,
                mappingVersion == null ? "" : mappingVersion,
                mappingHash == null ? "" : mappingHash,
                reviewPolicyHash == null ? "" : reviewPolicyHash,
                approvalIdStr,
                fieldMaskHash == null ? "" : fieldMaskHash);
        return sha256Hex(payload);
    }

    private ComplianceDocumentDeliveryDto toDto(DocumentDelivery delivery) {
        ComplianceDocumentDeliveryDto dto = new ComplianceDocumentDeliveryDto();
        dto.setId(delivery.getId());
        dto.setDocumentId(delivery.getDocumentId());
        dto.setDocumentType(delivery.getDocumentType());
        dto.setTemplateVersion(delivery.getTemplateVersion() == null ? null
                : Integer.valueOf(delivery.getTemplateVersion()));
        dto.setSnapshotHash(delivery.getSnapshotHash());
        dto.setDeliveryMethod(delivery.getDeliveryMethod());
        dto.setDeliveryStatus(delivery.getDeliveryStatus());
        dto.setDeliveredAt(delivery.getDeliveredAt());
        dto.setConfirmedAt(delivery.getConfirmedAt());
        dto.setConfirmationNote(delivery.getConfirmationNote());
        dto.setRecipientNameSnapshot(delivery.getRecipientNameSnapshot());
        return dto;
    }
}
