package com.ses.service.certificationlearninggap;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.certificationlearninggap.CertificationLearningGapFilter;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.EngineerCertification;
import com.ses.entity.CertificationEvent;
import com.ses.mapper.CertificationEventMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerCertificationMapper;
import com.ses.service.DocumentService;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.SkillGapService;
import com.ses.service.security.impl.FileScopeValidationService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Objects;

/** 資格証憑のdownload境界。typed link・版/hash・CLEAN・legal holdを毎回再検証する。 */
@Service
public class CertificationEvidenceAccessService {

    private final EngineerCertificationMapper certificationMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentService documentService;
    private final FileScopeValidationService fileScopeValidationService;
    private final EngineerAccountLinkService accountLinkService;
    private final CertificationLearningGapQueryService queryService;
    private final Clock clock;
    private final CertificationEventMapper eventMapper;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CertificationEvidenceRestrictedResolver restrictedResolver;

    /** 既存の単体テスト／直接利用互換。Springは下記9引数constructorを使用する。 */
    public CertificationEvidenceAccessService(EngineerCertificationMapper certificationMapper,
                                              DocumentLinkMapper documentLinkMapper,
                                              DocumentVersionMapper documentVersionMapper,
                                              DocumentService documentService,
                                              FileScopeValidationService fileScopeValidationService,
                                              EngineerAccountLinkService accountLinkService,
                                              CertificationLearningGapQueryService queryService,
                                              Clock clock) {
        this(certificationMapper, documentLinkMapper, documentVersionMapper, documentService,
                fileScopeValidationService, accountLinkService, queryService, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CertificationEvidenceAccessService(EngineerCertificationMapper certificationMapper,
                                              DocumentLinkMapper documentLinkMapper,
                                              DocumentVersionMapper documentVersionMapper,
                                              DocumentService documentService,
                                              FileScopeValidationService fileScopeValidationService,
                                              EngineerAccountLinkService accountLinkService,
                                              CertificationLearningGapQueryService queryService,
                                              Clock clock, CertificationEventMapper eventMapper) {
        this.certificationMapper = certificationMapper;
        this.documentLinkMapper = documentLinkMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentService = documentService;
        this.fileScopeValidationService = fileScopeValidationService;
        this.accountLinkService = accountLinkService;
        this.queryService = queryService;
        this.clock = clock;
        this.eventMapper = eventMapper;
    }

    public EvidenceDownload downloadForManagement(Long engineerId, Long recordId, Long documentId, Integer versionNo,
                                                  Authentication authentication) {
        EngineerCertification record = record(recordId);
        if (!Objects.equals(engineerId, record.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        queryService.detail(record.getEngineerId(), new CertificationLearningGapFilter(record.getEngineerId(), null,
                null, null, null, LocalDate.now(clock), null, SkillGapService.DemandSource.COMBINED), authentication);
        return download(record, documentId, versionNo);
    }

    public EvidenceDownload downloadForSelf(Long actorUserId, Long recordId, Long documentId, Integer versionNo) {
        EngineerCertification record = record(recordId);
        Long ownEngineerId = actorUserId == null ? null : accountLinkService.findEngineerIdByUserId(actorUserId);
        if (!Objects.equals(ownEngineerId, record.getEngineerId())) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        return download(record, documentId, versionNo);
    }

    private EvidenceDownload download(EngineerCertification record, Long documentId, Integer versionNo) {
        if (restrictedResolver != null) {
            CertificationEvidenceRestrictedResolver.ResolvedEvidence resolved =
                    restrictedResolver.resolve(record.getId(), documentId, versionNo, true);
            return new EvidenceDownload(documentId, versionNo, resolved.version().getOriginalName(),
                    resolved.version().getContentType(), documentService.download(documentId, versionNo));
        }
        if (documentId == null || versionNo == null) {
            throw BusinessException.of(404, "error.document.versionNotFound");
        }
        if (!"ACTIVE".equals(record.getRecordState()) || !Integer.valueOf(1).equals(record.getCurrentFlag())) {
            throw BusinessException.of(403, "certification.evidence.linkRequired");
        }
        boolean linked = documentLinkMapper.selectList(new LambdaQueryWrapper<DocumentLink>()
                        .eq(DocumentLink::getTenantId, currentTenant())
                        .eq(DocumentLink::getDocumentId, documentId)
                        .eq(DocumentLink::getTargetType, "CERTIFICATION_RECORD")
                        .eq(DocumentLink::getTargetId, record.getId()))
                .stream().anyMatch(link -> !Integer.valueOf(1).equals(link.getDeletedFlag()));
        if (!linked) {
            throw BusinessException.of(403, "certification.evidence.linkRequired");
        }
        CertificationEvent verifyEvent = currentVerifyEvent(record.getId());
        if (verifyEvent == null
                || !Objects.equals(verifyEvent.getEvidenceDocumentId(), documentId)) {
            throw BusinessException.of(403, "certification.evidence.versionMismatch");
        }
        DocumentVersion version = documentVersionMapper.selectOne(new LambdaQueryWrapper<DocumentVersion>()
                .eq(DocumentVersion::getTenantId, currentTenant())
                .eq(DocumentVersion::getDocumentId, documentId).eq(DocumentVersion::getVersionNo, versionNo));
        if (version == null || !"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        if (!Objects.equals(verifyEvent.getEvidenceDocumentVersionId(), version.getId())
                || !StringUtils.hasText(verifyEvent.getEvidenceDocumentHash())
                || !Objects.equals(verifyEvent.getEvidenceDocumentHash().toLowerCase(java.util.Locale.ROOT),
                version.getSha256() == null ? null : version.getSha256().toLowerCase(java.util.Locale.ROOT))) {
            throw BusinessException.of(403, "certification.evidence.versionMismatch");
        }
        String storageKey = documentService.getVersionStorageKey(documentId, versionNo);
        if (storageKey == null) {
            throw BusinessException.of(404, "error.document.versionNotFound");
        }
        fileScopeValidationService.assertCertificationEvidenceDownloadAllowed(
                storageKey, record.getId(), documentId, version.getId(), version.getSha256());
        return new EvidenceDownload(documentId, versionNo, version.getOriginalName(), version.getContentType(),
                documentService.download(documentId, versionNo));
    }

    private CertificationEvent currentVerifyEvent(Long recordId) {
        if (eventMapper == null || recordId == null) {
            throw BusinessException.of(403, "certification.evidence.versionMismatch");
        }
        java.util.List<CertificationEvent> events = eventMapper.selectByTenantAndRecordId(
                currentTenant(), recordId);
        if ((events == null || events.isEmpty())
                && "default".equals(currentTenant())) {
            // 旧fixtureにはtenant列がないため、default tenantでのみ互換fallbackする。
            events = eventMapper.selectByRecordId(recordId).stream()
                    .filter(event -> event.getTenantId() == null || currentTenant().equals(event.getTenantId()))
                    .toList();
        }
        if (events == null) {
            return null;
        }
        return events.stream()
                .filter(event -> "VERIFY".equals(event.getEventType()))
                .reduce((first, second) -> second)
                .filter(event -> "ACTIVE".equals(event.getEffectiveRecordState()))
                .orElse(null);
    }

    private EngineerCertification record(Long recordId) {
        EngineerCertification record = recordId == null ? null : certificationMapper.selectOne(
                new LambdaQueryWrapper<EngineerCertification>().eq(EngineerCertification::getId, recordId)
                        .eq(EngineerCertification::getTenantId,
                                currentTenant()));
        if (record == null && recordId != null
                && "default".equals(currentTenant())) {
            EngineerCertification legacy = certificationMapper.selectById(recordId);
            if (legacy != null && legacy.getTenantId() == null) {
                record = legacy;
            }
        }
        if (record == null) {
            throw BusinessException.of(404, "certification.record.notFound");
        }
        return record;
    }

    private String currentTenant() {
        return com.ses.service.accounting.AccountingTenantContextHolder.getCurrentTenantId();
    }

    public record EvidenceDownload(Long documentId, Integer versionNo, String fileName, String contentType,
                                   InputStream content) { }
}
