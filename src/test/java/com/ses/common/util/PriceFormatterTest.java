package com.ses.common.util;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.assertEquals;

class PriceFormatterTest {

    @Test
    void testFormatBigDecimal() {
        assertEquals("800,000円", PriceFormatter.format(new BigDecimal("800000")));
        assertEquals("1,000,000円", PriceFormatter.format(new BigDecimal("1000000")));
        assertEquals("0円", PriceFormatter.format(new BigDecimal("0")));
        assertEquals("未設定", PriceFormatter.format((BigDecimal) null));
    }

    @Test
    void testFormatInteger() {
        assertEquals("800,000円", PriceFormatter.format(Integer.valueOf(800000)));
        assertEquals("0円", PriceFormatter.format(Integer.valueOf(0)));
        assertEquals("未設定", PriceFormatter.format((Integer) null));
    }

    @Test
    void testFormatLong() {
        assertEquals("8,000,000,000円", PriceFormatter.format(Long.valueOf(8000000000L)));
        assertEquals("0円", PriceFormatter.format(Long.valueOf(0L)));
        assertEquals("未設定", PriceFormatter.format((Long) null));
    }
}
