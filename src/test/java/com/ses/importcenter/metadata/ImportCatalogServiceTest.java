package com.ses.importcenter.metadata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.importcenter.foundation.ImportEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/** A1aのsource・mapping管理（登録・更新・一覧）のtenant境界・権限・CAS。 */
class ImportCatalogServiceTest {
    private ImportA1aH2Fixture fixture;
    private ImportCatalogService catalog;

    @BeforeEach
    void prepare() {
        fixture = new ImportA1aH2Fixture();
        catalog = new ImportCatalogService(fixture.authorization, fixture.metadata, fixture.jdbc, new ObjectMapper());
    }

    @Test
    void sourcesAreRegisteredNormalizedAndListedOnlyForTheCurrentTenant() {
        ImportSourceView created = catalog.createSource(new ImportSourceRequest("Legacy-CRM", "  旧CRM  "));
        fixture.jdbc.update("""
                INSERT INTO t_import_source_system(tenant_id, source_code, display_name, status, version_no,
                  lookup_key_version, created_by, updated_by) VALUES('other-tenant','foreign','他tenant','ACTIVE',1,'k',1,1)
                """);

        ImportCatalogPage<ImportSourceView> page = catalog.listSources(1, 10);

        assertEquals("legacy-crm", created.sourceCode());
        assertEquals("旧CRM", created.displayName());
        assertEquals("ACTIVE", created.status());
        assertEquals(1, page.total());
        assertEquals(List.of(created), page.items());
        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> catalog.createSource(new ImportSourceRequest("legacy-crm", "重複")));
        assertEquals(409, duplicate.getCode());
        BusinessException invalid = assertThrows(BusinessException.class,
                () -> catalog.createSource(new ImportSourceRequest("1-starts-with-digit", "不正")));
        assertEquals(400, invalid.getCode());
    }

    @Test
    void listPageSizeIsClampedToTheUpperLimit() {
        catalog.createSource(new ImportSourceRequest("source-a", "A"));

        ImportCatalogPage<ImportSourceView> page = catalog.listSources(-3, 50_000);

        assertEquals(1, page.page());
        assertEquals(ImportCatalogService.MAX_PAGE_SIZE, page.size());
        assertEquals(1, page.items().size());
    }

    @Test
    void sourceStatusIsChangedOnlyWithTheCurrentVersion() {
        ImportSourceView created = catalog.createSource(new ImportSourceRequest("legacy-crm", "旧CRM"));

        ImportSourceView disabled = catalog.changeSourceStatus(created.id(), created.version(), "DISABLED");
        assertEquals("DISABLED", disabled.status());
        assertEquals(created.version() + 1, disabled.version());

        BusinessException stale = assertThrows(BusinessException.class,
                () -> catalog.changeSourceStatus(created.id(), created.version(), "ACTIVE"));
        assertEquals(409, stale.getCode());
        BusinessException badStatus = assertThrows(BusinessException.class,
                () -> catalog.changeSourceStatus(created.id(), disabled.version(), "DELETED"));
        assertEquals(400, badStatus.getCode());
        assertEquals("ACTIVE", catalog.changeSourceStatus(created.id(), disabled.version(), "ACTIVE").status());
    }

    @Test
    void mappingVersionsAreImmutableAndIdenticalContentReusesTheVersion() {
        ImportSourceView source = catalog.createSource(new ImportSourceRequest("legacy-crm", "旧CRM"));
        Map<String, String> first = Map.of("顧客コード", "externalKey", "会社名", "companyName");

        ImportMappingView v1 = catalog.createMapping(source.id(), ImportEntity.CUSTOMER, null, first);
        ImportMappingView again = catalog.createMapping(source.id(), ImportEntity.CUSTOMER, "ses-import-v1", first);
        ImportMappingView v2 = catalog.createMapping(source.id(), ImportEntity.CUSTOMER, "ses-import-v1",
                Map.of("顧客コード", "externalKey", "会社名", "companyName", "担当者", "contactPerson"));

        assertEquals(1, v1.mappingVersion());
        assertEquals(v1.id(), again.id());
        assertEquals(2, v2.mappingVersion());
        assertEquals("externalKey", v1.headerToCanonicalField().get("顧客コード"));
        assertEquals(12, v1.mappingHashPrefix().length());
        assertEquals(2, catalog.listMappings(source.id(), 1, 10).total());
        BusinessException missingRequired = assertThrows(BusinessException.class, () -> catalog.createMapping(
                source.id(), ImportEntity.CUSTOMER, null, Map.of("会社名", "companyName")));
        assertEquals(400, missingRequired.getCode());
    }

    @Test
    void mappingsOfAnotherTenantsSourceAreNotFound() {
        fixture.jdbc.update("""
                INSERT INTO t_import_source_system(tenant_id, source_code, display_name, status, version_no,
                  lookup_key_version, created_by, updated_by) VALUES('other-tenant','foreign','他tenant','ACTIVE',1,'k',1,1)
                """);
        long foreign = fixture.jdbc.queryForObject("SELECT id FROM t_import_source_system WHERE tenant_id='other-tenant'",
                Long.class);

        BusinessException list = assertThrows(BusinessException.class, () -> catalog.listMappings(foreign, 1, 10));
        BusinessException create = assertThrows(BusinessException.class, () -> catalog.createMapping(foreign,
                ImportEntity.CUSTOMER, null, Map.of("k", "externalKey", "n", "companyName")));
        BusinessException status = assertThrows(BusinessException.class,
                () -> catalog.changeSourceStatus(foreign, 1, "DISABLED"));

        assertEquals(404, list.getCode());
        assertEquals("error.import.sourceNotFound", list.getMessageKey());
        assertEquals(404, create.getCode());
        assertEquals(404, status.getCode());
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM t_import_mapping", Integer.class));
    }

    @Test
    void everyOperationStopsWhenTheCurrentPrincipalLacksTheImportPermission() {
        when(fixture.authorization.requireCurrentAuthorization()).thenThrow(BusinessException.of(403, "error.forbidden"));

        assertEquals(403, assertThrows(BusinessException.class, () -> catalog.listSources(1, 10)).getCode());
        assertEquals(403, assertThrows(BusinessException.class,
                () -> catalog.createSource(new ImportSourceRequest("legacy-crm", "旧CRM"))).getCode());
        assertEquals(403, assertThrows(BusinessException.class, () -> catalog.listMappings(1, 1, 10)).getCode());
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM t_import_source_system", Integer.class));
    }
}
