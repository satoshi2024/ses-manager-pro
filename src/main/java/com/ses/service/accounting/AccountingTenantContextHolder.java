package com.ses.service.accounting;

import java.time.ZoneId;

/**
 * 会計連携処理時のテナント・タイムゾーンコンテキスト管理 (ThreadLocal try-finally / R4-T06)。
 * <p>
 * スケジューラ・Worker・リクエスト処理では {@link #runWithTenant(String, ZoneId, Runnable)} を
 * try-finally で使用し、スレッドプール再利用時の ThreadLocal リークを完全防止する (design §6.1)。
 */
public final class AccountingTenantContextHolder {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();
    private static final ThreadLocal<ZoneId> CURRENT_ZONE = new ThreadLocal<>();

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

    public static String getCurrentTenantId() {
        return getTenantId();
    }

    /** テナントの会計タイムゾーンを設定する。未設定時は Asia/Tokyo を返す。 */
    public static void setZoneId(ZoneId zoneId) {
        if (zoneId != null) {
            CURRENT_ZONE.set(zoneId);
        } else {
            CURRENT_ZONE.remove();
        }
    }

    /** 現在の会計タイムゾーンを返す。未設定時は Asia/Tokyo (design §6.1 既定)。 */
    public static ZoneId getZoneId() {
        ZoneId zone = CURRENT_ZONE.get();
        return zone != null ? zone : ZoneId.of("Asia/Tokyo");
    }

    public static void clear() {
        CURRENT_TENANT.remove();
        CURRENT_ZONE.remove();
    }

    /** テナントとタイムゾーンを設定して実行し、finally で完全解除する。 */
    public static void runWithTenant(String tenantId, ZoneId zoneId, Runnable runnable) {
        String prevTenant = CURRENT_TENANT.get();
        ZoneId prevZone = CURRENT_ZONE.get();
        try {
            setTenantId(tenantId);
            setZoneId(zoneId);
            runnable.run();
        } finally {
            if (prevTenant != null) {
                CURRENT_TENANT.set(prevTenant);
            } else {
                CURRENT_TENANT.remove();
            }
            if (prevZone != null) {
                CURRENT_ZONE.set(prevZone);
            } else {
                CURRENT_ZONE.remove();
            }
        }
    }

    public static void runWithTenant(String tenantId, Runnable runnable) {
        runWithTenant(tenantId, null, runnable);
    }

    public static <T> T runWithTenant(String tenantId, java.util.function.Supplier<T> supplier) {
        return runWithTenant(tenantId, null, supplier);
    }

    public static <T> T runWithTenant(String tenantId, ZoneId zoneId, java.util.function.Supplier<T> supplier) {
        String prevTenant = CURRENT_TENANT.get();
        ZoneId prevZone = CURRENT_ZONE.get();
        try {
            setTenantId(tenantId);
            setZoneId(zoneId);
            return supplier.get();
        } finally {
            if (prevTenant != null) {
                CURRENT_TENANT.set(prevTenant);
            } else {
                CURRENT_TENANT.remove();
            }
            if (prevZone != null) {
                CURRENT_ZONE.set(prevZone);
            } else {
                CURRENT_ZONE.remove();
            }
        }
    }
}
