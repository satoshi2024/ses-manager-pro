package com.ses.config;

import com.ses.service.security.AccountLockService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * ログイン成功ハンドラー。
 * 失敗回数・ロックをリセットし、ダッシュボードへ遷移する。
 */
@Component
@RequiredArgsConstructor
public class LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AccountLockService accountLockService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {
        accountLockService.onLoginSuccess(authentication.getName());
        // 要員ロールはダッシュボード権限が無いためマイ勤怠へ、それ以外はダッシュボードへ遷移する。
        boolean isEngineer = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_要員".equals(a.getAuthority()));
        setDefaultTargetUrl(isEngineer ? "/my/timesheet" : "/dashboard");
        setAlwaysUseDefaultTargetUrl(true);
        super.onAuthenticationSuccess(request, response, authentication);
    }
}
