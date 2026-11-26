package com.ses.featureenablement.config;

import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.featureenablement.state.DatabaseFeatureEnablementStateSource;
import com.ses.featureenablement.state.FeatureEnablementOverlay;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.featureenablement.state.FeatureEnablementStateSource;
import com.ses.featureenablement.state.PropertyFeatureEnablementStateSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Clock;

/**
 * 開放状態の源の選択（design §3.1・FE-E02）。{@code app.feature-enablement.source} が {@code database} なら DB の源、それ以外は
 * property の源。prod で {@code database} 以外を拒否するのは {@code FeatureEnablementProductionValidator}（FE-A08）。
 */
@Configuration
public class FeatureEnablementStateSourceConfiguration {

    @Bean
    public PropertyFeatureEnablementStateSource propertyFeatureEnablementStateSource(FeatureSwitchProperties properties) {
        return new PropertyFeatureEnablementStateSource(properties);
    }

    @Bean
    @Primary
    public FeatureEnablementStateSource featureEnablementStateSource(
            FeatureEnablementProperties properties, PropertyFeatureEnablementStateSource propertySource,
            ObjectProvider<FeatureEnablementSnapshotHolder> holder, FeatureEnablementOverlay overlay, Clock clock) {
        if (!properties.databaseSource()) {
            return propertySource;
        }
        return new DatabaseFeatureEnablementStateSource(holder.getObject(), overlay, properties, clock);
    }
}
