package com.ses.service.accounting;

import com.ses.entity.SystemConfig;
import com.ses.mapper.SystemConfigMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * テナント別会計タイムゾーン解決コンポーネント (R4-T06)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountingTimezoneResolver {

    public static final String CONFIG_KEY_TIMEZONE = "accounting.timezone";
    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Tokyo");

    private final SystemConfigMapper systemConfigMapper;

    /**
     * 指定されたテナントIDの会計タイムゾーンを取得する。
     * 解決順序:
     *   1. m_system_config の accounting.timezone.{tenantId}
     *   2. m_system_config の accounting.timezone (共通)
     *   3. デフォルト: Asia/Tokyo
     */
    public ZoneId resolve(String tenantId) {
        String effectiveTenant = (tenantId != null && !tenantId.isBlank()) ? tenantId.trim() : "default";
        try {
            // 1. テナント固有設定
            String tenantKey = CONFIG_KEY_TIMEZONE + "." + effectiveTenant;
            SystemConfig tenantConfig = systemConfigMapper.selectById(tenantKey);
            if (tenantConfig != null && tenantConfig.getConfigValue() != null && !tenantConfig.getConfigValue().isBlank()) {
                return ZoneId.of(tenantConfig.getConfigValue().trim());
            }

            // 2. テナント共通設定
            SystemConfig commonConfig = systemConfigMapper.selectById(CONFIG_KEY_TIMEZONE);
            if (commonConfig != null && commonConfig.getConfigValue() != null && !commonConfig.getConfigValue().isBlank()) {
                return ZoneId.of(commonConfig.getConfigValue().trim());
            }
        } catch (Exception e) {
            log.warn("Invalid accounting timezone config for tenant={}, falling back to {}: {}", effectiveTenant, DEFAULT_ZONE, e.getMessage());
        }
        return DEFAULT_ZONE;
    }

    /**
     * 現在のスレッドコンテキストのテナント会計タイムゾーンを取得する。
     */
    public ZoneId getTenantZoneId() {
        return resolve(AccountingTenantContextHolder.getTenantId());
    }

    /**
     * 現在時刻をテナントタイムゾーンで取得する。
     */
    public LocalDateTime now() {
        return LocalDateTime.now(getTenantZoneId());
    }

    /**
     * 指定テナントの現在時刻を取得する。
     */
    public LocalDateTime now(String tenantId) {
        return LocalDateTime.now(resolve(tenantId));
    }

    /**
     * 本日の日付をテナントタイムゾーンで取得する。
     */
    public LocalDate today() {
        return LocalDate.now(getTenantZoneId());
    }

    /**
     * 指定テナントの本日の日付を取得する。
     */
    public LocalDate today(String tenantId) {
        return LocalDate.now(resolve(tenantId));
    }

    /**
     * UTC日時をテナントタイムゾーンのLocalDateTimeに変換する。
     */
    public LocalDateTime fromUtc(LocalDateTime utcDateTime) {
        if (utcDateTime == null) return null;
        return utcDateTime.atZone(ZoneId.of("UTC"))
                .withZoneSameInstant(getTenantZoneId())
                .toLocalDateTime();
    }
}
