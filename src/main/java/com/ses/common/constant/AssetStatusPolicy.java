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

    /**
     * 一般状態変更で許可する遷移。
     *
     * <p>ASSIGNED/IN_STOCK は貸与サービス、LOST/DISPOSED は専用終端処理が扱うため、
     * {@code AssetService.changeStatus} からは選択できない。</p>
     */
    public static final Map<String, Set<String>> ALLOWED_GENERIC_TRANSITIONS = Map.of(
            IN_STOCK, Set.of(UNDER_MAINTENANCE, RESERVED),
            UNDER_MAINTENANCE, Set.of(),
            RESERVED, Set.of());

    /** 汎用状態変更では到達させない状態。専用サービスが副作用と一緒に処理する。 */
    public static final Set<String> DEDICATED_TRANSITION_TARGETS = Set.of(
            ASSIGNED, IN_STOCK, LOST, DISPOSED);

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
