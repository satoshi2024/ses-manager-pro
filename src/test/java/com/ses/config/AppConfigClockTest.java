package com.ses.config;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigClockTest {

    @Test
    void 共通ClockはAsiaTokyoを使う() {
        assertEquals(ZoneId.of("Asia/Tokyo"), new AppConfig().clock().getZone());
    }
}
