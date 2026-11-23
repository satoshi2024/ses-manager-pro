package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffRequirement;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 開放単位が要する署名（design §4.3）。目録の要る種別ごとに、確認済みの署名があり、{@code signed_on ≤ 今日 < expires_on}（JST の
 * 業務日）であること。{@code validUntil} は失効日。署名日前・失効は {@code temporal=true}（D-8: 署名日前の署名では開かず、失効で
 * 自動閉鎖する）。条件付きの種別は条件（例: 公開範囲の値）を snapshot から判定し、当てはまらなければ NOT_APPLICABLE。組に属する種別は、
 * 確認済みの組の member であることも要する（member の有効性はここだけが判定する。design §2.6.1 の P2-N1）。
 */
@Component
public class SignOffConditionEvaluator implements CommonFeatureConditionEvaluator {

    private final FeatureEnablementCatalog catalog;

    public SignOffConditionEvaluator(FeatureEnablementCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    @Override
    public List<FeatureConditionCheck> evaluate(Feature feature, FeatureEvaluationContext context) {
        FeatureEnablementSnapshot snapshot = context.snapshot();
        LocalDate today = context.businessDate();
        List<String> applicable = SignOffRequirements.applicableKinds(catalog, feature, snapshot.settings());
        List<FeatureConditionCheck> checks = new ArrayList<>();
        for (SignOffRequirement requirement : catalog.unit(feature).signOffs()) {
            String kind = requirement.kindCode();
            String prefix = "signoff." + kind;
            if (!applicable.contains(kind)) {
                checks.add(FeatureConditionCheck.notApplicable(prefix + ".notApplicable",
                        FeatureConditionCheck.Category.SIGN_OFF));
                continue;
            }
            FeatureEnablementSnapshot.ConfirmedSignOff signOff = snapshot.signOffs().get(kind);
            if (signOff == null) {
                checks.add(FeatureConditionCheck.unsatisfied(prefix + ".missing", FeatureConditionCheck.Category.SIGN_OFF,
                        false));
            } else if (!SignOffRequirements.inConfirmedBundle(catalog, kind, signOff, snapshot.bundles())) {
                checks.add(FeatureConditionCheck.unsatisfied(prefix + ".bundle.missing",
                        FeatureConditionCheck.Category.SIGN_OFF, false));
            } else if (signOff.signedOn().isAfter(today)) {
                checks.add(FeatureConditionCheck.unsatisfied(prefix + ".signedOn.future",
                        FeatureConditionCheck.Category.SIGN_OFF, true));
            } else if (!today.isBefore(signOff.expiresOn())) {
                checks.add(FeatureConditionCheck.unsatisfied(prefix + ".expiresOn.expired",
                        FeatureConditionCheck.Category.SIGN_OFF, true));
            } else {
                checks.add(FeatureConditionCheck.satisfied(prefix + ".valid", FeatureConditionCheck.Category.SIGN_OFF,
                        signOff.expiresOn()));
            }
        }
        return checks;
    }
}
