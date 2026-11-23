package com.ses.service.lifecycle.dual;

import jakarta.annotation.PostConstruct;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 二者確認の Privacy 署名の参照の規則（計画 §7.8 開始条件・§10 Privacy・§12 O-8、統括の判断）。
 *
 * <p>FE-B01（feature-enablement-console design §8.2、requirements §7）: 以前は prod profile の起動時検査で、子スイッチの実効値が
 * ONなのに参照が無い・不正・placeholderなら起動を止めていた。機能の条件では起動を止めず、開放単位を閉じる形に改めたため、本classは
 * どの profile でも起動時に動く bean ではない（判定は {@link LifecycleDualConfirmationEnablementConditions} が使う）。</p>
 */
class LifecycleDualConfirmationReleaseGateTest {

    @Test
    void prodでも起動を止めない_beanでも起動時の検査でもない() {
        assertThat(LifecycleDualConfirmationReleaseGate.class.getAnnotation(Component.class)).isNull();
        assertThat(LifecycleDualConfirmationReleaseGate.class.getAnnotation(Profile.class)).isNull();
        assertThat(Modifier.isFinal(LifecycleDualConfirmationReleaseGate.class.getModifiers())).isTrue();
        assertThat(Arrays.stream(LifecycleDualConfirmationReleaseGate.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostConstruct.class)).map(Method::getName))
                .as("起動時の検査（@PostConstruct）を持たない").isEmpty();
        assertThat(Arrays.stream(LifecycleDualConfirmationReleaseGate.class.getDeclaredConstructors())
                .allMatch(constructor -> Modifier.isPrivate(constructor.getModifiers()))).isTrue();
    }

    @Test
    void 参照が無いか不正かplaceholderなら受け付けない() {
        for (String reference : new String[]{null, "", "   ", "TODO", "changeme", "Pending", "abc", " PRIV-2026-001",
                "PRIV-2026-001 ", "署名済み", "PRIV 2026 001",
                // W3-21a review P3-6: 完全一致だけでは通っていた値（部分一致・前方一致・英字と数字の両方を要求）
                "TODO-1", "pending-privacy-1", "test-ref-1", "xxxxx1", "TBD2026", "PLACEHOLDER-1", "PRIV-TODO-2026",
                "review-abcd", "20261120", "tmp-2026", "CHANGE_ME-1", "sample-2026", "dev-signoff-1"}) {
            assertThat(LifecycleDualConfirmationReleaseGate.acceptable(reference)).as(String.valueOf(reference)).isFalse();
        }
    }

    @Test
    void 署名の参照があれば受け付ける() {
        for (String reference : new String[]{"PRIV-REVIEW-2026-11-20", "PRIV-2026-001",
                "reviews/nf01-o8-privacy-signoff.md#v2", "NF01:O8:2026-11-20"}) {
            assertThat(LifecycleDualConfirmationReleaseGate.acceptable(reference)).as(reference).isTrue();
        }
    }
}
