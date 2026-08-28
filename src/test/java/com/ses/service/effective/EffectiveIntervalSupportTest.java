package com.ses.service.effective;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveIntervalSupportTest {

    @Test
    void closeEffectiveTo_変更日当日のasOfで非在籍になる() {
        LocalDate changeDate = LocalDate.of(2026, 8, 28);
        LocalDate openFrom = LocalDate.of(2026, 7, 1);
        LocalDate closeTo = EffectiveIntervalSupport.closeEffectiveTo(openFrom, changeDate);
        assertEquals(LocalDate.of(2026, 8, 27), closeTo);
        assertFalse(EffectiveIntervalSupport.isActiveAtAsOf(openFrom, closeTo, changeDate));
        assertTrue(EffectiveIntervalSupport.isActiveAtAsOf(openFrom, closeTo, LocalDate.of(2026, 8, 27)));
    }

    @Test
    void closeEffectiveTo_同日開始は開始前日でdegenerateを避ける() {
        LocalDate changeDate = LocalDate.of(2026, 8, 28);
        LocalDate closeTo = EffectiveIntervalSupport.closeEffectiveTo(changeDate, changeDate);
        assertEquals(LocalDate.of(2026, 8, 27), closeTo);
        assertFalse(EffectiveIntervalSupport.isActiveAtAsOf(changeDate, closeTo, changeDate));
    }
}
