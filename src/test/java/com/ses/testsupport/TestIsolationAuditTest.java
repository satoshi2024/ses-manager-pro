package com.ses.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.annotation.AnnotatedElementUtils;
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
import java.util.List;
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
 *   <li>{@code @Transactional} でもテストTXの外でcommitする経路（REQUIRES_NEW / TestTransaction の commit）を
 *       持つクラスは、専用 H2 DB か、commitした行を削除する明示cleanupを持つ</li>
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
     * 原則空（真にread-only / no-datasourceスライスのみ）。
     * 例外: クラスTXで意味が変わる実TX・Servlet境界の検証（専用DBまたは明示cleanup前提）。
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
            entry("com.ses.report.ReportDeliveryTransactionIntegrationTest", "ReportDeliveryIssueService実Spring proxy TX rollback検証", true, "テスト専用行を@AfterEachで明示削除", "report-delivery-tx"),
            entry("com.ses.report.ReportReauthenticationSessionIntegrationTest", "afterCommit grantとsession間境界の実service検証", true, "一意なテストuser/delivery/runをIDで削除", "report-reauth-session"),
            entry("com.ses.service.ai.AiExecutionGatewayPersistMetadataTest", "gateway永続化metadataの実DB再読込", true, "trace_id固定prefixを@AfterEachでdelete", "ai-gateway-persist-metadata"),
            entry("com.ses.service.ai.AiRecommendationRetentionTenantIsolationH2Test", "tenant別retentionのcommit可視性", true, "trace_id固定prefixを@AfterEachでdelete", "ai-retention-tenant-isolation"),
            entry("com.ses.service.pwa.PwaRestoreTransactionIntegrationTest", "REQUIRES_NEWとcommit失敗の実TX境界", true, "専用H2 DBとテスト固有user/linkを@AfterEachでdelete", "pwa-restore-transaction"),
            entry("com.ses.web.PwaRestoreServletBoundaryIntegrationTest", "実Servlet thread/session/listener境界", true, "専用H2 DBとテスト固有user/linkを@AfterEachでdelete", "pwa-restore-servlet"),
            entry("com.ses.config.feature.FeatureSwitchServletContainerIntegrationTest", "機能スイッチOFF時の実Servlet container・Security chain順序・error dispatch（RANDOM_PORTのHTTPはテストTXを共有できない）", true, "専用H2 DBとテスト固有user・session・監査行を@AfterEachでdelete", "feature-switch-servlet"),
            entry("com.ses.controller.api.ImportRawUploadServletContainerE2ETest", "取込raw uploadの実Tomcat上のContent-Type・chunked転送・413のエンドツーエンド（RANDOM_PORTのHTTPはテストTXを共有できない）", true, "専用H2 DBとテスト固有user・session・監査行を@AfterEachでdelete", "import-raw-upload-servlet"),
            entry("com.ses.config.forwarded.ForwardedHeaderServletContainerIntegrationTest", "転送ヘッダー清浄化の実Tomcat connector検証（RANDOM_PORTのHTTPはテストTXを共有できない）", false, "DB書込なし（認証不要のecho endpointだけを呼ぶ。起動時の初期化はクラス専用H2 DBに閉じ@DirtiesContextで破棄）", "forwarded-header-servlet"),
            entry("com.ses.config.forwarded.ForwardedHeaderUntrustedPeerServletContainerIntegrationTest", "信頼しない接続元の転送ヘッダー無視の実Tomcat connector検証（RANDOM_PORTのHTTPはテストTXを共有できない）", false, "DB書込なし（認証不要のecho endpointだけを呼ぶ。起動時の初期化はクラス専用H2 DBに閉じ@DirtiesContextで破棄）", "forwarded-header-untrusted-servlet")
    );

    /** テストTXの外でcommitする経路の目印。これらで確定した行はテストTXのrollbackでは消えない。 */
    private static final List<String> TEST_TRANSACTION_ESCAPE_MARKERS = List.of(
            "PROPAGATION_REQUIRES_NEW",
            "Propagation.REQUIRES_NEW",
            "TestTransaction.flagForCommit",
            "@Commit",
            "@Rollback(false)");

    /**
     * commit経路を持つが、共有H2へ行を残さないことをコードで確認済みのクラス。
     * 業務行をcommitするクラスはここへ追加せず、専用DBか明示削除を持たせること。
     */
    private static final Map<String, String> TEST_TRANSACTION_ESCAPE_ALLOWLIST = Map.of(
            "com.ses.migration.IntegrationHubF1RetentionH2Test",
            "REQUIRES_NEW内で必ず例外を投げてrollbackさせ、commitしない",
            "com.ses.service.impl.DocumentAccessLogFailureIntegrationTest",
            "REQUIRES_NEW内で監査ログ失敗を起こして必ずrollbackさせ、commitしない",
            "com.ses.portal.PortalAdminApiTest",
            "commitするのはm_system_configの値と全体認可epochの増分だけで、設定値はfinallyで元の値へ戻す"
                    + "（epochは単調増加で戻さないが、epochを読む試験はすべて差分で比べる）",
            "com.ses.portal.PortalAuthFlowTest",
            "commitするのはm_system_configの値だけで、finallyで元の値へ戻す");

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

    /**
     * {@code @Transactional} のクラスでも、REQUIRES_NEW や TestTransaction の commit で確定した行は
     * rollbackされず共有H2に残る。全tenantを数える {@code LegalEntityReadinessService#assertReady} などは
     * 他テストの残骸で失敗するため、実行順次第のflakyになる（AllocationPlanServiceTest と
     * StaffingContractSyncTest が法人NULLの顧客・案件・要員を残し、ExternalApiReadSnapshotIntegrationTest が
     * LEGAL_ENTITY_BACKFILL_INCOMPLETE で落ちた）。commit経路を持つクラスには、専用H2 DBか、
     * TX終了後（{@code @AfterTransaction} / {@code @AfterEach}）に行を削除する明示cleanupを要求する。
     */
    @Test
    void テストTX外でcommitするSpringBootTestは専用DBか明示削除を持つ() throws Exception {
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
            String className = meta.getClassName();
            if (meta.isAbstract()) {
                continue;
            }
            String source = readTestSource(className);
            if (source == null || TEST_TRANSACTION_ESCAPE_MARKERS.stream().noneMatch(source::contains)) {
                continue;
            }
            if (!isSpringBootTest(meta, className)
                    || hasTag(meta, className, MYSQL_TAG)
                    || hasTag(meta, className, BROWSER_TAG)
                    || hasTag(meta, className, PERFORMANCE_TAG)) {
                continue;
            }
            if (TEST_TRANSACTION_ESCAPE_ALLOWLIST.containsKey(className)) {
                continue;
            }
            if (!hasDedicatedDatabase(source) && !hasPostTransactionDelete(source)) {
                offenders.add(className);
            }
        }

        assertThat(offenders)
                .as("テストTX外でcommitするSpringBootTestは専用H2 DBか@AfterTransaction/@AfterEachでの削除が必須: %s",
                        offenders)
                .isEmpty();
    }

    @Test
    void commit経路allowlistは実在する経路だけを載せる() {
        assertThat(TEST_TRANSACTION_ESCAPE_ALLOWLIST).allSatisfy((className, reason) -> {
            assertThat(reason).isNotBlank();
            String source = readTestSource(className);
            assertThat(source).as("allowlistのクラスが存在しない: %s", className).isNotNull();
            assertThat(TEST_TRANSACTION_ESCAPE_MARKERS.stream().anyMatch(source::contains))
                    .as("commit経路が無くなったクラスはallowlistから外すこと: %s", className)
                    .isTrue();
        });
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

    private static boolean isSpringBootTest(AnnotationMetadata meta, String className) {
        if (meta.hasAnnotation(SpringBootTest.class.getName())) {
            return true;
        }
        try {
            // BaseIntegrationTest 等の親クラスから継承した @SpringBootTest も対象にする
            Class<?> clazz = Class.forName(className, false, TestIsolationAuditTest.class.getClassLoader());
            return AnnotatedElementUtils.hasAnnotation(clazz, SpringBootTest.class);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readTestSource(String className) {
        String topLevelName = className.contains("$") ? className.substring(0, className.indexOf('$')) : className;
        Path source = Path.of("src", "test", "java", topLevelName.replace('.', File.separatorChar) + ".java");
        try {
            return Files.exists(source) ? Files.readString(source, StandardCharsets.UTF_8) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 共有 testdb ではなくクラス専用の H2 DB へ接続先を差し替えているか。 */
    private static boolean hasDedicatedDatabase(String source) {
        return source.contains("jdbc:h2:mem:") && !source.contains("jdbc:h2:mem:testdb");
    }

    /** テストTX終了後に走る位置で、commitした行を物理削除しているか（論理削除は残骸になる）。 */
    private static boolean hasPostTransactionDelete(String source) {
        boolean runsAfterTest = source.contains("@AfterTransaction") || source.contains("@AfterEach");
        boolean deletesRows = source.contains("DELETE FROM")
                || source.contains("deleteBy")
                || source.contains(".delete(");
        return runsAfterTest && deletesRows;
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
