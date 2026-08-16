package com.ses.config.portal;

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
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * portal APIのrate limit適用フィルタ（R4.5）。
 * login/招待はIP単位、download/upload/検収はuser単位（未認証は対象APIに到達できない）。
 */
@RequiredArgsConstructor
public class PortalRateLimitFilter extends OncePerRequestFilter {

    private static final Pattern DOWNLOAD = Pattern.compile("^/api/portal/.*/download$");
    private static final Pattern UPLOAD = Pattern.compile("^/api/portal/.*/(attachments|files)$");
    private static final Pattern ACCEPTANCE = Pattern.compile("^/api/portal/customer/acceptances/[^/]+/(accept|reject)$");

    private final PortalRateLimiter rateLimiter;
    private final PortalSecurityProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String method = request.getMethod();
        String uri = request.getRequestURI();
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
        } else if ("POST".equals(method) && (UPLOAD.matcher(uri).matches() || uri.contains("/submissions/"))) {
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

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof PortalLoginUser user) {
            return String.valueOf(user.getPortalUserId());
        }
        return "anonymous";
    }
}
