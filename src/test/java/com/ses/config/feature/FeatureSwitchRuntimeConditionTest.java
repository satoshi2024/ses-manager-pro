package com.ses.config.feature;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.privacy.production.PrivacyFeatureProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W3-14b: 機能スイッチの実行時条件（{@link FeatureRuntimeCondition}）。設定ONでも条件を満たさない間はOFFとして扱い、
 * 起動ログの状態とWARNにも反映する（review P3-9）。条件の無い機能・Spring管理外の {@code new} は従来どおり。
 */
class FeatureSwitchRuntimeConditionTest {

    private ListAppender<ILoggingEvent> appender;
    private Logger logger;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(FeatureSwitches.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void 実行時条件を満たさない機能は起動ログでOFFと表示し理由をWARNで出す() {
        AtomicBoolean satisfied = new AtomicBoolean(false);
        FeatureSwitches switches = switches(true, satisfied);

        switches.logStates();

        assertTrue(infos().stream().anyMatch(message -> message.contains("NF07_RETENTION=OFF")));
        List<String> warnings = warnings();
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("NF07_RETENTION"));
        assertTrue(warnings.get(0).contains("実行時条件"));
        assertFalse(switches.isEnabled(Feature.NF07_RETENTION));
        assertEquals(Feature.NF07_RETENTION, switches.blockingFeature("/api/privacy/policies").orElseThrow());
        assertTrue(switches.disabledKeys().contains(Feature.NF07_RETENTION.key()));
        assertEquals(List.of(), switches.visibleMenuKeys(List.of("privacy-retention")),
                "OFFと同じくmenuを隠す");

        satisfied.set(true);
        assertTrue(switches.isEnabled(Feature.NF07_RETENTION), "条件は呼出しのたびに評価する");
        assertTrue(switches.blockingFeature("/api/privacy/policies").isEmpty());
    }

    @Test
    void 実行時条件を満たす機能と設定OFFの機能では実行時条件のWARNを出さない() {
        switches(true, new AtomicBoolean(true)).logStates();
        switches(false, new AtomicBoolean(false)).logStates();

        assertTrue(warnings().isEmpty(), () -> String.join("\n", warnings()));
    }

    @Test
    void 条件の無い機能とSpring管理外のnewは従来どおり設定値だけで決まる() {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.usePrivacyFeatureProperties(privacy(true));
        properties.getNf01().setEnabled(true);
        FeatureSwitches plain = new FeatureSwitches(properties);

        assertTrue(plain.isEnabled(Feature.NF07_RETENTION));
        assertTrue(plain.isEnabled(Feature.NF01));

        FeatureSwitches conditioned = switches(true, new AtomicBoolean(false));
        assertFalse(conditioned.isEnabled(Feature.NF07_RETENTION));
        assertFalse(conditioned.isEnabled(Feature.NF01), "NF01は設定OFFのまま（条件の影響を受けない）");
        conditioned.setRuntimeConditions(null);
        assertTrue(conditioned.isEnabled(Feature.NF07_RETENTION), "nullは条件なしとして扱う");
    }

    private static FeatureSwitches switches(boolean retention, AtomicBoolean satisfied) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.usePrivacyFeatureProperties(privacy(retention));
        FeatureSwitches switches = new FeatureSwitches(properties);
        switches.setRuntimeConditions(List.of(new FeatureRuntimeCondition() {
            @Override
            public Feature feature() {
                return Feature.NF07_RETENTION;
            }

            @Override
            public boolean satisfied() {
                return satisfied.get();
            }
        }));
        return switches;
    }

    private static PrivacyFeatureProperties privacy(boolean retention) {
        PrivacyFeatureProperties properties = new PrivacyFeatureProperties();
        properties.getRetention().setEnabled(retention);
        return properties;
    }

    private List<String> warnings() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> infos() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
