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

    @Override
    public String toString() {
        return "PrivacyHoldProperties{allowedTargetTypes=" + allowedTargetTypes + "}";
    }
}
