package com.ses.service;

import java.util.Set;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

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
        int at = value.indexOf('@');
        if (at > 2) return value.substring(0, 2) + "***" + value.substring(at);
        return value.length() > 4 ? value.substring(0, 2) + "***" + value.substring(value.length() - 2) : "***";
    }

    /** MySQL DATETIME(0)の丸めで期限が次秒へ進まないよう、保存値と比較値を揃える。 */
    public static LocalDateTime seconds(LocalDateTime value) {
        return value == null ? null : value.truncatedTo(ChronoUnit.SECONDS);
    }

    public static boolean isUnregisterable(com.ses.entity.ExternalAccountReference row) {
        return row.getLegalEntityId() == null || "EXCEPTION_HOLD".equals(row.getStatus())
                || (row.getIdempotencyKey() != null && row.getIdempotencyKey().isBlank());
    }
}
