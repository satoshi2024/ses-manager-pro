package com.ses.service.lifecycle.dual;

import com.ses.common.util.LogRedaction;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.service.lifecycle.LifecycleNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 二者確認の通知をattemptのcommit後に送る（NF01細粒度epoch計画 §5.4「afterCommit」）。
 *
 * <p>{@code LifecycleCaseServiceImpl#publishCaseCompletedAfterCommit} と同じ形で、attemptのtransactionに
 * afterCommitを登録し、commit後に {@code REQUIRES_NEW} の {@link TransactionTemplate} で通知を書く。attemptがrollbackすれば
 * 何も送らない。通知の失敗はWARNだけで、確定済みの提出・確認の応答を変えない。</p>
 */
@Slf4j
@Component
public class LifecycleDualConfirmationNotifier {

    private final ObjectProvider<LifecycleNotificationService> notificationService;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;

    public LifecycleDualConfirmationNotifier(ObjectProvider<LifecycleNotificationService> notificationService,
                                             ObjectProvider<PlatformTransactionManager> transactionManager) {
        this.notificationService = notificationService;
        this.transactionManager = transactionManager;
    }

    /** 確認待ちの通知を登録する（提出者本人は宛先から除く）。 */
    public void submittedAfterCommit(LifecycleTask task, LifecycleCase lcCase, long submissionId, long submitterUserId) {
        afterCommit("提出", submissionId, service -> service.notifyDualConfirmationSubmitted(task, lcCase, submissionId,
                submitterUserId));
    }

    /** 確認・差戻しの結果の通知を登録する。 */
    public void decidedAfterCommit(LifecycleTask task, LifecycleCase lcCase, long submissionId, long submitterUserId,
                                   boolean confirmed, boolean selfService) {
        afterCommit(confirmed ? "確認" : "差戻し", submissionId, service -> service.notifyDualConfirmationDecided(task,
                lcCase, submissionId, submitterUserId, confirmed, selfService));
    }

    private void afterCommit(String label, long submissionId,
                             java.util.function.Consumer<LifecycleNotificationService> send) {
        Runnable publish = () -> {
            LifecycleNotificationService service = notificationService.getIfAvailable();
            if (service == null) {
                return;
            }
            try {
                PlatformTransactionManager manager = transactionManager.getIfAvailable();
                if (manager == null) {
                    send.accept(service);
                    return;
                }
                TransactionTemplate requiresNew = new TransactionTemplate(manager);
                requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                requiresNew.executeWithoutResult(status -> send.accept(service));
            } catch (RuntimeException e) {
                log.warn("[二者確認] {}の通知に失敗しました（確定済みの結果は変わりません）: submissionId={} exceptionType={}",
                        label, submissionId, LogRedaction.exceptionType(e));
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
