package com.ses.config.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.result.ApiResult;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

import java.io.IOException;
import java.util.Optional;

/**
 * 機能スイッチがOFFの機能の画面・API・静的入口を一括で404にするServlet filter。
 *
 * <p>配置: Spring Securityのfilter chain（order -100）より前に置く。これにより
 * <ul>
 *   <li>内部chain・portal chain（{@code /api/portal/**}）の両方を1か所で遮断できる。</li>
 *   <li>認証・CSRF・{@code MenuPermissionFilter}・監査より前に止まるため、管理者を含むどのロールでも、
 *       未認証でも同じ404になる（機能スイッチは権限ではなく公開可否。管理者bypassの対象外）。</li>
 *   <li>401/403ではなく404を返す。認証済み利用者には、存在しないURLの404と同じstatus・本文・
 *       主要なsecurity headerになる。未認証利用者は通常URLだと302（画面）/401（API）になるため、
 *       OFFの機能pathだけ404になる差は残る（認証後に判定すると、MenuPermissionFilterより後に
 *       認証されるHTTP Basic等で遮断を迂回できる経路ができるため、認証前に止める方を選んでいる）。</li>
 * </ul>
 * CorrelationIdFilter・PwaNoStoreFilterより後ろに置き、遮断応答にも相関IDとno-storeを付ける。
 *
 * <p>応答: {@code /api/**} は {@code CustomErrorController} の404と同じ {@link ApiResult} JSON、
 * 画面・静的ファイルは {@code sendError(404)} で統一エラーページへ委ねる。Security chainを通らない
 * API応答にも、Spring Securityの既定header（nosniff・frame拒否等）と同じ値を付ける。
 *
 * <p>{@link FeatureSwitches} はObjectProvider経由で取得する。{@code @WebMvcTest} 等のスライスでは
 * 存在しないため素通しにする（本番コンテキストでは必ず存在する）。
 */
@Slf4j
@Component
@Order(FeatureSwitchFilter.ORDER)
@RequiredArgsConstructor
public class FeatureSwitchFilter extends OncePerRequestFilter {

    /** Spring Security（{@code SecurityProperties.DEFAULT_FILTER_ORDER} = -100）より十分前。 */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

    /** {@code CustomErrorController} の404と同じ文言。機能の有無を応答本文から区別させない。 */
    static final String NOT_FOUND_MESSAGE = "お探しのページは存在しないか、移動された可能性があります。";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ObjectProvider<FeatureSwitches> featureSwitchesProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        FeatureSwitches switches = featureSwitchesProvider.getIfAvailable();
        if (switches == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String rawPath = rawPath(request);
        String decodedPath = decodedPath(request, rawPath);
        // %エンコード等の表記ゆれで迂回されないよう、復号後のpathと生のpathの両方で照合する。
        Optional<Feature> blocked = switches.blockingFeature(decodedPath);
        if (blocked.isEmpty() && !rawPath.equals(decodedPath)) {
            blocked = switches.blockingFeature(rawPath);
        }
        if (blocked.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        if (log.isDebugEnabled()) {
            log.debug("[機能スイッチ] OFFの機能への要求を404で遮断しました: feature={}, method={}, path={}",
                    blocked.get().name(), request.getMethod(), decodedPath);
        }
        writeBlockedResponseHeaders(request, response);
        if (isApiPath(decodedPath) || isApiPath(rawPath)) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(JSON.writeValueAsString(
                    ApiResult.error(HttpServletResponse.SC_NOT_FOUND, NOT_FOUND_MESSAGE)));
            return;
        }
        response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }

    /**
     * 遮断応答のheader。no-store系はPwaNoStoreFilterと同じ値（ON切替後に古い404を残さない）、
     * それ以外はSpring Security既定のHeaderWriterと同じ値にして、存在しないURLの404との差を減らす。
     */
    private static void writeBlockedResponseHeaders(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setHeader(HttpHeaders.EXPIRES, "0");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("X-XSS-Protection", "0");
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000 ; includeSubDomains");
        }
    }

    private static boolean isApiPath(String path) {
        return path.equals("/api") || path.startsWith("/api/");
    }

    /** context pathを除いた生のrequest URI（未復号）。 */
    private static String rawPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return "";
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri;
    }

    /** Spring MVCのhandler解決と同じく、%復号・セミコロン除去・連続スラッシュ正規化をしたpath。 */
    private static String decodedPath(HttpServletRequest request, String fallback) {
        try {
            String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
            return path == null || path.isEmpty() ? fallback : path;
        } catch (IllegalArgumentException malformed) {
            // 不正な%エンコードはServlet container/firewallで拒否される。ここでは生のpathだけで照合する。
            return fallback;
        }
    }
}
