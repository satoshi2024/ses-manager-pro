package com.ses.service.integration;

import java.time.LocalDateTime;

/** 外部カレンダー連携の境界。接続先未設定時も業務トランザクションを外部APIへ依存させない。 */
public interface CalendarProviderAdapter {
    String createEvent(String title, LocalDateTime start, LocalDateTime end, String correlationId);
}
