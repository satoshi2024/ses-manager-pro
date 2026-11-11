package com.ses.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.result.ApiResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/** CSV取込のtransportだけをbody未読で検査する。CSRF token検証は後続CsrfFilterに委ねる。 */
@Component
public final class ImportRawBodyTransportGuardFilter extends OncePerRequestFilter {
    public static final long MAX_RAW_BYTES = 64L * 1024 * 1024;
    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (!"/api/import-center/uploads".equals(path)) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!"POST".equals(request.getMethod())) {
            reject(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "error.import.uploadMethod");
            return;
        }
        String contentType = request.getHeader("Content-Type");
        if (contentType == null || !"text/csv".equals(contentType.trim().toLowerCase(Locale.ROOT))) {
            reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "error.import.uploadMediaType");
            return;
        }
        String contentEncoding = request.getHeader("Content-Encoding");
        if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding.trim())) {
            reject(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "error.import.uploadContentEncoding");
            return;
        }
        String csrfHeader = request.getHeader("X-XSRF-TOKEN");
        if (csrfHeader == null || csrfHeader.isBlank() || csrfHeader.length() > 4096) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "error.forbidden");
            return;
        }
        String sourceCode = request.getHeader("X-Import-Source-Code");
        String encoding = request.getHeader("X-Import-Encoding");
        if (sourceCode == null || !SOURCE_CODE.matcher(sourceCode).matches()
                || encoding == null || encoding.length() > 16
                || !("UTF8".equals(encoding) || "SHIFT_JIS".equals(encoding))
                || request.getHeader("X-Import-Entity") != null) {
            reject(response, HttpServletResponse.SC_BAD_REQUEST, "error.import.invalidUploadHeaders");
            return;
        }
        if (request.getContentLengthLong() > MAX_RAW_BYTES) {
            reject(response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "error.import.uploadTooLarge");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        JSON.writeValue(response.getOutputStream(), ApiResult.error(status, message));
    }
}
