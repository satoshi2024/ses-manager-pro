package com.ses.featureenablement.evaluation;

import com.ses.featureenablement.condition.FeatureEvaluationContext;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 機能の開放の定期の評価（feature-enablement-console design §7.2）。どちらも機能のスイッチで止めない（開いている開放単位を閉じる側の
 * 処理で、止めると条件が外れても開いたままになる）。{@code app.scheduling.enabled=false} の間は、期限による閉鎖（hot path）と
 * 変更の時の評価だけが働く。
 */
@Slf4j
@Component
public class FeatureEnablementConditionScheduler {

    private final FeatureEnablementEvaluationService evaluation;

    public FeatureEnablementConditionScheduler(FeatureEnablementEvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    /** 開いている開放単位と副入口を評価し、自動閉鎖の書込み待ちを再試行する（既定 5 分ごと）。 */
    @Scheduled(fixedDelayString = "${app.feature-enablement.evaluation-interval:PT5M}",
            initialDelayString = "${app.feature-enablement.evaluation-initial-delay:PT1M}")
    @SchedulerLock(name = "featureEnablementEvaluation", lockAtLeastFor = "PT10S", lockAtMostFor = "PT10M")
    public void evaluateAll() {
        try {
            evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.PERIODIC, false);
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 定期の評価に失敗しました: errorCode=FEATURE_ENABLEMENT_EVALUATION_FAILED exceptionClass={}",
                    e.getClass().getSimpleName());
        }
    }

    /** JST の日の始まり（0:00:05）の処理: 失効の反映・申請の期限切れ・失効の接近の通知・開放の時だけの条件の警告。 */
    @Scheduled(cron = "5 0 0 * * *", zone = "Asia/Tokyo")
    @SchedulerLock(name = "featureEnablementDayStart", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void evaluateAtBusinessDayStart() {
        try {
            evaluation.evaluateAtBusinessDayStart();
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 日の始まりの処理に失敗しました: errorCode=FEATURE_ENABLEMENT_DAY_START_FAILED exceptionClass={}",
                    e.getClass().getSimpleName());
        }
    }
}
