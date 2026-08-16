package com.ses.config.portal;

import com.ses.config.PortalSecurityProperties;
import com.ses.service.portal.PortalRateLimiter;
import com.ses.service.portal.PortalSessionService;
import com.ses.portal.PortalLoginUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * portal専用session解決フィルタ（portal chain内）。
 * cookie token → t_portal_session検証 → principal解決（PortalLoginUser。内部LoginUserへ変換しない）。
 * 利用規約未同意（termsPending）の間は、同意画面/API以外を遮断して同意へ誘導する（G3・R4）。
 */
@RequiredArgsConstructor
public class PortalSessionFilter extends OncePerRequestFilter {

    public static final String TERMS_REQUIRED_CODE = "TERMS_REQUIRED";

    private final PortalSessionService sessionService;
    private final PortalSecurityProperties properties;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        PortalLoginUser user = sessionService.resolve(request);
        if (user != null) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
            SecurityContextHolder.getContext().setAuthentication(authentication);
            if (user.isTermsPending() && !isTermsAllowedPath(request.getRequestURI())) {
                if (request.getRequestURI().startsWith("/api/portal/")) {
                    response.setStatus(403);
                    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
                    response.getWriter().write("{\"code\":403,\"message\":\"" + TERMS_REQUIRED_CODE + "\"}");
                } else {
                    response.sendRedirect("/portal/terms");
                }
                return;
            }
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            // STATELESS chainのため毎リクエストでcontextを破棄する（thread再利用対策）
            SecurityContextHolder.clearContext();
        }
    }

    /** 規約同意待ちでも到達できる経路（同意画面・同意API・logout・静的資産）。 */
    private boolean isTermsAllowedPath(String uri) {
        if ("/portal/terms".equals(uri) || "/api/portal/auth/consent".equals(uri)
                || "/api/portal/auth/logout".equals(uri)
                || "/api/portal/auth/me".equals(uri)) {
            return true;
        }
        return uri.startsWith("/portal/css/") || uri.startsWith("/portal/js/")
                || uri.startsWith("/portal/img/");
    }
}
