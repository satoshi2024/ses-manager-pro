package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.constant.NotificationLinks;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.featureenablement.evaluation.FeatureEnablementDayStartHook;
import com.ses.featureenablement.service.FeatureEnablementNotifier;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.privacy.production.PrivacyDecisionBinding;
import com.ses.service.NotificationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * DSAR が閉じている（自動閉鎖を含む）間の未完了の請求の通知（feature-enablement-console design §7.5。review P3-21。FE-C05）。
 *
 * <p>法定の期限は受付の時に {@code due_date} へ書かれて進み続ける一方、期限の通知の scheduler（{@link PrivacyDsarDeadlineScheduler}）は
 * DSAR が閉じている間止まる。そこで日の始まりの評価の後（{@link FeatureEnablementDayStartHook}）に、DSAR の実効値が閉で、期限を追う状態
 * （{@link PrivacyDsarVocabulary#DEADLINE_TRACKED_STATES}）の請求があれば、privacy-admin（{@link PrivacyDsarRecipientResolver}）へ
 * 件数と最も近い期限日だけを通知する（受付番号・subject 参照・氏名を入れない。同じ業務日・宛先は1回だけ）。DSAR が開いていれば何もしない
 * （期限の通知は scheduler が出す）。遷移先は画面「機能の開放」（{@link NotificationLinks#FEATURE_ENABLEMENT}。閉じている間の
 * {@code /privacy/dsar} は 404 のため。宛先の privacy-admin は管理者で、この画面に入れる。review rb-fe-c P2-5）。</p>
 */
public class PrivacyDsarClosedNotifier implements FeatureEnablementDayStartHook {

    private static final Logger log = LoggerFactory.getLogger(PrivacyDsarClosedNotifier.class);

    static final String NOTIFICATION_TYPE = "PRIVACY_DSAR_CLOSED_PENDING";
    static final String LOG_CODE = "NF07_DSAR_CLOSED_PENDING";

    private final PrivacyRequestMapper requestMapper;
    private final PrivacyDsarRecipientResolver recipientResolver;
    private final NotificationService notificationService;
    private final OidcSecurityProperties oidcProperties;
    private final FeatureSwitches featureSwitches;

    public PrivacyDsarClosedNotifier(PrivacyRequestMapper requestMapper, PrivacyDsarRecipientResolver recipientResolver,
                                     NotificationService notificationService, OidcSecurityProperties oidcProperties,
                                     FeatureSwitches featureSwitches) {
        this.requestMapper = Objects.requireNonNull(requestMapper, "requestMapper");
        this.recipientResolver = Objects.requireNonNull(recipientResolver, "recipientResolver");
        this.notificationService = Objects.requireNonNull(notificationService, "notificationService");
        this.oidcProperties = oidcProperties;
        this.featureSwitches = featureSwitches;
    }

    @Override
    public void onBusinessDayStart(FeatureEnablementSnapshot snapshot, LocalDate today) {
        if (featureSwitches == null || featureSwitches.isEnabled(Feature.NF07_DSAR)) {
            return;
        }
        notifyPending(today);
    }

    /** 閉じている間の未完了の請求を通知する。送信を試みた件数（宛先ごと）を返す。 */
    int notifyPending(LocalDate today) {
        String tenantId = authorizationTenantId();
        return AccountingTenantContextHolder.runWithTenant(tenantId, PrivacyDecisionBinding.BUSINESS_ZONE,
                () -> notifyTenant(tenantId, today));
    }

    private int notifyTenant(String tenantId, LocalDate today) {
        Long pending = requestMapper.selectCount(new LambdaQueryWrapper<PrivacyRequest>()
                .eq(PrivacyRequest::getTenantId, tenantId)
                .in(PrivacyRequest::getStatus, PrivacyDsarVocabulary.DEADLINE_TRACKED_STATES));
        if (pending == null || pending == 0L) {
            return 0;
        }
        List<PrivacyRequest> nearest = requestMapper.selectPage(new Page<>(1, 1, false),
                new LambdaQueryWrapper<PrivacyRequest>()
                        .select(PrivacyRequest::getId, PrivacyRequest::getDueDate)
                        .eq(PrivacyRequest::getTenantId, tenantId)
                        .in(PrivacyRequest::getStatus, PrivacyDsarVocabulary.DEADLINE_TRACKED_STATES)
                        .isNotNull(PrivacyRequest::getDueDate)
                        .orderByAsc(PrivacyRequest::getDueDate)
                        .orderByAsc(PrivacyRequest::getId)).getRecords();
        LocalDate nearestDue = nearest.isEmpty() ? null : nearest.get(0).getDueDate();
        log.warn("{}: 本人請求（DSAR）が閉じている間に未完了の請求があります: pending={} nearestDueDate={}", LOG_CODE,
                pending, nearestDue == null ? "-" : nearestDue);
        List<Long> recipients = recipientResolver.resolve(tenantId);
        if (recipients.isEmpty()) {
            log.warn("{}: 通知の宛先（privacy-admin）がいません: pending={}", LOG_CODE, pending);
            return 0;
        }
        String title = "本人請求の機能が閉じている間に未完了の請求があります";
        String message = "未完了の請求 " + pending + " 件"
                + (nearestDue == null ? "（期限日の無い請求だけ）" : "。最も近い期限日は " + nearestDue + " です。")
                + "画面『機能の開放』で開き直すか、期限の管理を手で行ってください。";
        String dedupeKey = NOTIFICATION_TYPE + ":" + tenantId + ":" + today;
        int attempted = 0;
        for (Long userId : recipients) {
            try {
                notificationService.publishToUser(userId, NOTIFICATION_TYPE, title, message,
                        NotificationLinks.FEATURE_ENABLEMENT, dedupeKey, FeatureEnablementNotifier.MENU_KEY);
                attempted++;
            } catch (RuntimeException e) {
                log.warn("[privacy-dsar] 閉じている間の通知を登録できません: exceptionClass={}", e.getClass().getSimpleName());
            }
        }
        return attempted;
    }

    private String authorizationTenantId() {
        String configured = oidcProperties == null ? null : oidcProperties.getTenantId();
        return StringUtils.hasText(configured) ? configured.trim() : "default";
    }
}
