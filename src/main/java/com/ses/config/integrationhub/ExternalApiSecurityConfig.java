package com.ses.config.integrationhub;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/** NF-05公開API専用SecurityFilterChain。既存internal/portal chainと相互排他的にする。 */
@Configuration
@RequiredArgsConstructor
public class ExternalApiSecurityConfig {
    private final ExternalApiAuditBoundary auditBoundary;
    private final ExternalApiCorrelationFilter correlationFilter;
    private final ExternalApiDisabledFilter disabledFilter;
    private final ExternalApiAuthenticationFilter authenticationFilter;
    private final ExternalApiAuthorizationFilter authorizationFilter;
    private final ExternalApiResponseBoundaryFilter responseBoundaryFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public FilterRegistrationBean<ExternalApiAuditBoundary> disableExternalAuditAutoRegistration(
            ExternalApiAuditBoundary filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<ExternalApiCorrelationFilter> disableExternalCorrelationAutoRegistration(
            ExternalApiCorrelationFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<ExternalApiDisabledFilter> disableExternalDisabledAutoRegistration(
            ExternalApiDisabledFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<ExternalApiAuthenticationFilter> disableExternalAuthenticationAutoRegistration(
            ExternalApiAuthenticationFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<ExternalApiAuthorizationFilter> disableExternalAuthorizationAutoRegistration(
            ExternalApiAuthorizationFilter filter) {
        return disabledRegistration(filter);
    }

    @Bean
    public FilterRegistrationBean<ExternalApiResponseBoundaryFilter> disableExternalResponseBoundaryAutoRegistration(
            ExternalApiResponseBoundaryFilter filter) {
        return disabledRegistration(filter);
    }

    private <T extends jakarta.servlet.Filter> FilterRegistrationBean<T> disabledRegistration(T filter) {
        FilterRegistrationBean<T> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean(name = "externalApiSecurityFilterChain")
    @Order(0)
    public SecurityFilterChain externalApiSecurityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint entryPoint = this::writeAuthenticationFailure;
        AccessDeniedHandler deniedHandler = this::writeForbidden;
        http
                .securityMatcher("/external-api/v1/**")
                .addFilterBefore(auditBoundary, SecurityContextHolderFilter.class)
                .addFilterAfter(correlationFilter, ExternalApiAuditBoundary.class)
                .addFilterAfter(disabledFilter, ExternalApiCorrelationFilter.class)
                .addFilterAfter(authenticationFilter, ExternalApiDisabledFilter.class)
                .addFilterAfter(authorizationFilter, ExternalApiAuthenticationFilter.class)
                .addFilterAfter(responseBoundaryFilter, ExternalApiAuthorizationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, ExternalApiRouteCatalog.SECURITY_MATCHERS).authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(deniedHandler))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(new NullSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable);
        return http.build();
    }

    private void writeAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            org.springframework.security.core.AuthenticationException ignored)
            throws java.io.IOException {
        request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "AUTHENTICATION_FAILED");
        ExternalApiErrorWriter.write(response, objectMapper, correlationId(request), 401,
                "AUTHENTICATION_FAILED", false, 0);
    }

    private void writeForbidden(HttpServletRequest request, HttpServletResponse response,
                                org.springframework.security.access.AccessDeniedException ignored)
            throws java.io.IOException {
        request.setAttribute(ExternalApiErrorWriter.DECISION_ATTRIBUTE, "FORBIDDEN_SCOPE");
        ExternalApiErrorWriter.write(response, objectMapper, correlationId(request), 403,
                "FORBIDDEN_SCOPE", false, 0);
    }

    private String correlationId(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiErrorWriter.CORRELATION_ATTRIBUTE);
        return value instanceof String id ? id : "unavailable";
    }
}
