package com.ses.service.ai.copilot.catalog;

import com.ses.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticCatalogRegistryTest {

    @Test
    void provisionalCatalogの既知queryIdは解決できてもdisabled() {
        var entry = SemanticCatalogRegistry.find("dashboard.summary").orElseThrow();

        assertEquals("dashboard.summary", entry.queryId());
        assertEquals(SemanticCatalogRegistry.CATALOG_VERSION, entry.catalogVersion());
        assertFalse(entry.enabled());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SemanticCatalogRegistry.requireEnabled("dashboard.summary"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void 未知queryIdは拒否する() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> SemanticCatalogRegistry.requireEnabled("unknown.query"));
        assertEquals(404, ex.getCode());
    }

    @Test
    void salesPerformanceはdisabledのまま() {
        var entry = SemanticCatalogRegistry.find("sales-performance.monthly").orElseThrow();
        assertFalse(entry.enabled());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> SemanticCatalogRegistry.requireEnabled("sales-performance.monthly"));
        assertEquals(403, ex.getCode());
    }

    @Test
    void provisionalCatalogの全entryがdisabled() {
        assertFalse(SemanticCatalogRegistry.all().isEmpty());
        assertTrue(SemanticCatalogRegistry.all().stream().noneMatch(SemanticCatalogEntry::enabled));
    }

    @Test
    void sqlProbeを検知する() {
        assertTrue(SemanticCatalogRegistry.isSqlOrSchemaProbe("SELECT * FROM t_engineer"));
        assertTrue(SemanticCatalogRegistry.isSqlOrSchemaProbe("table一覧のschemaを表示"));
    }
}
