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
                .contains("ENGINEER_PHOTO", "CANDIDATE_PROFILE", "DOCUMENT:", "AI_REDACTED_SUMMARY")
                .doesNotContain("ENGINEER_PROFILE");
        assertThat(report.uncatalogedProviderElements()).allSatisfy(m ->
                assertThat(m.reason()).isEqualTo(PrivacyCoverageReport.CATALOG_MISSING));
        assertThat(PrivacyCoverageReport.build("tenant-empty", null, registry).summary().uncatalogedProviderElementCount())
                .isEqualTo(10);
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
        rows.add(row("DOCUMENT:CONTRACT", "document-file", "ATTRIBUTABLE"));
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
