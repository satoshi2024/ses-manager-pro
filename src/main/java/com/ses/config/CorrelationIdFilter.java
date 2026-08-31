package com.ses.config;

import com.ses.common.util.CorrelationContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * APIリクエストへ相関IDを付与し、パスに含まれる業務IDを安全な診断コンテキストへ登録する。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern DIGITAL_INVOICE_PATH = Pattern.compile(
            "^/api/digital-invoices/(?:preview/|dispatch/)?(\\d+)(?:/.*)?$");
    private static final Pattern DIGITAL_INVOICE_DETAIL_PATH = Pattern.compile(
            "^/api/digital-invoices/(\\d+)/(?:status-history|cancel|xml)$");
    private static final Pattern INBOUND_PATH = Pattern.compile("^/api/inbound-invoices/(\\d+)(?:/.*)?$");
    private static final Pattern JOB_PATH = Pattern.compile("^/api/accounting/jobs/(\\d+)(?:/.*)?$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = CorrelationContext.begin(request.getHeader("X-Correlation-ID"));
        response.setHeader("X-Correlation-ID", correlationId);
        registerPathIds(request.getRequestURI());
        request.setAttribute(CorrelationContext.CORRELATION_ID, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            CorrelationContext.clear();
        }
    }

    private static void registerPathIds(String uri) {
        if (uri == null) {
            return;
        }
        Matcher detail = DIGITAL_INVOICE_DETAIL_PATH.matcher(uri);
        if (detail.matches()) {
            CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, detail.group(1));
            return;
        }
        Matcher digital = DIGITAL_INVOICE_PATH.matcher(uri);
        if (digital.matches()) {
            if (uri.contains("/preview/") || uri.contains("/dispatch/")) {
                CorrelationContext.put(CorrelationContext.INVOICE_ID, digital.group(1));
            } else {
                CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, digital.group(1));
            }
            return;
        }
        Matcher inbound = INBOUND_PATH.matcher(uri);
        if (inbound.matches()) {
            CorrelationContext.put(CorrelationContext.DIGITAL_INVOICE_ID, inbound.group(1));
            return;
        }
        Matcher job = JOB_PATH.matcher(uri);
        if (job.matches()) {
            CorrelationContext.put(CorrelationContext.JOB_ID, job.group(1));
        }
    }
}
