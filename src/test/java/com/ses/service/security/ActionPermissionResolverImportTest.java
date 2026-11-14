package com.ses.service.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActionPermissionResolverImportTest {
    @Test
    void resolvesOnlyExactUploadAndNumericJobDetailRoutes() {
        assertEquals("import.metadata.manage", ActionPermissionResolver.resolve(
                "POST", "/api/import-center/uploads"));
        assertEquals("import.metadata.manage", ActionPermissionResolver.resolve(
                "GET", "/api/import-center/jobs/123"));
        assertNull(ActionPermissionResolver.resolve("PUT", "/api/import-center/uploads"));
        assertNull(ActionPermissionResolver.resolve("POST", "/api/import-center/jobs/123"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/import-center/jobs/not-a-number"));
        assertNull(ActionPermissionResolver.resolve("POST", "/api/import-center/uploads/extra"));
    }

    @Test
    void resolvesA1aValidationPreviewExportAndSourceMappingRoutesToTheSameImportAction() {
        for (String[] route : new String[][]{
                {"POST", "/api/import-center/jobs/7/validations"},
                {"GET", "/api/import-center/jobs/7/preview"},
                {"GET", "/api/import-center/jobs/7/error-export"},
                {"GET", "/api/import-center/sources"},
                {"POST", "/api/import-center/sources"},
                {"PUT", "/api/import-center/sources/3"},
                {"GET", "/api/import-center/sources/3/mappings"},
                {"POST", "/api/import-center/sources/3/mappings"}}) {
            assertEquals("import.metadata.manage", ActionPermissionResolver.resolve(route[0], route[1]),
                    route[0] + " " + route[1]);
        }
    }

    @Test
    void unpublishedMethodsAndShapesUnderTheSameRoutesStayUnresolved() {
        assertNull(ActionPermissionResolver.resolve("GET", "/api/import-center/jobs/7/validations"));
        assertNull(ActionPermissionResolver.resolve("DELETE", "/api/import-center/sources/3"));
        assertNull(ActionPermissionResolver.resolve("PUT", "/api/import-center/sources"));
        assertNull(ActionPermissionResolver.resolve("POST", "/api/import-center/jobs/7/preview"));
        assertNull(ActionPermissionResolver.resolve("PUT", "/api/import-center/sources/3/mappings/1"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/import-center/sources/x/mappings"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/import-center/jobs/7/error-export/extra"));
        assertNull(ActionPermissionResolver.resolve("POST", "/api/import-center/jobs/7/apply"));
    }
}
