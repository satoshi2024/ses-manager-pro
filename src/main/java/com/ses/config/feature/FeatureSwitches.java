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

    /** 開放状態の源（dev・test は property、prod は DB。feature-enablement-console design §3.4）。 */
    private final com.ses.featureenablement.state.FeatureEnablementStateSource source;

    /** 最後に log に出した実効値の一覧（変化した時だけ出す）。 */
    private volatile String lastDescribed;

    /** 実行時条件（{@link FeatureRuntimeCondition}）。Spring管理外で {@code new} した場合は無し。 */
    private List<FeatureRuntimeCondition> runtimeConditions = List.of();

    /** property の源で包む（{@code new} で作る既存の試験のため。dev・test の既定と同じ判定）。 */
    public FeatureSwitches(FeatureSwitchProperties properties) {
        this(new com.ses.featureenablement.state.PropertyFeatureEnablementStateSource(properties));
    }

    /** 開放状態の源を受け取る（Spring context の構築。snapshot の差替えで実効値が変わったら log に出す）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public FeatureSwitches(com.ses.featureenablement.state.FeatureEnablementStateSource source) {
        this.source = source;
        if (source != null) {
            source.addChangeListener(this::logIfChanged);
        }
    }

    /** 実行時条件を受け取る（現在は prod の NF07 決定ID束縛の有効期間だけ）。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRuntimeConditions(List<FeatureRuntimeCondition> runtimeConditions) {
        this.runtimeConditions = runtimeConditions == null ? List.of() : List.copyOf(runtimeConditions);
    }

    @PostConstruct
    void logStates() {
        lastDescribed = describe();
        log.info("[機能スイッチ] 起動時の状態: {}", lastDescribed);
        for (Feature feature : Feature.values()) {
            if (!isConfiguredOn(feature)) {
                continue;
            }
            // 子だけONにして親を忘れる設定誤りは、起動を止めずにOFFとして扱い、理由をログへ残す。
            if (!feature.parent().map(this::isEnabled).orElse(true)) {
                log.warn("[機能スイッチ] {} は設定上ONですが、親機能 {} がOFFのため無効です",
                        feature.name(), feature.root().name());
            } else if (!runtimeConditionsSatisfied(feature)) {
                log.warn("[機能スイッチ] {} は設定上ONですが、実行時条件（署名済み決定の有効期間等）を満たさないため無効です",
                        feature.name());
            }
        }
    }

    /**
     * 機能の実効値。子スイッチは自身の設定値と親の実効値の論理積（親がOFFなら子は常にOFF）。
     * 対象機能の実行時条件（{@link FeatureRuntimeCondition}）を1つでも満たさなければOFF。
     */
    public boolean isEnabled(Feature feature) {
        if (!isConfiguredOn(feature)) {
            return false;
        }
        if (!feature.parent().map(this::isEnabled).orElse(true)) {
            return false;
        }
        return runtimeConditionsSatisfied(feature);
    }

    /** 対象機能の実行時条件を全て満たすか。評価の例外はOFF（fail-closed）。 */
    private boolean runtimeConditionsSatisfied(Feature feature) {
        for (FeatureRuntimeCondition condition : runtimeConditions) {
            if (condition == null || condition.feature() != feature) {
                continue;
            }
            try {
                if (!condition.satisfied()) {
                    return false;
                }
            } catch (RuntimeException e) {
                log.warn("[機能スイッチ] {} の実行時条件を評価できないためOFFとして扱います", feature.name(), e);
                return false;
            }
        }
        return true;
    }

    /**
     * 親子の論理積を取る前の開閉（源の開放状態・総スイッチ・有効期限・一時の閉鎖。design §3.4）。property の源では設定値で、
     * 設定キーの欠落はOFF扱い（真偽値として解釈できない値はbind時に失敗し起動しない）。
     */
    private boolean isConfiguredOn(Feature feature) {
        if (feature == null || source == null) {
            return false;
        }
        return source.isOpen(feature);
    }

    /**
     * 実効値が閉じている理由の code（開いていれば空。画面の実効値の表示。design §15.1）。源の理由に、親が閉じていること
     * （{@code parent.<親>.closed}）と実行時条件の不成立（{@link FeatureRuntimeCondition#unsatisfiedReason}）を加える。
     */
    public List<String> closedReasons(Feature feature) {
        if (feature == null || isEnabled(feature)) {
            return List.of();
        }
        List<String> reasons = new java.util.ArrayList<>(source == null ? List.of("source.missing")
                : source.closedReasons(feature));
        feature.parent().filter(parent -> !isEnabled(parent))
                .ifPresent(parent -> reasons.add("parent." + parent.name() + ".closed"));
        for (FeatureRuntimeCondition condition : runtimeConditions) {
            if (condition == null || condition.feature() != feature) {
                continue;
            }
            try {
                condition.unsatisfiedReason().ifPresent(reasons::add);
            } catch (RuntimeException e) {
                reasons.add("runtime.unverifiable");
            }
        }
        return List.copyOf(reasons);
    }

    /** 実効値が変わっていれば INFO log に出す（snapshot の差替えのたび。並びと形式は起動時と同じ。design §3.4）。 */
    void logIfChanged() {
        String described = describe();
        if (!described.equals(lastDescribed)) {
            lastDescribed = described;
            log.info("[機能スイッチ] 状態: {}", described);
        }
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

    /**
     * 実効値がOFFの機能の識別子（例: {@code nf01}、{@code nf02-customer-portal}）。テンプレートの表示制御に使う。
     * 親がOFFなら子の識別子も含まれるため、子の導線は子の識別子だけで判定すればよい。
     */
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

    /** ログ出力用の実効値の一覧（例: {@code NF01=OFF, NF02=ON, NF02_CUSTOMER_PORTAL=OFF}）。 */
    public String describe() {
        return Arrays.stream(Feature.values())
                .map(feature -> feature.name() + "=" + (isEnabled(feature) ? "ON" : "OFF"))
                .collect(Collectors.joining(", "));
    }
}
