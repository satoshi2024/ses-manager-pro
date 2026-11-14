package com.ses.service;

import java.util.Set;

/** 外部アカウント失効要求の送信状態。確認pollとは対象を分離する。 */
public final class ExternalAccountRevocationStates {
    public static final String REQUESTED = "REVOKE_REQUESTED";
    public static final String SENDING = "REVOKE_SENDING";
    public static final String FAILED = "REVOKE_SEND_FAILED";
    public static final String SENT = "REVOKE_SENT";
    public static final String UNKNOWN = "REVOKE_SEND_UNKNOWN";
    public static final Set<String> UNSENT = Set.of(REQUESTED, SENDING, FAILED);

    private ExternalAccountRevocationStates() { }

    /** 表示・通知専用。送信には台帳の原値を使う。 */
    public static String maskIdentifier(String value) {
        if (value == null || value.isBlank()) return "***";
        return value.length() > 4 ? value.substring(0, 2) + "***" + value.substring(value.length() - 2) : "***";
    }
}
