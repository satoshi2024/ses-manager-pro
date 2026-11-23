package com.ses.featureenablement.evaluation;

import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.catalog.Subgate;
import com.ses.featureenablement.catalog.UnitDefinition;
import com.ses.featureenablement.condition.SignOffRequirements;
import com.ses.featureenablement.service.FeatureEnablementNotifier;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

/**
 * 副入口の成否の監視（feature-enablement-console design §7.4・§8.5 の 9。FE-C04）。
 *
 * <p>副入口（NF07 保持・保全の hold 開始・REL・REG）は DB の状態を持たない。定期の評価のたびに、親の開放単位が開いていること・目録の
 * 署名が有効であること・各機能の固有の条件（{@link Condition}。例: hold 開始は D-05 と Journal の appender、REL・REG は trust の
 * 構造・anchor・verifier）を確かめ、成否が前回から変わったら履歴 {@code SUBGATE_CLOSED}・{@code SUBGATE_OPENED} と通知を出す
 * （最初の観測は記録だけ）。条件が戻れば自動で開く（D-4 の例外。要件 FE-RQ-005 の理由: 署名は二人の確認を経ており、trust の鮮度は
 * 一時の要因）。要求ごとの gate は今のまま各機能が 404・409 を返す（本 class は入口を開閉しない）。副入口の署名（{@code NF07_D05} 等）の
 * 失効は副入口だけを閉じ、開放単位を閉じない（review P3-16）。</p>
 */
@Slf4j
@Component
public class SubgateMonitor {

    /** 副入口の固有の条件（各機能が bean として足す。例外は不成立として扱う）。 */
    public interface Condition {

        /** 対象の副入口（目録の {@link Subgate#code()}）。 */
        String subgateCode();

        /** 条件が成り立つか。 */
        boolean satisfied();

        /** 固定の条件を作る。 */
        static Condition of(String subgateCode, BooleanSupplier satisfied) {
            Objects.requireNonNull(subgateCode, "subgateCode");
            Objects.requireNonNull(satisfied, "satisfied");
            return new Condition() {
                @Override
                public String subgateCode() {
                    return subgateCode;
                }

                @Override
                public boolean satisfied() {
                    return satisfied.getAsBoolean();
                }
            };
        }
    }

    private final FeatureEnablementCatalog catalog;
    private final ObjectProvider<Condition> conditions;
    private final Map<String, Boolean> states = new ConcurrentHashMap<>();

    public SubgateMonitor(FeatureEnablementCatalog catalog, ObjectProvider<Condition> conditions) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.conditions = conditions;
    }

    /**
     * 副入口の成否を評価し、前回から変わった副入口を記録・通知する。
     *
     * @return 副入口 → 開いているか（目録の順）
     */
    public Map<String, Boolean> track(FeatureEnablementSnapshot snapshot, LocalDate today,
                                      FeatureEnablementEvaluationWriter writer, FeatureEnablementNotifier notifier) {
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (UnitDefinition unit : catalog.units()) {
            for (Subgate subgate : unit.subgates()) {
                boolean open = open(snapshot, subgate, today);
                result.put(subgate.code(), open);
                Boolean previous = states.put(subgate.code(), open);
                if (previous == null || previous == open) {
                    continue;
                }
                log.warn("[機能の開放] 副入口の成否が変わりました: errorCode=FEATURE_SUBGATE_{} subgate={} unit={}",
                        open ? "OPENED" : "CLOSED", subgate.code(), subgate.unit().name());
                if (writer != null) {
                    try {
                        writer.recordSubgateChange(subgate.code(), open);
                    } catch (RuntimeException e) {
                        log.warn("[機能の開放] 副入口の変化の履歴に失敗しました（次の変化で記録）: subgate={} exceptionClass={}",
                                subgate.code(), e.getClass().getSimpleName());
                    }
                }
                if (notifier != null) {
                    notifier.notifyAdministrators(open ? "subgateOpened" : "subgateClosed",
                            new Object[]{subgate.code(), subgate.unit().displayName()},
                            "FEATURE_ENABLEMENT:SUBGATE:" + subgate.code() + ":" + open + ":" + today, List.of());
                }
            }
        }
        return result;
    }

    /** 副入口が開いているか（親の開放単位・目録の署名・固有の条件）。 */
    public boolean open(FeatureEnablementSnapshot snapshot, Subgate subgate, LocalDate today) {
        if (!snapshot.unit(subgate.unit()).state().openLike()) {
            return false;
        }
        for (String kind : subgate.signOffKinds()) {
            if (!valid(snapshot, kind, today)) {
                return false;
            }
        }
        for (Condition condition : conditionsFor(subgate.code())) {
            try {
                if (!condition.satisfied()) {
                    return false;
                }
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    private List<Condition> conditionsFor(String code) {
        Stream<Condition> all = conditions == null ? Stream.empty() : conditions.orderedStream();
        return all.filter(condition -> code.equals(condition.subgateCode())).toList();
    }

    private boolean valid(FeatureEnablementSnapshot snapshot, String kind, LocalDate today) {
        FeatureEnablementSnapshot.ConfirmedSignOff signOff = snapshot.signOffs().get(kind);
        return signOff != null && !signOff.signedOn().isAfter(today) && today.isBefore(signOff.expiresOn())
                && SignOffRequirements.inConfirmedBundle(catalog, kind, signOff, snapshot.bundles());
    }
}
