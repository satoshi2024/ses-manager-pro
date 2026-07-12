package com.ses.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * アプリケーション共通のBean定義。
 */
@Configuration
public class AppConfig {

    /**
     * システム時計。テスト時は固定Clockに差し替え可能。
     */
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
