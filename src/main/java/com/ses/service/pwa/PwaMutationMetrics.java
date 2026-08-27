package com.ses.service.pwa;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** PWA commandの監視用メトリクス。ユーザーID・command内容・PIIをtagへ入れない。 */
@Component
@RequiredArgsConstructor
public class PwaMutationMetrics {
    private final MeterRegistry meterRegistry;

    public void increment(String outcome, String screen) {
        Counter.builder("ses.pwa.mutations")
                .description("要員PWA commandの処理結果")
                .tag("outcome", outcome)
                .tag("screen", screen == null ? "unknown" : screen)
                .register(meterRegistry)
                .increment();
    }
}
