package com.ses.config.integrationhub;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalApiRouteCatalogTest {
    @Test
    void onlyApprovedGetTemplatesResolve() {
        assertEquals("/external-api/v1/projects/count",
                ExternalApiRouteCatalog.resolve("GET", "/external-api/v1/projects/count").template());
        assertEquals("/external-api/v1/projects/{publicProjectId}",
                ExternalApiRouteCatalog.resolve("GET", "/external-api/v1/projects/p-1").template());
        assertNull(ExternalApiRouteCatalog.resolve("POST", "/external-api/v1/projects"));
        assertNull(ExternalApiRouteCatalog.resolve("GET", "/external-api/v1/projects/p-1/extra"));
        assertNull(ExternalApiRouteCatalog.resolve("GET", "/external-api/v1/engineer-availability/count"));
        assertEquals("/external-api/v1/webhooks/{provider}",
                ExternalApiRouteCatalog.resolve("POST", "/external-api/v1/webhooks/provider-a").template());
        assertNull(ExternalApiRouteCatalog.resolve("POST", "/external-api/v1/webhooks/provider-a/extra"));
        assertTrue(ExternalApiRouteCatalog.isExternalApiPath("/external-api/v1"));
        assertTrue(ExternalApiRouteCatalog.isExternalApiPath("/external-api/v1/unknown"));
    }
}
