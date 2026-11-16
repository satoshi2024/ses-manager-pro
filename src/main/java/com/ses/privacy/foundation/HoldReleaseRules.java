package com.ses.privacy.foundation;

import java.time.Duration;

/**
 * hold 解除 proof の判定規則（NF07-REL）。D-05「解除の承認者規則」と時計の許容ずれを、信頼できる設定から呼出しごとに渡す
 * （要求の本文からは受け取らない）。
 *
 * @param starterSeparationRequired hold の開始者が分かっているとき、発行者・承認者が開始者と別の主体であることを求めるか
 *                                  （既定 true）
 * @param rejectUnknownStarter      開始者が不明（{@link HoldReleaseBinding#UNKNOWN_STARTER}）の hold の解除を拒否するか
 *                                  （既定 true。false なら開始者との分離は判定しない）
 * @param notBeforeTolerance        claim の発行日時が検証時刻より未来であることを許す幅（{@code privacy.clock.max-skew}。
 *                                  期限の側には足さない）。null・負は 0
 */
public record HoldReleaseRules(boolean starterSeparationRequired, boolean rejectUnknownStarter,
                               Duration notBeforeTolerance) {

    public HoldReleaseRules {
        if (notBeforeTolerance == null || notBeforeTolerance.isNegative()) {
            notBeforeTolerance = Duration.ZERO;
        }
    }

    /** 最も厳しい規則（開始者と分離、不明は拒否、許容ずれ 0）。 */
    public static HoldReleaseRules strict() {
        return new HoldReleaseRules(true, true, Duration.ZERO);
    }
}
