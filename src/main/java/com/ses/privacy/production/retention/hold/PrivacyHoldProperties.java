package com.ses.privacy.production.retention.hold;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * hold 開始の設定（{@code privacy.hold.*}、production-bridge-phased-plan.md §3.4.3 の6）。
 *
 * <p>既定値は持たない（I-1）。D-05 の署名後に運用者が設定する。application.yml にも書かず、環境変数
 * （{@code PRIVACY_HOLD_ALLOWEDTARGETTYPES=SUBJECT,DATA_ELEMENT} 等）で与える。未設定・形式不正なら hold 開始を
 * {@code DECISION_UNKNOWN} で拒否する（{@link PrivacyHoldDecisions}）。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "privacy.hold")
public class PrivacyHoldProperties {

    /** D-05 で許可した hold の対象種別（{@code SUBJECT} / {@code DATA_ELEMENT}）。空なら hold 開始を拒否する。 */
    private List<String> allowedTargetTypes = new ArrayList<>();

    /** hold 解除（NF07-REL）の D-05「解除の承認者規則」（{@code privacy.hold.release.*}）。 */
    private Release release = new Release();

    @Override
    public String toString() {
        return "PrivacyHoldProperties{allowedTargetTypes=" + allowedTargetTypes + ", release=" + release + "}";
    }

    /**
     * D-05「解除の承認者規則」。既定は最も厳しい側（開始者と分離、開始者が不明なら拒否）で、解釈できない値も厳しい側に倒す。
     * D-05 の署名で緩める場合だけ運用者が設定する。
     */
    @Getter
    @Setter
    public static class Release {

        /** 開始者が分かっている hold で、発行者・承認者を開始者と別の主体にするか（{@code REQUIRED}（既定） / {@code NOT_REQUIRED}）。 */
        private String starterSeparation = "REQUIRED";

        /**
         * 開始者が不明（reconciler が Journal から復元し、開始者の値が戻っていない）の hold の解除（{@code REJECT}（既定） /
         * {@code ACCEPT}）。{@code ACCEPT} なら開始者との分離は判定せず、要求者・発行者・承認者の分離だけを判定する。
         */
        private String unknownStarter = "REJECT";

        /** 開始者との分離を求めるか（{@code NOT_REQUIRED} と明示された場合だけ false）。 */
        public boolean starterSeparationRequired() {
            return !"NOT_REQUIRED".equalsIgnoreCase(starterSeparation == null ? null : starterSeparation.trim());
        }

        /** 開始者が不明の hold の解除を拒否するか（{@code ACCEPT} と明示された場合だけ false）。 */
        public boolean rejectUnknownStarter() {
            return !"ACCEPT".equalsIgnoreCase(unknownStarter == null ? null : unknownStarter.trim());
        }

        @Override
        public String toString() {
            return "Release{starterSeparation=" + starterSeparation + ", unknownStarter=" + unknownStarter + "}";
        }
    }
}
