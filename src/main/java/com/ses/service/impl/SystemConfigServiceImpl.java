package com.ses.service.impl;

import com.ses.entity.SystemConfig;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * システム設定サービス実装。
 * 値はConcurrentHashMapにキャッシュし、put/初回アクセス時にDBと同期する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemConfigServiceImpl implements SystemConfigService {

    private final SystemConfigMapper systemConfigMapper;
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    private enum ConfigType { STRING, INT, DECIMAL, BOOLEAN, ENUM }

    private static class ConfigSchema {
        final ConfigType type;
        final java.util.Set<String> allowedValues;
        final BigDecimal min;
        final BigDecimal max;

        ConfigSchema(ConfigType type, java.util.Set<String> allowedValues, BigDecimal min, BigDecimal max) {
            this.type = type;
            this.allowedValues = allowedValues;
            this.min = min;
            this.max = max;
        }

        static ConfigSchema string() { return new ConfigSchema(ConfigType.STRING, null, null, null); }
        static ConfigSchema integer(Integer min, Integer max) { return new ConfigSchema(ConfigType.INT, null, min != null ? new BigDecimal(min) : null, max != null ? new BigDecimal(max) : null); }
        static ConfigSchema decimal(String min, String max) { return new ConfigSchema(ConfigType.DECIMAL, null, min != null ? new BigDecimal(min) : null, max != null ? new BigDecimal(max) : null); }
        static ConfigSchema bool() { return new ConfigSchema(ConfigType.BOOLEAN, java.util.Set.of("true", "false"), null, null); }
        static ConfigSchema enumOf(String... values) { return new ConfigSchema(ConfigType.ENUM, java.util.Set.of(values), null, null); }
    }

    private static final java.util.Map<String, ConfigSchema> SCHEMAS = new java.util.HashMap<>();
    static {
        SCHEMAS.put("company.name", ConfigSchema.string());
        SCHEMAS.put("company.email", ConfigSchema.string());
        SCHEMAS.put("company.address", ConfigSchema.string());
        SCHEMAS.put("company.invoice-registration-number", ConfigSchema.string());
        SCHEMAS.put("company.bank-info", ConfigSchema.string());
        SCHEMAS.put("company_name", ConfigSchema.string());
        SCHEMAS.put("company_email", ConfigSchema.string());
        SCHEMAS.put("default_settlement_min", ConfigSchema.integer(0, null));
        SCHEMAS.put("default_settlement_max", ConfigSchema.integer(0, null));
        SCHEMAS.put("ai_enabled", ConfigSchema.bool());
        SCHEMAS.put("billing.tax-rate", ConfigSchema.decimal("0", "100"));
        SCHEMAS.put("billing.payment-due-rule", ConfigSchema.enumOf("next-month-end", "next-next-month-end"));
        SCHEMAS.put("notice.contract-end-days", ConfigSchema.integer(0, null));
        SCHEMAS.put("notice.proposal-stale-days", ConfigSchema.integer(0, null));
        SCHEMAS.put("notice.bench-warn-days", ConfigSchema.integer(0, null));
        SCHEMAS.put("scope.sales-own-data-only", ConfigSchema.bool());
        SCHEMAS.put("commission.base-type", ConfigSchema.enumOf("粗利", "売上"));
        SCHEMAS.put("commission.rate", ConfigSchema.decimal("0", "100"));
        SCHEMAS.put("notification.webhook-url", ConfigSchema.string());
        SCHEMAS.put("notification.webhook-types", ConfigSchema.string());
        SCHEMAS.put("forecast.enabled", ConfigSchema.bool());
        SCHEMAS.put("forecast.win-rate.screening", ConfigSchema.integer(0, 100));
        SCHEMAS.put("forecast.win-rate.first-interview", ConfigSchema.integer(0, 100));
        SCHEMAS.put("forecast.win-rate.second-interview", ConfigSchema.integer(0, 100));
        SCHEMAS.put("forecast.win-rate.awaiting", ConfigSchema.integer(0, 100));
        SCHEMAS.put("closing.confirmed-months", ConfigSchema.string()); // Actually JSON but string is fine
        SCHEMAS.put("cashflow.opening-balance", ConfigSchema.decimal(null, null));
        SCHEMAS.put("cashflow.fixed-cost", ConfigSchema.decimal(null, null));
        SCHEMAS.put("cashflow.alert-threshold", ConfigSchema.decimal(null, null));
        SCHEMAS.put("cashflow.bp-payment-site-months", ConfigSchema.integer(0, 12));
        SCHEMAS.put("cashflow.payroll-estimate", ConfigSchema.decimal(null, null));
        SCHEMAS.put("cashflow.payroll-employer-burden-rate", ConfigSchema.decimal("0", "100"));
        SCHEMAS.put("retention.risk.bench-warn-days", ConfigSchema.integer(0, null));
        SCHEMAS.put("retention.risk.followup-interval-days", ConfigSchema.integer(0, null));
        SCHEMAS.put("retention.risk.threshold", ConfigSchema.integer(0, 100));
    }

    private void ensureLoaded() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    for (SystemConfig c : systemConfigMapper.selectList(null)) {
                        if (c.getConfigValue() != null) {
                            cache.put(c.getConfigKey(), c.getConfigValue());
                        }
                    }
                    loaded = true;
                }
            }
        }
    }

    @Override
    public String getString(String key, String defaultValue) {
        ensureLoaded();
        String v = cache.get(key);
        return StringUtils.hasText(v) ? v : defaultValue;
    }

    @Override
    public int getInt(String key, int defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            log.warn("システム設定の数値変換に失敗しました: key={} value={}", key, v);
            return defaultValue;
        }
    }

    @Override
    public BigDecimal getDecimal(String key, BigDecimal defaultValue) {
        String v = getString(key, null);
        if (v == null) {
            return defaultValue;
        }
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            log.warn("システム設定の小数変換に失敗しました: key={} value={}", key, v);
            return defaultValue;
        }
    }

    @Override
    public void put(String key, String value, String description) {
        ConfigSchema schema = SCHEMAS.get(key);
        if (schema == null) {
            throw com.ses.common.exception.BusinessException.of(400, "error.config.unknownKey");
        }
        if (value != null && !value.isBlank()) {
            if (schema.type == ConfigType.BOOLEAN || schema.type == ConfigType.ENUM) {
                if (schema.allowedValues != null && !schema.allowedValues.contains(value)) {
                    throw com.ses.common.exception.BusinessException.of(400, "error.config.invalidValue");
                }
            } else if (schema.type == ConfigType.INT || schema.type == ConfigType.DECIMAL) {
                try {
                    BigDecimal num = new BigDecimal(value.trim());
                    if (schema.min != null && num.compareTo(schema.min) < 0) {
                        throw com.ses.common.exception.BusinessException.of(400, "error.config.invalidValue");
                    }
                    if (schema.max != null && num.compareTo(schema.max) > 0) {
                        throw com.ses.common.exception.BusinessException.of(400, "error.config.invalidValue");
                    }
                    if (schema.type == ConfigType.INT && num.scale() > 0 && num.stripTrailingZeros().scale() > 0) {
                        throw com.ses.common.exception.BusinessException.of(400, "error.config.invalidValue");
                    }
                } catch (NumberFormatException e) {
                    throw com.ses.common.exception.BusinessException.of(400, "error.config.invalidValue");
                }
            }
        }

        SystemConfig existing = systemConfigMapper.selectById(key);
        SystemConfig config = new SystemConfig(key, value, description);
        if (existing == null) {
            systemConfigMapper.insert(config);
        } else {
            systemConfigMapper.updateById(config);
        }
        // キャッシュ更新 (トランザクションコミット後にのみ可視化し、ロールバック時は破棄)
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        if (value != null) {
                            cache.put(key, value);
                        } else {
                            cache.remove(key);
                        }
                    }

                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) {
                            cache.remove(key);
                        }
                    }
                }
            );
        } else {
            if (value != null) {
                cache.put(key, value);
            } else {
                cache.remove(key);
            }
        }
    }

    @Override
    public List<SystemConfig> all() {
        return systemConfigMapper.selectList(null);
    }
}
