package com.ses.featureenablement.state;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.SignOffRequirements;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A04（design §3.2）: snapshot の既定（行の無い開放単位は CLOSED・openUntil は閉）、最後に読めた時刻の更新、設定の未設定、
 * 空の snapshot（全て閉）、{@code openUntil} の計算（要る署名の最も早い失効日・欠落で閉・条件付きの種別）。
 */
class FeatureEnablementSnapshotTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();

    @Test
    void 行の無い開放単位はCLOSEDでopenUntilは閉() {
        FeatureEnablementSnapshot snapshot = FeatureEnablementTestSnapshots.builder(NOW).build();

        FeatureEnablementSnapshot.UnitView unit = snapshot.unit(Feature.NF04);
        assertThat(unit.state()).isEqualTo(EnablementState.CLOSED);
        assertThat(unit.openUntil()).isEqualTo(LocalDate.MIN);
        assertThat(unit.restartPending()).isFalse();
        assertThat(snapshot.setting(FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE)).isEmpty();
    }

    @Test
    void 最後に読めた時刻だけを更新した写しと空のsnapshot() {
        FeatureEnablementSnapshot snapshot = FeatureEnablementTestSnapshots.builder(NOW).generation(5).open(Feature.NF04)
                .build();
        FeatureEnablementSnapshot touched = snapshot.verifiedAt(NOW.plusSeconds(30));

        assertThat(touched.generation()).isEqualTo(5);
        assertThat(touched.loadedAt()).isEqualTo(NOW);
        assertThat(touched.verifiedAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(touched.unit(Feature.NF04).state()).isEqualTo(EnablementState.OPEN);

        FeatureEnablementSnapshot empty = FeatureEnablementSnapshot.empty(NOW, true);
        assertThat(empty.units()).isEmpty();
        assertThat(empty.verifiedAt()).isEqualTo(Instant.EPOCH);
        assertThat(empty.generation()).isNegative();
    }

    @Test
    void openUntilは要る署名の最も早い失効日で欠落なら閉で条件付きの種別は設定がある時だけ数える() {
        LocalDate signed = LocalDate.of(2026, 11, 1);
        FeatureEnablementSnapshot snapshot = FeatureEnablementTestSnapshots.builder(NOW)
                .signOff("NF02_PORTAL_PUBLICATION", 1, 0, signed, LocalDate.of(2027, 3, 1))
                .signOff("NF02_PORTAL_DG02_AC", 2, 0, signed, LocalDate.of(2027, 1, 15))
                .signOff("NF02_PORTAL_VISIBLE_SINCE_DECISION", 3, 0, signed, LocalDate.of(2027, 6, 1))
                .signOff("NF02_PORTAL_BROWSER_CHECK", 4, 0, signed, LocalDate.of(2028, 1, 1)).build();

        assertThat(SignOffRequirements.openUntil(catalog, Feature.NF02_CUSTOMER_PORTAL, snapshot.signOffs(),
                snapshot.bundles(), snapshot.settings())).isEqualTo(LocalDate.of(2027, 1, 15));
        assertThat(SignOffRequirements.openUntil(catalog, Feature.NF02_CUSTOMER_PORTAL, snapshot.signOffs(),
                snapshot.bundles(), Map.of(FeatureEnablementCatalog.SETTING_NF02_VISIBLE_SINCE, "2027-04-01T00:00:00+09:00")))
                .as("公開範囲を設定すると棚卸しも要る（無ければ閉）").isEqualTo(LocalDate.MIN);
        assertThat(SignOffRequirements.applicableKinds(catalog, Feature.NF02_CUSTOMER_PORTAL, Map.of()))
                .doesNotContain("NF02_PORTAL_INVENTORY");
    }
}
