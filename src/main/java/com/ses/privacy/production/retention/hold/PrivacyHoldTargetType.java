package com.ses.privacy.production.retention.hold;

import java.util.Optional;

/**
 * hold の対象の種別（{@code t_legal_hold.target_type}、production-bridge-phased-plan.md §3.4.3 の6）。
 *
 * <p>どちらを許可するかは D-05 の決定で設定する（{@code privacy.hold.allowed-target-types}）。未設定なら hold 開始自体を
 * {@code DECISION_UNKNOWN} で拒否する。
 */
public enum PrivacyHoldTargetType {
    /** 本人（{@code <種別>:<ID>}）。帰属できる行だけを止める。 */
    SUBJECT,
    /** data element（{@code m_pii_data_element.data_element_id}）。その element の行を全て止める。 */
    DATA_ELEMENT;

    /** 保存値・設定値を厳密に解釈する（大小文字違い・前後空白は受け付けない）。 */
    public static Optional<PrivacyHoldTargetType> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (PrivacyHoldTargetType type : values()) {
            if (type.name().equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
