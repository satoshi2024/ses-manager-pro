package com.ses.common.util;

import com.ses.common.exception.BusinessException;

public final class PasswordPolicyValidator {
    private PasswordPolicyValidator() {}
    /** 8文字以上・英字と数字を含む。違反は BusinessException。 */
    public static void validate(String password) {
        if (password == null || password.length() < 8
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*[0-9].*")) {
            throw BusinessException.of("error.passwordPolicy");
        }
    }
}


