package com.ses.config;

import com.ses.config.portal.PortalRateLimitFilter;
import com.ses.config.portal.PortalSessionFilter;
import com.ses.service.portal.PortalRateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.FilterSecurityInterceptor;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 外部顧客/BPポータル専用SecurityFilterChain（G3: 内部管理画面と別chain・別cookie・別CSRF）。
 * <ul>
 *   <li>対象: /portal/**（画面）と /api/portal/**（API）。内部chainより先にマッチさせる。</li>
 *   <li>principalは{@link com.ses.portal.PortalLoginUser}。内部{@code LoginUser}へ変換する経路を作らない。</li>
 *   <li>sessionはportal専用cookie（PORTAL_SESSION）+ DB永続。servlet HttpSessionは使わない（STATELESS）。</li>
 *   <li>CSRFはportal専用cookie/header（XSRF-TOKEN-PORTAL / X-XSRF-TOKEN-PORTAL）。</li>
 *   <li>rate limitはlogin/招待/download/upload/検収APIへ適用（R4.5）。</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class PortalSecurityConfig {

    public static final String PORTAL_CSRF_COOKIE = "XSRF-TOKEN-PORTAL";
    public static final String PORTAL_CSRF_HEADER = "X-XSRF-TOKEN-PORTAL";

    private final PortalSecurityProperties portalSecurityProperties;

    @Bean
    @Order(1)
    public SecurityFilterChain portalSecurityFilterChain(HttpSecurity http,
                                                         com.ses.service.portal.PortalSessionService portalSessionService,
                                                         PortalRateLimiter portalRateLimiter,
                                                         com.ses.common.util.ClientIpResolver clientIpResolver) throws Exception {
        PortalSessionFilter portalSessionFilter =
                new PortalSessionFilter(portalSessionService, portalSecurityProperties);
        PortalRateLimitFilter portalRateLimitFilter =
                new PortalRateLimitFilter(portalRateLimiter, portalSecurityProperties, clientIpResolver);

        http
            .securityMatcher("/portal/**", "/api/portal/**")
            // 認証前フィルタ（session解決 → rate limit。認可の前に実行する）
            .addFilterBefore(portalSessionFilter, FilterSecurityInterceptor.class)
            .addFilterBefore(portalRateLimitFilter, FilterSecurityInterceptor.class)
            .authorizeHttpRequests(auth -> auth
                // 認証不要（login・招待受諾・MFA有効化（session発行前の続き）・静的資産）
                .requestMatchers(
                    "/portal/login",
                    "/portal/accept-invitation",
                    "/portal/css/**",
                    "/portal/js/**",
                    "/portal/img/**",
                    "/api/portal/auth/login",
                    "/api/portal/auth/accept-invitation",
                    "/api/portal/auth/mfa/complete"
                ).permitAll()
                // 規約同意・logout・meは認証済みなら到達可（同意待ちでもPortalSessionFilterが許可）
                .requestMatchers(
                    "/portal/terms",
                    "/api/portal/auth/consent",
                    "/api/portal/auth/logout",
                    "/api/portal/auth/me"
                ).authenticated()
                // それ以外のportal経路は全て認証必須（portal userのみ。内部roleは適用しない）
                .anyRequest().authenticated()
            )
            // 未認証: APIは401 JSON、画面はloginへリダイレクト
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new org.springframework.security.web.util.matcher.AntPathRequestMatcher("/api/portal/**"))
                .defaultAuthenticationEntryPointFor(
                    new LoginUrlAuthenticationEntryPoint("/portal/login"),
                    new org.springframework.security.web.util.matcher.AntPathRequestMatcher("/portal/**"))
                // 認可失敗: APIは403 JSON、画面は403エラーページ
                .accessDeniedHandler((request, response, denied) -> {
                    if (request.getRequestURI().startsWith("/api/portal/")) {
                        response.setStatus(403);
                        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                        response.getWriter().write("{\"code\":403,\"message\":\"Forbidden\"}");
                    } else {
                        response.sendError(403);
                    }
                })
            )
            // portalはDB永続session（cookie token）で運用し、servlet HttpSessionを発行しない
            .sessionManagement(session -> session
                .sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
            // anonymous tokenを置かない（未認証APIは401、ページはloginへredirectさせる）
            .anonymous(org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer::disable)
            .csrf(csrf -> csrf
                .csrfTokenRepository(portalCsrfTokenRepository())
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            )
            .addFilterAfter(new PortalCsrfCookieFilter(), CsrfFilter.class);
        return http.build();
    }

    private CookieCsrfTokenRepository portalCsrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieName(PORTAL_CSRF_COOKIE);
        repository.setHeaderName(PORTAL_CSRF_HEADER);
        return repository;
    }

    /**
     * CSRFトークンを解決してportal専用Cookieを確実に発行する（内部chainと同じ手法）。
     */
    static class PortalCsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
