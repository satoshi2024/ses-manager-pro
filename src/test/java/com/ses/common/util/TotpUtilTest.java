package com.ses.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TotpUtilTest {

    @Test
    void RFC6238のSHA1ベクトルを検証できる() {
        String secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

        assertEquals("287082", TotpUtil.code(secret, 1L, 6));
        assertTrue(TotpUtil.matches(secret, "287082", 1L, 6));
    }

    @Test
    void 生成secretはbase32として再利用できる() {
        String secret = TotpUtil.generateSecret();

        assertEquals(32, secret.length());
        assertTrue(TotpUtil.matches(secret, TotpUtil.code(secret, 123L, 6), 123L, 6));
    }
}
