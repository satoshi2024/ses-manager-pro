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
    private static final Set<String> NON_TRANSACTIONAL_SPRING_BOOT_ALLOWLIST = Set.of(
            // 並行 afterCommit 順序・キャッシュ再読込。クラスTXでは他スレッドからcommitが見えない
            "com.ses.service.impl.SystemConfigCommitOrderingTest",
            // 並行再承認の commit 可視性。クラスTX不可
            "com.ses.service.ExternalIdentityProvisioningTransactionTest",
            // トークン refresh / ジョブ claim の並行。クラスTX不可
            "com.ses.integration.IntegrationConnectionAndJobTest",
            // triggerSalesSync 並行。クラスTX不可
            "com.ses.integration.SalesInvoiceIntegrationTest",
            // 学習スキーマ並行更新。クラスTX不可
            "com.ses.service.ai.AiFeedbackLearningSchemaTest",
            // CloudSign 派遣の並行・ゲート検証。クラスTXだと他スレッドから見えない
            "com.ses.service.cloudsign.CloudSignDispatchIntegrationTest",
            // provider HTTP を TX 外に出す契約検証（クラスTXを意図的に付けない）
            "com.ses.service.ai.AiExecutionGatewayPiiTest",
            // REQUIRES_NEW / 永続化可視性が必要（クラスTXだと更新が外から見えない）
            "com.ses.service.impl.FreeeReauthPersistenceTest",
            "com.ses.controller.api.ComplianceDocumentApiTest",
            "com.ses.expense.ExpenseRequestFlowIntegrationTest",
            "com.ses.service.impl.ReferentialIntegrityGuardTest",
            "com.ses.service.notification.NotificationOutboxSchedulerIntegrationTest",
            "com.ses.integration.AccountingWorkerRawExceptionLogTest",
            // 明示的に rollback 挙動を検証するためクラスTXと衝突する
            "com.ses.controller.api.SystemConfigScopeInvalidationTest",
            // MockRest + DB 接続読取がクラスTXで company_id が null 化する
            "com.ses.service.accounting.FreeeAccountingProviderTest",
            "com.ses.oneonone.OneOnOneSurveyFlowIntegrationTest",
            // ポータル連携の commit 可視性がクラスTXで潰れる
            "com.ses.web.EngineerSelfServicePortalMRegressionTest",
            // 添付ファイル公開メタデータがクラスTX内では download 経路から見えない
            "com.ses.changerequest.EngineerChangeRequestAttachmentApiTest"
    );

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
            if (NON_TRANSACTIONAL_SPRING_BOOT_ALLOWLIST.contains(className)) {
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
