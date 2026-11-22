package com.ses.featureenablement;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.BundleKind;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.catalog.SignOffKindClass;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 機能の開放の H2 試験の共通の構成（同じ構成の試験 class は1つの context を共有する）。
 *
 * <ul>
 *   <li>目録: 全ての開放単位に DB 管理の印を付け（段階 A の本番の目録は印なし）、組の流れの試験用の組の種類
 *       {@link #TEST_BUNDLE}（member {@code NF04_BUNDLE_A}・{@code NF04_BUNDLE_B}、NF04 が要する）を足した写し。</li>
 *   <li>時計: 進められる時計（{@link MutableTestClock}。試験の前に {@link MutableTestClock#reset} する）。</li>
 *   <li>監査: {@code AuditLogService} の spy（監査の失敗の試験が stub する）。</li>
 * </ul>
 */
@TestConfiguration
public class FeatureEnablementTestConfiguration {

    /** 監査の失敗で遷移ごと rollback することを確かめる試験が stub する（既定は実物へ委ねる。試験ごとに reset される）。 */
    @org.springframework.boot.test.mock.mockito.SpyBean
    private com.ses.service.AuditLogService auditLogService;

    /** 試験用の組の種類（FE-A12）。 */
    public static final String TEST_BUNDLE = "TEST_BUNDLE";
    public static final String TEST_BUNDLE_A = "NF04_BUNDLE_A";
    public static final String TEST_BUNDLE_B = "NF04_BUNDLE_B";
    /** 試験の時計の既定の時刻（2026-11-23T10:00:00+09:00）。 */
    public static final Instant DEFAULT_NOW = Instant.parse("2026-11-23T01:00:00Z");

    @Bean
    @Primary
    public FeatureEnablementCatalog featureEnablementTestCatalog() {
        return testCatalog();
    }

    @Bean
    @Primary
    public MutableTestClock featureEnablementTestClock() {
        return new MutableTestClock(DEFAULT_NOW, ZoneId.of("Asia/Tokyo"));
    }

    /** 試験の目録（Spring 管理外の試験でも使う）。 */
    public static FeatureEnablementCatalog testCatalog() {
        return FeatureEnablementCatalog.standard()
                .withBundle(new BundleKind(TEST_BUNDLE, List.of(TEST_BUNDLE_A, TEST_BUNDLE_B), List.of(), false),
                        List.of(new SignOffKind(TEST_BUNDLE_A, SignOffKindClass.DECISION, List.of("BUSINESS_OWNER"),
                                        SignOffKind.ReferenceRule.GENERIC, List.of(), TEST_BUNDLE, false),
                                new SignOffKind(TEST_BUNDLE_B, SignOffKindClass.EVIDENCE, List.of("SRE"),
                                        SignOffKind.ReferenceRule.GENERIC, List.of(), TEST_BUNDLE, false)),
                        Feature.NF04)
                .withDatabaseManaged(EnumSet.allOf(Feature.class));
    }

    /** 進められる時計（context の全ての bean が同じ時計を見る）。 */
    public static final class MutableTestClock extends Clock {

        private final AtomicReference<Instant> instant;
        private final ZoneId zone;

        public MutableTestClock(Instant instant, ZoneId zone) {
            this(new AtomicReference<>(instant), zone);
        }

        private MutableTestClock(AtomicReference<Instant> instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        public void set(Instant value) {
            instant.set(value);
        }

        public void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        public void reset() {
            instant.set(DEFAULT_NOW);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId other) {
            return new MutableTestClock(instant, other);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
