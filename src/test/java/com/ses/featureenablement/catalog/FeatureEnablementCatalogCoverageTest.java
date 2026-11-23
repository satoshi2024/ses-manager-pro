package com.ses.featureenablement.catalog;

import com.ses.config.feature.Feature;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A03（FE-AC-001-01）: 機能の開放の目録が13件の開放単位を網羅し、NF08 を持たず、署名の種別が一意で類と役割を持ち、親が循環せず、
 * 開放の時だけの条件は NF02 の暦だけ・開放の遷移の前提は NF05 outbound の barrier の OFF の記録だけであること。
 */
class FeatureEnablementCatalogCoverageTest {

    private final FeatureEnablementCatalog catalog = FeatureEnablementCatalog.standard();

    @Test
    void 全てのFeatureに目録の行がありNF08は無い() {
        assertThat(catalog.units()).extracting(UnitDefinition::feature)
                .containsExactlyInAnyOrder(Feature.values());
        assertThat(Feature.values()).hasSize(13);
        assertThat(Arrays.stream(Feature.values()).map(Enum::name)).noneMatch(name -> name.startsWith("NF08"));
        assertThat(catalog.kinds()).extracting(SignOffKind::code).noneMatch(code -> code.startsWith("NF08"));
        for (UnitDefinition unit : catalog.units()) {
            assertThat(unit.signOffs()).as("%s は要る署名を持つ", unit.feature()).isNotEmpty();
            assertThat(unit.sources()).as("%s は出処を持つ", unit.feature()).isNotEmpty();
        }
    }

    @Test
    void 署名の種別は一意で類と役割を持ち要る種別は全て目録にある() {
        List<String> codes = catalog.kinds().stream().map(SignOffKind::code).toList();
        assertThat(codes).doesNotHaveDuplicates();
        for (SignOffKind kind : catalog.kinds()) {
            assertThat(kind.kindClass()).as(kind.code()).isNotNull();
            assertThat(kind.signerRoles()).as(kind.code()).isNotEmpty().allMatch(role -> role.matches("[A-Z0-9_]+"));
            assertThat(kind.code()).as(kind.code()).matches("NF[0-9]{2}_[A-Z0-9_]+");
        }
        for (UnitDefinition unit : catalog.units()) {
            for (SignOffRequirement requirement : unit.signOffs()) {
                assertThat(catalog.kind(requirement.kindCode())).as(requirement.kindCode()).isPresent();
                assertThat(requirement.kindCode()).as("%s の種別は自分の NF の接頭辞を持つ", unit.feature())
                        .startsWith(unit.feature().name().substring(0, 4));
            }
            for (Subgate subgate : unit.subgates()) {
                subgate.signOffKinds().forEach(kind -> assertThat(catalog.kind(kind)).as(kind).isPresent());
            }
        }
        // 類の上限（J-08 の (b)。決定 366 日・証拠 1,095 日）と延長の可否
        assertThat(SignOffKindClass.DECISION.maxValidityDays()).isEqualTo(366);
        assertThat(SignOffKindClass.EVIDENCE.maxValidityDays()).isEqualTo(1095);
        assertThat(SignOffKindClass.DECISION.extendable()).isFalse();
        assertThat(SignOffKindClass.EVIDENCE.extendable()).isTrue();
        // 決定 ID・digest を持つ種別は決定の類
        for (String code : List.of("NF05_DG05_IN", "NF05_DG05_OUT", "NF06_ADMISSION", "NF07_D01", "NF07_D06", "NF07_D09",
                "NF01_DUAL_PRIVACY", "NF02_PORTAL_VISIBLE_SINCE_DECISION")) {
            assertThat(catalog.kind(code)).as(code).get().extracting(SignOffKind::kindClass)
                    .isEqualTo(SignOffKindClass.DECISION);
        }
        for (String code : List.of("NF01_STAGING_VERIFICATION", "NF04_DEVICE_ACCEPTANCE", "NF02_PORTAL_INVENTORY",
                "NF05_SRE_LOAD", "NF07_BRIDGE")) {
            assertThat(catalog.kind(code)).as(code).get().extracting(SignOffKind::kindClass)
                    .isEqualTo(SignOffKindClass.EVIDENCE);
        }
    }

