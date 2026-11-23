package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.SignOffKind;
import com.ses.featureenablement.catalog.SignOffRequirement;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * 開放単位が要する署名の判定の共通部品（snapshot の {@code openUntil}・署名の評価器・指紋が同じ規則を使う。design §3.2・§4.3・§4.5）。
 */
public final class SignOffRequirements {

    private SignOffRequirements() {
    }

    /** 今の設定で要る署名の種別（条件付きの種別は条件が成り立つ時だけ。種別の昇順）。 */
    public static List<String> applicableKinds(FeatureEnablementCatalog catalog, Feature feature,
                                               Map<String, String> settings) {
        TreeSet<String> kinds = new TreeSet<>();
        for (SignOffRequirement requirement : catalog.unit(feature).signOffs()) {
            if (!requirement.conditional() || hasValue(settings.get(requirement.whenSettingSet()))) {
                kinds.add(requirement.kindCode());
            }
        }
        return List.copyOf(kinds);
    }

    /** 今の設定で要る署名の組の種類（種類の昇順）。 */
    public static List<String> applicableBundles(FeatureEnablementCatalog catalog, Feature feature,
                                                 Map<String, String> settings) {
        TreeSet<String> bundles = new TreeSet<>();
        for (String kind : applicableKinds(catalog, feature, settings)) {
            catalog.kind(kind).flatMap(SignOffKind::bundle).ifPresent(bundles::add);
        }
        return List.copyOf(bundles);
    }

    /**
     * 開放単位が要する署名の最も早い失効日（排他）。要る署名が1つでも無い、または組に属する種別が確認済みの組の member でなければ
     * {@link LocalDate#MIN}（閉。review P2-3）。要る署名が無ければ {@link LocalDate#MAX}。
     */
    public static LocalDate openUntil(FeatureEnablementCatalog catalog, Feature feature,
                                      Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs,
                                      Map<String, FeatureEnablementSnapshot.BundleView> bundles,
                                      Map<String, String> settings) {
        LocalDate earliest = LocalDate.MAX;
        for (String kind : applicableKinds(catalog, feature, settings)) {
            FeatureEnablementSnapshot.ConfirmedSignOff signOff = signOffs.get(kind);
            if (signOff == null || !inConfirmedBundle(catalog, kind, signOff, bundles)) {
                return LocalDate.MIN;
            }
            if (signOff.expiresOn().isBefore(earliest)) {
                earliest = signOff.expiresOn();
            }
        }
        return earliest;
    }

    /** 組に属する種別なら、確認済みの組の member であること（組に属さなければ常に true）。 */
    public static boolean inConfirmedBundle(FeatureEnablementCatalog catalog, String kind,
                                            FeatureEnablementSnapshot.ConfirmedSignOff signOff,
                                            Map<String, FeatureEnablementSnapshot.BundleView> bundles) {
        Optional<String> bundleKind = catalog.kind(kind).flatMap(SignOffKind::bundle);
        if (bundleKind.isEmpty()) {
            return true;
        }
        FeatureEnablementSnapshot.BundleView bundle = bundles.get(bundleKind.get());
        if (bundle == null) {
            return false;
        }
        FeatureEnablementSnapshot.BundleMemberView member = bundle.members().get(kind);
        return member != null && member.signOffId() == signOff.id();
    }

    /** 種別の一覧（目録の順。画面と試験の補助）。 */
    public static List<String> requiredKinds(FeatureEnablementCatalog catalog, Feature feature) {
        List<String> kinds = new ArrayList<>();
        for (SignOffRequirement requirement : catalog.unit(feature).signOffs()) {
            kinds.add(requirement.kindCode());
        }
        return List.copyOf(kinds);
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }
}
