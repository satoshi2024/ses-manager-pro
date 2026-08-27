package com.ses.common.util;

/**
 * ログ向けの簡易マスキング。例外メッセージや宛先本文に混入しうる機微情報を出さない。
 */
public final class LogRedaction {

    private LogRedaction() {
    }

    /** メールアドレスを局所マスクする（{@code ab***@example.com}）。 */
    public static String maskEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "***";
        }
        int atIdx = email.indexOf('@');
        if (atIdx <= 2) {
            return email.charAt(0) + "***" + email.substring(atIdx);
        }
        return email.substring(0, 2) + "***" + email.substring(atIdx);
    }

    /** 例外本文（URL・トークン混入がありうる）は出さず、型名のみ返す。 */
    public static String exceptionType(Throwable e) {
        return e == null ? "UNKNOWN" : e.getClass().getName();
    }
}
