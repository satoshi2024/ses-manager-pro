package com.ses.privacy.production.dsar;

import com.ses.common.exception.BusinessException;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * NF07-A2b: DSAR の read-only 検索（production-bridge-phased-plan.md §3.5.2 の対象限定テスト「他 tenant 0 件、未登録 provider を報告」）。
 *
 * <p>catalog（{@code m_pii_data_element}）は H2 上に専用 tenant の行を作って実 mapper で読む（class の transaction で rollback し、
 * 他の tenant の行は読まない）。provider は照会の tenant を記録し、誤って他 tenant の行も返す fixture を使う。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacySearchServiceTest {

    private static final String TENANT = "nf07-a2b-search";
    private static final String OTHER_TENANT = "nf07-a2b-search-other";
    private static final String PROVIDER_ID = "dsar-search-fixture";
    private static final String PROFILE = "SEARCH_FIXTURE_PROFILE";
    private static final String SKILL = "SEARCH_FIXTURE_SKILL";

    @Autowired
    private PiiDataElementMapper dataElementMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TenantRecordingProvider provider;
    private PrivacySearchService service;

    @BeforeEach
    void setUp() {
        provider = new TenantRecordingProvider();
        service = new PrivacySearchService(new ProductionPrivacyProviderRegistry(List.of(provider)), dataElementMapper);
    }

    @Test
    void 検索は照会者のtenantだけでproviderを呼び他tenantの行は0件にする() {
        PrivacySearchService.SearchResult result = service.search(TENANT, PrivacySubjectRef.of(PrivacySubjectType.ENGINEER,
                101L));

        assertThat(provider.tenants).containsExactly(TENANT);
        assertThat(result.tenantId()).isEqualTo(TENANT);
        assertThat(result.subjectRef()).isEqualTo("ENGINEER:101");
        assertThat(result.hits()).hasSize(1);
        List<PrivacyRecord> records = result.hits().get(0).records();
        assertThat(records).as("provider が返した他 tenant の行は捨てる").hasSize(2);
        assertThat(records).allSatisfy(record -> assertThat(record.fields().get("tenant_id")).isEqualTo(TENANT));
        assertThat(result.recordCount()).isEqualTo(2);

        PrivacySearchService.SearchResult other = service.search(OTHER_TENANT,
                PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 101L));
        assertThat(other.recordCount()).as("他 tenant の照会では TENANT の行を返さない").isZero();
    }

    @Test
    void catalogのうちproviderが扱わないdata_elementは理由付きで要手動確認として報告し他tenantの行は報告しない() {
        catalog(TENANT, PROFILE, PROVIDER_ID, "ATTRIBUTABLE");
        catalog(TENANT, SKILL, PROVIDER_ID, "ATTRIBUTABLE");
        catalog(TENANT, "SEARCH_UNREGISTERED_MAILBOX", null, "ATTRIBUTABLE");
        catalog(TENANT, "SEARCH_UNKNOWN_PROVIDER", "external-mailbox-archive", "ATTRIBUTABLE");
        catalog(TENANT, "SEARCH_UNDECLARED", PROVIDER_ID, "ATTRIBUTABLE");
        catalog(TENANT, "SEARCH_MISMATCH", PROVIDER_ID, "UNKNOWN");
        catalog(OTHER_TENANT, "SEARCH_OTHER_TENANT_ONLY", null, "ATTRIBUTABLE");

        PrivacySearchService.SearchResult result = service.search(TENANT, PrivacySubjectRef.of(PrivacySubjectType.ENGINEER,
                101L));

        Map<String, String> reasons = new LinkedHashMap<>();
        result.unregistered().forEach(element -> reasons.put(element.dataElementId(), element.reason()));
        assertThat(reasons).containsEntry("SEARCH_UNREGISTERED_MAILBOX", "PROVIDER_UNREGISTERED")
                .containsEntry("SEARCH_UNKNOWN_PROVIDER", "PROVIDER_UNREGISTERED")
                .containsEntry("SEARCH_UNDECLARED", "PROVIDER_ELEMENT_UNDECLARED")
                .doesNotContainKeys(PROFILE, SKILL, "SEARCH_OTHER_TENANT_ONLY");
        assertThat(reasons.keySet()).as("UNKNOWN の分類は provider の宣言と一致しない")
                .contains("SEARCH_MISMATCH");
        assertThat(reasons.get("SEARCH_MISMATCH")).isEqualTo("PROVIDER_ELEMENT_UNDECLARED");
    }

    @Test
    void providerかcatalogを照会できなければ503で記録の欠けた結果を返さない() {
        provider.failing = true;
        assertThatThrownBy(() -> service.search(TENANT, PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 101L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarSourceUnavailable");
                });

        PiiDataElementMapper broken = mock(PiiDataElementMapper.class);
        when(broken.selectList(any())).thenThrow(new IllegalStateException("catalog unavailable"));
        PrivacySearchService brokenCatalog = new PrivacySearchService(
                new ProductionPrivacyProviderRegistry(List.of(new TenantRecordingProvider())), broken);
        assertThatThrownBy(() -> brokenCatalog.search(TENANT, PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 101L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
    }

    @Test
    void tenantかsubjectが無い検索は400にする() {
        assertThatThrownBy(() -> service.search(" ", PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 101L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThatThrownBy(() -> service.search(TENANT, null))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(400));
        assertThat(provider.tenants).isEmpty();
    }

    @Test
    void 検索はproviderを読むだけでDSARの表に書かない() {
        Integer before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_privacy_action WHERE tenant_id = ?",
                Integer.class, TENANT);
        service.search(TENANT, PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 101L));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM t_privacy_action WHERE tenant_id = ?",
                Integer.class, TENANT)).isEqualTo(before);
    }

    private void catalog(String tenantId, String elementId, String providerId, String attribution) {
        jdbcTemplate.update("INSERT INTO m_pii_data_element (tenant_id, data_element_id, storage_kind, storage_ref, "
                        + "provider_id, subject_attribution) VALUES (?, ?, 'DB_COLUMN', 't_fixture.column', ?, ?)",
                tenantId, elementId, providerId, attribution);
    }

    /** 照会の tenant を記録し、TENANT の照会では誤って OTHER_TENANT の行も1件返す fixture。 */
    static final class TenantRecordingProvider implements ProductionPrivacyProvider {

        final List<String> tenants = new ArrayList<>();
        boolean failing;

        @Override
        public String providerId() {
            return PROVIDER_ID;
        }

        @Override
        public List<ProviderDataElement> dataElements() {
            List<SubjectLink> links = List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_engineer.id"));
            return List.of(ProviderDataElement.exact(PROFILE, SubjectAttribution.ATTRIBUTABLE, links),
                    ProviderDataElement.exact(SKILL, SubjectAttribution.ATTRIBUTABLE, DsarDeliveryClass.SELF, links));
        }

        @Override
        public List<ProviderTable> tables() {
            return List.of(new ProviderTable("t_engineer", List.of("id", "tenant_id", "name"), List.of()));
        }

        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            tenants.add(tenantId);
            if (failing) {
                throw new IllegalStateException("provider unavailable");
            }
            if (!TENANT.equals(tenantId)) {
                return List.of();
            }
            return List.of(record(TENANT, subject.id()), record(TENANT, subject.id() * 10),
                    record(OTHER_TENANT, subject.id() * 100));
        }

        private PrivacyRecord record(String tenantId, long rowId) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("id", rowId);
            fields.put("tenant_id", tenantId);
            fields.put("name", "検索 太郎");
            return new PrivacyRecord(PROVIDER_ID, "t_engineer", rowId, List.of(PROFILE, SKILL), false, fields, Set.of());
        }
    }
}
