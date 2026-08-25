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
}
