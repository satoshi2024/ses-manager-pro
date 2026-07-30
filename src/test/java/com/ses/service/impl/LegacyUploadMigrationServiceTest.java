package com.ses.service.impl;

import com.ses.config.UploadProperties;
import com.ses.common.enums.FileKind;
import com.ses.entity.FileSecurityMetadata;
import com.ses.mapper.FileSecurityMetadataMapper;
import com.ses.service.FileReferenceProvider;
import com.ses.service.security.FileScanResult;
import com.ses.service.security.FileScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * quarantine/published導入前の既存ファイルが、移行後もダウンロードできる状態に
 * なることを確認する。移行が無いと{@code FileStorageServiceImpl#load}のfail-closed判定で
 * 既存のスキルシート・写真・取込原本が一斉に403になる。
 */
class LegacyUploadMigrationServiceTest {

    @TempDir
    Path uploadRoot;

    @Test
    void 参照されている既存ファイルはscan後にpublishedへ移りmetadataが登録される() throws Exception {
        Files.write(uploadRoot.resolve("aaa.pdf"), validPdf());
        List<FileSecurityMetadata> inserted = new ArrayList<>();

        LegacyUploadMigrationService.Result result =
                service(Set.of("aaa.pdf"), FileScanResult.clean("test-1"), inserted).migrate();

        assertEquals(1, result.published());
        assertTrue(Files.exists(uploadRoot.resolve("published/aaa.pdf")));
        assertFalse(Files.exists(uploadRoot.resolve("aaa.pdf")));
        assertEquals(1, inserted.size());
        assertEquals("PUBLISHED", inserted.get(0).getStorageState());
        assertEquals("CLEAN", inserted.get(0).getScanStatus());
        assertEquals("SKILL_SHEET", inserted.get(0).getFileKind());
    }

    @Test
    void scannerが使えない場合はquarantineへ残し公開しない() throws Exception {
        Files.write(uploadRoot.resolve("bbb.pdf"), validPdf());
        List<FileSecurityMetadata> inserted = new ArrayList<>();

        LegacyUploadMigrationService.Result result =
                service(Set.of("bbb.pdf"), FileScanResult.unavailable("daemon down"), inserted).migrate();

        assertEquals(0, result.published());
        assertEquals(1, result.quarantined());
        assertTrue(Files.exists(uploadRoot.resolve("quarantine/bbb.pdf")));
        assertFalse(Files.exists(uploadRoot.resolve("published/bbb.pdf")));
        assertEquals("QUARANTINED", inserted.get(0).getStorageState());
        assertEquals("UNAVAILABLE", inserted.get(0).getScanStatus());
    }

    @Test
    void 感染ファイルは公開せずquarantineへ残す() throws Exception {
        Files.write(uploadRoot.resolve("ccc.pdf"), validPdf());
        List<FileSecurityMetadata> inserted = new ArrayList<>();

        service(Set.of("ccc.pdf"), FileScanResult.infected("test-1"), inserted).migrate();

        assertTrue(Files.exists(uploadRoot.resolve("quarantine/ccc.pdf")));
        assertFalse(Files.exists(uploadRoot.resolve("published/ccc.pdf")));
        assertEquals("INFECTED", inserted.get(0).getScanStatus());
    }

    @Test
    void pdfを装ったplainTextはcleanScannerでも公開しない() throws Exception {
        Files.writeString(uploadRoot.resolve("fake.pdf"), "plain text");
        List<FileSecurityMetadata> inserted = new ArrayList<>();

        LegacyUploadMigrationService.Result result =
                service(Set.of("fake.pdf"), FileScanResult.clean("test-1"), inserted).migrate();

        assertEquals(0, result.published());
        assertEquals(1, result.quarantined());
        assertEquals("UNAVAILABLE", inserted.get(0).getScanStatus());
        assertTrue(Files.exists(uploadRoot.resolve("quarantine/fake.pdf")));
    }

    @Test
    void 種別上限を超える既存ファイルはcleanScannerでも公開しない() throws Exception {
        Files.write(uploadRoot.resolve("oversize.png"),
                new byte[(int) FileKind.PHOTO.getMaxBytes() + 1]);
        List<FileSecurityMetadata> inserted = new ArrayList<>();

        LegacyUploadMigrationService.Result result =
                service(Set.of("oversize.png"), FileScanResult.clean("test-1"), inserted).migrate();

        assertEquals(0, result.published());
        assertEquals(1, result.quarantined());
        assertEquals("UNAVAILABLE", inserted.get(0).getScanStatus());
    }

    @Test
    void どのproviderも参照しない実体は移行せず孤児清理へ委ねる() throws Exception {
        Files.writeString(uploadRoot.resolve("orphan.pdf"), "orphan");
        List<FileSecurityMetadata> inserted = new ArrayList<>();

        LegacyUploadMigrationService.Result result =
                service(Set.of(), FileScanResult.clean("test-1"), inserted).migrate();

        assertEquals(0, result.published());
        assertTrue(Files.exists(uploadRoot.resolve("orphan.pdf")));
        assertTrue(inserted.isEmpty());
    }

    @Test
    void 種別を判定できない拡張子は推測して公開しない() throws Exception {
        Files.writeString(uploadRoot.resolve("ddd.bin"), "unknown");
        List<FileSecurityMetadata> inserted = new ArrayList<>();

        LegacyUploadMigrationService.Result result =
                service(Set.of("ddd.bin"), FileScanResult.clean("test-1"), inserted).migrate();

        assertEquals(0, result.published());
        assertEquals(1, result.skipped());
        assertTrue(Files.exists(uploadRoot.resolve("ddd.bin")));
        assertTrue(inserted.isEmpty());
    }

    @Test
    void 既にmetadataがある実体は二重に移行しない() throws Exception {
        Files.writeString(uploadRoot.resolve("eee.pdf"), "already migrated");
        List<FileSecurityMetadata> inserted = new ArrayList<>();
        LegacyUploadMigrationService service = service(Set.of("eee.pdf"),
                FileScanResult.clean("test-1"), inserted, true);

        LegacyUploadMigrationService.Result result = service.migrate();

        assertEquals(0, result.published());
        assertTrue(inserted.isEmpty());
        assertTrue(Files.exists(uploadRoot.resolve("eee.pdf")));
    }

    private LegacyUploadMigrationService service(Set<String> referenced, FileScanResult scanResult,
                                                 List<FileSecurityMetadata> inserted) {
        return service(referenced, scanResult, inserted, false);
    }

    @SuppressWarnings("unchecked")
    private LegacyUploadMigrationService service(Set<String> referenced, FileScanResult scanResult,
                                                 List<FileSecurityMetadata> inserted,
                                                 boolean metadataAlreadyExists) {
        UploadProperties properties = new UploadProperties();
        properties.setBasePath(uploadRoot.toString());

        FileSecurityMetadataMapper metadataMapper = mock(FileSecurityMetadataMapper.class);
        when(metadataMapper.selectByStoredName(eq("default"), anyString()))
                .thenReturn(metadataAlreadyExists ? new FileSecurityMetadata() : null);
        when(metadataMapper.insert(any(FileSecurityMetadata.class))).thenAnswer(invocation -> {
            inserted.add(invocation.getArgument(0));
            return 1;
        });

        FileScanner scanner = mock(FileScanner.class);
        when(scanner.scan(any(), any())).thenReturn(scanResult);

        ObjectProvider<FileSecurityMetadataMapper> metadataProvider = mock(ObjectProvider.class);
        when(metadataProvider.getIfAvailable()).thenReturn(metadataMapper);
        ObjectProvider<FileScanner> scannerProvider = mock(ObjectProvider.class);
        when(scannerProvider.getIfAvailable()).thenReturn(scanner);

        FileReferenceProvider referenceProvider = () -> referenced;
        return new LegacyUploadMigrationService(properties, List.of(referenceProvider),
                metadataProvider, scannerProvider);
    }

    private byte[] validPdf() {
        return "%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }
}
