package com.ses.privacy.production;

import com.ses.privacy.production.PrivacyDecisionBinding.Decision;

import java.time.Duration;
import java.util.List;

/**
 * NF07 の承認の値の property の源（feature-enablement-console design §8.5 の 1。dev・test の既定）。{@code privacy.approval.*}
 * （{@link PrivacyApprovalProperties}）と、DSAR の {@code privacy.dsar.due-days}・{@code privacy.dsar.package-max-age}
 * （{@link PrivacyFeatureProperties.Dsar}）をそのまま返す。値は起動時に一度だけ bind される。
 *
 * <p>{@link PrivacyApprovalProperties} の getter と DSAR の期限規則・保存期間の getter を呼んでよいのは、この class だけである
 * （DB の源で空の値を読む漏れを防ぐ。{@code ApprovalValueReadGuardTest}。review P3-15）。</p>
 */
public class PropertyPrivacyApprovalSource implements PrivacyApprovalSource {

    private final PrivacyApprovalProperties approval;
    private final PrivacyFeatureProperties features;

    public PropertyPrivacyApprovalSource(PrivacyApprovalProperties approval, PrivacyFeatureProperties features) {
        this.approval = approval;
        this.features = features;
    }

    @Override
    public String tenantId() {
        return approval == null ? null : approval.getTenantId();
    }

    @Override
    public List<String> capabilities() {
        return approval == null || approval.getCapabilities() == null ? List.of()
                : List.copyOf(approval.getCapabilities().stream().map(value -> value == null ? "" : value).toList());
    }

    @Override
    public String approvalDigest() {
        return approval == null ? null : approval.getApprovalDigest();
    }

    @Override
    public DecisionValue reference(Decision decision) {
        PrivacyApprovalProperties.DecisionReference reference = approval == null ? null : approval.reference(decision);
        return reference == null ? null
                : new DecisionValue(reference.getDecisionId(), reference.getSignedOn(), reference.getExpiresOn());
    }

    @Override
    public Integer dsarDueDays() {
        return features == null || features.getDsar() == null ? null : features.getDsar().getDueDays();
    }

    @Override
    public Duration dsarPackageMaxAge() {
        return features == null || features.getDsar() == null ? null : features.getDsar().getPackageMaxAge();
    }

    @Override
    public int expiryWarningDays() {
        return approval == null ? PrivacyDecisionWindowMonitor.DEFAULT_WARNING_DAYS : approval.getExpiryWarningDays();
    }
}
