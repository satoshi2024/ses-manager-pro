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
     * テナントの会計タイムゾーンを取得する（未設定または不正値の場合は Asia/Tokyo）。
     */
    public ZoneId getTenantZoneId() {
        try {
            SystemConfig config = systemConfigMapper.selectById(CONFIG_KEY_TIMEZONE);
            if (config != null && config.getConfigValue() != null && !config.getConfigValue().isBlank()) {
                return ZoneId.of(config.getConfigValue().trim());
            }
        } catch (Exception e) {
            log.warn("Invalid accounting.timezone config, falling back to {}: {}", DEFAULT_ZONE, e.getMessage());
        }
        return DEFAULT_ZONE;
    }

    /**
     * 現在時刻をテナントタイムゾーンで取得する。
     */
    public LocalDateTime now() {
        return LocalDateTime.now(getTenantZoneId());
    }

    /**
     * 本日の日付をテナントタイムゾーンで取得する。
     */
    public LocalDate today() {
        return LocalDate.now(getTenantZoneId());
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
