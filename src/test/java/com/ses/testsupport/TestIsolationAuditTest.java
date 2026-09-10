package com.ses.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACC-TEST-P1-004 / REV-RP-P1-003: 共有 H2 への暗黙依存（固定 runOrder）を禁止する。
 * <ul>
 *   <li>surefire の {@code runOrder} が {@code random} であること（alphabetical / filesystem 禁止）</li>
 *   <li>pom が実行順を隔離手段として主張していないこと</li>
 *   <li>名前に Concurrent を含むテストは JUnit の mysql タグで実 MySQL 隔離されること</li>
 *   <li>実 Chrome（CDP）デモは browser タグ必須（fast suite から除外）</li>
 *   <li>非 mysql / browser / performance の {@code @SpringBootTest} は {@code @Transactional}
 *       （または極小 allowlist: 真に read-only / no-datasource スライスのみ）</li>
 * </ul>
 * <p>注: ソースに {@code @Tag}{@code mysql} の連結文字列を置かないこと。
 * {@code MySqlTestShardInventoryTest} は素朴な部分一致で mysql タグ付き test を列挙する。
 */
class TestIsolationAuditTest {

    private static final String MYSQL_TAG = "mysql";
    private static final String BROWSER_TAG = "browser";
    private static final String PERFORMANCE_TAG = "performance";

    /**
     * 非 {@code @Transactional} を許容する {@code @SpringBootTest} の極小 allowlist。
     * 原則空（真に read-only / no-datasource スライスのみ）。
     * 例外: クラスTXだと潰れる並行commit可視性検証（H2上・明示クリーンアップ前提）。
     */
    private static final Map<String, IsolationExceptionMetadata> NON_TRANSACTIONAL_SPRING_BOOT_ALLOWLIST = Map.ofEntries(
            entry("com.ses.service.impl.SystemConfigCommitOrderingTest", "並行afterCommitの可視性", true, "テスト固有fixtureをdelete", "system-config-commit"),
            entry("com.ses.service.ExternalIdentityProvisioningTransactionTest", "並行再承認のcommit可視性", true, "テスト固有identityをdelete", "external-identity-transaction"),
            entry("com.ses.integration.IntegrationConnectionAndJobTest", "token refreshとjob claimの並行性", true, "テスト固有connection/jobをdelete", "integration-connection-job"),
            entry("com.ses.integration.SalesInvoiceIntegrationTest", "triggerSalesSyncの並行性", true, "テスト固有invoice/jobをdelete", "sales-invoice-integration"),
            entry("com.ses.service.ai.AiFeedbackLearningSchemaTest", "学習スキーマの並行更新", true, "テスト固有learning rowをdelete", "ai-feedback-learning"),
            entry("com.ses.service.cloudsign.CloudSignDispatchIntegrationTest", "CloudSign派遣の並行可視性", true, "テスト固有dispatchをdelete", "cloudsign-dispatch"),
            entry("com.ses.service.ai.AiExecutionGatewayPiiTest", "provider HTTPをTX外で検証", false, "DB書込なし", "ai-gateway-pii"),
            entry("com.ses.service.impl.FreeeReauthPersistenceTest", "REQUIRES_NEW永続化可視性", true, "テスト固有freee tokenをdelete", "freee-reauth"),
            entry("com.ses.controller.api.ComplianceDocumentApiTest", "文書配布のcommit可視性", true, "テスト固有document/deliveryをdelete", "compliance-document"),
            entry("com.ses.expense.ExpenseRequestFlowIntegrationTest", "expenseの外部commit可視性", true, "テスト固有expenseをdelete", "expense-flow"),
            entry("com.ses.controller.api.SystemConfigScopeInvalidationTest", "rollback境界を明示検証", true, "テスト固有configをdelete", "system-config-scope"),
            entry("com.ses.service.accounting.FreeeAccountingProviderTest", "MockRestとDB再読込の可視性", true, "テスト固有accounting rowをdelete", "freee-accounting"),
            entry("com.ses.oneonone.OneOnOneSurveyFlowIntegrationTest", "survey commit可視性", true, "テスト固有surveyをdelete", "oneonone-survey"),
            entry("com.ses.web.EngineerSelfServicePortalMRegressionTest", "portal commit可視性", true, "テスト固有portal rowをdelete", "portal-regression"),
            entry("com.ses.changerequest.EngineerChangeRequestAttachmentApiTest", "添付公開metadataの可視性", true, "テスト固有attachmentをdelete", "change-request-attachment"),
            entry("com.ses.mapper.IntegrationHubWebhookResourceScopeMapperIntegrationTest", "jdbcTemplate更新後の再読込", true, "テスト固有resource scopeをdelete", "integration-hub-resource-scope"),
            entry("com.ses.report.ReportDeliveryTransactionIntegrationTest", "ReportDeliveryIssueService実Spring proxy TX rollback検証", true, "テスト専用行を@AfterEachで明示削除", "report-delivery-tx")
    );

