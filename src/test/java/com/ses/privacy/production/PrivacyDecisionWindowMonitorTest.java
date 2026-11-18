package com.ses.privacy.production;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.config.OidcSecurityProperties;
import com.ses.config.feature.Feature;
import com.ses.config.feature.FeatureRuntimeCondition;
import com.ses.config.feature.FeatureSwitchFilter;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.privacy.production.PrivacyAccessGuard.Capability;
import com.ses.service.security.AuthorizationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * W3-14b: NF07 決定ID束縛の時間要因（署名日前・失効）は起動を止めず、NF07 を OFF として扱う（NF06・NF08 と同じ）。
 *
 * <p>判定は固定・可変の Clock で JST 業務日ごとに行い、実行中に失効日を迎えた日から {@code FeatureSwitches}・
 * {@code FeatureSwitchFilter}・{@code PrivacyAccessGuard} が OFF と同じ404になることを確かめる。値は全て合成。
 */
class PrivacyDecisionWindowMonitorTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    /** 2026-11-14 12:00 JST。 */
    private static final Instant NOW = Instant.parse("2026-11-14T03:00:00Z");
    /** 2027-05-30 23:59 JST（D-09 失効日の前日の終わり）。 */
    private static final Instant LAST_VALID_MINUTE = Instant.parse("2027-05-30T14:59:00Z");
    /** 2027-05-31 00:00 JST（D-09 失効日）。 */
    private static final Instant EXPIRY_DAY_START = Instant.parse("2027-05-30T15:00:00Z");
    private static final String TENANT = "tenant-a";

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(PrivacyDecisionWindowMonitor.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void flagが全てOFFなら監視対象外で警告せず公開もしない() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrivacyDecisionWindowMonitor monitor = started(flags(false), approved(), new MutableClock(NOW), registry);

        assertEquals(PrivacyDecisionWindowMonitor.State.DISABLED, monitor.currentStatus().state());
        assertFalse(monitor.satisfied());
        assertEquals(1.0, stateGauge(registry, PrivacyDecisionWindowMonitor.State.DISABLED));
        assertTrue(warnings().isEmpty());
    }

    @Test
    void 有効期間内は公開し失効までの日数をmetricへ出す() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrivacyDecisionWindowMonitor monitor = started(flags(true), approved(), new MutableClock(NOW), registry);

        assertEquals(PrivacyDecisionWindowMonitor.State.VALID, monitor.currentStatus().state());
        assertTrue(monitor.satisfied());
        assertEquals(Feature.NF07_RETENTION, monitor.feature());
        assertEquals(1.0, stateGauge(registry, PrivacyDecisionWindowMonitor.State.VALID));
        assertEquals(0.0, stateGauge(registry, PrivacyDecisionWindowMonitor.State.EXPIRED));
        assertEquals(198.0, daysGauge(registry));
        assertTrue(warnings().isEmpty());
    }

    @Test
    void 失効日を起動時に過ぎていれば閉じたまま起動しWARNとmetricで知らせる() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PrivacyDecisionWindowMonitor monitor =
                started(flags(true), approved(), new MutableClock(EXPIRY_DAY_START), registry);

        assertEquals(PrivacyDecisionWindowMonitor.State.EXPIRED, monitor.currentStatus().state());
        assertFalse(monitor.satisfied());
        assertEquals(1.0, stateGauge(registry, PrivacyDecisionWindowMonitor.State.EXPIRED));
        assertEquals(0.0, daysGauge(registry));
        assertEquals(1, warnings().size(), "同じ状態・同じ日のWARNは1回だけ");
        assertTrue(warnings().get(0).getFormattedMessage().contains(PrivacyDecisionWindowMonitor.LOG_CODE));
        assertFalse(warnings().get(0).getFormattedMessage().contains("D09-NF07"), "決定IDを出さない");

        FeatureSwitches switches = switches(monitor, true);
        assertFalse(switches.isEnabled(Feature.NF07_RETENTION));
        assertEquals(Feature.NF07_RETENTION, switches.blockingFeature("/api/privacy/policies").orElseThrow());
        assertTrue(switches.disabledKeys().contains(Feature.NF07_RETENTION.key()));
    }

    @Test
    void 署名日前は閉じて署名日の到来とともに開く() {
        MutableClock clock = new MutableClock(Instant.parse("2026-11-09T03:00:00Z"));
        PrivacyDecisionWindowMonitor monitor = started(flags(true), approved(), clock, new SimpleMeterRegistry());
        PrivacyAccessGuard guard = guard(switches(monitor, true));

        assertEquals(PrivacyDecisionWindowMonitor.State.NOT_YET_VALID, monitor.currentStatus().state());
        assertFalse(guard.isEnabled(Capability.RETENTION));
        assertEquals(1, warnings().size());

        clock.set(Instant.parse("2026-11-09T15:00:00Z")); // 2026-11-10 00:00 JST（D-09 署名日）
        assertTrue(guard.isEnabled(Capability.RETENTION));
        assertEquals(PrivacyDecisionWindowMonitor.State.VALID, monitor.currentStatus().state());
    }

    @Test
    void 実行中に失効日を迎えるとその日から入口がOFFと同じ404になる() throws Exception {
        MutableClock clock = new MutableClock(LAST_VALID_MINUTE);
        PrivacyDecisionWindowMonitor monitor = started(flags(true), approved(), clock, new SimpleMeterRegistry());
        FeatureSwitches switches = switches(monitor, true);
        PrivacyAccessGuard guard = guard(switches);
        FeatureSwitchFilter filter = filter(switches);

        assertEquals(PrivacyDecisionWindowMonitor.State.EXPIRING, monitor.currentStatus().state());
        assertTrue(guard.isEnabled(Capability.RETENTION), "失効日の前日は開いている");
        MockFilterChain passed = new MockFilterChain();
        MockHttpServletResponse before = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/privacy/policies"), before, passed);
        assertNotNull(passed.getRequest(), "filterを通過する");

        clock.set(EXPIRY_DAY_START);

        assertFalse(guard.isEnabled(Capability.RETENTION), "失効日（JST）の0時から閉じる");
        assertThrows(PrivacyAccessGuard.PrivacyEntryNotFoundException.class,
                () -> guard.requireAllowed(Capability.RETENTION, "privacy.policy.view"));
        MockFilterChain blocked = new MockFilterChain();
        MockHttpServletResponse after = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/api/privacy/policies"), after, blocked);
        assertNull(blocked.getRequest(), "OFFと同じくfilterで止まる");
        assertEquals(404, after.getStatus());
        assertTrue(after.getContentAsString().contains(FeatureSwitchFilter.NOT_FOUND_MESSAGE));
        assertEquals(PrivacyDecisionWindowMonitor.State.EXPIRED, monitor.currentStatus().state());
        assertTrue(warnings().stream().anyMatch(event -> event.getFormattedMessage().contains("EXPIRED")),
                "日付が変わった後の最初の判定でWARNを出す");
    }

    @Test
    void 失効前の警告日数に入るとEXPIRINGとして開いたまま日付ごとに1回だけWARNを出す() {
        MutableClock clock = new MutableClock(Instant.parse("2027-05-01T03:00:00Z"));
        PrivacyDecisionWindowMonitor monitor = started(flags(true), approved(), clock, new SimpleMeterRegistry());

        assertEquals(PrivacyDecisionWindowMonitor.State.EXPIRING, monitor.currentStatus().state());
        assertTrue(monitor.satisfied());
        monitor.satisfied();
        assertEquals(1, warnings().size(), "同じ日は繰り返さない");

        clock.set(Instant.parse("2027-05-02T03:00:00Z"));
        assertTrue(monitor.satisfied());
        assertEquals(2, warnings().size(), "日付が変われば再度出す");
    }

    @Test
    void 束縛が不完全ならINCOMPLETEとして閉じる() {
        PrivacyApprovalProperties tampered = approved();
        tampered.getD01().setDecisionId("D01-NF07-2026-7777");
        PrivacyDecisionWindowMonitor monitor =
                started(flags(true), tampered, new MutableClock(NOW), new SimpleMeterRegistry());

        assertEquals(PrivacyDecisionWindowMonitor.State.INCOMPLETE, monitor.currentStatus().state());
        assertFalse(monitor.satisfied());
        assertEquals(1, warnings().size());
    }

    @Test
    void 実行時条件の評価で例外が出ればOFFとして扱い他の機能には影響しない() {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.usePrivacyFeatureProperties(flags(true));
        properties.getNf01().setEnabled(true);
        FeatureSwitches switches = new FeatureSwitches(properties);
        switches.setRuntimeConditions(List.of(new FeatureRuntimeCondition() {
            @Override
            public Feature feature() {
                return Feature.NF07_RETENTION;
            }

            @Override
            public boolean satisfied() {
                throw new IllegalStateException("broken");
            }
        }));

        assertFalse(switches.isEnabled(Feature.NF07_RETENTION));
        assertTrue(switches.isEnabled(Feature.NF01));

        FeatureSwitches withoutConditions = new FeatureSwitches(properties);
        assertTrue(withoutConditions.isEnabled(Feature.NF07_RETENTION), "条件が無ければ従来どおり設定値だけで決まる");
    }

    @Test
    void 本番profileだけで登録される() {
        Profile profile = PrivacyDecisionWindowMonitor.class.getAnnotation(Profile.class);
        assertArrayEquals(new String[]{"prod"}, profile.value());
        Profile dsarProfile = PrivacyDsarDecisionWindowCondition.class.getAnnotation(Profile.class);
        assertArrayEquals(new String[]{"prod"}, dsarProfile.value());
    }

    /** A2a: 機能スイッチ NF07_DSAR にも同じ有効期間の条件を掛ける（PrivacyDsarDecisionWindowCondition）。 */
    @Test
    void DSARの機能スイッチにも同じ有効期間の条件が掛かり失効日から閉じる() {
        MutableClock clock = new MutableClock(NOW);
        PrivacyFeatureProperties flags = flags(true);
        flags.getDsar().setEnabled(true);
        PrivacyDecisionWindowMonitor monitor = started(flags, approvedWithDsar(), clock, new SimpleMeterRegistry());
        PrivacyDsarDecisionWindowCondition condition = new PrivacyDsarDecisionWindowCondition(monitor);
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.usePrivacyFeatureProperties(flags);
        FeatureSwitches switches = new FeatureSwitches(properties);
        switches.setRuntimeConditions(List.of(monitor, condition));

        assertEquals(Feature.NF07_DSAR, condition.feature());
        assertTrue(switches.isEnabled(Feature.NF07_DSAR));
        assertTrue(switches.isEnabled(Feature.NF07_RETENTION));

        clock.set(EXPIRY_DAY_START);
        assertFalse(switches.isEnabled(Feature.NF07_DSAR), "失効日（JST）からDSARも閉じる");
        assertEquals(Feature.NF07_DSAR, switches.blockingFeature("/api/privacy/dsar").orElseThrow());

        // dsar を承認していない束縛のまま dsar を ON にした場合も閉じる（束縛の不足）
        PrivacyDecisionWindowMonitor retentionOnly = started(flags, approved(), new MutableClock(NOW),
                new SimpleMeterRegistry());
        FeatureSwitches notApproved = new FeatureSwitches(properties);
        notApproved.setRuntimeConditions(List.of(retentionOnly, new PrivacyDsarDecisionWindowCondition(retentionOnly)));
        assertFalse(notApproved.isEnabled(Feature.NF07_DSAR));
    }

    /** retention と dsar を承認した完全な束縛（D-06: 2026-11-05〜2027-11-04。最も早い失効は D-09 の 2027-05-31）。 */
    private static PrivacyApprovalProperties approvedWithDsar() {
        PrivacyApprovalProperties approval = approved();
        approval.setCapabilities(new ArrayList<>(List.of("retention", "dsar")));
        approval.getD06().setDecisionId("D06-NF07-2026-0006");
        approval.getD06().setSignedOn("2026-11-05");
        approval.getD06().setExpiresOn("2027-11-04");
        approval.setApprovalDigest(PrivacyDecisionBinding.computeDigest(approval));
        return approval;
    }

    // ---------------------------------------------------------------- helpers

    private static PrivacyDecisionWindowMonitor started(PrivacyFeatureProperties flags,
                                                        PrivacyApprovalProperties approval, Clock clock,
                                                        MeterRegistry registry) {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(TENANT);
        PrivacyDecisionWindowMonitor monitor = new PrivacyDecisionWindowMonitor(flags, approval, oidc, clock, registry);
        monitor.start();
        return monitor;
    }

    private static FeatureSwitches switches(PrivacyDecisionWindowMonitor monitor, boolean retention) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.usePrivacyFeatureProperties(flags(retention));
        FeatureSwitches switches = new FeatureSwitches(properties);
        switches.setRuntimeConditions(List.of(monitor));
        return switches;
    }

    @SuppressWarnings("unchecked")
    private static PrivacyAccessGuard guard(FeatureSwitches switches) {
        ObjectProvider<FeatureSwitches> switchesProvider = mock(ObjectProvider.class);
        when(switchesProvider.getIfAvailable()).thenReturn(switches);
        ObjectProvider<AuthorizationService> authorizationProvider = mock(ObjectProvider.class);
        return new PrivacyAccessGuard(switchesProvider, authorizationProvider);
    }

    @SuppressWarnings("unchecked")
    private static FeatureSwitchFilter filter(FeatureSwitches switches) {
        ObjectProvider<FeatureSwitches> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(switches);
        return new FeatureSwitchFilter(provider);
    }

    private static PrivacyFeatureProperties flags(boolean retention) {
        PrivacyFeatureProperties properties = new PrivacyFeatureProperties();
        properties.getRetention().setEnabled(retention);
        return properties;
    }

    /** retention を承認した完全な束縛（D-01: 2026-11-01〜2027-10-31、D-09: 2026-11-10〜2027-05-31）。 */
    private static PrivacyApprovalProperties approved() {
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        approval.setTenantId(TENANT);
        approval.setCapabilities(new ArrayList<>(List.of("retention")));
        approval.getD01().setDecisionId("D01-NF07-2026-0001");
        approval.getD01().setSignedOn("2026-11-01");
        approval.getD01().setExpiresOn("2027-10-31");
        approval.getD09().setDecisionId("D09-NF07-2026-0009");
        approval.getD09().setSignedOn("2026-11-10");
        approval.getD09().setExpiresOn("2027-05-31");
        approval.setApprovalDigest(PrivacyDecisionBinding.computeDigest(approval));
        return approval;
    }

    private List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    private static double stateGauge(SimpleMeterRegistry registry, PrivacyDecisionWindowMonitor.State state) {
        return registry.get(PrivacyDecisionWindowMonitor.STATE_METRIC)
                .tag(PrivacyDecisionWindowMonitor.STATE_TAG, state.name()).gauge().value();
    }

    private static double daysGauge(SimpleMeterRegistry registry) {
        return registry.get(PrivacyDecisionWindowMonitor.DAYS_REMAINING_METRIC).gauge().value();
    }

    /** 実行中の日付の経過を模す Clock（JST）。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void set(Instant next) {
            this.instant = next;
        }

        @Override
        public ZoneId getZone() {
            return JST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
