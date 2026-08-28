package com.ses.config;

import com.ses.common.security.OutboundUrlGuard;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

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
        // 資格期限・as-of・通知境界はJVMの実行環境に依存させず、tenant設定の未設定時既定値
        // と同じAsia/Tokyoで一貫させる。テストではClock beanを固定値へ差し替える。
        return Clock.system(ZoneId.of("Asia/Tokyo"));
    }

    /**
     * 外部HTTP呼び出し用の汎用RestTemplate。
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
     * Webhook送信専用のRestTemplate（SSRF対策）。
     * {@link PinningNoRedirectClientHttpRequestFactory} で検証済みIPへピン留め接続し、
     * リダイレクトは追跡しない。DNSリバインディング（判定後の再解決差し替え）を塞ぐ。
     */
    @Bean("webhookRestTemplate")
    public RestTemplate webhookRestTemplate(RestTemplateBuilder builder, OutboundUrlGuard outboundUrlGuard) {
        PinningNoRedirectClientHttpRequestFactory factory =
                new PinningNoRedirectClientHttpRequestFactory(outboundUrlGuard);
        factory.setConnectTimeout((int) Duration.ofSeconds(3).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(3).toMillis());
        return builder.requestFactory(() -> factory).build();
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
     * SSRF対策としてリダイレクトは追跡しない（宛先ホストはallowlistで固定検証する）。
     */
    @Bean("aiRestTemplate")
    public RestTemplate aiRestTemplate(RestTemplateBuilder builder) {
        NoRedirectClientHttpRequestFactory factory = new NoRedirectClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
        return builder.requestFactory(() -> factory).build();
    }
}
