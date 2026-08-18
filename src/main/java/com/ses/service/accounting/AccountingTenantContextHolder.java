package com.ses.service.accounting;

/**
 * 会計連携処理時のテナントコンテキスト管理 (ThreadLocal try-finally).
 */
public final class AccountingTenantContextHolder {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private AccountingTenantContextHolder() {
    }

    public static void setTenantId(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            CURRENT_TENANT.set(tenantId);
        } else {
            CURRENT_TENANT.remove();
        }
    }

    public static String getTenantId() {
        String tenant = CURRENT_TENANT.get();
        return (tenant != null && !tenant.isBlank()) ? tenant : "default";
    }

    public static void clear() {
        CURRENT_TENANT.remove();
    }
}
