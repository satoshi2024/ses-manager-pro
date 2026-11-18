package com.ses.importcenter.metadata;

import com.ses.common.exception.BusinessException;
import com.ses.service.DocumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * W3-12（W3-05 review P3-2）: {@link ImportMetadataService#bindMapping} は呼出元の可見性確認に頼らず、
 * job行の参照とlockに現在の法人を条件として付ける。別法人のjob・lock前に消えたjobは同じ404にする。
 * 専用H2（{@link ImportA1aH2Fixture}）だけを使う。
 */
class ImportMetadataBindMappingScopeTest {
    private static final String CSV = """
            externalKey,companyName
            C-1,株式会社アルファ
            """;

    private ImportA1aH2Fixture fixture;
    private long sourceId;
    private long mappingId;

    @BeforeEach
    void prepare() {
        fixture = new ImportA1aH2Fixture();
        sourceId = fixture.createSource("legacy-crm");
        mappingId = fixture.createMapping(sourceId, ImportA1aH2Fixture.mapping("externalKey", "companyName"));
    }

    @Test
    void jobOfAnotherLegalEntityIsReportedAsMissingAndLeftUntouched() {
        long foreign = fixture.uploadedJob(sourceId, CSV, 99);

        BusinessException failure = assertThrows(BusinessException.class,
                () -> fixture.metadata.bindMapping(foreign, 1, mappingId));

        assertEquals(404, failure.getCode());
        assertEquals("error.import.jobNotFound", failure.getMessageKey());
        assertEquals("UPLOADED", fixture.jobState(foreign));
        assertNull(fixture.jdbc.queryForObject("SELECT mapping_id FROM t_import_job WHERE tenant_id=? AND id=?",
                Long.class, ImportA1aH2Fixture.TENANT, foreign));
        // 同じ法人のjobは従来どおり束縛できる
        long own = fixture.uploadedJob(sourceId, CSV.replace("C-1", "C-2"), ImportA1aH2Fixture.LEGAL_ENTITY);
        assertEquals(2L, fixture.metadata.bindMapping(own, 1, mappingId));
        assertEquals("MAPPED", fixture.jobState(own));
    }

    @Test
    void jobDeletedBetweenTheLookupAndTheRowLockIsNotFoundInsteadOfAServerError() {
        long jobId = fixture.uploadedJob(sourceId, CSV, ImportA1aH2Fixture.LEGAL_ENTITY);
        AtomicBoolean deleted = new AtomicBoolean();
        JdbcTemplate racing = new JdbcTemplate(fixture.jdbc.getDataSource()) {
            @Override
            public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) throws DataAccessException {
                if (sql.contains("FROM t_import_job") && sql.contains("FOR UPDATE") && deleted.compareAndSet(false, true)) {
                    // 参照とlockの間に他の処理がjob行を消した状態を再現する
                    fixture.jdbc.update("DELETE FROM t_import_job WHERE tenant_id=? AND id=?",
                            ImportA1aH2Fixture.TENANT, jobId);
                }
                return super.query(sql, extractor, args);
            }
        };
        ImportMetadataService service = new ImportMetadataService(racing, fixture.authorization,
                mock(ImportIdentityKeyProvider.class), mock(DocumentService.class));

        BusinessException failure = assertThrows(BusinessException.class, () -> service.bindMapping(jobId, 1, mappingId));

        assertTrue(deleted.get(), "lockの直前にjob行を消したこと");
        assertEquals(404, failure.getCode());
        assertEquals("error.import.jobNotFound", failure.getMessageKey());
    }
}
