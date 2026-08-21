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
        SCHEMAS.put("company.representative", ConfigSchema.string());
        SCHEMAS.put("company.email", ConfigSchema.string());
        SCHEMAS.put("company.address", ConfigSchema.string());
        SCHEMAS.put("company.invoice-registration-number", ConfigSchema.string());
        SCHEMAS.put("company.bank-info", ConfigSchema.string());
        SCHEMAS.put("company_name", ConfigSchema.string());
        SCHEMAS.put("company_email", ConfigSchema.string());
        SCHEMAS.put("default_settlement_min", ConfigSchema.integer(0, null));
        SCHEMAS.put("default_settlement_max", ConfigSchema.integer(0, null));
        SCHEMAS.put("ai_enabled", ConfigSchema.bool());
        // 計算側は小数(0.10=10%)で乗算するため上限は1。commission.rate(百分数0–100)とは別口径。
        SCHEMAS.put("billing.tax-rate", ConfigSchema.decimal("0", "1"));
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
        SCHEMAS.put("forecast.assume-renew", ConfigSchema.bool());
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
        SCHEMAS.put("procurement.payment-max-days", ConfigSchema.integer(1, null));
        // S11 T072: 外部勤怠同期（provider選択・tenant timezone・cursor・直近結果）
        SCHEMAS.put("attendance.sync.provider", ConfigSchema.enumOf("mock", "freee"));
        SCHEMAS.put("attendance.sync.timezone", ConfigSchema.string());
        SCHEMAS.put("attendance.sync.freee.cursor", ConfigSchema.string()); // JSON/ISO cursor。操作状態
        SCHEMAS.put("attendance.sync.last-result", ConfigSchema.string()); // JSON。操作状態
        // S11 T073: 客先工数差異（閾値分・確認理由JSON。confirmedは操作状態）
        SCHEMAS.put("attendance.discrepancy.threshold-minutes", ConfigSchema.integer(0, null));
        SCHEMAS.put("attendance.discrepancy.confirmed", ConfigSchema.string()); // JSON。操作状態
        // S10 T064: 法定帳票のtemplate version（帳票種別別。既定1。帳票様式の更新時に管理者が変更する）
        SCHEMAS.put("compliance.template.EMPLOYMENT_CONDITIONS_STATEMENT.version", ConfigSchema.integer(1, null));
        SCHEMAS.put("compliance.template.DISPATCH_NOTICE.version", ConfigSchema.integer(1, null));
        SCHEMAS.put("compliance.template.DISPATCH_LEDGER.version", ConfigSchema.integer(1, null));
        SCHEMAS.put("compliance.template.INDIVIDUAL_CONTRACT.version", ConfigSchema.integer(1, null));
        // T066 M（外部専門家Review P2-2）: 派遣先通知書の猶予日数（派遣開始日から）。法定値gate確認待ちのconfig既定値
        SCHEMAS.put("compliance.delivery.notice-grace-days", ConfigSchema.integer(0, null));
        // S13 T082/T086: ポータル利用規約versionと公開host（管理者が更新。V104でseed）
        SCHEMAS.put("portal.terms.current-version", ConfigSchema.string());
        SCHEMAS.put("portal.base-domain", ConfigSchema.string());
        // S14 (engineer-self-service-portal-v2 / V105): サーベイ匿名閾値と経費の会計連携provider
        SCHEMAS.put("survey.min-answers", ConfigSchema.integer(1, null));
        SCHEMAS.put("expense.accounting.provider", ConfigSchema.enumOf("mock", "freee"));
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
        synchronized (this) {
            ensureLoaded();
            String v = cache.get(key);
            return StringUtils.hasText(v) ? v : defaultValue;
        }
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
        // キャッシュ更新（トランザクションコミット後にのみ可視化する）。
        // put前はキャッシュを書き換えていないため、rollbackで旧値を書き戻さない。
        // 別トランザクションのcommit済み値をrollback callbackが上書きする競合を防ぐ。
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // commit順序が逆転しても旧callbackが新値を上書きしないよう、
                        // 値は次回DBから再読込させる。scope generationもcommit側で別管理される。
                        // getString()と同じロック内で不可分に失効させ、空cache+loaded=trueの
                        // 窓からscope.sales-own-data-onlyがfalseへfail-openしないようにする。
                        synchronized (SystemConfigServiceImpl.this) {
                            cache.clear();
                            loaded = false;
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
