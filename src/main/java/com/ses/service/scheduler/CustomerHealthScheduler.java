package com.ses.service.scheduler;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.LogRedaction;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureSwitches;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.servicedesk.CustomerHealthService;
import com.ses.service.servicedesk.SnapshotExecutionContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;

/**
 * 顧客ヘルススコア・月次スナップショット日次バッチスケジューラ
 *
 * <p>対象月はtenantごとに、そのtenantのtimezoneの当月で決める（serviceの当月判定と同じClock・同じtenant timezone。
 * JVMのtimezoneで決めると、月初にtenantの当月とずれて過去月として拒否される。RB-I18N review P3-1）。
 * 1 tenantの失敗は当該tenantだけを中断し、ERRORとmetricに残して残りのtenantを続行する。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomerHealthScheduler {

    /** tenant単位で中断したsnapshot（outcome=rejected: 業務例外、failed: その他の例外）。 */
    static final String TENANT_FAILURE_METRIC = "ses.customer.health.snapshot.tenant.failures";

    private final CustomerHealthService customerHealthService;
    private final Clock clock;
    private final TenantAwareBatchRunner tenantAwareBatchRunner;
    /** NF-02機能スイッチ。Spring管理外で生成した単体テスト対象ではnull（従来どおり実行）。 */
    @org.springframework.beans.factory.annotation.Autowired
    private FeatureSwitches featureSwitches;
    /** metric。slice test・Spring管理外ではnull（記録しない）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private MeterRegistry meterRegistry;

    @Scheduled(cron = "${customerhealth.snapshot.cron:0 0 2 * * *}")
    @SchedulerLock(name = "customerHealthSnapshotDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void runDailySnapshot() {
        if (!FeatureSwitches.allows(featureSwitches, Feature.NF02)) {
            log.debug("[機能スイッチ] NF02がOFFのため顧客ヘルス日次スナップショットをスキップしました");
            return;
        }
        processDailySnapshot(null);
    }

    /**
     * テストおよび手動実行用エントリーポイント。
     *
     * @param targetMonth 対象月（YYYY-MM）。nullならtenantごとにそのtenantのtimezoneの当月
     */
    public void processDailySnapshot(String targetMonth) {
        String label = targetMonth == null ? "tenantの当月" : targetMonth;
        log.info("顧客ヘルス日次スナップショットバッチ実行開始: targetMonth={}", label);
        tenantAwareBatchRunner.run(tenantId -> {
            String month = targetMonth != null ? targetMonth : tenantCurrentMonth();
            log.debug("顧客ヘルス日次スナップショットtenant処理開始: tenantId={}, targetMonth={}", tenantId, month);
            try {
                customerHealthService.generateMonthlySnapshot(month, "日次定期バッチによるスナップショット更新",
                        SnapshotExecutionContext.systemScheduler());
            } catch (RuntimeException tenantFailure) {
                // 例外のmessage（SQL・値を含み得る）は出さず、型と原因の型の連鎖だけの要約を出す。
                boolean rejected = tenantFailure instanceof BusinessException;
                log.error("顧客ヘルス日次スナップショットのtenant処理が失敗しました。当該tenantだけを中断し、残りのtenantは"
                                + "続行します: tenantId={} targetMonth={} errorCode={} detail={}",
                        tenantId, month, rejected ? "CUSTOMER_HEALTH_SNAPSHOT_REJECTED" : "CUSTOMER_HEALTH_SNAPSHOT_FAILED",
                        LogRedaction.safeThrowableSummary(tenantFailure));
                recordTenantFailure(rejected ? "rejected" : "failed");
            }
        });
        log.info("顧客ヘルス日次スナップショットバッチ実行完了: targetMonth={}", label);
    }

    /** runnerが設定した実行中tenantのtimezoneでの当月（serviceの {@code tenantNow()} と同じ基準）。 */
    private String tenantCurrentMonth() {
        return YearMonth.from(LocalDateTime.now(clock.withZone(AccountingTenantContextHolder.getZoneId()))).toString();
    }

    private void recordTenantFailure(String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(TENANT_FAILURE_METRIC)
                .description("tenant単位で中断した顧客ヘルス日次スナップショット")
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }
}
