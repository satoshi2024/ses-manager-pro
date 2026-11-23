package com.ses.privacy.production;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * NF07 の承認の値の源の選択（feature-enablement-console design §8.5 の 1。FE-C01）。
 *
 * <p>開放状態の源が {@code database} で、目録が NF07 の開放単位（保持・保全・DSAR）を DB 管理にしていれば DB の源（確認済みの組と署名）、
 * それ以外は property の源（{@code privacy.approval.*}・{@code privacy.dsar.*}）。開放状態の源の選択
 * （{@code FeatureEnablementStateSourceConfiguration}）と同じ判定で、開閉と承認の値の源が食い違わない。</p>
 */
@Configuration
public class PrivacyApprovalSourceConfiguration {

    @Bean
    public PropertyPrivacyApprovalSource propertyPrivacyApprovalSource(PrivacyApprovalProperties approvalProperties,
                                                                       PrivacyFeatureProperties featureProperties) {
        return new PropertyPrivacyApprovalSource(approvalProperties, featureProperties);
    }

    @Bean
    @Primary
    public PrivacyApprovalSource privacyApprovalSource(FeatureEnablementProperties properties,
                                                       FeatureEnablementCatalog catalog,
                                                       PropertyPrivacyApprovalSource propertySource,
                                                       ObjectProvider<FeatureEnablementSnapshotHolder> holder) {
        if (!databaseManaged(properties, catalog)) {
            return propertySource;
        }
        return new DatabasePrivacyApprovalSource(holder.getObject(), propertySource);
    }

    /** NF07 の承認の値を DB の源から読むか（開放状態の源が database で、NF07 の開放単位が DB 管理）。 */
    static boolean databaseManaged(FeatureEnablementProperties properties, FeatureEnablementCatalog catalog) {
        return properties != null && properties.databaseSource() && catalog != null
                && (catalog.databaseManaged(Feature.NF07_RETENTION) || catalog.databaseManaged(Feature.NF07_DSAR));
    }
}
