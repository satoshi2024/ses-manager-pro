package com.ses.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Web MVC 設定クラス
 * 静的リソースハンドラーとエンコーディング設定を管理する
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 静的リソースハンドラーの追加
     * /lib/** パスをクラスパスの静的リソースにマッピングする
     *
     * @param registry リソースハンドラーレジストリ
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // ライブラリリソースのマッピング（CDNフォールバック用）
        registry.addResourceHandler("/lib/**")
                .addResourceLocations("classpath:/static/lib/");
    }

    /**
     * メッセージコンバーターの設定
     * デフォルトエンコーディングをUTF-8に設定（日本語文字化け防止）
     *
     * @param converters コンバーターリスト
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        // 文字列レスポンスのエンコーディングをUTF-8に設定
        converters.stream()
                .filter(converter -> converter instanceof StringHttpMessageConverter)
                .forEach(converter ->
                    ((StringHttpMessageConverter) converter).setDefaultCharset(StandardCharsets.UTF_8)
                );
    }
}
