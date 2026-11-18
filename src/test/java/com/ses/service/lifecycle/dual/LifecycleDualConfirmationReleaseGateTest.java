package com.ses.service.lifecycle.dual;

import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 二者確認の子スイッチを本番でONにする前の署名gate（計画 §7.8 開始条件・§10 Privacy・§12 O-8、統括の判断）。
 * prod profileでだけ動き、子スイッチの実効値がONなのにPrivacy署名の参照が無い・不正・placeholderなら起動を止める。
 */
class LifecycleDualConfirmationReleaseGateTest {

    @Test
    void prod_profileでだけ登録される() {
        assertThat(LifecycleDualConfirmationReleaseGate.class.getAnnotation(Profile.class).value())
                .containsExactly("prod");
    }

    @Test
    void 子スイッチがOFFなら署名の参照が無くても起動する() {
        assertThatCode(() -> gate(true, false, null).validate()).doesNotThrowAnyException();
        // 親がOFFなら子の設定値がONでも実効値はOFF
        assertThatCode(() -> gate(false, true, null).validate()).doesNotThrowAnyException();
    }

    @Test
    void 子スイッチの実効値がONで署名の参照が無いか不正なら起動を止める() {
        for (String reference : new String[]{null, "", "   ", "TODO", "changeme", "Pending", "abc", " PRIV-2026-001",
                "PRIV-2026-001 ", "署名済み", "PRIV 2026 001",
                // W3-21a review P3-6: 完全一致だけでは通っていた値（部分一致・前方一致・英字と数字の両方を要求）
                "TODO-1", "pending-privacy-1", "test-ref-1", "xxxxx1", "TBD2026", "PLACEHOLDER-1", "PRIV-TODO-2026",
                "review-abcd", "20261120", "tmp-2026", "CHANGE_ME-1", "sample-2026", "dev-signoff-1"}) {
            assertThatThrownBy(() -> gate(true, true, reference).validate())
                    .as(String.valueOf(reference))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(LifecycleDualConfirmationReleaseGate.VIOLATION_CODE);
        }
    }

    @Test
    void 子スイッチの実効値がONでも署名の参照があれば起動する() {
        for (String reference : new String[]{"PRIV-REVIEW-2026-11-20", "PRIV-2026-001",
                "reviews/nf01-o8-privacy-signoff.md#v2", "NF01:O8:2026-11-20"}) {
            assertThatCode(() -> gate(true, true, reference).validate()).as(reference).doesNotThrowAnyException();
        }
    }

    @SuppressWarnings("unchecked")
    private static LifecycleDualConfirmationReleaseGate gate(boolean parent, boolean child, String reference) {
        FeatureSwitchProperties properties = new FeatureSwitchProperties();
        properties.getNf01().setEnabled(parent);
        properties.getNf01DualConfirmation().setEnabled(child);
        properties.getNf01DualConfirmation().setPrivacySignOffRef(reference);
        ObjectProvider<FeatureSwitches> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(new FeatureSwitches(properties));
        return new LifecycleDualConfirmationReleaseGate(provider, properties);
    }
}
