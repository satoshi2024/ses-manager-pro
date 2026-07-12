package com.ses.service;

import com.ses.entity.SystemConfig;

import java.math.BigDecimal;
import java.util.List;

/**
 * システム設定サービス。型付きアクセサとキャッシュを提供する。
 */
public interface SystemConfigService {

    String getString(String key, String defaultValue);

    int getInt(String key, int defaultValue);

    BigDecimal getDecimal(String key, BigDecimal defaultValue);

    /** 設定を登録・更新し、キャッシュを更新する。 */
    void put(String key, String value, String description);

    /** 全設定を返す。 */
    List<SystemConfig> all();
}
