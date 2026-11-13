package com.ses.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.common.exception.SafeErrorPolicy;
import com.ses.common.result.ApiResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.LocaleResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CSV取込のtransportだけをbody未読で検査する。CSRF token検証は後続CsrfFilterに委ねる。
 *
 * <p>Content-Typeは単一headerで、{@code text/csv}、またはparameterが{@code charset}一つだけの
 * {@code text/csv; charset=utf-8} / {@code text/csv; charset=shift_jis}（大文字小文字は区別しない）に限る。
 * charsetは文字コードの自動判定・上書きに使わず、利用者が明示選択した{@code X-Import-Encoding}と一致する場合だけ許す。
 * それ以外のparameter、複数parameter、quoted値、別charset、不一致、Content-Type headerの重複はすべて415で拒否する。
 *
 * <p>拒否応答の{@code message}にはmessage keyそのものを出さない。本filterはDispatcherServletより前に動くため、
 * 画面と同じ{@link LocaleResolver}（SES_LOCALE cookie、既定は日本語）でlocaleを決め、{@link SafeErrorPolicy}経由で
 * 同梱bundleの許可済みkeyだけを文言へ解決する。MessageSourceが無い（単体test等）か解決できない場合は
 * status別の固定文言に倒す。HTTP statusは文言解決の結果にかかわらず本filterが決めた値のままとする。
 */
@Component
public final class ImportRawBodyTransportGuardFilter extends OncePerRequestFilter {
    public static final long MAX_RAW_BYTES = 64L * 1024 * 1024;
    private static final Pattern SOURCE_CODE = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");
    /** group(1)は省略可能なcharset値。parameterはcharset一つだけ、token形式だけを許す。 */
    private static final Pattern CSV_CONTENT_TYPE = Pattern.compile(
            "text/csv(?:[ \\t]*;[ \\t]*charset=(utf-8|shift_jis))?", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<MessageSource> messageSource;
    private final ObjectProvider<LocaleResolver> localeResolver;

    /** 単体test用。文言はstatus別の固定文言になる（message keyは出さない）。 */
    public ImportRawBodyTransportGuardFilter() {
        this(null, null);
    }

    @Autowired
    public ImportRawBodyTransportGuardFilter(ObjectProvider<MessageSource> messageSource,
                                             ObjectProvider<LocaleResolver> localeResolver) {
        this.messageSource = messageSource;
        this.localeResolver = localeResolver;
    }

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
            reject(request, response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "error.import.uploadMethod");
            return;
        }
        String contentType = singleHeader(request, "Content-Type");
        Matcher csvContentType = contentType == null ? null : CSV_CONTENT_TYPE.matcher(contentType.trim());
        if (csvContentType == null || !csvContentType.matches()) {
            reject(request, response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "error.import.uploadMediaType");
            return;
        }
        String declaredCharset = csvContentType.group(1);
        String contentEncoding = request.getHeader("Content-Encoding");
        if (contentEncoding != null && !"identity".equalsIgnoreCase(contentEncoding.trim())) {
            reject(request, response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    "error.import.uploadContentEncoding");
            return;
        }
        String csrfHeader = request.getHeader("X-XSRF-TOKEN");
        if (csrfHeader == null || csrfHeader.isBlank() || csrfHeader.length() > 4096) {
            reject(request, response, HttpServletResponse.SC_FORBIDDEN, "error.forbidden");
            return;
        }
        String sourceCode = request.getHeader("X-Import-Source-Code");
        String encoding = request.getHeader("X-Import-Encoding");
        if (sourceCode == null || !SOURCE_CODE.matcher(sourceCode).matches()
                || encoding == null || encoding.length() > 16
                || !("UTF8".equals(encoding) || "SHIFT_JIS".equals(encoding))
                || request.getHeader("X-Import-Entity") != null) {
            reject(request, response, HttpServletResponse.SC_BAD_REQUEST, "error.import.invalidUploadHeaders");
            return;
        }
        if (declaredCharset != null && !charsetMatchesSelectedEncoding(declaredCharset, encoding)) {
            // charsetで選択encodingを上書き・推定しない。不一致は片方を黙って採用せず拒否する。
            reject(request, response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE, "error.import.uploadMediaType");
            return;
        }
        if (request.getContentLengthLong() > MAX_RAW_BYTES) {
            reject(request, response, HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "error.import.uploadTooLarge");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean charsetMatchesSelectedEncoding(String declaredCharset, String selectedEncoding) {
        String charset = declaredCharset.toLowerCase(Locale.ROOT);
        return ("utf-8".equals(charset) && "UTF8".equals(selectedEncoding))
                || ("shift_jis".equals(charset) && "SHIFT_JIS".equals(selectedEncoding));
    }

    /** 同名headerが複数ある場合は解釈が曖昧なためnull（拒否）とする。 */
    private static String singleHeader(HttpServletRequest request, String name) {
        Enumeration<String> values = request.getHeaders(name);
        if (values == null) return request.getHeader(name);
        if (!values.hasMoreElements()) return null;
        String value = values.nextElement();
        return values.hasMoreElements() ? null : value;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, int status, String messageKey)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        JSON.writeValue(response.getOutputStream(), ApiResult.error(status, safeMessage(request, status, messageKey)));
    }

    /**
     * 許可済みkeyを利用者向け文言へ解決する。405/415はSafeErrorPolicyの許可statusに無いため、文言の
     * fallbackだけを業務エラー（400）の固定文言にする（HTTP statusは変えない）。
     */
    private String safeMessage(HttpServletRequest request, int status, String messageKey) {
        int fallbackCode = SafeErrorPolicy.safeHttpCode(status) == status ? status : 400;
        MessageSource source = messageSource == null ? null : messageSource.getIfUnique();
        Locale locale = resolveLocale(request);
        return SafeErrorPolicy.resolveBusinessMessage(BusinessException.of(fallbackCode, messageKey), source, locale);
    }

    private Locale resolveLocale(HttpServletRequest request) {
        LocaleResolver resolver = localeResolver == null ? null : localeResolver.getIfUnique();
        if (resolver != null) {
            try {
                Locale resolved = resolver.resolveLocale(request);
                if (resolved != null) return resolved;
            } catch (RuntimeException ignored) {
                // locale解決の失敗で拒否応答自体を失敗させない。既定（日本語）へ倒す。
            }
        }
        return Locale.JAPANESE;
    }
}
