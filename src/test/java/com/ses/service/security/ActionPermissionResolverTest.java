package com.ses.service.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ActionPermissionResolverTest {

    @Test
    void resource配下のexportはviewではなくexportActionになる() {
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("GET", "/api/contracts/export"));
        assertEquals("export.execute",
                ActionPermissionResolver.resolve("GET", "/api/engineers/export-csv"));
    }

    @Test
    void prefixの似た未知URIを既知resourceと誤認しない() {
        assertNull(ActionPermissionResolver.resolve("GET", "/api/users-archive"));
    }

    @Test
    void file再scanはuploadと別actionになる() {
        assertEquals("file.scan.retry",
                ActionPermissionResolver.resolve("POST", "/api/files/sample.pdf/rescan"));
    }
}
