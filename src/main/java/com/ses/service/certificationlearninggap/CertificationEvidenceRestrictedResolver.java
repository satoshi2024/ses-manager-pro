package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.CertificationEvent;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.EngineerCertification;
import com.ses.mapper.CertificationEventMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.impl.FileScopeValidationService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 資格証憑の唯一のrestricted resolver。
 * latest版ではなく、現在のVERIFY eventが指すdocument/version/hashだけを返す。
 */
@Service
public class CertificationEvidenceRestrictedResolver {

    private final EngineerCertificationMapper certificationMapper;
    private final DocumentLinkMapper linkMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper versionMapper;
    private final CertificationEventMapper eventMapper;
    private final DataScopeService dataScopeService;
    private final FileScopeValidationService fileScopeValidationService;

    public CertificationEvidenceRestrictedResolver(EngineerCertificationMapper certificationMapper,
                                                   DocumentLinkMapper linkMapper,
                                                   DocumentMapper documentMapper,
                                                   DocumentVersionMapper versionMapper,
                                                   CertificationEventMapper eventMapper,
                                                   DataScopeService dataScopeService,
                                                   FileScopeValidationService fileScopeValidationService) {
        this.certificationMapper = certificationMapper;
        this.linkMapper = linkMapper;
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.eventMapper = eventMapper;
        this.dataScopeService = dataScopeService;
        this.fileScopeValidationService = fileScopeValidationService;
    }

    public ResolvedEvidence resolve(Long recordId, Long documentId, Integer versionNo, boolean forDownload) {
        String tenantId = tenant();
        EngineerCertification record = certificationMapper.selectOne(new LambdaQueryWrapper<EngineerCertification>()
                .eq(EngineerCertification::getId, recordId)
                .eq(EngineerCertification::getTenantId, tenantId));
        if (record == null || !"ACTIVE".equals(record.getRecordState())
                || !Integer.valueOf(1).equals(record.getCurrentFlag())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        dataScopeService.assertAllowedEngineer(record.getEngineerId());

        DocumentLink link = linkMapper.selectOne(new LambdaQueryWrapper<DocumentLink>()
                .eq(DocumentLink::getTenantId, tenantId)
                .eq(DocumentLink::getDocumentId, documentId)
                .eq(DocumentLink::getTargetType, "CERTIFICATION_RECORD")
                .eq(DocumentLink::getTargetId, recordId));
        if (link == null) {
            throw BusinessException.of(403, "certification.evidence.linkRequired");
        }
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getId, documentId).eq(Document::getTenantId, tenantId));
        if (document == null || !"CERTIFICATION_EVIDENCE".equals(document.getDocumentType())) {
            throw BusinessException.of(403, "certification.evidence.linkRequired");
        }
        if (Integer.valueOf(1).equals(document.getLegalHoldFlag())
                || (document.getRetentionUntil() != null && document.getRetentionUntil().isBefore(LocalDate.now()))) {
            throw BusinessException.of(403, "error.file.legalHoldActive");
        }
        CertificationEvent verify = currentVerifyEvent(recordId);
        if (verify == null || !Objects.equals(verify.getEvidenceDocumentId(), documentId)) {
            throw BusinessException.of(403, "certification.evidence.versionMismatch");
        }
        if (versionNo == null) {
            throw BusinessException.of(403, "certification.evidence.versionMismatch");
        }
        DocumentVersion version = versionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getTenantId, tenantId).eq(DocumentVersion::getDocumentId, documentId)
                .eq(DocumentVersion::getVersionNo, versionNo));
        if (version == null || !"CLEAN".equals(version.getScanStatus())
                || !Objects.equals(version.getId(), verify.getEvidenceDocumentVersionId())
                || !Objects.equals(normalize(verify.getEvidenceDocumentHash()), normalize(version.getSha256()))) {
            throw BusinessException.of(403, "certification.evidence.versionMismatch");
        }
        if (forDownload) {
            fileScopeValidationService.assertCertificationEvidenceDownloadAllowed(
                    version.getStorageKey(), recordId, documentId, version.getId(), version.getSha256());
        }
        return new ResolvedEvidence(record, document, version, link);
    }

    public List<ResolvedEvidence> list(Long recordId) {
        String tenantId = tenant();
        return linkMapper.selectList(new LambdaQueryWrapper<DocumentLink>()
                        .eq(DocumentLink::getTenantId, tenantId)
                        .eq(DocumentLink::getTargetType, "CERTIFICATION_RECORD")
                        .eq(DocumentLink::getTargetId, recordId))
                .stream().map(link -> {
                    try {
                        CertificationEvent verify = currentVerifyEvent(recordId);
                        if (verify == null || !Objects.equals(verify.getEvidenceDocumentId(), link.getDocumentId())) {
                            return null;
                        }
                        DocumentVersion version = versionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                                .eq(DocumentVersion::getId, verify.getEvidenceDocumentVersionId())
                                .eq(DocumentVersion::getTenantId, tenantId));
                        return resolve(recordId, link.getDocumentId(), version == null ? null : version.getVersionNo(), false);
                    } catch (BusinessException ignored) {
                        return null;
                    }
                }).filter(Objects::nonNull).distinct().toList();
    }

    private CertificationEvent currentVerifyEvent(Long recordId) {
        return eventMapper.selectByTenantAndRecordId(tenant(), recordId).stream()
                .filter(event -> "VERIFY".equals(event.getEventType()))
                .reduce((first, second) -> second)
                .filter(event -> "ACTIVE".equals(event.getEffectiveRecordState()))
                .orElse(null);
    }

    private String tenant() {
        String tenantId = AccountingTenantContextHolder.getCurrentTenantId();
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.of(403, "error.tenant.contextRequired");
        }
        return tenantId;
    }

    private String normalize(String value) {
        return value == null ? null : value.toLowerCase(java.util.Locale.ROOT);
    }

    public record ResolvedEvidence(EngineerCertification record, Document document,
                                   DocumentVersion version, DocumentLink link) { }
}
