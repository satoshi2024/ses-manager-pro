package com.ses.service.integrationhub;

import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldPathGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * NF-05 retention/nonceをrequest pathから分離してbounded purgeするscheduler。
 * inbound/outboundのどちらかが有効なら生成する（{@link IntegrationHubRetentionPurgeCondition}）。
 *
 * <p>NF07 A1H-2（D7・D8）: record の purge（D7）は回の初めに保全 hold の gate を1回だけ照会し（hold 機構を使った全 tenant。
 * 使っていなければ照会1回）、record kind の data element（{@code INTEGRATION_HUB_<KIND>}、NOT_ATTRIBUTABLE）に
 * DATA_ELEMENT hold がある・分類が UNKNOWN・gate が判定できない場合はその kind を飛ばして件数を警告する。record は本人へ
 * 帰属できないため SUBJECT hold では止めない。nonce の purge（D8）は個人情報を含まないため gate に接続しない。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Conditional(IntegrationHubRetentionPurgeCondition.class)
public class IntegrationHubRetentionPurgeScheduler {

    private static final List<String> RECORD_KINDS = PrivacyHoldPath.INTEGRATION_HUB_KINDS;
    private static final List<String> RETENTION_CLASSES = List.of(
            IntegrationHubStates.RETENTION_SUCCEEDED_30D,
            IntegrationHubStates.RETENTION_FAILED_90D,
            IntegrationHubStates.RETENTION_AUDIT_1Y);
    /** 有効なkind/classの組み合わせを毎回同じ順序で走査し、未使用組み合わせも将来追加に備えて漏らさない。 */
    private static final List<PurgeTarget> TARGETS = buildTargets();

    private final ApiRetentionPurgeService retentionPurgeService;
    private final ApiNonceReplayService nonceReplayService;
    private final IntegrationHubExternalApiProperties properties;
    private final Clock clock;
    private final PrivacyHoldPathGuard privacyHoldPathGuard;

    @Scheduled(fixedDelayString = "${integration.hub.public-api.retention-purge-fixed-delay-ms:60000}")
    @SchedulerLock(name = "integrationHubRetentionPurge", lockAtLeastFor = "PT5S", lockAtMostFor = "PT5M")
    public void purgeExpired() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        int batchSize = properties.getPublicApi().getRetentionPurgeBatchSize();
        PrivacyHoldPathGuard.Batch hold = null;
        try {
            hold = privacyHoldPathGuard.openGlobalBatch(PrivacyHoldPath.D7);
        } catch (RuntimeException ex) {
            log.warn("[integration-hub retention] 保全holdを判定できないためrecordのpurgeを見送ります: errorType={}",
                    ex.getClass().getSimpleName());
        }
        try {
            for (PurgeTarget target : TARGETS) {
                PrivacyHoldGate.Verdict verdict = hold == null ? PrivacyHoldGate.Verdict.UNAVAILABLE
                        : hold.elementVerdict(PrivacyHoldPath.integrationHubElement(target.recordKind()));
                if (verdict != PrivacyHoldGate.Verdict.ALLOW) {
                    // その kind の record を消さない（件数は kind × retention class の対象数）
                    if (hold != null) {
                        hold.skip(verdict);
                    }
                    continue;
                }
                try {
                    retentionPurgeService.purgeExpired(target.recordKind(), target.retentionClass(), now, batchSize);
                } catch (RuntimeException ex) {
                    // 1対象の障害で他対象を止めず、同じpredicateを次回再試行する。
                    log.warn("[integration-hub retention] purge失敗: recordKind={}, retentionClass={}, errorType={}",
                            target.recordKind(), target.retentionClass(), ex.getClass().getSimpleName());
                }
            }
        } finally {
            if (hold != null) {
                hold.close();
            }
        }
        // D8: nonce は個人情報を含まないため保全holdに関係なく消す
        try {
            nonceReplayService.purgeExpired(now, batchSize);
        } catch (RuntimeException ex) {
            log.warn("[integration-hub nonce] purge失敗: errorType={}", ex.getClass().getSimpleName());
        }
    }

    private record PurgeTarget(String recordKind, String retentionClass) {
    }

    private static List<PurgeTarget> buildTargets() {
        List<PurgeTarget> targets = new java.util.ArrayList<>();
        for (String recordKind : RECORD_KINDS) {
            for (String retentionClass : RETENTION_CLASSES) {
                targets.add(new PurgeTarget(recordKind, retentionClass));
            }
        }
        return List.copyOf(targets);
    }
}
