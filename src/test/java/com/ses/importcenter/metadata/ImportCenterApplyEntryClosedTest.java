package com.ses.importcenter.metadata;

import com.ses.controller.api.ImportCenterApiController;
import com.ses.service.security.ActionPermissionResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
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
 * 初回リリースはupload・validation・previewだけを開放し、apply系（apply・restart・rollback・compensation）は含めない（B-NF06-3）。
 *
 * <p>apply系の入口が本番で閉じていることを、既存のDENY機構だけで確認する（機能スイッチは使わない）。
 * <ul>
 *   <li>取込API controllerが公開するrouteはupload（POST）とjob詳細（GET）の2本だけ。</li>
 *   <li>{@code /api/import-center/**}の他のpathは{@link ActionPermissionResolver}がaction keyを返さず（null）、
 *       {@code MenuPermissionFilter}が管理者を含め403で拒否する（fail-closed）。</li>
 *   <li>本番classpathの{@link ImportAdmissionPort}実装は{@link DefaultDenyImportAdmissionPort}だけで、
 *       承認済みsourceのadmission port（A-NF06-8）が入るまではuploadもbody読取前に403になる。</li>
 *   <li>本番classpathに{@code com.ses.importcenter.apply}等のapply実装packageが無い。</li>
 * </ul>
 * apply（W3-B6）を開放するバッチは、本testをそのバッチの承認条件（DG-06・admission port）に合わせて更新すること。
 */
class ImportCenterApplyEntryClosedTest {

    @Test
    void importCenterControllerExposesOnlyUploadAndJobDetailRoutes() {
        Set<String> routes = new TreeSet<>();
        RequestMapping root = AnnotatedElementUtils.findMergedAnnotation(ImportCenterApiController.class,
                RequestMapping.class);
        assertEquals(List.of("/api/import-center"), List.of(root.value()));
        for (Method method : ImportCenterApiController.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) continue;
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (mapping == null) continue;
            for (var httpMethod : mapping.method()) {
                for (String path : mapping.value().length == 0 ? mapping.path() : mapping.value()) {
                    routes.add(httpMethod.name() + " " + path);
                }
            }
        }
        assertEquals(new TreeSet<>(List.of("GET /jobs/{id}", "POST /uploads")), routes);
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
                new String[]{"GET", "/api/import-center/jobs/1/apply-status"});
        for (String[] route : closed) {
            assertNull(ActionPermissionResolver.resolve(route[0], route[1]), route[0] + " " + route[1]);
        }
    }

    @Test
    void productionClasspathHasOnlyTheDefaultDenyAdmissionPortAndNoApplyPackage() throws Exception {
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
        assertEquals(List.of(DefaultDenyImportAdmissionPort.class.getName()), admissionPorts);
    }
}
