package com.ses.service.certification;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 資格eventへ保存する証憑版をtyped link・hash・scan状態で固定する。 */
@Service
public class CertificationEvidenceValidator {

    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper documentVersionMapper;
    private final DocumentLinkMapper documentLinkMapper;

    public CertificationEvidenceValidator(DocumentMapper documentMapper,
                                          DocumentVersionMapper documentVersionMapper,
                                          DocumentLinkMapper documentLinkMapper) {
        this.documentMapper = documentMapper;
        this.documentVersionMapper = documentVersionMapper;
        this.documentLinkMapper = documentLinkMapper;
    }

    public void validate(Long certificationRecordId, Long documentId, Long documentVersionId, String expectedHash) {
        if (documentId == null || documentVersionId == null || expectedHash == null || expectedHash.isBlank()) {
            throw BusinessException.of(400, "certification.evidence.versionRequired");
        }
        String tenantId = com.ses.service.accounting.AccountingTenantContextHolder.requireTenantContext();
        Document document = documentMapper.selectOne(new QueryWrapper<Document>()
                .eq("id", documentId).eq("tenant_id", tenantId));
        DocumentVersion version = documentVersionMapper.selectOne(new QueryWrapper<DocumentVersion>()
                .eq("id", documentVersionId).eq("tenant_id", tenantId));
        if (document == null || !"CERTIFICATION_EVIDENCE".equals(document.getDocumentType())
                || version == null || !documentId.equals(version.getDocumentId())
                || !Objects.equals(document.getTenantId(), version.getTenantId())) {
            throw BusinessException.of(403, "certification.evidence.invalid");
        }
        if (!"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        if (version.getSha256() == null || !expectedHash.equalsIgnoreCase(version.getSha256())) {
            throw BusinessException.of(403, "error.file.hashMismatch");
        }
        boolean linked = documentLinkMapper.selectList(new QueryWrapper<DocumentLink>()
                .eq("document_id", documentId).eq("target_type", "CERTIFICATION_RECORD")
                        .eq("target_id", certificationRecordId).eq("tenant_id", tenantId))
                .stream().anyMatch(link -> "CERTIFICATION_RECORD".equals(link.getTargetType())
                        && Objects.equals(certificationRecordId, link.getTargetId())
                        && (Objects.equals(tenantId, link.getTenantId())
                            || ("default".equals(tenantId) && link.getTenantId() == null))
                        && !Integer.valueOf(1).equals(link.getDeletedFlag()));
        if (!linked) {
            throw BusinessException.of(403, "certification.evidence.linkRequired");
        }
    }
}
