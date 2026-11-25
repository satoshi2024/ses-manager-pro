package com.ses.featureenablement.catalog;

import com.ses.config.feature.Feature;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 開放単位の目録の行（requirements §6、design §4.1）。
 *
 * @param feature                   開放単位
 * @param restartRequired           開ける変更を次の起動で反映する（NF05 だけ）
 * @param privacyUnit               NF07 の開放単位（行為者を privacy-admin に限る）
 * @param signOffs                  要る署名の種別
 * @param keys                      条件になる鍵（常時の鍵は含めない）
 * @param specificEvaluatorRequired 固有の評価器を要する（無ければ {@code evaluator.missing} で申請・承認できない）
 * @param openOnlyCodes             開放の時だけの条件の code（D-9。NF02 の全社共通カレンダーだけ）
 * @param openTransitionCodes       開放の遷移の前提の code（NF05 outbound の barrier の OFF の記録だけ）
 * @param subgates                  副入口
 * @param sources                   出処（起動時検査・gate の class と method、FS・RB の節）
 */
public record UnitDefinition(Feature feature, boolean restartRequired, boolean privacyUnit,
                             List<SignOffRequirement> signOffs, List<KeyRequirement> keys,
                             boolean specificEvaluatorRequired,
                             Set<String> openOnlyCodes, Set<String> openTransitionCodes, List<Subgate> subgates,
                             List<String> sources) {

    public UnitDefinition {
        Objects.requireNonNull(feature, "feature");
        signOffs = List.copyOf(signOffs);
        keys = keys == null ? List.of() : List.copyOf(keys);
        openOnlyCodes = openOnlyCodes == null ? Set.of() : Set.copyOf(openOnlyCodes);
        openTransitionCodes = openTransitionCodes == null ? Set.of() : Set.copyOf(openTransitionCodes);
        subgates = subgates == null ? List.of() : List.copyOf(subgates);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 親の開放単位（{@link Feature#parent()}）。 */
    public Optional<Feature> parent() {
        return feature.parent();
    }
}
