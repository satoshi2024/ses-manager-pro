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

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 添付業務リンク確定の失敗を再試行可能な台帳へ記録する。
 * 台帳の日時（次回再試行・作成・解決）は注入したClockで決める（テストで時刻を固定できるようにする。P3-N2-6）。
 */
@Service
public class ServiceRequestAttachmentCompensationService {

    /** 失敗した確定を再試行するまでの待ち時間（分）。 */
    static final long RETRY_DELAY_MINUTES = 5;

    private final ServiceRequestAttachmentCompensationMapper mapper;
    private final ServiceRequestAttachmentCommitService commitService;
    private final Clock clock;

    @Autowired(required = false)
    private PlatformTransactionManager transactionManager;

    public ServiceRequestAttachmentCompensationService(ServiceRequestAttachmentCompensationMapper mapper,
                                                       ServiceRequestAttachmentCommitService commitService,
                                                       Clock clock) {
        this.mapper = mapper;
        this.commitService = commitService;
        this.clock = clock;
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
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
        LocalDateTime now = now();
        row.setNextRetryAt(now.plusMinutes(RETRY_DELAY_MINUTES));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        try {
            mapper.insert(row);
        } catch (DuplicateKeyException ignored) {
            // 同一業務キーの補償台帳は一行へ収束させる。
        }
    }

    public int retryDue(int limit) {
        int completed = 0;
        for (ServiceRequestAttachmentCompensation row : mapper.selectDue(now(), limit)) {
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
            LocalDateTime now = now();
            row.setResolvedAt(now);
            row.setUpdatedAt(now);
            mapper.updateById(row);
        });
    }

    protected void retry(ServiceRequestAttachmentCompensation row, Exception failure) {
        inShortTransaction(() -> {
            row.setAttemptCount((row.getAttemptCount() == null ? 0 : row.getAttemptCount()) + 1);
            row.setLastError(failure.getClass().getSimpleName());
            LocalDateTime now = now();
            row.setNextRetryAt(now.plusMinutes(RETRY_DELAY_MINUTES));
            row.setUpdatedAt(now);
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
