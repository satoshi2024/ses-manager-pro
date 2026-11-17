package com.ses.config.feature;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * 機能スイッチを試験ごとの専用の設定で判定させる補助（W3-21b review P3-4）。
 *
 * <p>共有の Spring context の {@link FeatureSwitchProperties} を試験の中で書き換えて {@code @AfterEach} で戻す方式は、
 * 同じ context を使う他の試験（JUnitの並列実行を有効にした場合を含む）へ切替えが漏れ得る。代わりに試験classは
 * {@code @MockBean FeatureSwitches} を宣言し（そのclass専用のcontextになる）、本補助で mock の判定を
 * 試験専用の設定から作った実物の {@link FeatureSwitches} へ委ねる。親子の論理積と実行時条件
 * （{@link FeatureRuntimeCondition}。例: prod の NF07 決定ID束縛の有効期間）の判定は実物が行うため、判定の規則そのものは
 * 置き換えない（RB-NF01 review RB-P3-4。条件は context の bean をそのまま渡す）。返した設定を試験の中で変えても
 * 共有の設定は変わらない。{@code IsolatedFeatureSwitchesTest} が実物と同じ判定になることを固定する。</p>
 */
public final class IsolatedFeatureSwitches {

    private IsolatedFeatureSwitches() {
    }

    /** 実行時条件を持たない場合（test profile では条件の bean が無い）。 */
    public static FeatureSwitchProperties isolate(FeatureSwitches mock, FeatureSwitchProperties source) {
        return isolate(mock, source, List.of());
    }

    /**
     * {@code source} の各機能の設定値を写した新しい設定を作り、{@code mock} の判定をその設定と {@code runtimeConditions} で
     * 作った実物へ委ねる。
     *
     * @param mock              試験classの {@code @MockBean FeatureSwitches}（試験ごとにresetされるため {@code @BeforeEach} で呼ぶ）
     * @param source            写す元の設定（test profile の値。読むだけで変えない）
     * @param runtimeConditions 実物の {@link FeatureSwitches} が受け取る実行時条件（context の bean。null は無し）
     * @return 試験の中で切り替える専用の設定
     */
    public static FeatureSwitchProperties isolate(FeatureSwitches mock, FeatureSwitchProperties source,
                                                  List<FeatureRuntimeCondition> runtimeConditions) {
        FeatureSwitchProperties local = new FeatureSwitchProperties();
        for (Feature feature : Feature.values()) {
            FeatureSwitchProperties.Toggle from = source == null ? null : source.toggle(feature);
            FeatureSwitchProperties.Toggle to = local.toggle(feature);
            if (from != null && to != null) {
                to.setEnabled(from.isEnabled());
            }
        }
        FeatureSwitches delegate = new FeatureSwitches(local);
        delegate.setRuntimeConditions(runtimeConditions);
        lenient().when(mock.isEnabled(any())).thenAnswer(invocation -> delegate.isEnabled(invocation.getArgument(0)));
        lenient().when(mock.blockingFeature(any()))
                .thenAnswer(invocation -> delegate.blockingFeature(invocation.getArgument(0)));
        lenient().when(mock.disabledKeys()).thenAnswer(invocation -> delegate.disabledKeys());
        lenient().when(mock.visibleMenuKeys(any()))
                .thenAnswer(invocation -> delegate.visibleMenuKeys(invocation.getArgument(0)));
        lenient().when(mock.describe()).thenAnswer(invocation -> delegate.describe());
        return local;
    }
}