    @Test
    void 親は循環せず子は3件だけでNF05とNF07は親子にしない() {
        Set<Feature> children = EnumSet.noneOf(Feature.class);
        for (Feature feature : Feature.values()) {
            Set<Feature> seen = new HashSet<>();
            Feature current = feature;
            while (current.parent().isPresent()) {
                assertThat(seen.add(current)).as("%s の親が循環する", feature).isTrue();
                current = current.parent().get();
                children.add(feature);
            }
        }
        assertThat(children).containsExactlyInAnyOrder(Feature.NF01_DUAL_CONFIRMATION, Feature.NF02_CUSTOMER_PORTAL,
                Feature.NF03_CERTIFICATION_PII);
        assertThat(catalog.children(Feature.NF02)).containsExactly(Feature.NF02_CUSTOMER_PORTAL);
        assertThat(catalog.children(Feature.NF05_INBOUND)).isEmpty();
        assertThat(catalog.children(Feature.NF07_RETENTION)).isEmpty();
    }

    @Test
    void 開放の時だけの条件はNF02の暦だけで開放の遷移の前提はNF05_outboundのbarrierだけ() {
        Map<Feature, Set<String>> openOnly = catalog.units().stream().filter(unit -> !unit.openOnlyCodes().isEmpty())
                .collect(Collectors.toMap(UnitDefinition::feature, UnitDefinition::openOnlyCodes));
        Map<Feature, Set<String>> openTransition = catalog.units().stream()
                .filter(unit -> !unit.openTransitionCodes().isEmpty())
                .collect(Collectors.toMap(UnitDefinition::feature, UnitDefinition::openTransitionCodes));

        // 暦を照会できない時の code も開放の時だけの条件（照会の失敗で NF02 を自動閉鎖しない。D-9。FE-A11）
        assertThat(openOnly).containsExactly(Map.entry(Feature.NF02, Set.of(FeatureEnablementCatalog.CODE_NF02_CALENDAR,
                FeatureEnablementCatalog.CODE_NF02_CALENDAR_UNVERIFIABLE)));
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(FeatureEnablementCatalog.CODE_NF02_CALENDAR_UNVERIFIABLE))
                .isTrue();
        assertThat(openTransition).containsExactly(
                Map.entry(Feature.NF05_OUTBOUND, Set.of(FeatureEnablementCatalog.CODE_NF05_BARRIER_OFF)));
    }

    @Test
    void 再起動の要否とNF07の印と固有の評価器の要否と条件付きの種別と副入口() {
        assertThat(catalog.units().stream().filter(UnitDefinition::restartRequired).map(UnitDefinition::feature))
                .containsExactlyInAnyOrder(Feature.NF05_INBOUND, Feature.NF05_OUTBOUND);
        assertThat(catalog.units().stream().filter(UnitDefinition::privacyUnit).map(UnitDefinition::feature))
                .containsExactlyInAnyOrder(Feature.NF07_RETENTION, Feature.NF07_DSAR);
        assertThat(catalog.units().stream().filter(UnitDefinition::specificEvaluatorRequired).map(UnitDefinition::feature))
                .containsExactlyInAnyOrder(Feature.NF01_DUAL_CONFIRMATION, Feature.NF02, Feature.NF05_INBOUND,
                        Feature.NF05_OUTBOUND, Feature.NF06, Feature.NF07_RETENTION, Feature.NF07_DSAR);
        List<String> conditional = catalog.units().stream().flatMap(unit -> unit.signOffs().stream())
                .filter(SignOffRequirement::conditional).map(SignOffRequirement::kindCode).toList();
        assertThat(conditional).containsExactly("NF02_PORTAL_INVENTORY");
        assertThat(catalog.unit(Feature.NF07_RETENTION).subgates()).extracting(Subgate::code)
                .containsExactly("HOLD_START", "REL", "REG");
        assertThat(catalog.units().stream().filter(unit -> !unit.subgates().isEmpty()).map(UnitDefinition::feature))
                .containsExactly(Feature.NF07_RETENTION);
        // 副入口の条件の署名（D-05 等）は開放単位の条件ではない（review P3-16）
        assertThat(catalog.unitsRequiring("NF07_D05")).isEmpty();
        // 共用の種別
        assertThat(catalog.unitsRequiring("NF05_SRE_LOAD")).containsExactlyInAnyOrder(Feature.NF05_INBOUND,
                Feature.NF05_OUTBOUND);
        assertThat(catalog.unitsRequiring("NF07_D01")).containsExactlyInAnyOrder(Feature.NF07_RETENTION,
                Feature.NF07_DSAR);
    }

    @Test
    void 組の種類のmemberは目録の種別で組に属する種別は組の種類を指す() {
        for (BundleKind bundle : catalog.bundleKinds()) {
            for (String member : bundle.allMembers()) {
                assertThat(catalog.kind(member)).as(member).get().extracting(SignOffKind::bundleKind)
                        .isEqualTo(bundle.code());
            }
        }
        for (SignOffKind kind : catalog.kinds()) {
            kind.bundle().ifPresent(bundle -> assertThat(catalog.bundleKind(bundle)).as(kind.code()).isPresent());
        }
        BundleKind binding = catalog.bundleKind(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING).orElseThrow();
        assertThat(binding.requiredMembers()).containsExactly("NF07_D01", "NF07_D09");
        assertThat(binding.optionalMembers()).containsExactly("NF07_D06");
        assertThat(catalog.unitsRequiringBundle(FeatureEnablementCatalog.BUNDLE_NF07_DECISION_BINDING))
                .containsExactlyInAnyOrder(Feature.NF07_RETENTION, Feature.NF07_DSAR);
    }

    /**
     * FE-A11: 段階 A は容易な7件に DB 管理の印を付ける。段階 B は NF01 二者確認（FE-B01）に印を足す。NF05・NF06・NF07 は各段階まで
     * property の源。
     */
    @Test
    void 段階Aの7件と段階Bで足した開放単位だけにDB管理の印があり写しで印を付け直せる() {
        EnumSet<Feature> stageA = EnumSet.of(Feature.NF01, Feature.NF02, Feature.NF02_CUSTOMER_PORTAL, Feature.NF03,
                Feature.NF03_CERTIFICATION_PII, Feature.NF04, Feature.NF10);
        EnumSet<Feature> stageB = EnumSet.of(Feature.NF01_DUAL_CONFIRMATION);
        for (Feature feature : Feature.values()) {
            assertThat(catalog.databaseManaged(feature)).as(feature.name())
                    .isEqualTo(stageA.contains(feature) || stageB.contains(feature));
        }
        assertThat(FeatureEnablementCatalog.STAGE_A_DATABASE_MANAGED).containsExactlyInAnyOrderElementsOf(stageA);
        FeatureEnablementCatalog managed = catalog.withDatabaseManaged(EnumSet.of(Feature.NF04, Feature.NF07_DSAR));
        assertThat(managed.databaseManaged(Feature.NF04)).isTrue();
        assertThat(managed.databaseManaged(Feature.NF07_DSAR)).isTrue();
        assertThat(managed.databaseManaged(Feature.NF01)).isFalse();
        assertThat(catalog.databaseManaged(Feature.NF01)).as("元の目録は変えない").isTrue();
    }

    @Test
    void 評価できない類のcodeを分類する() {
        for (String code : List.of("privacyAdmins.unverifiable", "secret.X.unverifiable",
                "privacyAdmins.unverifiable[groups]", "journal.unreachable", "journal.timeout", "evaluator.missing",
                "evaluator.unverifiable[SampleEvaluator]", "state=STATE_IO_ERROR")) {
            assertThat(FeatureEnablementCatalog.isUnverifiableCode(code)).as(code).isTrue();
        }
        for (String code : List.of("privacyAdmins.insufficient[current=1;required=2]", "decisionBinding.d01.expiresOn.expired",
                "dsar.dueDaysUnset", "state=MISSING", "signoff.NF04_SRE.missing", "master.closed", "unverifiable", "",
                "journal.appenderMissing")) {
            assertThat(FeatureEnablementCatalog.isUnverifiableCode(code)).as(code).isFalse();
        }
        assertThat(FeatureEnablementCatalog.isUnverifiableCode(null)).isFalse();
    }
}
