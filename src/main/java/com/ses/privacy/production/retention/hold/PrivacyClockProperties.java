package com.ses.privacy.production.retention.hold;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 時計の許容ずれ（{@code privacy.clock.max-skew}、journal-witness-grant-design.md §2.1）。値は P-ACT で決める（既定なし）。
 *
 * <p>未設定なら hold 開始を {@code DECISION_UNKNOWN} で拒否する（production-bridge-phased-plan.md §3.4.6 の6）。
 * reconciler は「Journal に command が無い」pending を消す前に、{@code pending_since} + {@code privacy.journal.pending-timeout}
 * に本値を足した時刻まで待つ（pending を書いた process と reconciler を動かす process の時計が違う場合の余裕）。
 * 未設定なら reconciler は pending を消さない。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "privacy.clock")
public class PrivacyClockProperties {

    /** 受け付ける上限（これより大きい値は設定誤りとして扱う）。 */
    public static final Duration MAX_ACCEPTED_SKEW = Duration.ofMinutes(10);

    /** application の process 間・Journal との時計の許容ずれ。正の値で {@link #MAX_ACCEPTED_SKEW} 以下。 */
    private Duration maxSkew;

    /** 設定値が有効か（正の値で上限以下）。 */
    public boolean isMaxSkewValid() {
        return maxSkew != null && !maxSkew.isNegative() && !maxSkew.isZero() && maxSkew.compareTo(MAX_ACCEPTED_SKEW) <= 0;
    }

    @Override
    public String toString() {
        return "PrivacyClockProperties{maxSkew=" + maxSkew + "}";
    }
}
