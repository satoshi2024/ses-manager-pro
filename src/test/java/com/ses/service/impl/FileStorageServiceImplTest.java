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

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        byte[] png = image("png");
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", png);

        StoredFile stored = service.store(file, FileKind.PHOTO);

        assertNotNull(stored.getStoredName());
        assertTrue(stored.getStoredName().endsWith(".png"), "拡張子が維持されること");
        assertEquals("avatar.png", stored.getOriginalName());
        assertEquals(png.length, stored.getSize());

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
                "file", "skill.pdf", "application/pdf",
                "%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII));

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
    void store_拡張子とMIMEが不一致なら拒否される() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "fake.docx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                ooxml("docx"));

        assertThrows(BusinessException.class, () -> service.store(file, FileKind.SKILL_SHEET));
    }

    @Test
    void store_汎用zipをdocxとして偽装した場合は拒否される() {
        byte[] genericZip = zip(Map.of("note.txt", "hello"));
        MockMultipartFile file = new MockMultipartFile("file", "fake.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", genericZip);

        assertThrows(BusinessException.class, () -> service.store(file, FileKind.SKILL_SHEET));
    }

    @Test
    void store_xlsx内部構造をdocxとして偽装した場合は拒否される() {
        MockMultipartFile file = new MockMultipartFile("file", "fake.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ooxml("xlsx"));

        assertThrows(BusinessException.class, () -> service.store(file, FileKind.SKILL_SHEET));
    }

    @Test
    void store_正しいdocx内部構造は保存できる() {
        MockMultipartFile file = new MockMultipartFile("file", "skill.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ooxml("docx"));

        assertTrue(service.store(file, FileKind.SKILL_SHEET).getStoredName().endsWith(".docx"));
    }

    @Test
    void store_末尾に別形式を連結したOOXMLは拒否される() {
        byte[] original = ooxml("xlsx");
        byte[] polyglot = java.util.Arrays.copyOf(original, original.length + 8);
        System.arraycopy("MZPAYLOAD".getBytes(StandardCharsets.US_ASCII), 0, polyglot, original.length, 8);
        MockMultipartFile file = new MockMultipartFile("file", "skill.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", polyglot);

        assertThrows(BusinessException.class, () -> service.store(file, FileKind.SKILL_SHEET));
    }

    @Test
    void store_壊れた画像とEOFのないPDFは拒否される() {
        MockMultipartFile image = new MockMultipartFile("file", "broken.png", "image/png",
                new byte[]{(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a});
        MockMultipartFile pdf = new MockMultipartFile("file", "broken.pdf", "application/pdf",
                "%PDF-1.7\ntruncated".getBytes(StandardCharsets.US_ASCII));

        assertThrows(BusinessException.class, () -> service.store(image, FileKind.PHOTO));
        assertThrows(BusinessException.class, () -> service.store(pdf, FileKind.SKILL_SHEET));
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

    private byte[] image(String format) {
        try {
            BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new AssertionError("image writer unavailable");
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private byte[] ooxml(String type) {
        Map<String, String> entries = new LinkedHashMap<>();
        String mainType = "docx".equals(type)
                ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml";
        entries.put("[Content_Types].xml", "<Types><Override ContentType=\"" + mainType + "\"/></Types>");
        entries.put("_rels/.rels", "<Relationships/>");
        entries.put("docx".equals(type) ? "word/document.xml" : "xl/workbook.xml", "<root/>");
        return zip(entries);
    }

    private byte[] zip(Map<String, String> entries) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    zip.putNextEntry(new ZipEntry(entry.getKey()));
                    zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }
}
