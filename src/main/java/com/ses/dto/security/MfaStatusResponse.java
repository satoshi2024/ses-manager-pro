package com.ses.dto.security;

/** 現在ユーザーのMFA状態。secretやrecovery codeは含めない。 */
public record MfaStatusResponse(
        boolean required,
        boolean configured,
        boolean pending,
        boolean verified) {
}
