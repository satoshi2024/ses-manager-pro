package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Availability;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Inspection;
import com.ses.privacy.production.retention.hold.PrivacyJournalStartupVerifier.Result;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-C03（feature-enablement-console design §8.5 の 3。review P2-4）: Journal の起動時検査の結果を2つに分ける。
 *
 * <ul>
 *   <li>hold の効力の結果（hold gate が読む）は reader の principal でも AVAILABLE で、開閉・再検査の一時の失敗で下がらない
 *       （保持・保全を閉じた後も hold は既存の hold を守り、文書の処分・purge・孤児 file の掃除は gate の判定のまま動く）。</li>
 *   <li>開放の条件の結果（保持・保全の開放の条件と hold の開始が読む）は appender を要し、再検査のたびに更新する。</li>
 *   <li>再検査には間隔の下限がある。</li>
 * </ul>
 */
class PrivacyJournalProtectionResultTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final List<String> READER_ONLY = List.of("privilege.missing:pj_head.INSERT");

    @Test
    void readerのprincipalでは開放の条件が閉でもhold_gateはAVAILABLEのまま() {
        AtomicReference<Inspection> inspection = new AtomicReference<>(new Inspection(List.of(), READER_ONLY));
        PrivacyJournalStartupVerifier verifier = verifier(inspection, new MutableClock(NOW), Duration.ofMinutes(10));

        verifier.afterSingletonsInstantiated();

        assertThat(verifier.protectionResult().availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(verifier.startupResult()).isEqualTo(verifier.protectionResult());
        assertThat(verifier.enablementResult().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(verifier.enablementResult().violations()).containsExactly("privilege.missing:pj_head.INSERT");

        // hold gate（availability）は hold の効力の結果、hold の開始（appendAvailability）は開放の条件の結果を読む
        InMemoryHoldJournal port = new InMemoryHoldJournal(Clock.fixed(NOW, ZoneId.of("UTC")));
        PrivacyHoldJournal journal = new PrivacyHoldJournal(verifier::protectionResult, verifier::enablementResult,
                () -> port, port, PrivacyHoldTestFixture.JOURNAL_ID, Duration.ofMinutes(5), Duration.ofSeconds(30),
                () -> "application-server-uuid", Clock.fixed(NOW, ZoneId.of("UTC")));
        assertThat(journal.availability()).isEqualTo(PrivacyHoldJournal.Availability.AVAILABLE);
        assertThat(journal.appendAvailability()).isEqualTo(PrivacyHoldJournal.Availability.UNAVAILABLE);
    }

    @Test
    void appenderの再検査の失敗でhold効力の結果は下がらず開放の条件の結果だけが下がる() {
        AtomicReference<Inspection> inspection = new AtomicReference<>(new Inspection(List.of(), List.of()));
        MutableClock clock = new MutableClock(NOW);
        PrivacyJournalStartupVerifier verifier = verifier(inspection, clock, Duration.ofMinutes(10));
        verifier.afterSingletonsInstantiated();
        assertThat(verifier.enablementResult().availability()).isEqualTo(Availability.AVAILABLE);

        inspection.set(new Inspection(List.of("journal.unreachable"), List.of("journal.unreachable")));
        clock.advance(Duration.ofMinutes(11));
        Result after = verifier.reverify();

        assertThat(after.availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(verifier.enablementResult().violations()).containsExactly("journal.unreachable");
        assertThat(verifier.protectionResult().availability()).as("一時の失敗で hold gate を下げない")
                .isEqualTo(Availability.AVAILABLE);
    }

    @Test
    void 起動時に使えなくても再検査が成功すればhold効力の結果は回復し再検査は間隔の下限を守る() {
        AtomicInteger inspections = new AtomicInteger();
        AtomicReference<Inspection> inspection = new AtomicReference<>(
                new Inspection(List.of("journal.unreachable"), List.of("journal.unreachable")));
        MutableClock clock = new MutableClock(NOW);
        PrivacyJournalStartupVerifier verifier = new PrivacyJournalStartupVerifier(
                PrivacyJournalConnections.create(properties()), () -> null, true,
                Duration.ofMinutes(10), clock, () -> {
                    inspections.incrementAndGet();
                    return inspection.get();
                });
        verifier.afterSingletonsInstantiated();
        assertThat(verifier.protectionResult().availability()).isEqualTo(Availability.UNAVAILABLE);
        assertThat(inspections).hasValue(1);

        inspection.set(new Inspection(List.of(), List.of()));
        clock.advance(Duration.ofMinutes(5));
        assertThat(verifier.reverify().availability()).as("間隔の下限の内は前回の結果を返す")
                .isEqualTo(Availability.UNAVAILABLE);
        assertThat(inspections).hasValue(1);

        clock.advance(Duration.ofMinutes(5));
        assertThat(verifier.reverify().availability()).isEqualTo(Availability.AVAILABLE);
        assertThat(inspections).hasValue(2);
        assertThat(verifier.protectionResult().availability()).as("成功した再検査で回復する")
                .isEqualTo(Availability.AVAILABLE);
    }

    private static PrivacyJournalStartupVerifier verifier(AtomicReference<Inspection> inspection, Clock clock,
                                                          Duration interval) {
        return new PrivacyJournalStartupVerifier(PrivacyJournalConnections.create(properties()), () -> null, true, interval, clock, inspection::get);
    }

    /** Journal の設定（接続はしない。検査は差し替える）。 */
    private static PrivacyJournalProperties properties() {
        PrivacyJournalProperties properties = new PrivacyJournalProperties();
        properties.getDatasource().setUrl("jdbc:mysql://journal.internal:3306/privacy_journal?sslMode=VERIFY_IDENTITY");
        properties.getDatasource().setUsername("nf07_journal_reader");
        properties.getDatasource().setPassword("unit-test-only-credential");
        properties.setJournalId("journal-test");
        return properties;
    }

    /** 進められる時計。 */
    private static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
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