    private static Map.Entry<String, IsolationExceptionMetadata> entry(String className, String reason,
                                                                        boolean writesDatabase,
                                                                        String cleanupMechanism, String fixtureKey) {
        return Map.entry(className,
                new IsolationExceptionMetadata(reason, writesDatabase, cleanupMechanism, fixtureKey));
    }

    private record IsolationExceptionMetadata(String reason, boolean writesDatabase,
                                              String cleanupMechanism, String fixtureKey) {
    }

    @Test
    void surefireのrunOrderはrandomである() throws Exception {
        Path pom = Path.of("pom.xml");
        String text = Files.readString(pom, StandardCharsets.UTF_8);
        assertThat(text)
                .as("ACC-TEST-P1-004: alphabetical runOrder 固定は共有H2汚染を隠すため禁止")
                .doesNotContain("<runOrder>alphabetical</runOrder>");
        assertThat(text)
                .as("ACC-TEST-P1-004: filesystem runOrder も固定順に依存するため禁止")
                .doesNotContain("<runOrder>filesystem</runOrder>");
        assertThat(text)
                .as("runOrder は random（順序非依存の証拠）。seed は -Dsurefire.runOrder.random.seed=N")
                .contains("<runOrder>random</runOrder>");
    }

    @Test
    void pomは実行順を隔離手段と主張しない() throws Exception {
        Path pom = Path.of("pom.xml");
        String text = Files.readString(pom, StandardCharsets.UTF_8);
        // surefire 設定コメント付近: 固定順序を隔離の代替にしない旨が書かれていること
        assertThat(text)
                .as("固定順序（alphabetical/filesystem）は隔離の代替にしない、と明記すること")
                .contains("隔離の代替にしない");
        assertThat(text)
                .as("隔離手段は @Transactional ロールバックまたは mysql タグであること")
                .contains("@Transactional")
                .contains("mysql");
    }

    @Test
    void Concurrent系テストはmysqlタグで実DB隔離する() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        Resource[] resources = resolver.getResources("classpath*:com/ses/**/*Concurrent*Test.class");

