package com.ses.featureenablement.evaluation;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 起動時の評価（feature-enablement-console design §7.1）。web server の lifecycle（phase は {@code Integer.MAX_VALUE} 近く）より前の
 * phase で、開いている開放単位を {@code Purpose.STARTUP} で評価し、成り立たなければ自動閉鎖する（総スイッチが閉なら全てを
 * {@code MASTER_SWITCH_CLOSED}）。NF05 の再起動待ちは boot resolver の判定を §9.2 で反映するため、ここでは評価しない。
 *
 * <p>DB に書けなければ自動閉鎖の書込み待ち（sticky）で閉じ、定期の評価が書込みを再試行する。起動は止めない（評価の失敗は閉じる側に
 * 倒れる）。起動の log に {@code [機能の開放] 起動時の評価: NF01=OPEN, NF04=AUTO_CLOSED(signoff.NF04_SRE.expired), …}
 * を出す（code だけ）。DB の源の時だけ動く。</p>
 */
@Slf4j
@Component
public class FeatureEnablementStartupEvaluator implements SmartLifecycle {

    /** web server の起動（{@code WebServerStartStopLifecycle}）より前。 */
    static final int PHASE = 0;

    private final FeatureEnablementEvaluationService evaluation;
    private final FeatureEnablementProperties properties;
    private volatile boolean running;

    public FeatureEnablementStartupEvaluator(FeatureEnablementEvaluationService evaluation,
                                             FeatureEnablementProperties properties) {
        this.evaluation = evaluation;
        this.properties = properties;
    }

    @Override
    public void start() {
        running = true;
        if (properties == null || !properties.databaseSource()) {
            return;
        }
        evaluateAtStartup();
    }

    /** 起動時の評価を1回行い、要約を返す（試験からも呼ぶ）。 */
    public String evaluateAtStartup() {
        try {
            Map<Feature, String> result = evaluation.evaluateOpenUnits(FeatureEvaluationContext.Purpose.STARTUP, true);
            String summary = result.isEmpty() ? "開放している単位なし" : result.entrySet().stream()
                    .map(entry -> entry.getKey().name() + "=" + entry.getValue())
                    .collect(Collectors.joining(", "));
            log.info("[機能の開放] 起動時の評価: {}", summary);
            return summary;
        } catch (RuntimeException e) {
            log.warn("[機能の開放] 起動時の評価に失敗しました（定期の評価が再試行します）: "
                    + "errorCode=FEATURE_ENABLEMENT_STARTUP_EVALUATION_FAILED exceptionClass={}", e.getClass().getSimpleName());
            return "評価できない";
        }
    }

    @Override
    public void stop() {
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return PHASE;
    }
}
