package com.ses.featureenablement.config;

import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 機能の開放の目録の bean（code の正本。design §4.1）。試験は {@code @Primary} の目録（DB 管理の印や試験用の組の種類を足した写し）で
 * 置き換える。
 */
@Configuration
public class FeatureEnablementCatalogConfiguration {

    @Bean
    public FeatureEnablementCatalog featureEnablementCatalog() {
        return FeatureEnablementCatalog.standard();
    }
}
