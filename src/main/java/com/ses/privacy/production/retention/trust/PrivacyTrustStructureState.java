package com.ses.privacy.production.retention.trust;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * NF07 の trust（D-03 = AWS KMS）の設定・文書の<b>構造的な不足</b>の状態（feature-enablement-console design §8.5 の 4。FE-C04）。
 *
 * <p>KMS の trust を設定した prod の起動時に、{@link PrivacyTrustStartupValidator} が機能の開閉（flag）に関係なく検査して書く。
 * 不足は起動を止めず、副入口 REL（hold 解除）・REG（policy の登録と有効化）の条件にだけ使う（不足があれば両方とも 404 のまま）。
 * 保持・保全の開放単位と hold の効力（gate）には影響しない。検査していない（trust 未設定・prod 以外）間は不足なしとして扱う
 * （その間は anchor 0件・verifier 無しで REL・REG は別の条件で閉じている）。実行中の文書の鮮度・anchor は今のまま判定のたびに
 * 読む（{@code KmsPrivacyTrustSource.refresh}）。値・公開鍵・ARN は持たず、固定の違反 code だけを持つ。</p>
 */
@Component
public class PrivacyTrustStructureState {

    private volatile List<String> structural = List.of();
    private volatile boolean inspected;

    /** 構造的な不足が無いか（副入口 REL・REG の条件）。 */
    public boolean sound() {
        return structural.isEmpty();
    }

    /** 構造的な不足の固定 code（無ければ空）。 */
    public List<String> structuralViolations() {
        return structural;
    }

    /** 起動時に検査したか。 */
    public boolean inspected() {
        return inspected;
    }

    /** 起動時検査の結果を書く（{@link PrivacyTrustStartupValidator} と試験だけが呼ぶ）。 */
    public void record(List<String> violations) {
        structural = violations == null ? List.of() : List.copyOf(violations);
        inspected = true;
    }
}
