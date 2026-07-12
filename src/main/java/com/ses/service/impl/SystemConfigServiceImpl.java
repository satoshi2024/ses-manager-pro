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
        SystemConfig existing = systemConfigMapper.selectById(key);
        SystemConfig config = new SystemConfig(key, value, description);
        if (existing == null) {
            systemConfigMapper.insert(config);
        } else {
            systemConfigMapper.updateById(config);
        }
        // キャッシュ更新
        if (value != null) {
            cache.put(key, value);
        } else {
            cache.remove(key);
        }
    }

    @Override
    public List<SystemConfig> all() {
        return systemConfigMapper.selectList(null);
    }
}
