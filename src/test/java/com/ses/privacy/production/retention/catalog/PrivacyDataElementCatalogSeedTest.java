package com.ses.privacy.production.retention.catalog;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.entity.privacy.PiiDataElement;
import com.ses.mapper.privacy.PiiDataElementMapper;
import com.ses.privacy.production.PrivacyCoverageReport;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.DocumentFileProvider;
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
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V196（F2 data element catalog seed）と V198（NF01 二者確認 B0 の catalog 行）を H2 で確認する（application-test.yml が
 * 起動時に V196・V198 をそのまま実行する）。
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
    /** V198: NF01 二者確認（B0）の7表と自由記述2列の catalog 行。 */
    private static final String B0_SEED = "db/migration/V198__nf01_dual_confirmation_privacy_catalog.sql";
    /**
     * V203: 機能の開放の署名者の氏名・署名の文書の catalog 行。V203 の DDL（ENGINE・STORED・trigger）は H2 で流せないため、
     * 末尾の {@code INSERT INTO m_pii_data_element} の文だけを再実行する（application-test.yml は H2 変種で同じ文を流す）。
     */
    private static final String FE_SEED = "db/migration/V203__feature_enablement_console.sql";
    private static final String TENANT = "default";

    /** V198 が入れる B0 の data element と保存先（V195 の7表と、別の data element にした自由記述2列）。 */
    static final Map<String, String> B0_ELEMENTS = Map.of(
            "LIFECYCLE_DUAL_SUBMISSION", "t_lifecycle_dual_submission.(business_comment以外)",
            "LIFECYCLE_DUAL_SUBMISSION_COMMENT", "t_lifecycle_dual_submission.business_comment",
            "LIFECYCLE_DUAL_SUBMISSION_DOCUMENT", "t_lifecycle_dual_submission_document",
            "LIFECYCLE_DUAL_SUBMISSION_ORG_FENCE", "t_lifecycle_dual_submission_org_fence",
            "LIFECYCLE_DUAL_CONFIRMATION", "t_lifecycle_dual_confirmation.(decision_comment以外)",
            "LIFECYCLE_DUAL_DECISION_COMMENT", "t_lifecycle_dual_confirmation.decision_comment",
            "LIFECYCLE_DUAL_PROJECTION", "t_lifecycle_dual_projection",
            "LIFECYCLE_DUAL_IDEMPOTENCY", "t_lifecycle_dual_idempotency",
            "LIFECYCLE_DUAL_EVENT", "t_lifecycle_dual_event");

    /** §3.4.1 で hold の gate に接続する data element（D4〜D7・D9。D1〜D3 は下の文書種別ごと）。 */
    static final List<String> GATE_CONNECTED = List.of("RESUME_INGESTION_ORIGINAL", "RESUME_INGESTION_TEXT",
            "BP_AVAILABILITY_INGESTION_TEXT", "AI_REDACTED_SUMMARY", "INTEGRATION_HUB_IDEMPOTENCY",
            "INTEGRATION_HUB_DELIVERY", "INTEGRATION_HUB_INBOUND", "INTEGRATION_HUB_INBOUND_REPLAY",
            "INTEGRATION_HUB_AUDIT", "INTEGRATION_HUB_EXTERNAL_AUDIT", "EXTERNAL_API_READ_SNAPSHOT");

    /** migration が m_document_type に入れる文書種別（V197 の RESUME_INGESTION・IMPORT を含む。D1〜D3）。 */
    static final List<String> DOCUMENT_TYPES = List.of("CONTRACT", "INVOICE_OUT", "INVOICE_IN", "QUOTATION",
            "WORK_REPORT", "SIGNED_PDF", "ESIGN_CERT", "DISPATCH_LEDGER", "EMPLOYMENT_CONDITIONS_STATEMENT",
            "DISPATCH_NOTICE", "INDIVIDUAL_CONTRACT", "ORDER_RECEIVED", "ORDER_ACKNOWLEDGEMENT", "ACCEPTANCE",
            "SKILL_SHEET", "RECEIPT", "PRIVATE_NOTE", "CHANGE_REQUEST_ATTACHMENT", "MANAGEMENT_REPORT",
            "CERTIFICATION_EVIDENCE", "SERVICE_REQUEST_ATTACHMENT", "BP_SUBMISSION", "RESUME_INGESTION", "IMPORT");

    /** 未登録として必ず報告される data element（Demo の例: 監査ログ・backup・外部 provider 側の写し）。 */
    private static final List<String> UNREGISTERED_EXAMPLES = List.of("AUDIT_LOG", "DATABASE_BACKUP",
            "AI_EXTERNAL_PROVIDER_COPY", "CLOUDSIGN_REMOTE_DOCUMENT", "FREEE_REMOTE_RECORD", "MAILBOX_SOURCE_MESSAGE",
            "AI_LEGACY_LOG", "PROJECT_INGESTION_TEXT", "FILE_SECURITY_METADATA", "AI_RECOMMENDATION_ITEM");

    private static final Set<String> CLASSIFIED = Set.of("ATTRIBUTABLE", "NOT_ATTRIBUTABLE", "NO_PERSONAL_DATA");

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private PiiDataElementMapper mapper;
    @Autowired
    private ProductionPrivacyProviderRegistry registry;
    @Autowired
    private org.springframework.context.ApplicationContext context;
    @Autowired
    private com.ses.privacy.production.provider.PrivacyReadOnlyQuery readOnlyQuery;

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
    void seedは構造行だけでownerとデータ分類とinventory状態はUNKNOWNのまま() throws Exception {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id = ? AND "
                + "(owner_state <> 'UNKNOWN' OR data_class <> 'UNKNOWN' OR inventory_status <> 'UNKNOWN' "
                + "OR owner_ref IS NOT NULL)", Long.class, TENANT)).isZero();
        // V196 の27件と V198（NF01 二者確認 B0）の9件と V203（機能の開放の署名者の氏名・署名の文書）の2件
        long fixed = jdbc.queryForObject("SELECT COUNT(*) FROM m_pii_data_element WHERE tenant_id = ? "
                + "AND data_element_id NOT LIKE 'DOCUMENT:%'", Long.class, TENANT);
        assertThat(fixed).isEqualTo(38L);
        // 配備の手順（RB §3.4 の DBA 立会いの照合 SQL）の件数も seed と同じ（review rb-fe-a1 P2-4。migration を足した時の書き漏れを防ぐ）
        java.util.regex.Matcher runbook = java.util.regex.Pattern.compile(
                "data_element_id NOT LIKE 'DOCUMENT:%';\\s*--\\s*(\\d+)").matcher(java.nio.file.Files.readString(
                java.nio.file.Path.of(".kiro/specs/production-launch/deploy-runbook.md")));
        assertThat(runbook.find()).as("RB §3.4 の catalog の件数の照会").isTrue();
        assertThat(Long.parseLong(runbook.group(1))).as("RB §3.4 の catalog の件数").isEqualTo(fixed);
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
                if (element.prefixMatch()) {
                    continue; // 未知の種別の受け皿（全ての既知の種別は個別に宣言している）
                }
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

    /**
     * V198（RB-NF01、W3-21a review P3-4）: NF01 二者確認（B0）の7表と自由記述2列は、V196 の未登録の data element と同じ形
     * （provider 無し・分類 UNKNOWN・owner・データ分類・inventory 状態は UNKNOWN の構造行。保持期間は持たない）で catalog にある。
     * V195 が作る表は1つも漏れず、coverage report では欠落（CATALOG_MISSING）ではなく PROVIDER_UNREGISTERED として報告される。
     */
    @Test
    void NF01二者確認の7表と自由記述2列はprovider未登録の構造行としてcatalogにありV195の表を漏らさない() throws Exception {
        List<PiiDataElement> rows = catalog(TENANT).stream()
                .filter(row -> B0_ELEMENTS.containsKey(row.getDataElementId())).toList();
        assertThat(rows).extracting(PiiDataElement::getDataElementId)
                .containsExactlyInAnyOrderElementsOf(B0_ELEMENTS.keySet());
        for (PiiDataElement row : rows) {
            String id = row.getDataElementId();
            assertThat(row.getStorageRef()).as(id).isEqualTo(B0_ELEMENTS.get(id));
            assertThat(row.getStorageKind()).as(id).isEqualTo("DB_COLUMN");
            assertThat(row.getProviderId()).as(id).isNull();
            assertThat(row.getSubjectAttribution()).as(id).isEqualTo("UNKNOWN");
            assertThat(row.getOwnerState()).as(id).isEqualTo("UNKNOWN");
            assertThat(row.getDataClass()).as(id).isEqualTo("UNKNOWN");
            assertThat(row.getInventoryStatus()).as(id).isEqualTo("UNKNOWN");
            assertThat(row.getInventoryRef()).as(id).matches("pii-inventory\\.md#DB-14[1-7]");
        }

        // V195 の CREATE TABLE を読み、全ての表と自由記述の2列が catalog の storage_ref で覆われる
        String v195 = new String(new ClassPathResource("db/migration/V195__nf01_dual_confirmation_coordinator.sql")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        java.util.regex.Matcher created = java.util.regex.Pattern.compile("CREATE TABLE\\s+(t_lifecycle_dual_\\w+)")
                .matcher(v195);
        Set<String> tables = new java.util.TreeSet<>();
        while (created.find()) {
            tables.add(created.group(1));
        }
        assertThat(tables).hasSize(7);
        for (String table : tables) {
            assertThat(rows).as(table).anyMatch(row -> row.getStorageRef().equals(table)
                    || row.getStorageRef().startsWith(table + ".("));
        }
        assertThat(v195).contains("business_comment", "decision_comment");

        PrivacyCoverageReport report = PrivacyCoverageReport.build(TENANT, catalog(TENANT), registry);
        assertThat(report.unregisteredElements()).filteredOn(entry -> B0_ELEMENTS.containsKey(entry.dataElementId()))
                .hasSize(B0_ELEMENTS.size())
                .allSatisfy(entry -> assertThat(entry.reason()).isEqualTo(PrivacyCoverageReport.PROVIDER_UNREGISTERED));
        assertThat(report.registeredElements()).noneMatch(entry -> B0_ELEMENTS.containsKey(entry.dataElementId()));
        assertThat(report.uncatalogedProviderElements())
                .noneMatch(missing -> missing.dataElementId().startsWith("LIFECYCLE_DUAL_"));
    }

    /**
     * catalog の文書種別ごとの分類は DocumentFileProvider の宣言（唯一の正本）と一致し、残余の種別は catalog・coverage の
     * どちらでも NOT_ATTRIBUTABLE として見える（§3.4.3 規則5の入力を1つに保つ。NF07-F2 delta review P2-B）。
     */
    @Test
    void catalogの文書種別ごとの分類はproviderの宣言と一致し残余はNOT_ATTRIBUTABLEとして見える() {
        ProductionPrivacyProvider document = registry.require(DocumentFileProvider.PROVIDER_ID);
        List<PiiDataElement> rows = catalog(TENANT).stream()
                .filter(row -> row.getDataElementId().startsWith(DocumentFileProvider.ELEMENT_PREFIX)).toList();

        for (String code : DocumentFileProvider.DOCUMENT_TYPE_RULES.keySet()) {
            assertThat(rows).as(code).anyMatch(row -> row.getDataElementId().equals("DOCUMENT:" + code));
        }
        for (PiiDataElement row : rows) {
            ProviderDataElement declaration = ProductionPrivacyProviderRegistry
                    .declaration(document, row.getDataElementId()).orElseThrow();
            assertThat(declaration.prefixMatch()).as(row.getDataElementId()).isFalse();
            assertThat(row.getSubjectAttribution()).as(row.getDataElementId())
                    .isEqualTo(declaration.attribution().name());
        }
        PrivacyCoverageReport report = PrivacyCoverageReport.build(TENANT, catalog(TENANT), registry);
        assertThat(report.unregisteredElements())
                .noneMatch(entry -> PrivacyCoverageReport.ATTRIBUTION_MISMATCH.equals(entry.reason()));
        assertThat(report.registeredElements())
                .filteredOn(entry -> entry.dataElementId().startsWith(DocumentFileProvider.ELEMENT_PREFIX)
                        && entry.subjectAttribution().equals("NOT_ATTRIBUTABLE"))
                .extracting(PrivacyCoverageReport.ElementEntry::dataElementId)
                .containsExactlyInAnyOrder("DOCUMENT:IMPORT", "DOCUMENT:MANAGEMENT_REPORT",
                        "DOCUMENT:SERVICE_REQUEST_ATTACHMENT");
    }

    /**
     * Spring に登録された provider（@Bean や継承で作られたものを含む）は classpath を走査した main の実装と一致し、
     * registry もその全てを登録している。DB 接続口は read-only transaction の proxy である（P3-3、P3-11）。
     */
    @Test
    void Springのproviderはclasspath走査の実装と一致し読取り口はtransactionのproxyである() throws Exception {
        org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider scanner =
                new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AssignableTypeFilter(
                ProductionPrivacyProvider.class));
        Set<Class<?>> scanned = new java.util.HashSet<>();
        for (org.springframework.beans.factory.config.BeanDefinition candidate
                : scanner.findCandidateComponents("com.ses")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            if (!type.getProtectionDomain().getCodeSource().getLocation().toString().contains("test-classes")) {
                scanned.add(type);
            }
        }
        Set<Class<?>> beans = context.getBeansOfType(ProductionPrivacyProvider.class).values().stream()
                .map(org.springframework.util.ClassUtils::getUserClass)
                .collect(java.util.stream.Collectors.toSet());
        Set<Class<?>> registered = registry.providers().stream()
                .map(org.springframework.util.ClassUtils::getUserClass)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(scanned).isNotEmpty();
        assertThat(beans).isEqualTo(scanned);
        assertThat(registered).isEqualTo(scanned);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(readOnlyQuery)).isTrue();
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
                    + "AND e.subject_attribution IN ('ATTRIBUTABLE', 'NOT_ATTRIBUTABLE') AND e.provider_id = 'document-file')",
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

    /** catalog の seed（V196・V198 と V203 の catalog 行）を再実行する。 */
    private static void runSeed(Connection connection) throws java.io.IOException {
        for (String script : List.of(SEED, B0_SEED)) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(new ClassPathResource(script),
                    StandardCharsets.UTF_8));
        }
        String v203 = new ClassPathResource(FE_SEED).getContentAsString(StandardCharsets.UTF_8);
        String catalogInsert = v203.substring(v203.lastIndexOf("INSERT INTO m_pii_data_element"));
        ScriptUtils.executeSqlScript(connection, new EncodedResource(new org.springframework.core.io.ByteArrayResource(
                catalogInsert.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
    }
}
