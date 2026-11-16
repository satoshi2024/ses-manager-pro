package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.privacy.production.PrivacyDecisionBinding;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * DSAR の期限接近・超過を privacy-admin へ知らせる日次 scheduler（NF07-A2a、production-bridge-phased-plan.md §3.5.1）。
 *
 * <p>機能スイッチ {@code NF07_DSAR} が OFF なら何もしない（{@code FeatureSwitchRouteCoverageTest} の gated scheduler）。
 * test profile は {@code app.scheduling.enabled=false} で定期実行そのものが止まる。対象は権限評価の設定 tenant の、期限を追う状態
 * （{@link PrivacyDsarVocabulary#DEADLINE_TRACKED_STATES}）で期限日が「今日 + 通知日数」以内の case。期限日の無い case
 * （D-06 の期限規則が未設定のまま受け付けたもの）は対象にしない。
 *
 * <p>宛先は {@link PrivacyDsarRecipientResolver}（{@code privacy-admin} group の所属者のうち {@code privacy.dsar.view} を持つ者）で、
 * 管理者 role の全員には送らない。本文は受付番号と期限日だけで、subject 参照・氏名・ticket を入れない。同じ case・期限日・段階
 * （期限接近 / 超過）・宛先の通知は1回だけ（notification の dedupe key）。log は件数と例外の種類だけを出す。
 */
public class PrivacyDsarDeadlineScheduler {

    private static final Logger log = LoggerFactory.getLogger(PrivacyDsarDeadlineScheduler.class);

    static final String NOTIFICATION_TYPE = "PRIVACY_DSAR_DUE";
    static final String MENU_KEY = "privacy-dsar";
    static final String STAGE_DUE_SOON = "DUE_SOON";
    static final String STAGE_OVERDUE = "OVERDUE";
    static final String LOG_CODE_NO_RECIPIENT = "NF07_DSAR_DEADLINE_NO_RECIPIENT";
    /** 1回の実行で扱う case の上限（期限日の早い順）。 */
    static final int MAX_REQUESTS_PER_RUN = 500;

    private final PrivacyRequestMapper requestMapper;
    private final PrivacyDsarRecipientResolver recipientResolver;
    private final NotificationService notificationService;
    private final PrivacyDsarDecisions decisions;
    private final OidcSecurityProperties oidcProperties;
    private final Clock clock;
    /** NF07 DSAR の機能スイッチ。Spring 管理外で生成した単体テスト対象では null（{@link FeatureSwitches#allows}）。 */
    private final FeatureSwitches featureSwitches;

    public PrivacyDsarDeadlineScheduler(PrivacyRequestMapper requestMapper, PrivacyDsarRecipientResolver recipientResolver,
                                        NotificationService notificationService, PrivacyDsarDecisions decisions,
                                        OidcSecurityProperties oidcProperties, Clock clock,
                                        FeatureSwitches featureSwitches) {
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper");
        this.recipientResolver = Objects.requireNonNull(recipientResolver, "recipientResolver");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.decisions = Objects.requireNonNull(decisions, "decisions");
        this.oidcProperties = oidcProperties;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.featureSwitches = featureSwitches;
    }

    @Scheduled(cron = "0 30 8 * * ?", zone = "Asia/Tokyo")
    @SchedulerLock(name = "privacyDsarDeadlineDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
    public int notifyDaily() {
        if (!FeatureSwitches.allows(featureSwitches, Feature.NF07_DSAR)) {
            log.debug("[機能スイッチ] NF07_DSARがOFFのためDSARの期限通知をスキップしました");
            return 0;
        }
        return dispatch(PrivacyDecisionBinding.businessDate(clock));
    }

    /** {@code today}（JST 業務日）時点の通知を送る。送信を試みた件数（宛先ごと）を返す。 */
    public int dispatch(LocalDate today) {
        String tenantId = authorizationTenantId();
        return AccountingTenantContextHolder.runWithTenant(tenantId, PrivacyDecisionBinding.BUSINESS_ZONE,
                () -> dispatchTenant(tenantId, today));
    }

    private int dispatchTenant(String tenantId, LocalDate today) {
        LocalDate horizon = today.plusDays(decisions.deadlineWarningDays());
        List<PrivacyRequest> due = requestMapper.selectList(new LambdaQueryWrapper<PrivacyRequest>()
                .select(PrivacyRequest::getId, PrivacyRequest::getRequestNo, PrivacyRequest::getStatus,
                        PrivacyRequest::getDueDate)
                .eq(PrivacyRequest::getTenantId, tenantId)
                .in(PrivacyRequest::getStatus, PrivacyDsarVocabulary.DEADLINE_TRACKED_STATES)
                .isNotNull(PrivacyRequest::getDueDate)
                .le(PrivacyRequest::getDueDate, horizon)
                .orderByAsc(PrivacyRequest::getDueDate)
                .orderByAsc(PrivacyRequest::getId)
                .last("LIMIT " + MAX_REQUESTS_PER_RUN));
        if (due.isEmpty()) {
            return 0;
        }
        List<Long> recipients = recipientResolver.resolve(tenantId);
        if (recipients.isEmpty()) {
            log.warn("{}: 期限が近いDSARがありますが通知の宛先（privacy-admin）がいません: requests={}",
                    LOG_CODE_NO_RECIPIENT, due.size());
            return 0;
        }
        int attempted = 0;
        for (PrivacyRequest request : due) {
            boolean overdue = request.getDueDate().isBefore(today);
            String stage = overdue ? STAGE_OVERDUE : STAGE_DUE_SOON;
            String title = overdue ? "本人請求の期限を過ぎています" : "本人請求の期限が近づいています";
            String message = "受付番号 " + request.getRequestNo() + " の期限は " + request.getDueDate() + " です。";
            String dedupeKey = NOTIFICATION_TYPE + ":" + tenantId + ":" + request.getId() + ":" + request.getDueDate()
                    + ":" + stage;
            for (Long userId : recipients) {
                try {
                    notificationService.publishToUser(userId, NOTIFICATION_TYPE, title, message,
                            "/privacy/dsar/" + request.getId(), dedupeKey, MENU_KEY);
                    attempted++;
                } catch (RuntimeException e) {
                    log.warn("[privacy-dsar] 期限通知を登録できません: exceptionClass={}", e.getClass().getSimpleName());
                }
            }
        }
        return attempted;
    }

    private String authorizationTenantId() {
        String configured = oidcProperties == null ? null : oidcProperties.getTenantId();
        return StringUtils.hasText(configured) ? configured.trim() : "default";
    }
}
