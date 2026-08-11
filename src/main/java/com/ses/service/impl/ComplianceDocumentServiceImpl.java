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
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
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
    private final ComplianceFindingMapper findingMapper;
    private final SystemConfigService systemConfigService;
    private final DataScopeService dataScopeService;
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
        if (profile == null) {
            throw BusinessException.of(400, "contract.compliance.profileRequired");
        }
        ContractComplianceSnapshot snapshot = snapshotWriter.ensureSnapshot(contract, profile);

        String idempotencyKey = idempotencyKey(contractId, request.getDocumentType(), templateVersion,
                snapshot.getSnapshotHash());
        DocumentDelivery existing = deliveryMapper.selectOne(
                new LambdaQueryWrapper<DocumentDelivery>()
                        .eq(DocumentDelivery::getIdempotencyKey, idempotencyKey)
                        .last("LIMIT 1"));
        if (existing != null) {
            return toDto(existing);
        }

        String engineerName = contract.getEngineerId() == null ? null
                : (engineerMapper.selectById(contract.getEngineerId()) == null ? null
                : engineerMapper.selectById(contract.getEngineerId()).getFullName());
        com.ses.entity.ContractComplianceWorkerSnapshot workerSnapshot = workerSnapshot(contract, snapshot.getSnapshotAt());
        // archive正本は常にFULLで生成する（R4.2）。download時にviewer roleで再maskする。
        com.ses.service.compliance.ComplianceDocumentGenerator.Content content = documentGenerator.build(
                contract, snapshot, request.getDocumentType(), "FULL", engineerName, workerSnapshot);
        byte[] pdf = documentGenerator.toPdf(content, messageSource);

        com.ses.dto.document.DocumentRegisterRequest registerRequest =
                com.ses.dto.document.DocumentRegisterRequest.builder()
                        .documentType(request.getDocumentType())
                        .title(messageSource.getMessage("doc.title." + request.getDocumentType(),
                                null, request.getDocumentType(), org.springframework.context.i18n.LocaleContextHolder.getLocale()))
                        .counterpartyType("CUSTOMER")
                        .counterpartyId(contract.getCustomerId())
                        .counterpartyNameSnapshot(customerName(contract))
                        .transactionDate(java.time.LocalDate.now())
                        .sourceType("GENERATED")
                        .businessKey("COMPLIANCE:" + contractId + ":" + request.getDocumentType())
                        .versionDiscriminator("v" + templateVersion + ":" + snapshot.getSnapshotHash())
                        .originalName(request.getDocumentType() + "-" + contractId + ".pdf")
                        .contentType("application/pdf")
                        .targetType("CONTRACT")
                        .targetId(contractId)
                        .build();
        Document document = documentService.registerGenerated(registerRequest, new ByteArrayInputStream(pdf));

        DocumentDelivery delivery = new DocumentDelivery();
        delivery.setTenantId("default");
        delivery.setContractId(contractId);
        delivery.setDocumentId(document.getId());
        delivery.setDocumentType(request.getDocumentType());
        delivery.setTemplateVersion(String.valueOf(templateVersion));
        delivery.setEffectiveFrom(snapshot.getDispatchFrom());
        delivery.setEffectiveTo(snapshot.getDispatchTo());
        delivery.setSnapshotHash(snapshot.getSnapshotHash());
        fillRecipient(delivery, request.getRecipientContactId(), contract);
        delivery.setDeliveryMethod(request.getDeliveryMethod());
        delivery.setDeliveryStatus("DELIVERED");
        delivery.setDeliveredAt(LocalDateTime.now());
        delivery.setIdempotencyKey(idempotencyKey);
        try {
            deliveryMapper.insert(delivery);
        } catch (DuplicateKeyException e) {
            DocumentDelivery concurrent = deliveryMapper.selectOne(
                    new LambdaQueryWrapper<DocumentDelivery>()
                            .eq(DocumentDelivery::getIdempotencyKey, idempotencyKey)
                            .last("LIMIT 1"));
            if (concurrent != null) {
                return toDto(concurrent);
            }
            throw e;
        }
        return toDto(delivery);
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
        DocumentVersion version = documentVersionMapper.findLatestByDocumentId(delivery.getDocumentId());
        if (version == null || !"CLEAN".equals(version.getScanStatus())) {
            // 未知/未scanの生成物はfail-closed（download不可）
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        recordAccessLog(delivery.getDocumentId(), version.getId());
        // R4.2: downloadはviewer roleで再maskする（archive正本はFULLのまま保持）。
        // マネージャー=MASK、営業=LIMITED、管理者/HR=FULL。
        ContractComplianceSnapshot snapshot = snapshotMapper.selectOne(
                new LambdaQueryWrapper<ContractComplianceSnapshot>()
                        .eq(ContractComplianceSnapshot::getContractId, contractId)
                        .eq(ContractComplianceSnapshot::getSnapshotHash, delivery.getSnapshotHash())
                        .orderByDesc(ContractComplianceSnapshot::getSnapshotVersion)
                        .last("LIMIT 1"));
        if (snapshot == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        String engineerName = contract.getEngineerId() == null ? null
                : (engineerMapper.selectById(contract.getEngineerId()) == null ? null
                : engineerMapper.selectById(contract.getEngineerId()).getFullName());
        String viewerMask = maskLevel();
        com.ses.service.compliance.ComplianceDocumentGenerator.Content content = documentGenerator.build(
                contract, snapshot, delivery.getDocumentType(), viewerMask, engineerName,
                workerSnapshot(contract, delivery.getDeliveredAt()));
        return documentGenerator.toPdf(content, messageSource);
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
