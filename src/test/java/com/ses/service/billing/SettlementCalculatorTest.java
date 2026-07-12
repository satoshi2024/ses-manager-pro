package com.ses.service.billing;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementCalculatorTest {

    @Test
    void testFixed() {
        BigDecimal unitPrice = new BigDecimal("50"); // 50万
        BigDecimal amount = SettlementCalculator.calc(unitPrice, null, null, new BigDecimal("160"));
        assertThat(amount).isEqualByComparingTo("500000");
    }

    @Test
    void testWithinRange() {
        BigDecimal unitPrice = new BigDecimal("60"); // 60万
        BigDecimal hoursMin = new BigDecimal("140");
        BigDecimal hoursMax = new BigDecimal("180");
        BigDecimal actualHours = new BigDecimal("150");
        BigDecimal amount = SettlementCalculator.calc(unitPrice, hoursMin, hoursMax, actualHours);
        assertThat(amount).isEqualByComparingTo("600000");
    }

    @Test
    void testExactBounds() {
        BigDecimal unitPrice = new BigDecimal("60"); // 60万
        BigDecimal hoursMin = new BigDecimal("140");
        BigDecimal hoursMax = new BigDecimal("180");
        
        // Lower bound
        BigDecimal amountMin = SettlementCalculator.calc(unitPrice, hoursMin, hoursMax, new BigDecimal("140"));
        assertThat(amountMin).isEqualByComparingTo("600000");
        
        // Upper bound
        BigDecimal amountMax = SettlementCalculator.calc(unitPrice, hoursMin, hoursMax, new BigDecimal("180"));
        assertThat(amountMax).isEqualByComparingTo("600000");
    }

    @Test
    void testOver() {
        BigDecimal unitPrice = new BigDecimal("60"); // 60万
        BigDecimal hoursMin = new BigDecimal("140");
        BigDecimal hoursMax = new BigDecimal("180");
        BigDecimal actualHours = new BigDecimal("185"); // +5h
        // 600,000 / 180 = 3333.3333333333
        // 5 * 3333.3333333333 = 16666.6666666665
        // 600000 + 16666 = 616666
        BigDecimal amount = SettlementCalculator.calc(unitPrice, hoursMin, hoursMax, actualHours);
        assertThat(amount).isEqualByComparingTo("616666");
    }

    @Test
    void testUnder() {
        BigDecimal unitPrice = new BigDecimal("60"); // 60万
        BigDecimal hoursMin = new BigDecimal("140");
        BigDecimal hoursMax = new BigDecimal("180");
        BigDecimal actualHours = new BigDecimal("130"); // -10h
        // 600,000 / 140 = 4285.7142857143
        // 10 * 4285.7142857143 = 42857.142857143
        // 600000 - 42857.142857143 = 557142.857142857 => 557142
        BigDecimal amount = SettlementCalculator.calc(unitPrice, hoursMin, hoursMax, actualHours);
        assertThat(amount).isEqualByComparingTo("557142");
    }
}
