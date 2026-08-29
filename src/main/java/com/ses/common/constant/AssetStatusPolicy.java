package com.ses.common.constant;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 資産ステータスの共通語彙を定義するポリシー。
 */
public final class AssetStatusPolicy {

    public static final String IN_STOCK = "IN_STOCK";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String UNDER_MAINTENANCE = "UNDER_MAINTENANCE";
    public static final String LOST = "LOST";
    public static final String DISPOSED = "DISPOSED";
    public static final String RESERVED = "RESERVED";

    public static final Set<String> ALLOWED_VALUES = Set.of(
            IN_STOCK, ASSIGNED, UNDER_MAINTENANCE, LOST, DISPOSED, RESERVED);

    /** 一般状態変更で許可する遷移。IN_STOCK と ASSIGNED の相互遷移は専用貸与サービスが扱う。 */
    public static final Map<String, Set<String>> ALLOWED_GENERIC_TRANSITIONS = Map.of(
            IN_STOCK, Set.of(UNDER_MAINTENANCE, DISPOSED, RESERVED, LOST),
            ASSIGNED, Set.of(LOST),
            UNDER_MAINTENANCE, Set.of(IN_STOCK, LOST),
            LOST, Set.of(DISPOSED),
            DISPOSED, Set.of(LOST),
            RESERVED, Set.of(IN_STOCK, DISPOSED, LOST));

    private AssetStatusPolicy() {
    }

    public static String normalize(String status) {
        return status == null || status.isBlank()
                ? status
                : status.trim().toUpperCase(Locale.ROOT);
    }

    public static boolean isAllowedGenericTransition(String fromStatus, String toStatus) {
        return ALLOWED_GENERIC_TRANSITIONS.getOrDefault(fromStatus, Set.of()).contains(toStatus);
    }
}
