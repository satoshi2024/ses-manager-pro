package com.ses.config.portal;

import com.ses.common.util.ClientIpResolver;
import com.ses.common.util.IpCidrMatcher;
import com.ses.common.util.RequestPaths;
import com.ses.config.PortalSecurityProperties;
import com.ses.service.portal.PortalRateLimiter;
import com.ses.portal.PortalLoginUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * portal APIのrate limit適用フィルタ（R4.5）。
 * login/招待はIP単位、download/upload/検収はuser単位（未認証は対象APIに到達できない）。
 * IPは {@link ClientIpResolver}（信頼プロキシが追記したXFF右端1 hopのみ）で解決する（S13-XFF-01 / FWD-SANITIZE）。
 * 対象pathの判定はX-Forwarded-Prefix・context pathを含まないアプリ内pathで行う（FWD-SANITIZE PRE-P1-1）。
 */
@RequiredArgsConstructor
public class PortalRateLimitFilter extends OncePerRequestFilter {

    private static final Pattern DOWNLOAD = Pattern.compile("^/api/portal/.*/download$");
    private static final Pattern UPLOAD = Pattern.compile("^/api/portal/.*/(attachments|files)$");
    /** BP提出物アップロード。実パスは .../payments/{id}/submissions（末尾スラッシュ無し。S13-P2-02） */
    private static final Pattern SUBMISSION_UPLOAD = Pattern.compile("^/api/portal/.*/submissions$");
    private static final Pattern ACCEPTANCE = Pattern.compile("^/api/portal/customer/acceptances/[^/]+/(accept|reject)$");

    private final PortalRateLimiter rateLimiter;
    private final PortalSecurityProperties properties;
    private final ClientIpResolver clientIpResolver;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        String uri = RequestPaths.pathWithinApplication(request);
        String key = null;
        int perMinute = 0;

        if ("POST".equals(method) && "/api/portal/auth/login".equals(uri)) {
            key = "login:" + clientIp(request);
            perMinute = properties.getRateLimit().getLoginPerMinute();
        } else if ("POST".equals(method) && "/api/portal/auth/accept-invitation".equals(uri)) {
            key = "invite:" + clientIp(request);
            perMinute = properties.getRateLimit().getInvitePerMinute();
        } else if ("GET".equals(method) && DOWNLOAD.matcher(uri).matches()) {
            key = "download:" + currentUserId();
            perMinute = properties.getRateLimit().getDownloadPerMinute();
        } else if ("POST".equals(method)
                && (UPLOAD.matcher(uri).matches() || SUBMISSION_UPLOAD.matcher(uri).matches())) {
            key = "upload:" + currentUserId();
            perMinute = properties.getRateLimit().getUploadPerMinute();
        } else if ("POST".equals(method) && ACCEPTANCE.matcher(uri).matches()) {
            key = "acceptance:" + currentUserId();
            perMinute = properties.getRateLimit().getAcceptancePerMinute();
        }

        if (key != null && perMinute > 0 && !rateLimiter.tryAcquire(key, perMinute)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write("{\"code\":429,\"message\":\"Too Many Requests\"}");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /**
     * IP単位keyの送信元。IPv6は/64へ集約する（FWD-SANITIZE P3-8）。IPv6では1利用者が/64を丸ごと持つのが通例で、
     * /128のままだと同じ/64内でaddressを替えるだけで上限を回避できる。IPv4はそのまま使う。
     */
    private String clientIp(HttpServletRequest request) {
        String ip = clientIpResolver.resolve(request);
        String network64 = IpCidrMatcher.ipv6Network64(ip);
        return network64 != null ? network64 : ip;
    }

    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PortalLoginUser user) {
            return String.valueOf(user.getPortalUserId());
        }
        return "anonymous";
    }
}
