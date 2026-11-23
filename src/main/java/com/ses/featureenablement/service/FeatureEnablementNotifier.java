package com.ses.featureenablement.service;

import com.ses.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 機能の開放の通知（design §7.5）。commit の後に best-effort で送る（失敗しても遷移は戻さず log だけ）。宛先は有効な管理者全員
 * （申請の通知は申請者を除く）。文言は message bundle の日本語（base bundle）で作り、値・digest・氏名を含めない（開放単位の
 * code と表示名・理由の code・日付だけ）。dedupe key は開放単位・事象・申請・業務日で作る。
 *
 * <ul>
 *   <li>宛先は遷移の transaction の中で決める（同じ transaction で管理者になった利用者も含む）。</li>
 *   <li>commit の後の DB の書込みは {@code REQUIRES_NEW} の新しい transaction で行う（afterCommit の中の REQUIRED は完了済みの
 *       transaction の resource に参加し、通知の outbox の即時配信の afterCommit が呼ばれないため。
 *       {@code LifecycleCaseServiceImpl#publishCaseCompletedAfterCommit} と {@code LifecycleDualConfirmationNotifier} と同じ規則。
 *       review rb-fe-a1 P2-2）。宛先ごとに別の transaction にし、1名の失敗が他の宛先を止めない。</li>
 * </ul>
 */
@Slf4j
@Component
public class FeatureEnablementNotifier {

    /** 通知の種類（{@code t_notification.type}）。 */
    public static final String NOTIFICATION_TYPE = "FEATURE_ENABLEMENT";
    /** 通知の link と menu key。 */
    public static final String LINK = "/feature-enablement";
    public static final String MENU_KEY = "feature-enablement";

    /** commit の後に送る通知（宛先は遷移の transaction の中で決めた値）。 */
    public record Notice(String eventKey, Object[] args, String dedupeKey, List<Long> recipients) {

        public Notice {
            args = args == null ? new Object[0] : args.clone();
            recipients = recipients == null ? List.of() : List.copyOf(recipients);
        }
    }

    /** commit の後に通知を送る同期（試験は登録された通知を読む。{@link #notice()}）。 */
    public static final class AfterCommitDelivery implements TransactionSynchronization {

        private final FeatureEnablementNotifier notifier;
        private final Notice notice;

        AfterCommitDelivery(FeatureEnablementNotifier notifier, Notice notice) {
            this.notifier = notifier;
            this.notice = notice;
        }

        public Notice notice() {
            return notice;
        }

        @Override
        public void afterCommit() {
            notifier.deliver(notice);
        }
    }

    private final ObjectProvider<NotificationService> notificationService;
    private final ObjectProvider<FeatureEnablementActorPolicy> actorPolicy;
    private final MessageSource messageSource;
    private final com.ses.featureenablement.state.FeatureEnablementTenant tenant;
    private final ObjectProvider<PlatformTransactionManager> transactionManager;

    public FeatureEnablementNotifier(ObjectProvider<NotificationService> notificationService,
                                     ObjectProvider<FeatureEnablementActorPolicy> actorPolicy,
                                     MessageSource messageSource,
                                     com.ses.featureenablement.state.FeatureEnablementTenant tenant,
                                     ObjectProvider<PlatformTransactionManager> transactionManager) {
        this.notificationService = notificationService;
        this.actorPolicy = actorPolicy;
        this.messageSource = messageSource;
        this.tenant = tenant;
        this.transactionManager = transactionManager;
    }

    /**
     * 有効な管理者全員へ通知する（宛先はこの時点で決め、送るのは transaction の中なら commit の後、外なら直ちに）。
     *
     * @param eventKey  事象の key（{@code featureEnablement.notice.<eventKey>.title/message}）
     * @param args      文言の引数（code・表示名・日付だけ）
     * @param dedupeKey 重複を除く key
     * @param excluded  送らない利用者（申請者等。null は無し）
     */
    public void notifyAdministrators(String eventKey, Object[] args, String dedupeKey, Collection<Long> excluded) {
        List<Long> recipients = new ArrayList<>();
        for (Long userId : administrators(eventKey)) {
            if (userId != null && (excluded == null || !excluded.contains(userId))) {
                recipients.add(userId);
            }
        }
        schedule(new Notice(eventKey, args, dedupeKey, recipients));
    }

    /** 1名へ通知する（却下・取下げの申請者への通知等）。 */
    public void notifyUser(Long userId, String eventKey, Object[] args, String dedupeKey) {
        if (userId == null) {
            return;
        }
        schedule(new Notice(eventKey, args, dedupeKey, List.of(userId)));
    }

    private void schedule(Notice notice) {
        Objects.requireNonNull(notice, "notice");
        if (notice.recipients().isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new AfterCommitDelivery(this, notice));
        } else {
            deliver(notice);
        }
    }

    /** 通知を送る（宛先ごとに REQUIRES_NEW の transaction と設定の tenant の文脈で書く）。 */
    void deliver(Notice notice) {
        NotificationService notifications = notificationService == null ? null : notificationService.getIfAvailable();
        if (notifications == null) {
            return;
        }
        String title = message("featureEnablement.notice." + notice.eventKey() + ".title", notice.args());
        String body = message("featureEnablement.notice." + notice.eventKey() + ".message", notice.args());
        for (Long userId : notice.recipients()) {
            try {
                inNewTransaction(() -> com.ses.service.accounting.AccountingTenantContextHolder.runWithTenant(
                        tenant.tenantId(), () -> notifications.publishToUser(userId, NOTIFICATION_TYPE, title, body,
                                LINK, notice.dedupeKey() + ":" + userId, MENU_KEY)));
            } catch (RuntimeException e) {
                log.warn("[機能の開放] 通知を登録できません: event={} exceptionClass={}", notice.eventKey(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /** 宛先の有効な管理者（遷移の transaction の中で読む。読めなければ送らない）。 */
    private List<Long> administrators(String eventKey) {
        FeatureEnablementActorPolicy policy = actorPolicy == null ? null : actorPolicy.getIfAvailable();
        if (policy == null) {
            return List.of();
        }
        try {
            return policy.activeAdministratorIds();
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 通知の宛先を読めません: event={} exceptionClass={}", eventKey,
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private void inNewTransaction(Runnable action) {
        PlatformTransactionManager manager = transactionManager == null ? null : transactionManager.getIfAvailable();
        if (manager == null) {
            action.run();
            return;
        }
        TransactionTemplate requiresNew = new TransactionTemplate(manager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        requiresNew.executeWithoutResult(status -> action.run());
    }

    private String message(String key, Object[] args) {
        try {
            return messageSource.getMessage(key, args, Locale.JAPANESE);
        } catch (RuntimeException e) {
            return key;
        }
    }
}
