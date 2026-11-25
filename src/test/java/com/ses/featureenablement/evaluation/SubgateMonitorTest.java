package com.ses.featureenablement.evaluation;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementNotifier;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FE-C04（feature-enablement-console design §7.4・§8.5 の 9。review P3-16・P3-17）: 副入口は DB の状態を持たず、親の開放単位・目録の
 * 署名・固有の条件（trust の構造等）で成否を決め、変化したら履歴と通知を出し、条件が戻れば自動で開く。D-05 の失効や trust の構造の
 * 不足は副入口だけを閉じ、保持・保全の開放単位は開いたままにする。
 */
class SubgateMonitorTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();

    @Test
    void D05の失効で副入口だけが閉じ戻れば自動で開き変化ごとに履歴と通知を出す() {
        AtomicBoolean trustSound = new AtomicBoolean(true);
        SubgateMonitor monitor = new SubgateMonitor(catalog, conditions(
                SubgateMonitor.Condition.of("REL", trustSound::get)));
        FeatureEnablementEvaluationWriter writer = mock(FeatureEnablementEvaluationWriter.class);
        FeatureEnablementNotifier notifier = mock(FeatureEnablementNotifier.class);

        Map<String, Boolean> first = monitor.track(snapshot(TODAY.plusDays(30)), TODAY, writer, notifier);
        assertThat(first).containsEntry("HOLD_START", true).containsEntry("REL", true).containsEntry("REG", false);
        verify(writer, never()).recordSubgateChange(anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        // D-05 の失効（副入口 HOLD_START・REL の条件で、開放単位の条件ではない）
        FeatureEnablementSnapshot expired = snapshot(TODAY);
        Map<String, Boolean> closed = monitor.track(expired, TODAY, writer, notifier);
        assertThat(closed).containsEntry("HOLD_START", false).containsEntry("REL", false);
        assertThat(expired.unit(Feature.NF07_RETENTION).state().openLike()).as("保持・保全は開いたまま").isTrue();
        verify(writer).recordSubgateChange("HOLD_START", false);
        verify(writer).recordSubgateChange("REL", false);
        verify(notifier, times(2)).notifyAdministrators(eq("subgateClosed"), any(), anyString(), any());

        // 署名が戻れば自動で開く（D-4 の例外）
        monitor.track(snapshot(TODAY.plusDays(30)), TODAY, writer, notifier);
        verify(writer).recordSubgateChange("HOLD_START", true);
        verify(writer).recordSubgateChange("REL", true);

        // trust の構造の不足は REL だけを閉じる
        trustSound.set(false);
        Map<String, Boolean> structure = monitor.track(snapshot(TODAY.plusDays(30)), TODAY, writer, notifier);
        assertThat(structure).containsEntry("REL", false).containsEntry("HOLD_START", true);
        verify(writer, times(2)).recordSubgateChange("REL", false);
    }

    @Test
    void 親の開放単位が閉じていれば副入口は開かず条件の例外は閉として扱う() {
        SubgateMonitor monitor = new SubgateMonitor(catalog, conditions(SubgateMonitor.Condition.of("HOLD_START", () -> {
            throw new IllegalStateException("broken");
        })));
        FeatureEnablementSnapshot closedUnit = FeatureEnablementTestSnapshots.builder(NOW)
                .signOff("NF07_D05", 1, 0, TODAY.minusDays(10), TODAY.plusDays(30))
                .signOff("NF07_D05_OWNER_ACCEPTANCE", 2, 0, TODAY.minusDays(10), TODAY.plusDays(30)).build();
        assertThat(monitor.track(closedUnit, TODAY, null, null)).containsEntry("HOLD_START", false);

        FeatureEnablementSnapshot open = snapshot(TODAY.plusDays(30));
        assertThat(monitor.open(open, catalog.unit(Feature.NF07_RETENTION).subgates().get(0), TODAY))
                .as("固有の条件の例外は閉").isFalse();
    }

    /** 保持・保全が開いていて、副入口の署名（D-05・受理・D-03・trust の2件）が {@code d05ExpiresOn} まで有効な snapshot。 */
    private static FeatureEnablementSnapshot snapshot(LocalDate d05ExpiresOn) {
        return FeatureEnablementTestSnapshots.builder(NOW)
                .open(Feature.NF07_RETENTION)
                .signOff("NF07_D05", 1, 0, TODAY.minusDays(10), d05ExpiresOn)
                .signOff("NF07_D05_OWNER_ACCEPTANCE", 2, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .signOff("NF07_D03", 3, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .signOff("NF07_TRUST_KMS", 4, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .signOff("NF07_TRUST_STAGING", 5, 0, TODAY.minusDays(10), TODAY.plusDays(300))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<SubgateMonitor.Condition> conditions(SubgateMonitor.Condition... conditions) {
        ObjectProvider<SubgateMonitor.Condition> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(conditions));
        return provider;
    }
}
