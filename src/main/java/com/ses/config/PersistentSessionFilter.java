package com.ses.config;

import com.ses.service.security.PersistentSessionService;
import com.ses.service.security.BreakGlassService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 永続sessionが失効・期限切れなら、同じJSESSIONIDでの継続利用を拒否する。 */
@Component
@RequiredArgsConstructor
public class PersistentSessionFilter extends OncePerRequestFilter {

    private final ObjectProvider<PersistentSessionService> persistentSessionServiceProvider;
    private final ObjectProvider<BreakGlassService> breakGlassServiceProvider;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        PersistentSessionService persistentSessionService = persistentSessionServiceProvider.getIfAvailable();
        if (persistentSessionService == null) {
            // @WebMvcTest等のsliceではsession実行層を配線しない。実アプリではBeanが必須で存在する。
            filterChain.doFilter(request, response);
            return;
        }
        BreakGlassService breakGlassService = breakGlassServiceProvider.getIfAvailable();
        boolean valid = authentication == null || !authentication.isAuthenticated()
                || (persistentSessionService.validateAndTouch(request, authentication)
                && (breakGlassService == null || breakGlassService.validateBoundSession(request, authentication)));
        if (!valid) {
            jakarta.servlet.http.HttpSession session = request.getSession(false);
            if (session != null) {
                session.invalidate();
            }
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
