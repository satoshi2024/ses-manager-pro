package com.ses.config;

import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;

/**
 * local login が許されていない利用者（通常 local login 無効で、break-glass・bootstrap のどちらでもない）への拒否。
 *
 * <p>{@link CustomUserDetailsService#loadUserByUsername} がパスワード照合より前に投げるため、この拒否は
 * パスワードの当否を一切判定していない。{@link LoginFailureHandler} はこの拒否を失敗回数に数えない
 * （D-P3-1: 数えると、未認証者が OIDC 管理者の username を送り続けるだけで {@code locked_until} を立てられ、
 * ロック状態を見る privacy・integration hub の認可判定を妨害できるため）。
 * パスワードを照合する経路（local login 許可の利用者、break-glass、bootstrap）の失敗は従来どおり数える。
 *
 * <p>{@code DaoAuthenticationProvider} は {@code loadUserByUsername} の例外を
 * {@code InternalAuthenticationServiceException} に包むので、判定は原因の連鎖をたどる {@link #isCauseOf} で行う。
 */
final class LocalLoginDisabledException extends DisabledException {

    private static final long serialVersionUID = 1L;

    LocalLoginDisabledException() {
        super("local login is disabled");
    }

    /** 認証失敗の例外（または包まれた原因）がこの拒否か。 */
    static boolean isCauseOf(AuthenticationException exception) {
        Throwable current = exception;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (current instanceof LocalLoginDisabledException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
