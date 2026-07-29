package com.ses.config;

import com.ses.service.security.PersistentSessionService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 永続sessionが失効・期限切れなら、同じJSESSIONIDでの継続利用を拒否する。 */
@Component
@RequiredArgsConstructor
public class PersistentSessionFilter extends OncePerRequestFilter {

    private final PersistentSessionService persistentSessionService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !persistentSessionService.validateAndTouch(request, authentication)) {
            request.getSession(false).invalidate();
            SecurityContextHolder.clearContext();
            if (request.getRequestURI().startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"code\":401,\"message\":\"sessionが失効しています\",\"data\":null}");
            } else {
                response.sendRedirect(request.getContextPath() + "/login?sessionExpired");
            }
            return;
        }
        filterChain.doFilter(request, response);
    }
}
