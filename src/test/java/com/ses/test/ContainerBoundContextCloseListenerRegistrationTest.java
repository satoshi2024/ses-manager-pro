package com.ses.test;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTestContextBootstrapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.test.context.BootstrapUtils;
import org.springframework.test.context.TestContextBootstrapper;
import org.springframework.test.context.TestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.TestExecutionListeners.MergeMode;
import org.springframework.test.context.event.EventPublishingTestExecutionListener;
import org.springframework.test.context.jdbc.SqlScriptsTestExecutionListener;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.support.DirtiesContextTestExecutionListener;
import org.springframework.test.context.transaction.TransactionalTestExecutionListener;
import org.springframework.test.context.util.TestContextSpringFactoriesUtils;
import org.springframework.util.StringUtils;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ContainerBoundContextCloseListener} が Spring の既定 listener として実際に解決されることの守り。
 *
 * <p>登録は {@code META-INF/spring.factories} だけに依存する。登録を消す、または対象クラスが
 * {@code @TestExecutionListeners} で既定 listener を置き換えると、コンテキストが再び JVM 終了まで残り、
 * Surefire の fork 終了期限超過（dump）として CI でしか見えない形で再発するため、Docker 無しで検出する。</p>
 */
class ContainerBoundContextCloseListenerRegistrationTest {

    /** 判定ロジックと独立に比較するための JUnit タグ値。 */
    private static final String MYSQL = "mysql";

    @Test
    void springFactoriesに登録されSpringの既定listener読込みで生成される() throws IOException {
        Set<String> registered = new TreeSet<>();
        Enumeration<URL> urls = getClass().getClassLoader()
                .getResources(SpringFactoriesLoader.FACTORIES_RESOURCE_LOCATION);
        while (urls.hasMoreElements()) {
            Properties properties = PropertiesLoaderUtils.loadProperties(new UrlResource(urls.nextElement()));
            String names = properties.getProperty(TestExecutionListener.class.getName(), "");
            for (String name : StringUtils.commaDelimitedListToStringArray(names)) {
                registered.add(name.trim());
            }
        }

        assertThat(registered)
                .as("META-INF/spring.factories の %s キーに登録すること", TestExecutionListener.class.getName())
                .contains(ContainerBoundContextCloseListener.class.getName());
        // AbstractTestContextBootstrapper#getDefaultTestExecutionListeners が使う読込み経路そのもの。
        assertThat(TestContextSpringFactoriesUtils.loadFactoryImplementations(TestExecutionListener.class))
                .anyMatch(ContainerBoundContextCloseListener.class::isInstance);
    }

    @Test
    void 既定listener解決に含まれcontextを使うafter系listenerの後で破棄する() {
        List<TestExecutionListener> listeners = resolveListeners(DefaultListenersFixture.class);

        // 見つからなければ indexOf が AssertionError で失敗する。
        int self = indexOf(listeners, ContainerBoundContextCloseListener.class);
        // TestContextManager は afterTestClass を逆順に呼ぶ。index が小さいほど後に実行される。
        assertThat(self).isLessThan(indexOf(listeners, DirtiesContextTestExecutionListener.class));
        assertThat(self).isLessThan(indexOf(listeners, TransactionalTestExecutionListener.class));
        assertThat(self).isLessThan(indexOf(listeners, SqlScriptsTestExecutionListener.class));
        assertThat(self).isLessThan(indexOf(listeners, EventPublishingTestExecutionListener.class));
    }

    @Test
    void TestExecutionListenersで既定を置き換えたクラスには適用されずMERGEなら適用される() {
        assertThat(resolveListeners(ReplacingDefaultsFixture.class))
                .noneMatch(ContainerBoundContextCloseListener.class::isInstance);
        assertThat(resolveListeners(MergingDefaultsFixture.class))
                .anyMatch(ContainerBoundContextCloseListener.class::isInstance);
    }

    @Test
    void container束縛の全テストクラスで既定listenerが解決される() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        ClassLoader classLoader = getClass().getClassLoader();

        Set<String> targets = new TreeSet<>();
        Set<String> undetected = new TreeSet<>();
        Set<String> withoutListener = new TreeSet<>();
        Set<Class<?>> bootstrappers = new HashSet<>();
        for (String pattern : List.of("classpath*:com/ses/**/*Test.class", "classpath*:com/ses/**/*Tests.class")) {
            for (Resource resource : resolver.getResources(pattern)) {
                if (!resource.isReadable()) {
                    continue;
                }
                String className = factory.getMetadataReader(resource).getClassMetadata().getClassName();
                if (className.contains("$")) {
                    // 内部クラスは単独のテストクラスとして実行されない（@Nested は外側クラスの終了時に閉じる）。
                    continue;
                }
                // static初期化（container生成）を走らせずに注釈だけを読む。
                Class<?> testClass = Class.forName(className, false, classLoader);
                boolean declared = testClass.isAnnotationPresent(Testcontainers.class)
                        || Arrays.stream(testClass.getAnnotationsByType(Tag.class))
                                .anyMatch(tag -> MYSQL.equals(tag.value()));
                boolean target = ContainerBoundContextCloseListener.closesContextAfterClass(testClass);
                if (declared && !target) {
                    undetected.add(className);
                }
                if (!target) {
                    continue;
                }
                targets.add(className);
                TestContextBootstrapper bootstrapper = BootstrapUtils.resolveTestContextBootstrapper(testClass);
                bootstrappers.add(bootstrapper.getClass());
                if (bootstrapper.getTestExecutionListeners().stream()
                        .noneMatch(ContainerBoundContextCloseListener.class::isInstance)) {
                    withoutListener.add(className);
                }
            }
        }

        assertThat(targets).as("container束縛のテストクラスが1件も見つからない（判定の退行）").isNotEmpty();
        assertThat(bootstrappers)
                .as("実 MySQL の @SpringBootTest（SpringBootTestContextBootstrapper）で解決を確認すること")
                .contains(SpringBootTestContextBootstrapper.class);
        assertThat(undetected)
                .as("Testcontainers注釈またはmysqlタグを直接持つのに対象外と判定されたクラス: %s", undetected)
                .isEmpty();
        assertThat(withoutListener)
                .as("@TestExecutionListeners で既定 listener を置き換えたため、クラス終了時に破棄されないクラス"
                        + "（mergeMode = MERGE_WITH_DEFAULTS にすること）: %s", withoutListener)
                .isEmpty();
    }

    private static List<TestExecutionListener> resolveListeners(Class<?> testClass) {
        return BootstrapUtils.resolveTestContextBootstrapper(testClass).getTestExecutionListeners();
    }

    private static int indexOf(List<TestExecutionListener> listeners, Class<?> type) {
        for (int i = 0; i < listeners.size(); i++) {
            if (type.isInstance(listeners.get(i))) {
                return i;
            }
        }
        throw new AssertionError(type.getName() + " が既定 listener に見つからない: " + listeners);
    }

    private static class DefaultListenersFixture {
    }

    @TestExecutionListeners(DependencyInjectionTestExecutionListener.class)
    private static class ReplacingDefaultsFixture {
    }

    @TestExecutionListeners(
            listeners = DependencyInjectionTestExecutionListener.class,
            mergeMode = MergeMode.MERGE_WITH_DEFAULTS)
    private static class MergingDefaultsFixture {
    }
}
