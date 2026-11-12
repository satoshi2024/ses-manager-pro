package com.ses.config.feature;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 機能スイッチの判定窓口。filter・ControllerAdvice・schedulerは必ずここを経由して判定する。
 *
 * <p>機能スイッチは権限ではなく公開可否であり、管理者ロールでも迂回できない。
 */
@Slf4j
@Component
public class FeatureSwitches {

    private final FeatureSwitchProperties properties;

    public FeatureSwitches(FeatureSwitchProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void logStates() {
        log.info("[機能スイッチ] 起動時の状態: {}", describe());
    }

    public boolean isEnabled(Feature feature) {
        if (feature == null || properties == null) {
            return false;
        }
        FeatureSwitchProperties.Toggle toggle = switch (feature) {
            case NF01 -> properties.getNf01();
            case NF02 -> properties.getNf02();
            case NF03 -> properties.getNf03();
            case NF04 -> properties.getNf04();
            case NF10 -> properties.getNf10();
        };
        // 設定キーの欠落（Toggleがnull）はOFF扱い。真偽値として解釈できない値はbind時に失敗し起動しない。
        return toggle != null && toggle.isEnabled();
    }

    /**
     * scheduler等の入口判定用。
     *
     * <p>{@code switches} がnullになるのは、Spring管理外で {@code new} した単体テスト対象だけである
     * （アプリ本体では必須Beanとして常に注入され、欠けていれば起動に失敗する）。その場合だけは
     * 既存テストの呼出し経路を変えないため従来どおり処理を続ける（本番では到達しないfail-open）。
     */
    public static boolean allows(FeatureSwitches switches, Feature feature) {
        return switches == null || switches.isEnabled(feature);
    }

    /** pathがOFFの機能の入口なら、その機能を返す。 */
    public Optional<Feature> blockingFeature(String path) {
        return Feature.forPath(path).filter(feature -> !isEnabled(feature));
    }

    /** OFFの機能の識別子（例: {@code nf01}）。テンプレートの表示制御に使う。 */
    public Set<String> disabledKeys() {
        Set<String> keys = Arrays.stream(Feature.values())
                .filter(feature -> !isEnabled(feature))
                .map(Feature::key)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(keys);
    }

    /** OFFの機能のメニューを除いたメニューキー一覧。元のリストは変更しない。 */
    public List<String> visibleMenuKeys(List<String> menuKeys) {
        if (menuKeys == null || menuKeys.isEmpty()) {
            return menuKeys;
        }
        Set<String> hidden = Arrays.stream(Feature.values())
                .filter(feature -> !isEnabled(feature))
                .flatMap(feature -> feature.menuKeys().stream())
                .collect(Collectors.toSet());
        if (hidden.isEmpty()) {
            return menuKeys;
        }
        return menuKeys.stream().filter(key -> !hidden.contains(key)).toList();
    }

    /** ログ出力用の状態一覧（例: {@code NF01=OFF, NF02=ON}）。 */
    public String describe() {
        return Arrays.stream(Feature.values())
                .map(feature -> feature.name() + "=" + (isEnabled(feature) ? "ON" : "OFF"))
                .collect(Collectors.joining(", "));
    }
}
