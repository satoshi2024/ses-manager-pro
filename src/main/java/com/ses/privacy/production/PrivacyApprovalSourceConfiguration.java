package com.ses.privacy.production;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.evaluation.FeatureEnablementEvaluationService;
import com.ses.featureenablement.state.FeatureEnablementSnapshotHolder;
import com.ses.service.SystemConfigService;
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
                                                       ObjectProvider<FeatureEnablementSnapshotHolder> holder,
                                                       ObjectProvider<SystemConfigService> systemConfig) {
        if (!databaseManaged(properties, catalog)) {
            return propertySource;
        }
        return new DatabasePrivacyApprovalSource(holder.getObject(), () -> expiryWarningDays(systemConfig));
    }

    /**
     * DB の源の失効の警告日数: 署名の失効の接近の通知と同じ system 設定（{@code feature-enablement.expiry-warning-days}、1〜366、既定30。
     * review rb-fe-c P3-4）。読めない・範囲外は既定。
     */
    static int expiryWarningDays(ObjectProvider<SystemConfigService> systemConfig) {
        SystemConfigService config = systemConfig == null ? null : systemConfig.getIfAvailable();
        if (config == null) {
            return FeatureEnablementEvaluationService.DEFAULT_EXPIRY_WARNING_DAYS;
        }
        try {
            int days = config.getInt(FeatureEnablementEvaluationService.EXPIRY_WARNING_DAYS_KEY,
                    FeatureEnablementEvaluationService.DEFAULT_EXPIRY_WARNING_DAYS);
            return days < 1 || days > PrivacyDecisionBinding.MAX_VALIDITY_DAYS
                    ? FeatureEnablementEvaluationService.DEFAULT_EXPIRY_WARNING_DAYS : days;
        } catch (RuntimeException e) {
            return FeatureEnablementEvaluationService.DEFAULT_EXPIRY_WARNING_DAYS;
        }
    }

    /** NF07 の承認の値を DB の源から読むか（開放状態の源が database で、NF07 の開放単位が DB 管理）。 */
    static boolean databaseManaged(FeatureEnablementProperties properties, FeatureEnablementCatalog catalog) {
        return properties != null && properties.databaseSource() && catalog != null
                && (catalog.databaseManaged(Feature.NF07_RETENTION) || catalog.databaseManaged(Feature.NF07_DSAR));
    }
}
