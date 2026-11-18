package com.ses.privacy.production;

import com.ses.entity.privacy.PiiDataElement;
import com.ses.privacy.production.provider.AiLogProvider;
import com.ses.privacy.production.provider.CandidateProvider;
import com.ses.privacy.production.provider.DocumentFileProvider;
import com.ses.privacy.production.provider.EngineerProfileProvider;
import com.ses.privacy.production.provider.IngestionTextProvider;
import com.ses.privacy.production.provider.PrivacyReadOnlyQuery;
import com.ses.privacy.production.provider.SysUserProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** catalog と provider の対応表: 未登録（provider_id NULL を含む）は理由付きで必ず報告し、provider の照会は呼ばない。 */
class PrivacyCoverageReportTest {

    private final PrivacyReadOnlyQuery query = mock(PrivacyReadOnlyQuery.class);
    private final ProductionPrivacyProviderRegistry registry = new ProductionPrivacyProviderRegistry(List.of(
            new EngineerProfileProvider(query), new CandidateProvider(query), new SysUserProvider(query),
            new DocumentFileProvider(query), new IngestionTextProvider(query), new AiLogProvider(query)));

    @Test
    void 登録済みと未登録を分けprovider_idがNULLの行はPROVIDER_UNREGISTEREDとして報告する() {
        List<PiiDataElement> catalog = new ArrayList<>(fullyCataloged());
        catalog.add(row("AUDIT_LOG", null, "UNKNOWN"));
        catalog.add(row("DATABASE_BACKUP", null, "UNKNOWN"));
        catalog.add(row("INTEGRATION_HUB_DELIVERY", null, "NOT_ATTRIBUTABLE"));

        PrivacyCoverageReport report = PrivacyCoverageReport.build("default", catalog, registry);

        assertThat(report.tenantId()).isEqualTo("default");
        assertThat(report.unregisteredElements()).extracting(PrivacyCoverageReport.ElementEntry::dataElementId)
                .containsExactly("AUDIT_LOG", "DATABASE_BACKUP", "INTEGRATION_HUB_DELIVERY");
        assertThat(report.unregisteredElements()).allSatisfy(entry ->
                assertThat(entry.reason()).isEqualTo(PrivacyCoverageReport.PROVIDER_UNREGISTERED));
        assertThat(report.unregisteredElements()).filteredOn(e -> e.dataElementId().equals("AUDIT_LOG")).singleElement()
                .satisfies(e -> {
                    assertThat(e.subjectAttribution()).isEqualTo("UNKNOWN");
                    assertThat(e.attributionClassified()).isFalse();
                });
        assertThat(report.registeredElements()).allSatisfy(entry -> assertThat(entry.reason()).isNull());
        assertThat(report.registeredElements()).extracting(PrivacyCoverageReport.ElementEntry::dataElementId)
                .contains("ENGINEER_PROFILE", "DOCUMENT:CONTRACT", "AI_REDACTED_SUMMARY");
        assertThat(report.uncatalogedProviderElements()).isEmpty();
        assertThat(report.summary().unregisteredCount()).isEqualTo(3);
        assertThat(report.summary().unknownAttributionCount()).isEqualTo(2);
        assertThat(report.providers()).extracting(PrivacyCoverageReport.ProviderEntry::providerId)
                .containsExactly("ai-log", "candidate", "document-file", "engineer-profile", "ingestion-text", "sys-user");
        assertThat(report.providers()).filteredOn(p -> p.providerId().equals("sys-user")).singleElement()
                .satisfies(p -> assertThat(p.columnAllowList().get("sys_user")).doesNotContain("password"));
        verifyNoInteractions(query);
    }

    @Test
    void registryに無いproviderと宣言外の要素と分類の不一致は登録済みにしない() {
        List<PiiDataElement> catalog = new ArrayList<>(fullyCataloged());
        catalog.add(row("GEMINI_COPY", "gemini-adapter", "ATTRIBUTABLE"));
        catalog.add(row("ENGINEER_SALARY", "engineer-profile", "ATTRIBUTABLE"));
        catalog.removeIf(r -> r.getDataElementId().equals("SYS_USER_ACCOUNT"));
        catalog.add(row("SYS_USER_ACCOUNT", "sys-user", "UNKNOWN"));

        PrivacyCoverageReport report = PrivacyCoverageReport.build("default", catalog, registry);

        assertThat(reason(report, "GEMINI_COPY")).isEqualTo(PrivacyCoverageReport.PROVIDER_UNREGISTERED);
        assertThat(reason(report, "ENGINEER_SALARY")).isEqualTo(PrivacyCoverageReport.PROVIDER_ELEMENT_UNDECLARED);
        assertThat(reason(report, "SYS_USER_ACCOUNT")).isEqualTo(PrivacyCoverageReport.ATTRIBUTION_MISMATCH);
    }

