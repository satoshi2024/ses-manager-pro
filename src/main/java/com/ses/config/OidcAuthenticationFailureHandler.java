package com.ses.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;

import java.io.IOException;

/** OIDC失敗理由を画面へ漏らさず、一般的な認証失敗表示へ遷移する。 */
public class OidcAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    public OidcAuthenticationFailureHandler() {
        super("/login?oidcError");
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        // providerのsubject、claim、secret等はURLやログへ出力しない。
        super.onAuthenticationFailure(request, response, exception);
    }
}
