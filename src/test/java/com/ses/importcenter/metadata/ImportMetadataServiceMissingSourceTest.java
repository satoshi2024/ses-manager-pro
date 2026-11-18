package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.service.DocumentService;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 存在しないsourceと他tenantのsourceを同じnot-found境界で拒否する。 */
class ImportMetadataServiceMissingSourceTest {
    private static final ImportAuthorizedContext CONTEXT = new ImportAuthorizedContext("tenant-a", 11L, 21L);

    private JdbcTemplate jdbc;
    private ImportMetadataService service;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:missing_source_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE t_import_source_system (
                  id BIGINT NOT NULL,
                  tenant_id VARCHAR(80) NOT NULL,
                  source_code VARCHAR(64) NOT NULL,
                  status VARCHAR(16) NOT NULL,
                  version_no BIGINT NOT NULL,
                  lookup_key_version VARCHAR(80) NOT NULL,
                  PRIMARY KEY (tenant_id, id)
                )
                """);
        var authorization = mock(ImportMetadataAuthorizationService.class);
        when(authorization.requireCurrentAuthorization()).thenReturn(CONTEXT);
        service = new ImportMetadataService(jdbc, authorization, mock(ImportIdentityKeyProvider.class),
                mock(DocumentService.class));
    }

    @Test
    void nonexistentSourceReturnsNotFoundInsteadOfDereferencingNull() {
        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.disableSource(404L, 1L, true));

        assertEquals(404, failure.getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_import_source_system", Integer.class));
    }

    @Test
    void foreignTenantSourceIsIndistinguishableFromMissingSource() {
        jdbc.update("INSERT INTO t_import_source_system VALUES(?,?,?,?,?,?)",
                404L, "tenant-b", "external", "ACTIVE", 1L, "lookup-v1");

        BusinessException failure = assertThrows(BusinessException.class,
                () -> service.disableSource(404L, 1L, true));

        assertEquals(404, failure.getCode());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM t_import_source_system WHERE tenant_id='tenant-b' AND id=404", String.class));
        assertEquals(1L, jdbc.queryForObject(
                "SELECT version_no FROM t_import_source_system WHERE tenant_id='tenant-b' AND id=404", Long.class));
    }
}
