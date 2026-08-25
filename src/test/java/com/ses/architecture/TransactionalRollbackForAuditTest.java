package com.ses.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-ARCH-P1-002: 更新系 {@code @Transactional} は {@code rollbackFor = Exception.class} を必須とする。
 * {@code readOnly = true} は対象外。
 */
class TransactionalRollbackForAuditTest {

    @Test
    void service配下の更新系TransactionalはrollbackForにExceptionを含むこと() throws Exception {
        List<String> violations = new ArrayList<>();
        for (Class<?> type : loadServiceClasses()) {
            scanType(type, violations);
            for (Method method : type.getDeclaredMethods()) {
                scanMethod(type, method, violations);
            }
        }
        assertTrue(violations.isEmpty(),
                "@Transactional に rollbackFor=Exception.class がありません: " + violations);
    }

    private void scanType(Class<?> type, List<String> violations) {
        Transactional tx = type.getAnnotation(Transactional.class);
        if (tx != null && !tx.readOnly() && !includesException(tx)) {
            violations.add(type.getName() + " (class-level)");
        }
    }

    private void scanMethod(Class<?> type, Method method, List<String> violations) {
        Transactional tx = method.getAnnotation(Transactional.class);
        if (tx != null && !tx.readOnly() && !includesException(tx)) {
            violations.add(type.getName() + "#" + method.getName());
        }
    }

    private boolean includesException(Transactional tx) {
        return Arrays.stream(tx.rollbackFor()).anyMatch(c -> c == Exception.class);
    }

    private List<Class<?>> loadServiceClasses() throws IOException, ClassNotFoundException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/ses/service/**/*.class");
        List<Class<?>> classes = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (Resource resource : resources) {
            String url = resource.getURL().toString().replace('\\', '/');
            if (url.contains("/test-classes/") || url.contains("-tests.jar")) {
                continue;
            }
            int idx = url.indexOf("com/ses/service/");
            if (idx < 0) {
                continue;
            }
            String fqn = url.substring(idx).replace('/', '.').replace(".class", "");
            if (fqn.contains("$")) {
                continue;
            }
            classes.add(Class.forName(fqn, false, cl));
        }
        assertTrue(!classes.isEmpty(), "serviceクラスが1件も見つかりません");
        return classes;
    }
}
