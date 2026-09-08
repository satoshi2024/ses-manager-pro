package com.ses.service.servicedesk;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Document;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.entity.ServiceAttachmentLink;
import com.ses.entity.ServiceRequest;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.ServiceAttachmentLinkMapper;
import com.ses.mapper.ServiceRequestMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** CLEAN済み文書とサービスデスク業務リンクを短い独立transactionで確定する。 */
@Service
public class ServiceRequestAttachmentCommitService {

    private final ServiceRequestMapper requestMapper;
    private final DocumentMapper documentMapper;
    private final DocumentVersionMapper versionMapper;
    private final DocumentLinkMapper documentLinkMapper;
    private final ServiceAttachmentLinkMapper attachmentLinkMapper;

    public ServiceRequestAttachmentCommitService(ServiceRequestMapper requestMapper,
                                                 DocumentMapper documentMapper,
                                                 DocumentVersionMapper versionMapper,
                                                 DocumentLinkMapper documentLinkMapper,
                                                 ServiceAttachmentLinkMapper attachmentLinkMapper) {
        this.requestMapper = requestMapper;
        this.documentMapper = documentMapper;
        this.versionMapper = versionMapper;
        this.documentLinkMapper = documentLinkMapper;
        this.attachmentLinkMapper = attachmentLinkMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public ServiceAttachmentLink commit(String tenantId, Long requestId, Long commentId, Long documentId,
                                         String visibility, String fileName, Long fileSize, String businessKey) {
        requireTenant(tenantId);
        ServiceRequest request = requestMapper.selectByIdAndTenant(requestId, tenantId);
        Document document = documentMapper.selectOne(new LambdaQueryWrapper<Document>()
                .eq(Document::getId, documentId).eq(Document::getTenantId, tenantId));
        if (request == null || document == null) {
            throw BusinessException.of(404, "error.scope.notFound");
        }
        DocumentVersion version = versionMapper.findLatestByTenantAndDocumentId(tenantId, documentId);
        if (version == null || !tenantId.equals(version.getTenantId()) || !"CLEAN".equals(version.getScanStatus())) {
            throw BusinessException.of(403, "error.file.scanNotReady");
        }
        DocumentLink typedLink = documentLinkMapper.selectOne(new LambdaQueryWrapper<DocumentLink>()
                .eq(DocumentLink::getTenantId, tenantId)
                .eq(DocumentLink::getDocumentId, documentId)
                .eq(DocumentLink::getTargetType, "SERVICE_REQUEST")
                .eq(DocumentLink::getTargetId, requestId));
        if (typedLink != null && !tenantId.equals(typedLink.getTenantId())) {
            throw BusinessException.of(403, "error.tenant.mismatch");
        }
        if (typedLink == null) {
            // DocumentService側のlink確定が一時的に失敗しても、補償再試行で
            // typed linkと業務添付linkを同一短transactionへ収束させる。
            DocumentLink repairedLink = new DocumentLink();
            repairedLink.setTenantId(tenantId);
            repairedLink.setDocumentId(documentId);
            repairedLink.setTargetType("SERVICE_REQUEST");
            repairedLink.setTargetId(requestId);
            boolean inserted = false;
            try {
                documentLinkMapper.insert(repairedLink);
                inserted = true;
            } catch (DuplicateKeyException duplicate) {
                // 並行実行時は既存の同一業務linkを再確認する。
                typedLink = documentLinkMapper.selectOne(new LambdaQueryWrapper<DocumentLink>()
                        .eq(DocumentLink::getTenantId, tenantId)
                        .eq(DocumentLink::getDocumentId, documentId)
                        .eq(DocumentLink::getTargetType, "SERVICE_REQUEST")
                        .eq(DocumentLink::getTargetId, requestId));
            }
            if (typedLink == null && !inserted) {
                throw BusinessException.of(403, "error.scope.notFound");
            }
        }

        if (businessKey == null || businessKey.isBlank()) {
            throw BusinessException.of(400, "error.file.idempotencyKeyRequired");
        }
        ServiceAttachmentLink existing = attachmentLinkMapper.selectByBusinessKey(tenantId, businessKey);
        if (existing != null) {
            return existing;
        }
        ServiceAttachmentLink link = new ServiceAttachmentLink();
        link.setTenantId(tenantId);
        link.setServiceRequestId(requestId);
        link.setCommentId(commentId);
        link.setDocumentId(documentId);
        link.setVisibility(visibility);
        link.setBusinessKey(businessKey);
        link.setFileName(fileName);
        link.setFileSize(fileSize);
        link.setCreatedAt(LocalDateTime.now());
        try {
            attachmentLinkMapper.insert(link);
            return link;
        } catch (DuplicateKeyException duplicate) {
            ServiceAttachmentLink raced = attachmentLinkMapper.selectByBusinessKey(tenantId, businessKey);
            if (raced != null) {
                return raced;
            }
            throw duplicate;
        }
    }

    private void requireTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw BusinessException.of(403, "error.tenant.contextRequired");
        }
    }
}
