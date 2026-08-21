package com.ses.config;

import com.ses.common.enums.FileKind;
import com.ses.entity.FileSecurityMetadata;
import com.ses.mapper.FileSecurityMetadataMapper;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * HFP-02-BUG-04: uploads/contracts バックフィルは CONTRACT_PDF でscanする。
 */
class ContractDocumentBackfillRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void backfillはCONTRACT_PDFでscanする() throws Exception {
        Path contractDir = tempDir.resolve("contracts").resolve("7");
        Files.createDirectories(contractDir);
        Path pdfFile = contractDir.resolve("document-7.pdf");
        Files.write(pdfFile, "%PDF-1.4\nx\ntrailer\n%%EOF\n".getBytes());

        FileSecurityMetadataMapper mapper = mock(FileSecurityMetadataMapper.class);
        when(mapper.selectByStoredName(eq("default"), any())).thenReturn(null);

        FileScanner scanner = mock(FileScanner.class);
        when(scanner.scan(any(), eq(FileKind.CONTRACT_PDF))).thenReturn(FileScanResult.clean("ok"));

        ObjectProvider<FileSecurityMetadataMapper> metaProv = mock(ObjectProvider.class);
        when(metaProv.getIfAvailable()).thenReturn(mapper);
        ObjectProvider<FileScanner> scanProv = mock(ObjectProvider.class);
        when(scanProv.getIfAvailable()).thenReturn(scanner);

        ContractDocumentBackfillRunner runner = new ContractDocumentBackfillRunner(metaProv, scanProv);
        ReflectionTestUtils.setField(runner, "uploadBase", tempDir.toString());

        runner.run(mock(org.springframework.boot.ApplicationArguments.class));

        verify(scanner, times(1)).scan(any(), eq(FileKind.CONTRACT_PDF));
        verify(scanner, never()).scan(any(), eq(FileKind.SKILL_SHEET));
        verify(mapper).insert(argThat((FileSecurityMetadata m) ->
                "PUBLISHED".equals(m.getStorageState()) && "CLEAN".equals(m.getScanStatus())));
    }
}
