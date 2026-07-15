package com.ses.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;

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

    /**
     * Webhook送信等の外部HTTP呼び出し用RestTemplate。
     * 接続先の遅延・無応答でバッチ処理が長時間ブロックされないよう短いタイムアウトを設定する。
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(3))
                .setReadTimeout(Duration.ofSeconds(3))
                .build();
    }
}
