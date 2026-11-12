package com.ses.service.scheduler;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACC-OPS-P1-001: {@code @Scheduled} 付きメソッドには必ず {@code @SchedulerLock} を付ける。
 * クラスパス上の {@code com.ses} を ASM（Spring MetadataReader）で走査し、欠落を列挙して失敗する。
 *
 * <p>あわせて、{@code @SchedulerLock} 付きメソッドがプリミティブ型を返さないことも検証する。
 * ShedLock の method proxy モード（{@code @EnableSchedulerLock} の既定）はプリミティブ戻り値の
 * メソッドを呼び出すたびに {@code LockingNotSupportedException} を投げるため、
 * そのバッチは起動ログ上は登録済みに見えても一度も本体が実行されない。
 */
class ScheduledMethodsHaveSchedulerLockTest {

    private static final String SCHEDULED = Scheduled.class.getName();
    private static final String SCHEDULER_LOCK = SchedulerLock.class.getName();

    /** ShedLock が method proxy でロックできない戻り値型（void は対象外）。 */
    private static final Set<String> PRIMITIVE_RETURN_TYPES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double");

    /**
     * 本番コードの不変条件を検証するため、意図的に違反を含むテスト用fixtureは走査対象から外す。
     * 内側クラス（{@code $} 付き）も前方一致で除外される。
     */
    private static final List<String> EXCLUDED_CLASS_PREFIXES = List.of(
            "com.ses.service.scheduler.ScheduledMethodsHaveSchedulerLockTest",
            "com.ses.service.scheduler.SchedulerLockMethodProxyTest");

    @Test
    void Scheduledメソッドには必ずSchedulerLockが付いている() throws Exception {
        Set<String> offenders = new TreeSet<>();
        for (MetadataReader reader : scanProductionClasses()) {
            String className = reader.getClassMetadata().getClassName();
            for (MethodMetadata method : reader.getAnnotationMetadata().getAnnotatedMethods(SCHEDULED)) {
                if (!method.isAnnotated(SCHEDULER_LOCK)) {
                    offenders.add(className + "#" + method.getMethodName());
                }
            }
        }

        assertThat(offenders)
                .as("@Scheduled に @SchedulerLock が無いメソッド: %s", offenders)
                .isEmpty();
    }

    @Test
    void SchedulerLockメソッドはプリミティブ型を返さない() throws Exception {
        Set<String> offenders = new TreeSet<>();
        int lockedMethodCount = 0;
        for (MetadataReader reader : scanProductionClasses()) {
            String className = reader.getClassMetadata().getClassName();
            for (MethodMetadata method : reader.getAnnotationMetadata().getAnnotatedMethods(SCHEDULER_LOCK)) {
                lockedMethodCount++;
                if (PRIMITIVE_RETURN_TYPES.contains(method.getReturnTypeName())) {
                    offenders.add(className + "#" + method.getMethodName() + " : " + method.getReturnTypeName());
                }
            }
        }

        // 走査が空振りしていない（クラスパス解決の失敗で常に成功する状態になっていない）ことを確認する。
        assertThat(lockedMethodCount)
                .as("@SchedulerLock 付きメソッドを1件も検出できない（走査対象の解決に失敗している可能性）")
                .isPositive();
        assertThat(offenders)
                .as("@SchedulerLock 付きメソッドがプリミティブ型を返している（ShedLock が LockingNotSupportedException を"
                        + "投げ本体が実行されない。void にし、件数は別のロックなしメソッドで返すこと）: %s", offenders)
                .isEmpty();
    }

    private static List<MetadataReader> scanProductionClasses() throws IOException {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        Resource[] resources = resolver.getResources("classpath*:com/ses/**/*.class");

        List<MetadataReader> readers = new ArrayList<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            // 内側クラスの名前に '$' を含むものも走査対象（CGLIB等の生成クラスはスキップ）
            String filename = resource.getFilename();
            if (filename != null && filename.contains("$$")) {
                continue;
            }
            MetadataReader reader = factory.getMetadataReader(resource);
            String className = reader.getClassMetadata().getClassName();
            if (EXCLUDED_CLASS_PREFIXES.stream().anyMatch(className::startsWith)) {
                continue;
            }
            readers.add(reader);
        }
        return readers;
    }
}
