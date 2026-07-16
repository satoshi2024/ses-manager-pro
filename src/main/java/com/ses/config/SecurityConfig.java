package com.ses.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import lombok.RequiredArgsConstructor;

/**
 * Spring Security 設定クラス
 * 認証・認可、ログイン、ログアウト、CSRF設定を管理する
 */
@Configuration
@EnableWebSecurity
@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    @Value("${app.security.require-https:false}")
    private boolean requireHttps;

    private final MenuPermissionFilter menuPermissionFilter;
    private final ApiAuditFilter apiAuditFilter;
    private final LoginSuccessHandler loginSuccessHandler;
    private final LoginFailureHandler loginFailureHandler;

    /**
     * MenuPermissionFilterのServletコンテナへの自動登録を無効化する
     * （Spring Securityフィルタチェーン内で明示的に addFilterAfter するため、二重登録を防ぐ）
     */
    @Bean
    public FilterRegistrationBean<MenuPermissionFilter> disableAutoRegistration(MenuPermissionFilter filter) {
        FilterRegistrationBean<MenuPermissionFilter> registrationBean = new FilterRegistrationBean<>(filter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

    /**
     * ApiAuditFilterのServletコンテナへの自動登録を無効化する
     * （Spring Securityフィルタチェーン内で明示的に addFilterAfter するため、二重登録を防ぐ）
     */
    @Bean
    public FilterRegistrationBean<ApiAuditFilter> disableAuditAutoRegistration(ApiAuditFilter filter) {
        FilterRegistrationBean<ApiAuditFilter> registrationBean = new FilterRegistrationBean<>(filter);
        registrationBean.setEnabled(false);
        return registrationBean;
    }

    /**
     * セキュリティフィルタチェーンの設定
     * アクセス制御、フォームログイン、ログアウト、CSRF設定を定義する
     *
     * @param http HttpSecurityオブジェクト
     * @return SecurityFilterChain フィルタチェーン
     * @throws Exception セキュリティ設定エラー
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // ロール別メニューアクセス制御フィルター（認証フィルターの後、認可判定の前に実行）
            .addFilterAfter(menuPermissionFilter, UsernamePasswordAuthenticationFilter.class)
            // API操作ログフィルター（メニュー権限フィルターの後に実行）
            .addFilterAfter(apiAuditFilter, MenuPermissionFilter.class)
            // アクセス制御の設定
            .authorizeHttpRequests(auth -> auth
                // 認証不要のパス（ログインページ、静的リソース、認証API）
                .requestMatchers(
                    "/login",
                    "/error",
                    "/css/**",
                    "/js/**",
                    "/lib/**",
                    "/img/**",
                    "/api/auth/**"
                ).permitAll()
                // ユーザー管理・ロール権限設定は管理者のみアクセス可能
                .requestMatchers(
                    "/user/**",
                    "/api/users/**",
                    "/api/role-menus/**",
                    "/api/notifications/generate",
                    "/system-config/**",
                    "/api/system-configs/**",
                    "/api/work-records/reopen",
                    "/audit-log/**",
                    "/api/audit-logs/**",
                    "/api/contracts/generate-renewals",
                    "/api/autocomplete/users"
                ).hasRole("管理者")
                // その他のリクエストは認証が必要
                .anyRequest().authenticated()
            )
            // フォームログインの設定
            .formLogin(form -> form
                .loginPage("/login")
                .loginProcessingUrl("/login")
                .successHandler(loginSuccessHandler)
                .failureHandler(loginFailureHandler)
                .permitAll()
            )
            // ログアウトの設定
            .logout(logout -> logout
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                .logoutSuccessUrl("/login?logout")
                .invalidateHttpSession(true)
                .deleteCookies("JSESSIONID")
                .permitAll()
            )
            // CSRF: Cookie(XSRF-TOKEN)→ヘッダー(X-XSRF-TOKEN)方式に移行。
            // 生トークンをCookieに載せ、JSがヘッダーへ複製する（SPA/AJAX向け標準構成）。
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .headers(headers -> {
                if (requireHttps) {
                    headers.httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .preload(false)
                        .maxAgeInSeconds(31536000));
                }
            });

        if (requireHttps) {
            http.requiresChannel(channel -> channel.anyRequest().requiresSecure());
        }

        http
            // CSRFトークンを毎リクエストで解決し、XSRF-TOKEN Cookieを確実に発行する
            .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);

        return http.build();
    }

    private CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
            .secure(requireHttps)
            .sameSite("Lax"));
        return repository;
    }

    /**
     * パスワードエンコーダーの設定（開発環境）
     * 開発中は平文のまま比較する（既存の平文シードデータと整合させるため）
     *
     * @return PasswordEncoder 平文エンコーダー
     */
    @Bean
    @Profile("!prod")
    public PasswordEncoder passwordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    /**
     * パスワードエンコーダーの設定（本番環境）
     * 本番環境ではBCrypt方式でパスワードをハッシュ化する
     *
     * @return PasswordEncoder BCryptパスワードエンコーダー
     */
    @Bean
    @Profile("prod")
    public PasswordEncoder prodPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * CSRFトークンを解決してCookie(XSRF-TOKEN)を確実に発行するためのフィルター。
     * CookieCsrfTokenRepositoryはトークンが読み出された時にCookieを書き込むため、
     * 各リクエストでgetToken()を呼び出してCookie発行をトリガーする。
     */
    static class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // getToken() の呼び出しでCookieへの書き込みが行われる
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
