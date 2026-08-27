package com.ses.architecture;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACC-ARCH-P1-001: RestController（controllerパッケージ）に {@code @Transactional} を置かない。
 * トランザクション境界はサービス層へ移す。
 */
class ControllerTransactionalBanTest {

    @Test
    void controllerパッケージのメソッドにTransactionalが付いていないこと() throws Exception {
        Class<?> jakartaTransactional = loadJakartaTransactionalOrNull();
        List<String> violations = new ArrayList<>();
        for (Class<?> type : loadControllerClasses()) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Transactional.class)
                        || (jakartaTransactional != null
                        && method.getAnnotation(jakartaTransactional.asSubclass(java.lang.annotation.Annotation.class)) != null)) {
                    violations.add(type.getName() + "#" + method.getName());
                }
            }
            if (type.isAnnotationPresent(Transactional.class)
                    || (jakartaTransactional != null
                    && type.getAnnotation(jakartaTransactional.asSubclass(java.lang.annotation.Annotation.class)) != null)) {
                violations.add(type.getName() + " (class-level)");
            }
        }
        assertTrue(violations.isEmpty(),
                "com.ses.controller 配下に @Transactional があります（サービスへ移してください）: " + violations);
    }

    private static Class<?> loadJakartaTransactionalOrNull() {
        try {
            return Class.forName("jakarta.transaction.Transactional");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private List<Class<?>> loadControllerClasses() throws IOException, ClassNotFoundException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:com/ses/controller/**/*.class");
        List<Class<?>> classes = new ArrayList<>();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        for (Resource resource : resources) {
            String url = resource.getURL().toString().replace('\\', '/');
            // テストクラス（*-tests.jar / target/test-classes）は対象外
            if (url.contains("/test-classes/") || url.contains("-tests.jar")) {
                continue;
            }
            int idx = url.indexOf("com/ses/controller/");
            if (idx < 0) {
                continue;
            }
            String fqn = url.substring(idx).replace('/', '.').replace(".class", "");
            if (fqn.contains("$")) {
                continue;
            }
            classes.add(Class.forName(fqn, false, cl));
        }
        assertTrue(!classes.isEmpty(), "controllerクラスが1件も見つかりません");
        return classes;
    }
}
