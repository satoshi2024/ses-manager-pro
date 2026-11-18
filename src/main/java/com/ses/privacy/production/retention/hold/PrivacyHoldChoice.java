package com.ses.privacy.production.retention.hold;

import java.util.Optional;

/**
 * SUBJECT hold の開始時に、帰属できない data element ごとに privacy-admin が明示する選択（plan 再 Review C-2 の4、
 * production-bridge-phased-plan.md §3.4.3 の5）。既定値は無い（選択の欠落は開始を拒否する）。
 *
 * <p>選択は HOLD_STARTED command の request hash（Journal 正本）に束縛し、element ごとの required 監査にも残す。
 * これにより「帰属できない element の行は SUBJECT hold で守られない」残余（R-15）は、hold ごとに知った上で受理した
 * ものになる。
 */
public enum PrivacyHoldChoice {
    /**
     * その element に DATA_ELEMENT hold を掛けて守る。SUBJECT hold の開始時点でその element の ACTIVE な
     * DATA_ELEMENT hold が存在しなければならない（画面は SUBJECT hold の前に DATA_ELEMENT hold を開始する）。
     */
    DATA_ELEMENT_HOLD,
    /** その element の行は本 hold で守られないことを受理する。 */
    ACCEPT_RISK;

    /** 厳密に解釈する。完全一致しない値は空（既定値へ倒さない）。 */
    public static Optional<PrivacyHoldChoice> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (PrivacyHoldChoice choice : values()) {
            if (choice.name().equals(value)) {
                return Optional.of(choice);
            }
        }
        return Optional.empty();
    }
}
