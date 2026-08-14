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

    /**
     * 外部SaaS（freee, CloudSign等）用のRestTemplate。
     * 連携処理用に長めのタイムアウトを設定する。
     */
    @Bean("saasRestTemplate")
    public RestTemplate saasRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * CloudSign専用のRestTemplate。
     * HFP-02-AC-02-04: cloudsign.read-timeout-ms は公式の「最大180秒接続維持」より短い既定15秒を使い、
     * タイムアウト時に provider が処理継続中の可能性があることを「結果不明」として扱う。
     */
    @Bean("cloudsignRestTemplate")
    public RestTemplate cloudsignRestTemplate(RestTemplateBuilder builder, CloudSignProperties properties) {
        return builder
                .setConnectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()))
                .build();
    }

    /**
     * AI API（Gemini等）用のRestTemplate。
     * 生成モデルの応答は遅い場合があるため、読取タイムアウトを長めに設定する。
     */
    @Bean("aiRestTemplate")
    public RestTemplate aiRestTemplate(RestTemplateBuilder builder) {
        return builder
                .setConnectTimeout(Duration.ofSeconds(5))
                .setReadTimeout(Duration.ofSeconds(60))
                .build();
    }
}
