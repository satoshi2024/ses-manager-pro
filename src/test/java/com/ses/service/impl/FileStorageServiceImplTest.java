package com.ses.service.impl;

import com.ses.common.enums.FileKind;
import com.ses.common.exception.BusinessException;
import com.ses.config.UploadProperties;
import com.ses.dto.file.StoredFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ファイル保存サービスの単体テスト（P8 Task3）。
 * 正常保存・サイズ超過・拡張子違反・パストラバーサル拒否を検証する。
 */
class FileStorageServiceImplTest {

    private FileStorageServiceImpl service;
    private Path tempDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        this.tempDir = tempDir;
        UploadProperties props = new UploadProperties();
        props.setBasePath(tempDir.toString());
        service = new FileStorageServiceImpl(props);
    }

    @Test
    void store_正常な写真は保存されload可能になる() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});

        StoredFile stored = service.store(file, FileKind.PHOTO);

        assertNotNull(stored.getStoredName());
        assertTrue(stored.getStoredName().endsWith(".png"), "拡張子が維持されること");
        assertEquals("avatar.png", stored.getOriginalName());
        assertEquals(8, stored.getSize());

        Resource resource = service.load(stored.getStoredName());
        assertTrue(resource.exists(), "保存直後にloadで取得できること");
    }

    @Test
    void store_サイズ超過はBusinessException() {
        byte[] tooBig = new byte[(int) (2L * 1024 * 1024 + 1)]; // 2MB+1
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.png", "image/png", tooBig);

        assertThrows(BusinessException.class, () -> service.store(file, FileKind.PHOTO));
    }

    @Test
    void store_許可されない拡張子はBusinessException() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.exe", "application/octet-stream", new byte[]{1});

        assertThrows(BusinessException.class, () -> service.store(file, FileKind.SKILL_SHEET));
    }

    @Test
    void store_スキルシートのpdfは保存できる() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "skill.pdf", "application/pdf", "%PDF-1.7\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        StoredFile stored = service.store(file, FileKind.SKILL_SHEET);
        assertTrue(stored.getStoredName().endsWith(".pdf"));
    }

    @Test
    void load_パストラバーサルは拒否される() {
        assertThrows(BusinessException.class, () -> service.load("../secret.txt"));
        assertThrows(BusinessException.class, () -> service.load("sub/dir.png"));
        assertThrows(BusinessException.class, () -> service.load("..\\win.txt"));
    }

    @Test
    void load_存在しないファイルはBusinessException() {
        assertThrows(BusinessException.class, () -> service.load("notexist.png"));
    }

    @Test
    void store_拡張子だけ一致する偽装ファイルは拒否される() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.png", "image/png", new byte[]{1, 2, 3, 4});

        assertThrows(BusinessException.class, () -> service.store(file, FileKind.PHOTO));
    }

    @Test
    void store_EICAR相当fixtureは感染扱いで拒否される() {
        byte[] eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);

        assertThrows(BusinessException.class,
                () -> service.store(eicar, "malware.txt", FileKind.PROJECT_EMAIL));
        assertEquals(1, regularFileCount(tempDir.resolve("quarantine")));
    }

    @Test
    void store_scannerUnavailableはfailClosedする() throws java.io.IOException {
        UploadProperties props = new UploadProperties();
        Path unavailableDir = java.nio.file.Files.createTempDirectory("file-scan-unavailable");
        props.setBasePath(unavailableDir.toString());
        FileStorageServiceImpl unavailable = new FileStorageServiceImpl(
                props, new com.ses.service.security.impl.UnavailableFileScanner(), null);

        assertThrows(BusinessException.class,
                () -> unavailable.store("safe text".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        "safe.txt", FileKind.PROJECT_EMAIL));
        assertEquals(1, regularFileCount(unavailableDir.resolve("quarantine")));
    }

    @Test
    void rescan_cleanになったquarantineファイルを公開する() throws java.io.IOException {
        String storedName = "quarantined.txt";
        java.nio.file.Files.createDirectories(tempDir.resolve("quarantine"));
        java.nio.file.Files.writeString(tempDir.resolve("quarantine").resolve(storedName), "safe text");
        com.ses.entity.FileSecurityMetadata metadata = new com.ses.entity.FileSecurityMetadata();
        metadata.setId(10L);
        metadata.setStoredName(storedName);
        metadata.setFileKind(FileKind.PROJECT_EMAIL.name());
        metadata.setStorageState("QUARANTINED");
        com.ses.mapper.FileSecurityMetadataMapper mapper = org.mockito.Mockito.mock(
                com.ses.mapper.FileSecurityMetadataMapper.class);
        org.mockito.Mockito.when(mapper.selectByStoredName("default", storedName)).thenReturn(metadata);
        org.mockito.Mockito.when(mapper.updateScanResult(org.mockito.ArgumentMatchers.eq(10L),
                org.mockito.ArgumentMatchers.eq("PUBLISHED"), org.mockito.ArgumentMatchers.eq("CLEAN"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull())).thenReturn(1);
        com.ses.service.security.FileScanner scanner = (path, kind) ->
                com.ses.service.security.FileScanResult.clean("test-scanner");
        UploadProperties props = new UploadProperties();
        props.setBasePath(tempDir.toString());
        FileStorageServiceImpl rescannable = new FileStorageServiceImpl(props, scanner, mapper);

        assertTrue(rescannable.rescan(storedName));
        assertTrue(java.nio.file.Files.exists(tempDir.resolve("published").resolve(storedName)));
        assertFalse(java.nio.file.Files.exists(tempDir.resolve("quarantine").resolve(storedName)));
    }

    private long regularFileCount(Path directory) {
        try (java.util.stream.Stream<Path> files = java.nio.file.Files.list(directory)) {
            return files.filter(java.nio.file.Files::isRegularFile).count();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
