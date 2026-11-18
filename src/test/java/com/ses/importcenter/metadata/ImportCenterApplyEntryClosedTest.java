package com.ses.importcenter.metadata;

import com.ses.controller.api.ImportCenterApiController;
import com.ses.controller.api.ImportCenterSourceApiController;
import com.ses.controller.api.ImportCenterValidationApiController;
import com.ses.service.security.ActionPermissionResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 初回リリースはupload・validation・preview（＋error export・source/mapping管理）だけを開放し、apply系
 * （apply・restart・rollback・compensation）は含めない（B-NF06-3）。
 *
 * <p>apply系の入口が本番で閉じていることを、DENY機構で確認する。
 * <ul>
 *   <li>{@code /api/import-center}のcontrollerが公開するrouteは、A1aの許可routeだけ（下記の完全一致）。</li>
 *   <li>{@code /api/import-center/**}の他のpathは{@link ActionPermissionResolver}がaction keyを返さず（null）、
 *       {@code MenuPermissionFilter}が管理者を含め403で拒否する（fail-closed）。</li>
 *   <li>本番classpathの{@link ImportAdmissionPort}実装は{@link ApprovedSourceImportAdmissionPort}だけで、
 *       署名済みData Owner決定IDとsource承認一覧が揃うまでuploadはbody読取前に403になる（Q-5）。</li>
 *   <li>本番classpathに{@code com.ses.importcenter.apply}等のapply実装packageが無い。</li>
 * </ul>
 * 機能スイッチNF06（既定OFF）は公開可否の別層であり、apply系の閉鎖はスイッチに依存しない。
 * apply（W3-B6）を開放するバッチは、本testをそのバッチの承認条件（DG-06・admission port）に合わせて更新すること。
 */
class ImportCenterApplyEntryClosedTest {

    @Test
    void importCenterControllersExposeOnlyTheA1aRoutes() throws Exception {
        Set<String> routes = new TreeSet<>();
        for (Class<?> controller : importCenterControllers()) {
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            assertEquals(List.of("/api/import-center"), List.of(root.value()), controller.getName());
            for (Method method : controller.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) continue;
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) continue;
                for (var httpMethod : mapping.method()) {
                    for (String path : mapping.value().length == 0 ? mapping.path() : mapping.value()) {
                        routes.add(httpMethod.name() + " " + path);
                    }
                }
            }
        }
        assertEquals(new TreeSet<>(List.of(
                "GET /jobs/{id}", "POST /uploads",
                "POST /jobs/{id}/validations", "GET /jobs/{id}/preview", "GET /jobs/{id}/error-export",
                "GET /sources", "POST /sources", "PUT /sources/{id}",
                "GET /sources/{id}/mappings", "POST /sources/{id}/mappings")), routes);
        for (String route : routes) {
            String[] parts = route.split(" ", 2);
            String concrete = "/api/import-center" + parts[1].replace("{id}", "1");
            assertEquals("import.metadata.manage", ActionPermissionResolver.resolve(parts[0], concrete), route);
        }
    }

    @Test
    void applyRestartRollbackAndOtherImportCenterPathsResolveToNoActionAndAreDenied() {
        List<String[]> closed = List.of(
                new String[]{"POST", "/api/import-center/jobs/1/apply"},
                new String[]{"POST", "/api/import-center/apply"},
                new String[]{"POST", "/api/import-center/jobs/1/restart"},
                new String[]{"POST", "/api/import-center/jobs/1/rollback"},
                new String[]{"POST", "/api/import-center/jobs/1/compensate"},
                new String[]{"PUT", "/api/import-center/jobs/1"},
                new String[]{"DELETE", "/api/import-center/jobs/1"},
                new String[]{"PATCH", "/api/import-center/uploads"},
                new String[]{"GET", "/api/import-center/jobs/1/apply-status"},
                new String[]{"DELETE", "/api/import-center/sources/1"},
                new String[]{"DELETE", "/api/import-center/sources/1/mappings/2"},
                new String[]{"POST", "/api/import-center/jobs/1/validations/2/apply"});
        for (String[] route : closed) {
            assertNull(ActionPermissionResolver.resolve(route[0], route[1]), route[0] + " " + route[1]);
        }
    }

    @Test
    void productionClasspathHasOnlyTheApprovedSourceAdmissionPortAndNoApplyPackage() throws Exception {
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        List<String> admissionPorts = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (Resource resource : resolver.getResources("classpath*:com/ses/**/*.class")) {
            String url = resource.getURL().toString().replace('\\', '/');
            if (url.contains("/test-classes/") || url.contains("-tests.jar")) continue;
            int index = url.indexOf("com/ses/");
            if (index < 0) continue;
            String className = url.substring(index, url.length() - ".class".length()).replace('/', '.');
            assertTrue(!className.startsWith("com.ses.importcenter.apply."),
                    "初回リリースにapply実装packageを含めない: " + className);
            if (!className.startsWith("com.ses.importcenter.") || className.contains("$")) continue;
            Class<?> type = Class.forName(className, false, loader);
            if (ImportAdmissionPort.class.isAssignableFrom(type) && !type.isInterface()) {
                admissionPorts.add(type.getName());
            }
        }
        assertEquals(List.of(ApprovedSourceImportAdmissionPort.class.getName()), admissionPorts);
    }

    /** {@code /api/import-center}をrootに持つ本番controller。新しいcontrollerを足したら本testの許可routeも見直す。 */
    private static List<Class<?>> importCenterControllers() throws Exception {
        List<Class<?>> found = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (Resource resource : resolver.getResources("classpath*:com/ses/controller/**/*.class")) {
            String url = resource.getURL().toString().replace('\\', '/');
            if (url.contains("/test-classes/") || url.contains("-tests.jar")) continue;
            int index = url.indexOf("com/ses/");
            if (index < 0) continue;
            String className = url.substring(index, url.length() - ".class".length()).replace('/', '.');
            if (className.contains("$")) continue;
            Class<?> type = Class.forName(className, false, loader);
            if (!AnnotatedElementUtils.hasAnnotation(type, Controller.class)) continue;
            RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            if (root != null && List.of(root.value()).stream().anyMatch(path -> path.startsWith("/api/import-center"))) {
                found.add(type);
            }
        }
        assertEquals(new TreeSet<>(List.of(ImportCenterApiController.class.getName(),
                        ImportCenterSourceApiController.class.getName(), ImportCenterValidationApiController.class.getName())),
                new TreeSet<>(found.stream().map(Class::getName).toList()));
        return found;
    }
}
