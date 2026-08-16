package com.ses.service.storage;

import com.ses.common.exception.BusinessException;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.BpAvailabilityIngestionMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectIngestionMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ResumeIngestionMapper;
import com.ses.service.security.impl.FileScopeValidationService;
import com.ses.service.storage.impl.LocalDocumentStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * T023 Storage Adapter & Stream Download 単体テスト。
 */
@ExtendWith(MockitoExtension.class)
class DocumentStorageTest {

    @Mock ResumeIngestionMapper resumeIngestionMapper;
    @Mock EngineerMapper engineerMapper;
    @Mock ProposalMapper proposalMapper;
    @Mock ProjectIngestionMapper projectIngestionMapper;
    @Mock BpAvailabilityIngestionMapper bpAvailabilityIngestionMapper;
    @Mock DocumentVersionMapper documentVersionMapper;
    @Mock ObjectProvider<DocumentVersionMapper> documentVersionMapperProvider;

    LocalDocumentStorage localStorage;
    FileScopeValidationService fileScopeValidationService;

    @BeforeEach
    void setUp() {
        localStorage = new LocalDocumentStorage("./target/test-storage");
        lenient().when(documentVersionMapperProvider.getIfAvailable()).thenReturn(documentVersionMapper);

        fileScopeValidationService = new FileScopeValidationService(
                resumeIngestionMapper,
                engineerMapper,
                proposalMapper,
                projectIngestionMapper,
                bpAvailabilityIngestionMapper,
                documentVersionMapperProvider,
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(ObjectProvider.class),
                mock(com.ses.service.security.DataScopeService.class),
                mock(ObjectProvider.class)
        );
    }

    @Test
    void localStorage_putAndOpen_success() throws Exception {
        String key = "test/file1.txt";
        byte[] data = "sample data".getBytes(StandardCharsets.UTF_8);

        localStorage.put(key, data, true);
        localStorage.promote(key);

        try (InputStream is = localStorage.open(key)) {
            byte[] readBytes = is.readAllBytes();
            assertArrayEquals(data, readBytes);
        }
    }

    @Test
    void localStorage_partialWriteFailure_removesQuarantineOrphan() throws Exception {
        String key = "partial/" + System.nanoTime() + ".pdf";
        InputStream failing = new InputStream() {
            private boolean emitted;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (!emitted) {
                    emitted = true;
                    buffer[offset] = 'x';
                    return 1;
                }
                throw new IOException("simulated partial stream failure");
            }

            @Override
            public int read() throws IOException {
                throw new IOException("simulated partial stream failure");
            }
        };

        assertThrows(RuntimeException.class, () -> localStorage.put(key, failing, true));
        Path quarantineFile = Path.of("./target/test-storage/documents/quarantine").resolve(key);
        assertFalse(Files.exists(quarantineFile), "部分書込みのquarantine orphanを残してはならない");
    }

    @Test
    void fileScopeValidation_scanNotReady_throws403() {
        String key = "test/pending_file.pdf";

        DocumentVersion v = new DocumentVersion();
        v.setStorageKey(key);
        v.setScanStatus("PENDING");

        when(documentVersionMapper.selectOne(any())).thenReturn(v);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                fileScopeValidationService.assertDownloadAllowed(key));
        assertEquals(403, ex.getCode());
        assertEquals("error.file.scanNotReady", ex.getMessageKey());
    }

    @Test
    void fileScopeValidation_unknownKey_throws403UnknownReference() {
        String key = "unknown/key.pdf";
        when(documentVersionMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () ->
                fileScopeValidationService.assertDownloadAllowed(key));
        assertEquals(403, ex.getCode());
        assertEquals("error.file.unknownReference", ex.getMessageKey());
    }
}