        Set<String> offenders = new TreeSet<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            MetadataReader reader = factory.getMetadataReader(resource);
            AnnotationMetadata meta = reader.getAnnotationMetadata();
            String className = meta.getClassName();
            if (!hasTag(meta, className, MYSQL_TAG)) {
                offenders.add(className);
            }
        }

        assertThat(offenders)
                .as("Concurrent*Test は JUnit mysql タグ必須: %s", offenders)
                .isEmpty();
    }

    @Test
    void 実Chromeデモはbrowserタグでfastから除外する() throws Exception {
        // AiBrowserApiKeyContractTest は静的契約のみ（Chrome不要）なので対象外。
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        String[] patterns = {
                "classpath*:com/ses/web/*BrowserDemo*Test.class",
                "classpath*:com/ses/web/*BrowserMTest.class",
                "classpath*:com/ses/web/RealBrowser*Test.class",
                "classpath*:com/ses/web/G2GateBrowser*Test.class"
        };
        Set<String> offenders = new TreeSet<>();
        for (String pattern : patterns) {
            for (Resource resource : resolver.getResources(pattern)) {
                if (!resource.isReadable()) {
                    continue;
                }
                MetadataReader reader = factory.getMetadataReader(resource);
                AnnotationMetadata meta = reader.getAnnotationMetadata();
                String className = meta.getClassName();
                if (!hasTag(meta, className, BROWSER_TAG)) {
                    offenders.add(className);
                }
            }
        }
        assertThat(offenders)
                .as("実Chrome系テストは browser タグ必須: %s", offenders)
                .isEmpty();
    }

    @Test
    void 非mysqlのSpringBootTestはTransactionalまたはallowlist() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        Resource[] resources = resolver.getResources("classpath*:com/ses/**/*Test.class");

        Set<String> offenders = new TreeSet<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            MetadataReader reader = factory.getMetadataReader(resource);
            AnnotationMetadata meta = reader.getAnnotationMetadata();
            if (meta.isAbstract() || !meta.hasAnnotation(SpringBootTest.class.getName())) {
                continue;
            }
            String className = meta.getClassName();
            if (hasTag(meta, className, MYSQL_TAG)
                    || hasTag(meta, className, BROWSER_TAG)
                    || hasTag(meta, className, PERFORMANCE_TAG)) {
                continue;
            }
            if (NON_TRANSACTIONAL_SPRING_BOOT_ALLOWLIST.containsKey(className)) {
                IsolationExceptionMetadata exception = NON_TRANSACTIONAL_SPRING_BOOT_ALLOWLIST.get(className);
                if (exception.writesDatabase() && !hasConcreteCleanupMechanism(className)) {
                    offenders.add(className);
                }
                continue;
            }
            if (!hasTransactional(className)) {
                offenders.add(className);
            }
        }

        assertThat(offenders)
                .as("非mysql SpringBootTest は @Transactional（または allowlist）必須: %s", offenders)
                .isEmpty();
    }

    @Test
    void allowlistは理由だけでなく実annotationまたは実cleanupを要求する() {
        assertThat(NON_TRANSACTIONAL_SPRING_BOOT_ALLOWLIST).isNotEmpty()
                .allSatisfy((className, metadata) -> {
                    assertThat(metadata.reason()).isNotBlank();
                    assertThat(metadata.cleanupMechanism()).isNotBlank();
                    assertThat(metadata.fixtureKey()).isNotBlank();
                    if (metadata.writesDatabase()) {
                        assertThat(metadata.cleanupMechanism()).doesNotContain("DB書込なし");
                        assertThat(hasTransactional(className) || hasConcreteCleanupMechanism(className))
                                .as("allowlistのcleanup metadataだけでは共有DBを許可しない: %s", className)
                                .isTrue();
                    }
                });
    }

    private static boolean hasConcreteCleanupMechanism(String className) {
        if (hasTransactional(className)) {
            return true;
        }
        Path source = Path.of("src", "test", "java", className.replace('.', File.separatorChar) + ".java");
        try {
            if (!Files.exists(source)) {
                return false;
            }
            String text = Files.readString(source, StandardCharsets.UTF_8);
            boolean hasAfterEach = text.contains("@AfterEach") || text.contains("AfterEach");
            boolean hasNarrowCleanup = text.contains("cleanup")
                    || text.contains("restore")
                    || text.contains("reset")
                    || text.contains("DELETE FROM")
                    || text.contains("deleteBy")
                    || text.contains("removeBy")
                    || text.contains(".delete(")
                    || text.contains(".remove(");
            return hasAfterEach && hasNarrowCleanup;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean hasTransactional(String className) {
        try {
            Class<?> clazz = Class.forName(className);
            // @Transactional は @Inherited — 親（例: BaseIntegrationTest）も検出する
            return clazz.isAnnotationPresent(Transactional.class);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean hasTag(AnnotationMetadata meta, String className, String expected) {
        if (meta.hasAnnotation(Tag.class.getName())) {
            Map<String, Object> attrs = meta.getAnnotationAttributes(Tag.class.getName());
            Object value = attrs == null ? null : attrs.get("value");
            if (expected.equals(String.valueOf(value))) {
                return true;
            }
        }
        try {
            Class<?> clazz = Class.forName(className);
            for (Tag t : clazz.getAnnotationsByType(Tag.class)) {
                if (expected.equals(t.value())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // attributes のみ
        }
        return false;
    }
}
