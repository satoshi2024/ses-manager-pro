package com.ses.privacy.production;

import com.ses.common.exception.BusinessException;
import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureSignOffBundleConsistencyCheck;
import com.ses.privacy.production.PrivacyAccessGuard.Capability;
import com.ses.privacy.production.PrivacyDecisionBinding.Decision;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 組「NF07 の決定の束縛」の整合の検査（feature-enablement-console design §2.6.1。FE-C01）。組の確認の時に呼ばれ、合わなければ
 * 400 {@code error.featureEnablement.bundleInconsistent}（引数は固定の違反 code だけ。値は返さない）で確認を何も変えずに終える。
 *
 * <ul>
 *   <li>D-09 の {@code capabilities} が {@code dsar} を承認する時は D-06 を member に含め、承認しない時は含めない
 *       （D-06 は D-09 の digest の正規形が {@code dsar} の時だけ含む決定。{@code PrivacyDecisionBinding#canonicalContent}）。</li>
 *   <li>組の D-01・(D-06)・D-09 の参照・日付と D-09 の承認 tenant・承認 capability から {@code PrivacyDecisionBinding} の正規形 v1 で
 *       計算した digest が、D-09 の {@code approvalDigest} と一致する。tenant は設定の tenant と一致する。形式・placeholder・
 *       決定 ID の重複・有効期間の上限の違反も拒否する（構造の違反だけ。署名日・失効日は組の確認の手順が別に確かめる）。</li>
 * </ul>
 * 入力は組の全ての member（引き継ぐ確認済みの署名を含む）で、member の署名の行の状態は見ない（review P2-N1）。
 */
@Component
public class PrivacyDecisionBindingConsistencyCheck implements FeatureSignOffBundleConsistencyCheck {

    static final String MESSAGE_KEY = "error.featureEnablement.bundleInconsistent";

    @Override
    public String bundleKind() {
        return FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING;
    }

    @Override
    public void verify(String tenantId, Map<String, FeatureSignOff> members) {
        Map<String, PrivacyApprovalSource.SignOffValue> rows = new LinkedHashMap<>();
        if (members != null) {
            members.forEach((kind, row) -> {
                if (row != null) {
                    rows.put(kind, new PrivacyApprovalSource.SignOffValue(row.getReference(), row.getSignedOn(),
                            row.getExpiresOn(), DatabasePrivacyApprovalSource.structured(row.getStructuredValues())));
                }
            });
        }
        if (!rows.containsKey(PrivacyApprovalSource.kindCode(Decision.D01))
                || !rows.containsKey(PrivacyApprovalSource.kindCode(Decision.D09))) {
            throw inconsistent("members.missing");
        }
        PrivacyApprovalSource values = PrivacyApprovalSource.fromSignOffs(rows,
                PrivacyDecisionWindowMonitor.DEFAULT_WARNING_DAYS);
        Set<Capability> approved = PrivacyDecisionBinding.parseCapabilities(values.capabilities());
        if (approved == null) {
            throw inconsistent("capabilities.format");
        }
        boolean d06Member = rows.containsKey(PrivacyApprovalSource.kindCode(Decision.D06));
        if (approved.contains(Capability.DSAR) && !d06Member) {
            throw inconsistent("d06.missing");
        }
        if (!approved.contains(Capability.DSAR) && d06Member) {
            throw inconsistent("d06.notApproved");
        }
        // 期間（署名日前・失効）は判定しない（組の確認の手順が別に確かめる）。構造の違反だけで拒否する
        PrivacyDecisionBinding.Evaluation evaluation = PrivacyDecisionBinding.evaluate(values, approved, tenantId,
                LocalDate.of(2000, 1, 1), Set.of());
        List<String> structural = PrivacyDecisionBinding.structuralViolations(evaluation);
        if (!structural.isEmpty()) {
            throw inconsistent(structural.get(0));
        }
    }

    private static BusinessException inconsistent(String code) {
        return BusinessException.of(400, MESSAGE_KEY, code);
    }
}
