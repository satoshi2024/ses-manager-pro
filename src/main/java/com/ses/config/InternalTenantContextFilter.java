package com.ses.config;

import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTimezoneResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内部SecurityFilterChainの認証済みSysUserからtenant contextを固定する。
 * request parameter・body・headerはtenant認可の根拠にしない。
 */
@Component
public class InternalTenantContextFilter extends OncePerRequestFilter {

    public static final String TENANT_CONTEXT_REQUIRED = "TENANT_CONTEXT_REQUIRED";
    public static final String OIDC_TENANT_MISMATCH = "OIDC_TENANT_MISMATCH";

    private OidcSecurityProperties oidcSecurityProperties;
    private AccountingTimezoneResolver timezoneResolver;

    public InternalTenantContextFilter() {
    }

    @Autowired(required = false)
    public void configure(OidcSecurityProperties oidcSecurityProperties,
                          AccountingTimezoneResolver timezoneResolver) {
        this.oidcSecurityProperties = oidcSecurityProperties;
        this.timezoneResolver = timezoneResolver;
    }

    /** 単体テストおよび既存のfilterテスト向けの明示的な依存性注入。 */
    public InternalTenantContextFilter(OidcSecurityProperties oidcSecurityProperties,
                                       AccountingTimezoneResolver timezoneResolver) {
        this.oidcSecurityProperties = oidcSecurityProperties;
        this.timezoneResolver = timezoneResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        Object principal = authentication.getPrincipal();
        // metrics scraper等の機械認証はtenantを持たない専用境界であり、内部LoginUser契約へ混在させない。
        // 通常の内部ログイン主体は必ずLoginUser/OidcLoginUserである。
        if (!(principal instanceof LoginUser loginUser)) {
            filterChain.doFilter(request, response);
            return;
        }

        String tenantId = loginUser.getTenantId();
        if (!StringUtils.hasText(tenantId)) {
            deny(response, TENANT_CONTEXT_REQUIRED);
            return;
        }
        tenantId = tenantId.trim();

        if (principal instanceof OidcLoginUser
                && oidcSecurityProperties != null
                && StringUtils.hasText(oidcSecurityProperties.getTenantId())
                && !tenantId.equals(oidcSecurityProperties.getTenantId().trim())) {
            deny(response, OIDC_TENANT_MISMATCH);
            return;
        }

        AtomicReference<Exception> failure = new AtomicReference<>();
        String boundTenantId = tenantId;
        Runnable requestChain = () -> {
            try {
                filterChain.doFilter(request, response);
            } catch (Exception e) {
                failure.set(e);
            }
        };
        ZoneId zoneId = timezoneResolver == null
                ? ZoneId.of("Asia/Tokyo")
                : timezoneResolver.resolve(boundTenantId);
        AccountingTenantContextHolder.runWithTenant(boundTenantId, zoneId, requestChain);

        Exception exception = failure.get();
        if (exception instanceof IOException io) {
            throw io;
        }
        if (exception instanceof ServletException servlet) {
            throw servlet;
        }
        if (exception != null) {
            throw new ServletException(exception);
        }
    }

    private void deny(HttpServletResponse response, String code) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader("X-SES-Error-Code", code);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":403,\"message\":\"" + code + "\",\"data\":null}");
    }
}
