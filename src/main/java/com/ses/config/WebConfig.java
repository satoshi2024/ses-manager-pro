package com.ses.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Validator;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.context.MessageSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Web MVC 設定クラス
 * 静的リソースハンドラー、エンコーディング設定、言語切替(i18n)機構を管理する
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

    /**
     * Locale解決方式。Cookie(SES_LOCALE)に保存することで、ログイン前後・
     * セッション切れ・複数タブでも選択言語を維持する。
     * Bean名は "localeResolver" 固定（Spring Boot自動構成が参照するため）。
     *
     * @return CookieLocaleResolver Cookieベースのロケールリゾルバー
     */
    @Bean
    public LocaleResolver localeResolver() {
        CookieLocaleResolver resolver = new CookieLocaleResolver("SES_LOCALE");
        resolver.setDefaultLocale(Locale.JAPANESE);
        resolver.setCookieMaxAge(Duration.ofDays(365));
        return resolver;
    }

    /**
     * "lang" クエリパラメータ(?lang=ja|en|zh-CN|ko)によるロケール切替インターセプター
     *
     * @return LocaleChangeInterceptor ロケール変更インターセプター
     */
    @Bean
    public LocaleChangeInterceptor localeChangeInterceptor() {
        LocaleChangeInterceptor interceptor = new LocaleChangeInterceptor();
        interceptor.setParamName("lang");
        interceptor.setIgnoreInvalidLocale(true); // 不正値で500にしない
        return interceptor;
    }

    /**
     * ロケール切替インターセプターの登録
     * 静的リソース・APIは "lang" パラメータによる書き換え対象から除外する
     * (Locale解決自体はCookie経由で全リクエストに対して行われる)
     *
     * @param registry インターセプターレジストリ
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(localeChangeInterceptor())
                .excludePathPatterns("/js/**", "/css/**", "/lib/**", "/img/**", "/api/**");
    }
}

