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

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        UploadProperties props = new UploadProperties();
        props.setBasePath(tempDir.toString());
        service = new FileStorageServiceImpl(props);
    }

    @Test
    void store_正常な写真は保存されload可能になる() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[]{1, 2, 3, 4});

        StoredFile stored = service.store(file, FileKind.PHOTO);

        assertNotNull(stored.getStoredName());
        assertTrue(stored.getStoredName().endsWith(".png"), "拡張子が維持されること");
        assertEquals("avatar.png", stored.getOriginalName());
        assertEquals(4, stored.getSize());

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
                "file", "skill.pdf", "application/pdf", new byte[]{1, 2, 3});

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
}
