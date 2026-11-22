package com.ses.featureenablement.service;

import com.ses.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 機能の開放の通知（design §7.5）。commit の後に best-effort で送る（失敗しても遷移は戻さず log だけ）。宛先は有効な管理者全員
 * （申請の通知は申請者を除く）。文言は message bundle の日本語（base bundle）で作り、値・digest・氏名を含めない（開放単位の
 * code と表示名・理由の code・日付だけ）。dedupe key は開放単位・事象・申請・業務日で作る。
 */
@Slf4j
@Component
public class FeatureEnablementNotifier {

    /** 通知の種類（{@code t_notification.type}）。 */
    public static final String NOTIFICATION_TYPE = "FEATURE_ENABLEMENT";
    /** 通知の link と menu key。 */
    public static final String LINK = "/feature-enablement";
    public static final String MENU_KEY = "feature-enablement";

    private final ObjectProvider<NotificationService> notificationService;
    private final ObjectProvider<FeatureEnablementActorPolicy> actorPolicy;
    private final MessageSource messageSource;
    private final com.ses.featureenablement.state.FeatureEnablementTenant tenant;

    public FeatureEnablementNotifier(ObjectProvider<NotificationService> notificationService,
                                     ObjectProvider<FeatureEnablementActorPolicy> actorPolicy,
                                     MessageSource messageSource,
                                     com.ses.featureenablement.state.FeatureEnablementTenant tenant) {
        this.notificationService = notificationService;
        this.actorPolicy = actorPolicy;
        this.messageSource = messageSource;
        this.tenant = tenant;
    }

    /**
     * 有効な管理者全員へ通知する（transaction の中なら commit の後、外なら直ちに）。
     *
     * @param eventKey  事象の key（{@code featureEnablement.notice.<eventKey>.title/message}）
     * @param args      文言の引数（code・表示名・日付だけ）
     * @param dedupeKey 重複を除く key
     * @param excluded  送らない利用者（申請者等。null は無し）
     */
    public void notifyAdministrators(String eventKey, Object[] args, String dedupeKey, Collection<Long> excluded) {
        List<Long> excludedIds = excluded == null ? List.of() : List.copyOf(excluded);
        afterCommit(() -> sendInTenant(eventKey, args, dedupeKey, excludedIds, null));
    }

    /** 1名へ通知する（却下・取下げの申請者への通知等）。 */
    public void notifyUser(Long userId, String eventKey, Object[] args, String dedupeKey) {
        if (userId == null) {
            return;
        }
        afterCommit(() -> sendInTenant(eventKey, args, dedupeKey, List.of(), List.of(userId)));
    }

    /** 設定の tenant の文脈で送る（scheduler・起動時の評価は tenant の文脈を持たない）。 */
    private void sendInTenant(String eventKey, Object[] args, String dedupeKey, List<Long> excluded,
                              List<Long> recipients) {
        try {
            com.ses.service.accounting.AccountingTenantContextHolder.runWithTenant(tenant.tenantId(),
                    () -> send(eventKey, args, dedupeKey, excluded, recipients));
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 通知を送れません: event={} exceptionClass={}", eventKey, e.getClass().getSimpleName());
        }
    }

    private void send(String eventKey, Object[] args, String dedupeKey, List<Long> excluded, List<Long> recipients) {
        NotificationService notifications = notificationService == null ? null : notificationService.getIfAvailable();
        if (notifications == null) {
            return;
        }
        List<Long> targets;
        try {
            if (recipients != null) {
                targets = recipients;
            } else {
                FeatureEnablementActorPolicy policy = actorPolicy == null ? null : actorPolicy.getIfAvailable();
                targets = policy == null ? List.of() : policy.activeAdministratorIds();
            }
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 通知の宛先を読めません: event={} exceptionClass={}", eventKey,
                    e.getClass().getSimpleName());
            return;
        }
        String title = message("featureEnablement.notice." + eventKey + ".title", args);
        String body = message("featureEnablement.notice." + eventKey + ".message", args);
        for (Long userId : targets) {
            if (userId == null || excluded.contains(userId)) {
                continue;
            }
            try {
                notifications.publishToUser(userId, NOTIFICATION_TYPE, title, body, LINK, dedupeKey + ":" + userId,
                        MENU_KEY);
            } catch (RuntimeException e) {
                log.warn("[機能の開放] 通知を登録できません: event={} exceptionClass={}", eventKey,
                        e.getClass().getSimpleName());
            }
        }
    }

    private String message(String key, Object[] args) {
        try {
            return messageSource.getMessage(key, args, Locale.JAPANESE);
        } catch (RuntimeException e) {
            return key;
        }
    }

    private static void afterCommit(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
