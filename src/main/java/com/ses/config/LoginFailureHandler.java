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

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        String username = request.getParameter("username");
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
