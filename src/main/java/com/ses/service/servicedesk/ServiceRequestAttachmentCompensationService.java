package com.ses.service.servicedesk;

import com.ses.entity.ServiceAttachmentLink;
import com.ses.entity.ServiceRequestAttachmentCompensation;
import com.ses.mapper.ServiceRequestAttachmentCompensationMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/** 添付業務リンク確定の失敗を再試行可能な台帳へ記録する。 */
@Service
public class ServiceRequestAttachmentCompensationService {

    private final ServiceRequestAttachmentCompensationMapper mapper;
    private final ServiceRequestAttachmentCommitService commitService;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public ServiceRequestAttachmentCompensationService(ServiceRequestAttachmentCompensationMapper mapper,
                                                       ServiceRequestAttachmentCommitService commitService) {
        this.mapper = mapper;
        this.commitService = commitService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void record(String tenantId, Long requestId, Long commentId, Long documentId, String visibility,
                       String fileName, Long fileSize, String businessKey, Exception failure) {
        if (mapper.selectRetry(tenantId, businessKey) != null) return;
        ServiceRequestAttachmentCompensation row = new ServiceRequestAttachmentCompensation();
        row.setTenantId(tenantId);
        row.setServiceRequestId(requestId);
        row.setCommentId(commentId);
        row.setDocumentId(documentId);
        row.setVisibility(visibility);
        row.setFileName(fileName);
        row.setFileSize(fileSize);
        row.setBusinessKey(businessKey);
        row.setStatus("RETRY");
        row.setAttemptCount(1);
        row.setLastError(failure == null ? null : failure.getClass().getSimpleName());
        row.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
        row.setCreatedAt(LocalDateTime.now());
        row.setUpdatedAt(LocalDateTime.now());
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // 同一業務キーの補償台帳は一行へ収束させる。
        }
    }

    public int retryDue(int limit) {
        int completed = 0;
        for (ServiceRequestAttachmentCompensation row : mapper.selectDue(limit)) {
            try {
                ServiceAttachmentLink link = commitService.commit(row.getTenantId(), row.getServiceRequestId(),
                        row.getCommentId(), row.getDocumentId(), row.getVisibility(), row.getFileName(), row.getFileSize(),
                        row.getBusinessKey());
                if (link != null) {
                    markCompleted(row);
                    completed++;
                }
            } catch (Exception failure) {
                retry(row, failure);
            }
        }
        return completed;
    }

    protected void markCompleted(ServiceRequestAttachmentCompensation row) {
        inShortTransaction(() -> {
            row.setStatus("COMPLETED");
            row.setResolvedAt(LocalDateTime.now());
            row.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(row);
        });
    }

    protected void retry(ServiceRequestAttachmentCompensation row, Exception failure) {
        inShortTransaction(() -> {
            row.setAttemptCount((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1);
            row.setLastError(failure.getClass().getSimpleName());
            row.setNextRetryAt(LocalDateTime.now().plusMinutes(5));
            row.setUpdatedAt(LocalDateTime.now());
            mapper.updateById(row);
        });
    }

    private void inShortTransaction(Runnable work) {
        if (transactionManager == null) {
            work.run();
            return;
        }
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.executeWithoutResult(status -> work.run());
    }
}
