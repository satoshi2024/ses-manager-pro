package com.ses.featureenablement.condition;

import com.ses.config.feature.Feature;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A03: 条件の指紋の正規形（design §4.5）を golden vector で固定する。値は {@code printf '…' | sha256sum} で独立に計算した。
 * 正規形を変えると、申請の時と承認の時の指紋が版をまたいで食い違い、全ての未決が陳腐化する。
 */
class FeatureConditionFingerprintTest {

    private static final String NF04_CANONICAL = "fe-condition-v1\nunit=NF04\ntype=OPEN\nvalue=\n"
            + "signoff.NF04_BUSINESS_OWNER=11:0\nsignoff.NF04_DEVICE_ACCEPTANCE=15:2\nsignoff.NF04_PRIVACY=13:0\n"
            + "signoff.NF04_SECURITY=14:1\nsignoff.NF04_SRE=12:0\n";
    private static final String NF04_DIGEST = "6243673a2393e6374e2962cd60f885d87dc0e73844f01289d3de2f6435f3692a";

    private static final String PORTAL_CANONICAL = "fe-condition-v1\nunit=NF02_CUSTOMER_PORTAL\ntype=OPEN\nvalue=\n"
            + "signoff.NF02_PORTAL_BROWSER_CHECK=24:0\nsignoff.NF02_PORTAL_DG02_AC=22:0\nsignoff.NF02_PORTAL_PUBLICATION=21:0\n"
            + "signoff.NF02_PORTAL_VISIBLE_SINCE_DECISION=23:1\nbundle.TEST_BUNDLE=5:3\nparent=NF02:OPEN\n";
    private static final String PORTAL_DIGEST = "3b134d9bde1f9dfa33b08b15faceb1157ddfb6d39939a00715fe8bd725cae80d";

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();

    @Test
    void 正規形とSHA256はgolden_vectorと一致する() {
        Map<String, String> signOffs = new TreeMap<>(Map.of("NF04_SRE", "12:0", "NF04_BUSINESS_OWNER", "11:0",
                "NF04_PRIVACY", "13:0", "NF04_SECURITY", "14:1", "NF04_DEVICE_ACCEPTANCE", "15:2"));
        String canonical = FeatureConditionFingerprint.canonical("NF04", "OPEN", null, signOffs, Map.of(), null);
        assertThat(canonical).isEqualTo(NF04_CANONICAL);
        assertThat(FeatureConditionFingerprint.sha256(canonical)).isEqualTo(NF04_DIGEST);

        String portal = FeatureConditionFingerprint.canonical("NF02_CUSTOMER_PORTAL", "OPEN", "",
                Map.of("NF02_PORTAL_PUBLICATION", "21:0", "NF02_PORTAL_DG02_AC", "22:0",
                        "NF02_PORTAL_VISIBLE_SINCE_DECISION", "23:1", "NF02_PORTAL_BROWSER_CHECK", "24:0"),
                Map.of("TEST_BUNDLE", "5:3"), "NF02:OPEN");
        assertThat(portal).isEqualTo(PORTAL_CANONICAL);
        assertThat(FeatureConditionFingerprint.sha256(portal)).isEqualTo(PORTAL_DIGEST);
    }

    @Test
    void 状態から作る指紋は要る署名のidと版と親の状態だけを入れ自動検査の結果を入れない() {
        LocalDate signed = LocalDate.of(2026, 11, 1);
        LocalDate expires = LocalDate.of(2027, 11, 1);
        FeatureEnablementSnapshot snapshot = FeatureEnablementTestSnapshots.builder(Instant.EPOCH)
                .signOff("NF04_BUSINESS_OWNER", 11, 0, signed, expires).signOff("NF04_SRE", 12, 0, signed, expires)
                .signOff("NF04_PRIVACY", 13, 0, signed, expires).signOff("NF04_SECURITY", 14, 1, signed, expires)
                .signOff("NF04_DEVICE_ACCEPTANCE", 15, 2, signed, expires)
                // 要らない種別の署名は指紋に入らない
                .signOff("NF03_BUSINESS_OWNER", 99, 7, signed, expires).build();

        assertThat(FeatureConditionFingerprint.canonical(catalog, Feature.NF04, "OPEN", null, snapshot))
                .isEqualTo(NF04_CANONICAL);
        assertThat(FeatureConditionFingerprint.of(catalog, Feature.NF04, "OPEN", null, snapshot)).isEqualTo(NF04_DIGEST);

        // 署名の差替え（id・版の変化）で指紋が変わる
        FeatureEnablementSnapshot replaced = FeatureEnablementTestSnapshots.builder(Instant.EPOCH)
                .signOff("NF04_BUSINESS_OWNER", 11, 0, signed, expires).signOff("NF04_SRE", 16, 0, signed, expires)
                .signOff("NF04_PRIVACY", 13, 0, signed, expires).signOff("NF04_SECURITY", 14, 1, signed, expires)
                .signOff("NF04_DEVICE_ACCEPTANCE", 15, 2, signed, expires).build();
        assertThat(FeatureConditionFingerprint.of(catalog, Feature.NF04, "OPEN", null, replaced)).isNotEqualTo(NF04_DIGEST);

        // 欠けた署名は「-」、親は状態つき
        FeatureEnablementSnapshot child = FeatureEnablementTestSnapshots.builder(Instant.EPOCH).open(Feature.NF03).build();
        assertThat(FeatureConditionFingerprint.canonical(catalog, Feature.NF03_CERTIFICATION_PII, "OPEN", null, child))
                .isEqualTo("fe-condition-v1\nunit=NF03_CERTIFICATION_PII\ntype=OPEN\nvalue=\n"
                        + "signoff.NF03_DG07=-\nsignoff.NF03_EVIDENCE_PRIVACY=-\nparent=NF03:OPEN\n");
    }
}
