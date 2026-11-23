package com.ses.importcenter.metadata;

import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * NF-06 admission の承認の値の源の選択（feature-enablement-console design §8.3。FE-B02）。{@code app.feature-enablement.source} が
 * {@code database} なら確認済みの署名から読む源（目録の DB 管理の印が無ければ property の値）、それ以外は property の源
 * （{@code FeatureEnablementStateSourceConfiguration} と同じ選び方）。
 */
@Configuration
public class ImportAdmissionApprovalSourceConfiguration {

    @Bean
    public ImportAdmissionApprovalSource.Property propertyImportAdmissionApprovalSource(
            ImportAdmissionApprovalProperties properties) {
        return new ImportAdmissionApprovalSource.Property(properties);
    }

    @Bean
    @Primary
    public ImportAdmissionApprovalSource importAdmissionApprovalSource(
            FeatureEnablementProperties properties, ImportAdmissionApprovalSource.Property propertySource,
            ObjectProvider<FeatureEnablementSnapshotHolder> holder, FeatureEnablementCatalog catalog) {
        if (properties == null || !properties.databaseSource()) {
            return propertySource;
        }
        return new ImportAdmissionApprovalSource.Database(holder.getObject(), catalog, propertySource);
    }
}
