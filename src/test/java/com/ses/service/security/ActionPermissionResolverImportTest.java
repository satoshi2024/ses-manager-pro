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
}
