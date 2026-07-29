package com.ses.config;

import com.ses.service.AuditLogService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** OIDC失敗理由を画面へ漏らさず、一般的な認証失敗表示へ遷移する。 */
@Component
public class OidcAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final AuditLogService auditLogService;

    public OidcAuthenticationFailureHandler() {
        this(null);
    }

    @Autowired
    public OidcAuthenticationFailureHandler(AuditLogService auditLogService) {
        super("/login?oidcError");
        this.auditLogService = auditLogService;
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        // providerのsubject、claim、secret等はURLやログへ出力しない。
        if (auditLogService != null) {
            auditLogService.record(null, "AUTH", "/oauth2/login", 401, "OIDC_LOGIN_FAILURE", false);
        }
        super.onAuthenticationFailure(request, response, exception);
    }
}
