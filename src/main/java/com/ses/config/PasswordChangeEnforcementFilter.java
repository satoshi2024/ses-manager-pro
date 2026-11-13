package com.ses.config;

import com.ses.common.util.SecurityInfrastructureUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 初回ログイン時のパスワード変更が済むまで、変更画面・変更API以外の画面とAPIを遮断する（配備決定-3）。
 *
 * <p>対象は {@code sys_user.password_change_required = 1} のユーザーがlocal password loginしたsessionで、
 * {@link LoginSuccessHandler} がsessionへ {@link #PASSWORD_CHANGE_REQUIRED_ATTRIBUTE} を立てる。
 * 本人が {@code PUT /api/profile/password} で変更すると {@code ProfileApiController} が列とsession属性を消す。
 *
 * <ul>
 *   <li>画面: {@value #CHANGE_PAGE} へredirectする。</li>
 *   <li>API: 403のApiResult JSONを返す。</li>
 *   <li>通す: 変更画面のGET、変更APIのPUT、logout、静的リソース、ERRORディスパッチ。</li>
 *   <li>MFA未完了の間は {@link MfaEnforcementFilter} の判定に任せる（MFA → パスワード変更の順に完了させる）。</li>
 *   <li>bootstrap session（{@link ProductionBootstrapMode}）はMFA pendingを立てないため、本filterが先に
 *       パスワード変更を強制し、変更後に {@link BootstrapSessionFilter} がMFA登録を強制する（改密 → MFAの順）。</li>
 * </ul>
 * 依存beanを持たないため、{@code @WebMvcTest} 等のsliceでもsession属性が無ければ何もしない。
 */
@Component
public class PasswordChangeEnforcementFilter extends OncePerRequestFilter {

    public static final String PASSWORD_CHANGE_REQUIRED_ATTRIBUTE = "SES_PASSWORD_CHANGE_REQUIRED";
    public static final String CHANGE_PAGE = "/password/change";
    static final String CHANGE_API = "/api/profile/password";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        HttpSession session = request.getSession(false);
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && isChangeRequired(session) && !isMfaPending(session) && !isAllowed(request)) {
            if (request.getRequestURI().startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                        "{\"code\":403,\"message\":\"パスワードの変更が必要です\",\"data\":null}");
            } else {
                response.sendRedirect(request.getContextPath() + CHANGE_PAGE);
            }
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** sessionに初回パスワード変更の要求があるか（break-glass・bootstrapの判定も同じ属性を参照する）。 */
    public static boolean isChangeRequired(HttpSession session) {
        return session != null && Boolean.TRUE.equals(session.getAttribute(PASSWORD_CHANGE_REQUIRED_ATTRIBUTE));
    }

    private static boolean isMfaPending(HttpSession session) {
        return session != null
                && Boolean.TRUE.equals(session.getAttribute(MfaEnforcementFilter.MFA_PENDING_ATTRIBUTE))
                && !Boolean.TRUE.equals(session.getAttribute(MfaEnforcementFilter.MFA_VERIFIED_ATTRIBUTE));
    }

    private static boolean isAllowed(HttpServletRequest request) {
        String uri = request.getRequestURI();
        String method = request.getMethod();
        return isChangeEndpoint(method, uri)
                // HEADの変更画面を自分自身へredirectし続けない（review P3-8）
                || ("HEAD".equalsIgnoreCase(method) && CHANGE_PAGE.equals(uri))
                || "/logout".equals(uri)
                || SecurityInfrastructureUtils.isStaticResource(request)
                || SecurityInfrastructureUtils.isErrorDispatch(request);
    }

    /**
     * 初回パスワード変更の入口（変更画面のGETと変更APIのPUT）か。
     * break-glass session（{@code BreakGlassServiceImpl}）とbootstrap session（{@link BootstrapSessionFilter}）も
     * 同じ定義で変更経路を判定する。
     */
    public static boolean isChangeEndpoint(String method, String uri) {
        return ("GET".equalsIgnoreCase(method) && CHANGE_PAGE.equals(uri))
                || ("PUT".equalsIgnoreCase(method) && CHANGE_API.equals(uri));
    }
}
