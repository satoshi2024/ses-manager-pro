package com.ses.service.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.config.AiConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF08 N8-3: 承認の失効は起動を止めず、失効前警告・失効・不完全をWARN logと監視metricで知らせる。 */
class AiExternalSendApprovalMonitorTest {

    /** 2026-11-13 10:00 JST。 */
    private static final Instant NOW = Instant.parse("2026-11-13T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(AiExternalSendApprovalMonitor.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void externalSend無効なら監視対象外で警告しない() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.setExternalSendEnabled(false);
        MutableClock clock = new MutableClock(NOW);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        AiExternalSendApprovalMonitor monitor = monitor(config, clock, registry);

        assertEquals(AiExternalSendApprovalMonitor.State.DISABLED, monitor.currentStatus().state());
        assertEquals(1.0, stateGauge(registry, AiExternalSendApprovalMonitor.State.DISABLED));
        assertTrue(Double.isNaN(daysGauge(registry)));
        assertTrue(warnings().isEmpty());
    }

    @Test
    void 有効期間内は正常で失効までの日数をmetricへ出す() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        AiExternalSendApprovalMonitor monitor = monitor(config, new MutableClock(NOW), registry);

        assertEquals(AiExternalSendApprovalMonitor.State.VALID, monitor.currentStatus().state());
        assertEquals(1.0, stateGauge(registry, AiExternalSendApprovalMonitor.State.VALID));
        assertEquals(0.0, stateGauge(registry, AiExternalSendApprovalMonitor.State.EXPIRED));
        assertEquals(90.0, daysGauge(registry));
        assertTrue(warnings().isEmpty());
    }

    @Test
    void 失効前の警告日数に入るとEXPIRINGとしてWARNを日付ごとに1回だけ出す() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendMonitor().setExpiryWarningDays(95);
        MutableClock clock = new MutableClock(NOW);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        AiExternalSendApprovalMonitor monitor = monitor(config, clock, registry);
        daysGauge(registry);
        daysGauge(registry);

        assertEquals(AiExternalSendApprovalMonitor.State.EXPIRING, monitor.currentStatus().state());
        assertEquals(1.0, stateGauge(registry, AiExternalSendApprovalMonitor.State.EXPIRING));
        assertEquals(1, warnings().size(), "同じ状態・同じ日はscrapeのたびに繰り返さない");
        assertTrue(warnings().get(0).getFormattedMessage().contains(AiExternalSendApprovalMonitor.LOG_CODE));
        assertTrue(warnings().get(0).getFormattedMessage().contains("EXPIRING"));

        clock.set(NOW.plusSeconds(86_400));
        daysGauge(registry);
        assertEquals(2, warnings().size(), "JST日付が変われば再度知らせる");
    }

    @Test
    void 失効すると外部送信停止中としてEXPIREDを出しdecision等の値をlogへ出さない() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setExpiresOn(TODAY.plusDays(1).toString());
        AiExternalSendApprovalFixtures.resign(config);
        MutableClock clock = new MutableClock(NOW);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AiExternalSendApprovalMonitor monitor = monitor(config, clock, registry);
        assertEquals(AiExternalSendApprovalMonitor.State.EXPIRING, monitor.currentStatus().state());

        clock.set(NOW.plusSeconds(86_400));

        assertEquals(AiExternalSendApprovalMonitor.State.EXPIRED, monitor.currentStatus().state());
        assertEquals(0.0, daysGauge(registry));
        assertEquals(1.0, stateGauge(registry, AiExternalSendApprovalMonitor.State.EXPIRED));
        ILoggingEvent last = warnings().get(warnings().size() - 1);
        assertTrue(last.getFormattedMessage().contains("EXPIRED"));
        assertFalse(last.getFormattedMessage().contains(AiExternalSendApprovalFixtures.DECISION_ID));
    }

    @Test
    void 署名日前と承認設定の不完全は外部送信停止中として区別する() {
        AiConfig notYetSigned = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        notYetSigned.getExternalSendApproval().setSignedOn(TODAY.plusDays(1).toString());
        AiExternalSendApprovalFixtures.resign(notYetSigned);
        AiConfig incomplete = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        incomplete.getExternalSendApproval().setApprovalDigest(null);

        assertEquals(AiExternalSendApprovalMonitor.State.NOT_YET_VALID,
                monitor(notYetSigned, new MutableClock(NOW), null).currentStatus().state());
        AiExternalSendApprovalMonitor incompleteMonitor = monitor(incomplete, new MutableClock(NOW), null);
        assertEquals(AiExternalSendApprovalMonitor.State.INCOMPLETE, incompleteMonitor.currentStatus().state());
        assertTrue(AiExternalSendApprovalMonitor.State.INCOMPLETE.requiresAttention());
        assertFalse(AiExternalSendApprovalMonitor.State.VALID.requiresAttention());
        assertTrue(warnings().stream().anyMatch(event -> event.getFormattedMessage().contains("NOT_YET_VALID")));
        assertTrue(warnings().stream().anyMatch(event -> event.getFormattedMessage().contains("INCOMPLETE")));
    }

    @Test
    void 警告日数の設定は1から366日に丸める() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendMonitor().setExpiryWarningDays(0);
        assertEquals(1, monitor(config, new MutableClock(NOW), null).warningDays());
        config.getExternalSendMonitor().setExpiryWarningDays(10_000);
        assertEquals(366, monitor(config, new MutableClock(NOW), null).warningDays());
        config.setExternalSendMonitor(null);
        assertEquals(AiExternalSendApprovalMonitor.DEFAULT_WARNING_DAYS,
                monitor(config, new MutableClock(NOW), null).warningDays());
    }

    @Test
    void MeterRegistryが無い環境でも起動時logだけで動作する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setExpiresOn(TODAY.toString());
        AiExternalSendApprovalFixtures.resign(config);

        AiExternalSendApprovalMonitor monitor = assertDoesNotThrow(() -> monitor(config, new MutableClock(NOW), null));

        assertEquals(AiExternalSendApprovalMonitor.State.EXPIRED, monitor.currentStatus().state());
        assertEquals(1, warnings().size());
        assertEquals(Long.valueOf(0L), monitor.currentStatus().daysRemaining());
    }

    private static AiExternalSendApprovalMonitor monitor(AiConfig config, Clock clock, MeterRegistry registry) {
        AiExternalSendApprovalMonitor monitor = new AiExternalSendApprovalMonitor(config,
                new AiProductionApprovalGate(config, clock), clock, registry);
        monitor.start();
        return monitor;
    }

    private static double stateGauge(SimpleMeterRegistry registry, AiExternalSendApprovalMonitor.State state) {
        return registry.get(AiExternalSendApprovalMonitor.STATE_METRIC)
                .tag(AiExternalSendApprovalMonitor.STATE_TAG, state.name()).gauge().value();
    }

    private static double daysGauge(SimpleMeterRegistry registry) {
        return registry.get(AiExternalSendApprovalMonitor.DAYS_REMAINING_METRIC).gauge().value();
    }

    private java.util.List<ILoggingEvent> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }

    /** テスト用の可変時計。 */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant initial) {
            this.now = new AtomicReference<>(initial);
        }

        void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
