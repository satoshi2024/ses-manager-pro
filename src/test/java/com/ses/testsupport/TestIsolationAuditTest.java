package com.ses.testsupport;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACC-TEST-P1-004: 共有 H2 への暗黙依存（alphabetical runOrder 固定）を禁止する。
 * <ul>
 *   <li>surefire の {@code runOrder} が alphabetical でないこと</li>
 *   <li>名前に Concurrent を含むテストは JUnit の mysql タグで実 MySQL 隔離されること
 *       （共有 H2 上での順序依存並行更新を禁止）</li>
 *   <li>実 Chrome（CDP）デモは browser タグ必須（fast suite から除外）</li>
 * </ul>
 * <p>注: ソースに {@code @Tag}{@code mysql} の連結文字列を置かないこと。
 * {@code MySqlTestShardInventoryTest} は素朴な部分一致で mysql タグ付き test を列挙する。
 */
class TestIsolationAuditTest {

    private static final String MYSQL_TAG = "mysql";
    private static final String BROWSER_TAG = "browser";

    @Test
    void surefireのrunOrderはalphabetical固定ではない() throws Exception {
        Path pom = Path.of("pom.xml");
        String text = Files.readString(pom, StandardCharsets.UTF_8);
        assertThat(text)
                .as("ACC-TEST-P1-004: alphabetical runOrder 固定は共有H2汚染を隠すため禁止")
                .doesNotContain("<runOrder>alphabetical</runOrder>");
        assertThat(text)
                .as("runOrder は filesystem 等の非 alphabetical であること")
                .contains("<runOrder>filesystem</runOrder>");
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

    private static boolean hasTag(AnnotationMetadata meta, String className, String expected) {
        if (!meta.hasAnnotation(Tag.class.getName())) {
            return false;
        }
        Map<String, Object> attrs = meta.getAnnotationAttributes(Tag.class.getName());
        Object value = attrs == null ? null : attrs.get("value");
        if (expected.equals(String.valueOf(value))) {
            return true;
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
