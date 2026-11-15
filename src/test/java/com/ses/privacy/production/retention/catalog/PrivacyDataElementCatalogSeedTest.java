package com.ses.privacy.production.retention.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.privacy.production.PrivacyCoverageReport;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V196（F2 data element catalog seed）を H2 で確認する（application-test.yml が起動時に V196 をそのまま実行する）。
 *
 * <ul>
 *   <li>C-2-2: §3.4.1 で hold の gate に接続する data element は seed で UNKNOWN にならない</li>
 *   <li>構造行だけ（owner・データ分類・inventory 状態は UNKNOWN、owner 参照なし）</li>
 *   <li>provider の宣言と seed の分類・provider ID が一致し、外部 provider を登録しない</li>
 *   <li>再実行しても重複せず、新しい tenant には1回だけ入る（{@code NOT EXISTS}）</li>
 * </ul>
 * 実 MySQL での再実行（collation の異なる表との比較を含む）は {@code FlywayNf07PrivacyF1MigrationTest} が見る。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyDataElementCatalogSeedTest {

    private static final String SEED = "db/migration/V196__nf07_privacy_data_element_catalog_seed.sql";
    private static final String TENANT = "default";

    /** §3.4.1 で hold の gate に接続する data element（D4〜D7・D9。D1〜D3 は下の文書種別ごと）。 */
    static final List<String> GATE_CONNECTED = List.of("RESUME_INGESTION_ORIGINAL", "RESUME_INGESTION_TEXT",
            "BP_AVAILABILITY_INGESTION_TEXT", "AI_REDACTED_SUMMARY", "INTEGRATION_HUB_IDEMPOTENCY",
            "INTEGRATION_HUB_DELIVERY", "INTEGRATION_HUB_INBOUND", "INTEGRATION_HUB_INBOUND_REPLAY",
            "INTEGRATION_HUB_AUDIT", "INTEGRATION_HUB_EXTERNAL_AUDIT", "EXTERNAL_API_READ_SNAPSHOT");

    /** migration が m_document_type に入れる文書種別と、アプリが文書台帳に登録する RESUME_INGESTION（D1〜D3）。 */
    static final List<String> DOCUMENT_TYPES = List.of("CONTRACT", "INVOICE_OUT", "INVOICE_IN", "QUOTATION",
            "WORK_REPORT", "SIGNED_PDF", "ESIGN_CERT", "DISPATCH_LEDGER", "EMPLOYMENT_CONDITIONS_STATEMENT",
            "DISPATCH_NOTICE", "INDIVIDUAL_CONTRACT", "ORDER_RECEIVED", "ORDER_ACKNOWLEDGEMENT", "ACCEPTANCE",
            "SKILL_SHEET", "RECEIPT", "PRIVATE_NOTE", "CHANGE_REQUEST_ATTACHMENT", "MANAGEMENT_REPORT",
            "CERTIFICATION_EVIDENCE", "SERVICE_REQUEST_ATTACHMENT", "BP_SUBMISSION", "RESUME_INGESTION");

    /** 未登録として必ず報告される data element（Demo の例: 監査ログ・backup・外部 provider 側の写し）。 */
    private static final List<String> UNREGISTERED_EXAMPLES = List.of("AUDIT_LOG", "DATABASE_BACKUP",
            "AI_EXTERNAL_PROVIDER_COPY", "CLOUDSIGN_REMOTE_DOCUMENT", "FREEE_REMOTE_RECORD", "MAILBOX_SOURCE_MESSAGE",
            "AI_LEGACY_LOG", "PROJECT_INGESTION_TEXT", "FILE_SECURITY_METADATA");

    private static final Set<String> CLASSIFIED = Set.of("ATTRIBUTABLE", "NOT_ATTRIBUTABLE", "NO_PERSONAL_DATA");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PiiDataElementMapper mapper;
    @Autowired
    private ProductionPrivacyProviderRegistry registry;

    @Test
    void gateに接続するdata_elementはseedでUNKNOWNにならない() {
        List<String> required = new ArrayList<>(GATE_CONNECTED);
        DOCUMENT_TYPES.forEach(code -> required.add("DOCUMENT:" + code));
        for (String id : required) {
            List<String> attribution = jdbc.queryForList("SELECT subject_attribution FROM m_pii_data_element "
                    + "WHERE tenant_id = ? AND data_element_id = ?", String.class, TENANT, id);
            assertThat(attribution).as(id).singleElement().satisfies(value -> assertThat(CLASSIFIED).contains(value));
        }
        assertThat(attribution("BP_AVAILABILITY_INGESTION_TEXT")).isEqualTo("ATTRIBUTABLE");
        assertThat(attribution("INTEGRATION_HUB_DELIVERY")).isEqualTo("NOT_ATTRIBUTABLE");
        assertThat(attribution("EXTERNAL_API_READ_SNAPSHOT")).isEqualTo("NOT_ATTRIBUTABLE");
        assertThat(attribution("DOCUMENT:CONTRACT")).isEqualTo("ATTRIBUTABLE");
    }

    @Test
    void seedは構造行だけでownerとデータ分類とinventory状態はUNKNOWNのまま() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id = ? AND "
                + "(owner_state <> 'UNKNOWN' OR data_class <> 'UNKNOWN' OR inventory_status <> 'UNKNOWN' "
                + "OR owner_ref IS NOT NULL)", Long.class, TENANT)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id = ? "
                + "AND data_element_id NOT LIKE 'DOCUMENT:%'", Long.class, TENANT)).isEqualTo(26L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id = ? "
                + "AND inventory_ref IS NULL", Long.class, TENANT)).isZero();
    }

    @Test
    void providerの宣言とseedの分類が一致し未登録の要素は理由付きで報告され外部providerは登録しない() {
        PrivacyCoverageReport report = PrivacyCoverageReport.build(TENANT, catalog(TENANT), registry);

        assertThat(report.uncatalogedProviderElements()).as("provider の宣言は全て catalog にある").isEmpty();
        assertThat(report.unregisteredElements()).allSatisfy(entry -> {
            assertThat(entry.providerId()).as(entry.dataElementId()).isNull();
            assertThat(entry.reason()).isEqualTo(PrivacyCoverageReport.PROVIDER_UNREGISTERED);
        });
        assertThat(report.unregisteredElements()).extracting(PrivacyCoverageReport.ElementEntry::dataElementId)
                .containsAll(UNREGISTERED_EXAMPLES).containsAll(GATE_CONNECTED.stream()
                        .filter(id -> id.startsWith("INTEGRATION_HUB_") || id.startsWith("EXTERNAL_API_")).toList());
        for (ProductionPrivacyProvider provider : registry.providers()) {
            for (ProviderDataElement element : provider.dataElements()) {
                assertThat(report.registeredElements()).as(element.dataElementId())
                        .anyMatch(entry -> element.matches(entry.dataElementId())
                                && provider.providerId().equals(entry.providerId()));
            }
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id = ? "
                + "AND provider_id IS NOT NULL AND (provider_id LIKE '%gemini%' OR provider_id LIKE '%cloudsign%' "
                + "OR provider_id LIKE '%freee%' OR provider_id LIKE '%mail%' OR provider_id LIKE '%backup%' "
                + "OR provider_id LIKE '%replica%')", Long.class, TENANT)).isZero();
    }

    @Test
    void 再実行しても重複せず新しいtenantには1回だけ入る() throws Exception {
        long before = count(TENANT);
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            runSeed(connection);
            assertThat(count(TENANT)).isEqualTo(before);
            // 再実行の後は、現在の全文書種別（論理削除済みを含む）に分類済みの DOCUMENT:<code> がある
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m_document_type dt WHERE NOT EXISTS (SELECT 1 FROM "
                    + "m_pii_data_element e WHERE e.tenant_id = ? AND e.data_element_id = CONCAT('DOCUMENT:', dt.code) "
                    + "AND e.subject_attribution = 'ATTRIBUTABLE' AND e.provider_id = 'document-file')",
                    Long.class, TENANT)).isZero();

            jdbc.update("INSERT INTO sys_user (id, tenant_id, username, password, real_name, role, status) "
                    + "VALUES (970009001, 'nf07-f2-seed', 'nf07-f2-seed-user', 'x', 'NF07 seed', '管理者', 1)");
            runSeed(connection);
            long seeded = count("nf07-f2-seed");
            assertThat(seeded).isEqualTo(count(TENANT));
            runSeed(connection);
            assertThat(count("nf07-f2-seed")).isEqualTo(seeded);
            assertThat(count(TENANT)).isEqualTo(before);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT tenant_id, data_element_id FROM "
                    + "m_pii_data_element GROUP BY tenant_id, data_element_id HAVING COUNT(*) > 1) d", Long.class)).isZero();
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    private String attribution(String id) {
        return jdbc.queryForObject("SELECT subject_attribution FROM m_pii_data_element WHERE tenant_id = ? "
                + "AND data_element_id = ?", String.class, TENANT, id);
    }

    private long count(String tenant) {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id = ?", Long.class, tenant);
        return value == null ? 0 : value;
    }

    private List<PiiDataElement> catalog(String tenant) {
        return mapper.selectList(new LambdaQueryWrapper<PiiDataElement>().eq(PiiDataElement::getTenantId, tenant));
    }

    private static void runSeed(Connection connection) {
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(SEED), StandardCharsets.UTF_8));
    }
}
