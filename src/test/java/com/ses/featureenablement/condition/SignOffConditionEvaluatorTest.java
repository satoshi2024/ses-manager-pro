package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A03: 署名の評価器（design §4.3）。JST の業務日の境（失効日の当日の JST 0:00 から無効）・署名日前（D-8: 署名日前の署名では
 * 開かない）・条件付きの種別（公開範囲を設定する時だけ要る棚卸し）・組の member（組に属する種別は確認済みの組の member であること）。
 */
class SignOffConditionEvaluatorTest {

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();
    private final SignOffConditionEvaluator evaluator = new SignOffConditionEvaluator(catalog);

    @Test
    void 失効日の当日のJST0時から無効になり署名日の当日から有効() {
        LocalDate expires = LocalDate.of(2026, 11, 24);
        FeatureEnablementSnapshot snapshot = nf03(LocalDate.of(2026, 11, 23), expires);

        // 2026-11-23T23:59:59+09:00（UTC 14:59:59）はまだ有効
        Clock before = Clock.fixed(Instant.parse("2026-11-23T14:59:59Z"), ZoneOffset.UTC);
        assertThat(FeatureEvaluationContext.businessDate(before)).isEqualTo(LocalDate.of(2026, 11, 23));
        FeatureConditionCheck valid = only(evaluate(snapshot, before));
        assertThat(valid.status()).isEqualTo(FeatureConditionCheck.Status.SATISFIED);
        assertThat(valid.validUntil()).isEqualTo(expires);

        // 2026-11-24T00:00:00+09:00（UTC 15:00:00）から失効
        Clock after = Clock.fixed(Instant.parse("2026-11-23T15:00:00Z"), ZoneOffset.UTC);
        assertThat(FeatureEvaluationContext.businessDate(after)).isEqualTo(expires);
        FeatureConditionCheck expired = only(evaluate(snapshot, after));
        assertThat(expired.code()).isEqualTo("signoff.NF03_BUSINESS_OWNER.expiresOn.expired");
        assertThat(expired.status()).isEqualTo(FeatureConditionCheck.Status.UNSATISFIED);
        assertThat(expired.temporal()).isTrue();

        // 署名日の前日は不成立（時間の経過で成り立つ不足）。署名日の当日は成り立つ
        Clock dayBefore = Clock.fixed(Instant.parse("2026-11-22T14:59:59Z"), ZoneOffset.UTC);
        FeatureConditionCheck future = only(evaluate(snapshot, dayBefore));
        assertThat(future.code()).isEqualTo("signoff.NF03_BUSINESS_OWNER.signedOn.future");
        assertThat(future.temporal()).isTrue();
        Clock signedDay = Clock.fixed(Instant.parse("2026-11-22T15:00:00Z"), ZoneOffset.UTC);
        assertThat(only(evaluate(snapshot, signedDay)).status()).isEqualTo(FeatureConditionCheck.Status.SATISFIED);
    }

    @Test
    void 要る署名が無ければ不成立で時間要因ではない() {
        FeatureEnablementSnapshot snapshot = FeatureEnablementTestSnapshots.builder(Instant.EPOCH).build();
        FeatureConditionCheck missing = only(evaluate(snapshot, Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"),
                ZoneOffset.UTC)));
        assertThat(missing.code()).isEqualTo("signoff.NF03_BUSINESS_OWNER.missing");
        assertThat(missing.temporal()).isFalse();
    }

    @Test
    void 条件付きの種別は公開範囲を設定する時だけ要る() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC);
        FeatureEnablementTestSnapshots.Builder builder = FeatureEnablementTestSnapshots.builder(clock.instant());
        for (String kind : List.of("NF02_PORTAL_PUBLICATION", "NF02_PORTAL_DG02_AC", "NF02_PORTAL_VISIBLE_SINCE_DECISION",
                "NF02_PORTAL_BROWSER_CHECK")) {
            builder.signOff(kind, kind.hashCode() & 0xffff, 0, LocalDate.of(2026, 11, 1), LocalDate.of(2027, 11, 1));
        }
        List<FeatureConditionCheck> unset = evaluator.evaluate(Feature.NF02_CUSTOMER_PORTAL,
                context(builder.build(), clock));
        assertThat(unset).filteredOn(check -> check.code().startsWith("signoff.NF02_PORTAL_INVENTORY"))
                .singleElement().satisfies(check -> {
                    assertThat(check.status()).isEqualTo(FeatureConditionCheck.Status.NOT_APPLICABLE);
                    assertThat(check.passing()).isTrue();
                });
        assertThat(unset).allMatch(FeatureConditionCheck::passing);

        builder.setting(FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE, "2027-04-01T00:00:00+09:00");
        List<FeatureConditionCheck> set = evaluator.evaluate(Feature.NF02_CUSTOMER_PORTAL, context(builder.build(), clock));
        assertThat(set).extracting(FeatureConditionCheck::code).contains("signoff.NF02_PORTAL_INVENTORY.missing");
    }

    @Test
    void 組に属する種別は確認済みの組のmemberであることを要する() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-23T01:00:00Z"), ZoneOffset.UTC);
        LocalDate signed = LocalDate.of(2026, 11, 1);
        LocalDate expires = LocalDate.of(2027, 11, 1);
        FeatureEnablementTestSnapshots.Builder builder = FeatureEnablementTestSnapshots.builder(clock.instant())
                .signOff("NF07_D01", 11, 0, signed, expires).signOff("NF07_D09", 12, 0, signed, expires);

        assertThat(code(builder.build(), clock, "NF07_D01")).isEqualTo("signoff.NF07_D01.bundle.missing");

        builder.bundle(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING, 5, 0,
                Map.of("NF07_D01", 11L, "NF07_D09", 99L));
        assertThat(code(builder.build(), clock, "NF07_D01")).isEqualTo("signoff.NF07_D01.valid");
        assertThat(code(builder.build(), clock, "NF07_D09")).as("組の member が別の署名").isEqualTo(
                "signoff.NF07_D09.bundle.missing");
        // openUntil も同じ規則（要る署名が組の member でなければ閉）
        assertThat(SignOffRequirements.openUntil(catalog, Feature.NF07_RETENTION, builder.build().signOffs(),
                builder.build().bundles(), Map.of())).isEqualTo(LocalDate.MIN);
    }

    private String code(FeatureEnablementSnapshot snapshot, Clock clock, String kind) {
        return evaluator.evaluate(Feature.NF07_RETENTION, context(snapshot, clock)).stream()
                .filter(check -> check.code().startsWith("signoff." + kind + "."))
                .map(FeatureConditionCheck::code).findFirst().orElseThrow();
    }

    private FeatureEnablementSnapshot nf03(LocalDate signedOn, LocalDate expiresOn) {
        return FeatureEnablementTestSnapshots.builder(Instant.EPOCH)
                .signOff("NF03_BUSINESS_OWNER", 1, 0, signedOn, expiresOn).build();
    }

    private List<FeatureConditionCheck> evaluate(FeatureEnablementSnapshot snapshot, Clock clock) {
        return evaluator.evaluate(Feature.NF03, context(snapshot, clock));
    }

    private static FeatureEvaluationContext context(FeatureEnablementSnapshot snapshot, Clock clock) {
        return new FeatureEvaluationContext("default", FeatureEvaluationContext.businessDate(clock), clock, snapshot,
                FeatureEvaluationContext.Purpose.PERIODIC, Set.of(), false);
    }

    private static FeatureConditionCheck only(List<FeatureConditionCheck> checks) {
        assertThat(checks).hasSize(1);
        return checks.get(0);
    }
}
