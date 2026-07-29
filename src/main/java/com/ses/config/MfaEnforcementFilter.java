package com.ses.config;

import com.ses.common.util.SecurityUtils;
import com.ses.service.security.MfaService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** break-glassのMFA検証完了まで通常画面/APIへの到達を止める。 */
@Component
@RequiredArgsConstructor
public class MfaEnforcementFilter extends OncePerRequestFilter {

    public static final String MFA_PENDING_ATTRIBUTE = "SES_MFA_PENDING";
    public static final String MFA_VERIFIED_ATTRIBUTE = "SES_MFA_VERIFIED";

    private final MfaService mfaService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        boolean pending = session != null && Boolean.TRUE.equals(session.getAttribute(MFA_PENDING_ATTRIBUTE));
        boolean verified = session != null && Boolean.TRUE.equals(session.getAttribute(MFA_VERIFIED_ATTRIBUTE));
        if (authentication != null && authentication.isAuthenticated() && pending && !verified
                && !isMfaEndpoint(request)) {
            if (request.getRequestURI().startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":403,\"message\":\"MFA認証が必要です\",\"data\":null}");
            } else {
                response.sendRedirect(request.getContextPath() + "/mfa/challenge");
            }
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isMfaEndpoint(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals("/logout") || uri.startsWith("/mfa/") || uri.startsWith("/api/security/mfa/")
                || uri.equals("/error") || uri.startsWith("/css/") || uri.startsWith("/js/")
                || uri.startsWith("/img/") || uri.startsWith("/lib/");
    }
}
