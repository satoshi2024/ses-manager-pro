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

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ACC-OPS-P1-001: {@code @Scheduled} 付きメソッドには必ず {@code @SchedulerLock} を付ける。
 * クラスパス上の {@code com.ses} を ASM（Spring MetadataReader）で走査し、欠落を列挙して失敗する。
 */
class ScheduledMethodsHaveSchedulerLockTest {

    private static final String SCHEDULED = Scheduled.class.getName();
    private static final String SCHEDULER_LOCK = SchedulerLock.class.getName();

    @Test
    void Scheduledメソッドには必ずSchedulerLockが付いている() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        Resource[] resources = resolver.getResources("classpath*:com/ses/**/*.class");

        Set<String> offenders = new TreeSet<>();
        for (Resource resource : resources) {
            if (!resource.isReadable()) {
                continue;
            }
            // 内側クラスの名前に '$' を含むものも走査対象（匿名クラスはスキップ）
            String filename = resource.getFilename();
            if (filename != null && filename.contains("$$")) {
                continue;
            }
            MetadataReader reader = factory.getMetadataReader(resource);
            String className = reader.getClassMetadata().getClassName();
            // テストクラス自身は対象外（本番コードの不変条件を検証する）
            if (className.contains(".scheduler.ScheduledMethodsHaveSchedulerLockTest")) {
                continue;
            }
            Set<MethodMetadata> scheduledMethods =
                    reader.getAnnotationMetadata().getAnnotatedMethods(SCHEDULED);
            for (MethodMetadata method : scheduledMethods) {
                if (!method.isAnnotated(SCHEDULER_LOCK)) {
                    offenders.add(className + "#" + method.getMethodName());
                }
            }
        }

        assertThat(offenders)
                .as("@Scheduled に @SchedulerLock が無いメソッド: %s", offenders)
                .isEmpty();
    }

    /**
     * ShedLock の PROXY_METHOD（{@code @EnableSchedulerLock} の既定の interceptMode）は、primitive（void 以外）を返す method を
     * lock できず、lock を取る前に呼出しごとに {@code LockingNotSupportedException} を投げる。定期実行は毎回失敗し、method 本体
     * （機能スイッチの判定を含む）に届かない（NF07-A2a review P1-1・P1-X）。main の {@code @SchedulerLock} 付き method は
     * void か参照型を返すこと。
     */
    @Test
    void SchedulerLock付きメソッドはprimitiveを返さない() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        MetadataReaderFactory factory = new CachingMetadataReaderFactory(resolver);
        Set<String> locked = new TreeSet<>();
        Set<String> offenders = new TreeSet<>();
        for (Resource resource : resolver.getResources("classpath*:com/ses/**/*.class")) {
            String url = resource.getURL().toString().replace('\\', '/');
            if (!resource.isReadable() || url.contains("/test-classes/") || url.contains("-tests.jar")) {
                continue;
            }
            MetadataReader reader = factory.getMetadataReader(resource);
            for (MethodMetadata method : reader.getAnnotationMetadata().getAnnotatedMethods(SCHEDULER_LOCK)) {
                String name = reader.getClassMetadata().getClassName() + "#" + method.getMethodName();
                locked.add(name);
                if (returnsPrimitive(method)) {
                    offenders.add(name + " : " + method.getReturnTypeName());
                }
            }
        }

        assertThat(locked).as("@SchedulerLock 付きメソッドを1件も検出できない（走査の空振り）").isNotEmpty();
        assertThat(offenders)
                .as("@SchedulerLock 付きメソッドが primitive を返しています（ShedLock の proxy で毎回失敗します）: %s",
                        offenders)
                .isEmpty();
    }

    @Test
    void primitiveを返すSchedulerLock付きメソッドの検出規則は誤りを検出する() throws Exception {
        MetadataReader reader = new CachingMetadataReaderFactory().getMetadataReader(PrimitiveLockFixture.class.getName());
        Set<String> detected = new TreeSet<>();
        for (MethodMetadata method : reader.getAnnotationMetadata().getAnnotatedMethods(SCHEDULER_LOCK)) {
            if (returnsPrimitive(method)) {
                detected.add(method.getMethodName());
            }
        }
        assertThat(detected).containsExactly("returnsBoolean", "returnsInt");
    }

    private static boolean returnsPrimitive(MethodMetadata method) {
        return PRIMITIVE_RETURN_TYPES.contains(method.getReturnTypeName());
    }

    private static final Set<String> PRIMITIVE_RETURN_TYPES =
            Set.of("int", "long", "boolean", "double", "float", "short", "byte", "char");

    /** 検出規則の確認用（本番の走査では test-classes を除くため対象外）。 */
    @SuppressWarnings("unused")
    static class PrimitiveLockFixture {
        @SchedulerLock(name = "fixture-int")
        public int returnsInt() {
            return 0;
        }

        @SchedulerLock(name = "fixture-boolean")
        public boolean returnsBoolean() {
            return false;
        }

        @SchedulerLock(name = "fixture-void")
        public void returnsVoid() {
        }

        @SchedulerLock(name = "fixture-boxed")
        public Integer returnsBoxed() {
            return 0;
        }
    }
}
