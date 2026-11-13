package com.ses.config;

import com.ses.service.security.AccountLockService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

/**
 * ログイン失敗ハンドラー。
 * 認証情報誤りの場合は失敗回数を加算し、ロック中の場合はロックメッセージへ遷移する。
 */
@Component
@RequiredArgsConstructor
public class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final AccountLockService accountLockService;

    /** 認証失敗イベント監査。失敗したusername以外の認証情報は記録しない。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.ses.service.AuditLogService auditLogService;

    /** 本番初回の期限付きbootstrap mode。bootstrap loginの失敗・期限切れ拒否を区別して監査する。 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProductionBootstrapMode productionBootstrapMode;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String username = request.getParameter("username");
        boolean locked = exception instanceof LockedException;
        if (auditLogService != null) {
            String applicationCode = locked ? "LOGIN_LOCKED" : "LOGIN_FAILURE";
            if (productionBootstrapMode != null && productionBootstrapMode.isRequested()
                    && productionBootstrapMode.isBootstrapUsername(username)) {
                applicationCode = !productionBootstrapMode.isActive() ? "BOOTSTRAP_LOGIN_EXPIRED"
                        : locked ? "BOOTSTRAP_LOGIN_LOCKED" : "BOOTSTRAP_LOGIN_FAILURE";
            }
            auditLogService.record(username, "AUTH", "/login", locked ? 423 : 401, applicationCode, false);
        }
        String target;
        if (exception instanceof LockedException) {
            target = "/login?locked";
        } else {
            if (StringUtils.hasText(username)) {
                accountLockService.onLoginFailure(username);
            }
            target = "/login?error";
        }
        setDefaultFailureUrl(target);
        super.onAuthenticationFailure(request, response, exception);
    }
}