    @Test
    void providerが宣言しているのにcatalogに無い要素はCATALOG_MISSINGとして報告する() {
        PrivacyCoverageReport report = PrivacyCoverageReport.build("tenant-new",
                List.of(row("ENGINEER_PROFILE", "engineer-profile", "ATTRIBUTABLE")), registry);

        assertThat(report.uncatalogedProviderElements()).extracting(PrivacyCoverageReport.MissingElement::dataElementId)
                .contains("ENGINEER_PHOTO", "CANDIDATE_PROFILE", "DOCUMENT:CONTRACT", "DOCUMENT:IMPORT",
                        "AI_REDACTED_SUMMARY")
                // 接頭辞の宣言は未知の種別の受け皿なので欠落として数えない
                .doesNotContain("ENGINEER_PROFILE", "DOCUMENT:");
        assertThat(report.uncatalogedProviderElements()).allSatisfy(m ->
                assertThat(m.reason()).isEqualTo(PrivacyCoverageReport.CATALOG_MISSING));
        assertThat(PrivacyCoverageReport.build("tenant-empty", null, registry).summary().uncatalogedProviderElementCount())
                .isEqualTo(9 + DocumentFileProvider.DOCUMENT_TYPE_RULES.size());
    }

    @Test
    void 文書種別の宣言の除外と交付区分と本人帰属をproviderの一覧に示す() {
        PrivacyCoverageReport report = PrivacyCoverageReport.build("default", fullyCataloged(), registry);

        PrivacyCoverageReport.ProviderEntry document = report.providers().stream()
                .filter(p -> p.providerId().equals("document-file")).findFirst().orElseThrow();
        assertThat(document.dataElements()).filteredOn(PrivacyCoverageReport.ProviderElementEntry::prefixMatch)
                .singleElement().satisfies(e -> {
                    assertThat(e.excludedIds()).contains("DOCUMENT:IMPORT", "DOCUMENT:CONTRACT");
                    assertThat(e.deliveryClass()).isEqualTo("MIXED_THIRD_PARTY");
                });
        assertThat(document.dataElements()).filteredOn(e -> e.dataElementId().equals("DOCUMENT:IMPORT")).singleElement()
                .satisfies(e -> {
                    assertThat(e.subjectAttribution()).isEqualTo("NOT_ATTRIBUTABLE");
                    assertThat(e.subjectRules()).isEmpty();
                });
        assertThat(report.registeredElements()).filteredOn(e -> e.dataElementId().equals("DOCUMENT:IMPORT"))
                .singleElement().satisfies(e -> assertThat(e.subjectAttribution()).isEqualTo("NOT_ATTRIBUTABLE"));
        // catalog が残余の種別を ATTRIBUTABLE としていれば登録済みにしない（規則5の入力を1つに保つ）
        List<PiiDataElement> stale = new ArrayList<>(fullyCataloged());
        stale.removeIf(r -> r.getDataElementId().equals("DOCUMENT:IMPORT"));
        stale.add(row("DOCUMENT:IMPORT", "document-file", "ATTRIBUTABLE"));
        assertThat(reason(PrivacyCoverageReport.build("default", stale, registry), "DOCUMENT:IMPORT"))
                .isEqualTo(PrivacyCoverageReport.ATTRIBUTION_MISMATCH);
    }

    private static String reason(PrivacyCoverageReport report, String id) {
        return report.unregisteredElements().stream().filter(e -> e.dataElementId().equals(id))
                .map(PrivacyCoverageReport.ElementEntry::reason).findFirst().orElse(null);
    }

    /** 初期6 provider の宣言を全て満たす catalog。 */
    private static List<PiiDataElement> fullyCataloged() {
        List<PiiDataElement> rows = new ArrayList<>();
        for (String id : Arrays.asList("ENGINEER_PROFILE", "ENGINEER_PHOTO")) {
            rows.add(row(id, "engineer-profile", "ATTRIBUTABLE"));
        }
        rows.add(row("CANDIDATE_PROFILE", "candidate", "ATTRIBUTABLE"));
        rows.add(row("SYS_USER_ACCOUNT", "sys-user", "ATTRIBUTABLE"));
        DocumentFileProvider.DOCUMENT_TYPE_RULES.forEach((code, rule) ->
                rows.add(row("DOCUMENT:" + code, "document-file", rule.attribution().name())));
        for (String id : Arrays.asList("RESUME_INGESTION_ORIGINAL", "RESUME_INGESTION_TEXT",
                "BP_AVAILABILITY_INGESTION_ORIGINAL", "BP_AVAILABILITY_INGESTION_TEXT")) {
            rows.add(row(id, "ingestion-text", "ATTRIBUTABLE"));
        }
        rows.add(row("AI_REDACTED_SUMMARY", "ai-log", "ATTRIBUTABLE"));
        return rows;
    }

    private static PiiDataElement row(String id, String providerId, String attribution) {
        PiiDataElement row = new PiiDataElement();
        row.setTenantId("default");
        row.setDataElementId(id);
        row.setStorageKind("DB_COLUMN");
        row.setStorageRef("t.x");
        row.setProviderId(providerId);
        row.setSubjectAttribution(attribution);
        row.setOwnerState("UNKNOWN");
        row.setInventoryStatus("UNKNOWN");
        return row;
    }
}
