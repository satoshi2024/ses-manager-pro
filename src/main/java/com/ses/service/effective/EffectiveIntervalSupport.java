package com.ses.service.effective;

import java.time.LocalDate;

/**
 * as-of 契約（{@code from <= asOf} かつ {@code to IS NULL OR to >= asOf}）に沿った effective interval の閉じ方。
 * 変更日当日の as-of で旧 interval を非在籍にするため、閉じ日は変更日の前日（同日開始の degenerate は開始前日）。
 */
public final class EffectiveIntervalSupport {

    private EffectiveIntervalSupport() {
    }

    public static LocalDate closeEffectiveTo(LocalDate openEffectiveFrom, LocalDate changeDate) {
        LocalDate dayBeforeChange = changeDate.minusDays(1);
        if (dayBeforeChange.isBefore(openEffectiveFrom)) {
            return openEffectiveFrom.minusDays(1);
        }
        return dayBeforeChange;
    }

    public static boolean isActiveAtAsOf(LocalDate effectiveFrom, LocalDate effectiveTo, LocalDate asOf) {
        return !effectiveFrom.isAfter(asOf)
                && (effectiveTo == null || !effectiveTo.isBefore(asOf));
    }
}
