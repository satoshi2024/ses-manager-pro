package com.ses.service.impl;

import com.ses.config.UploadProperties;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.FileReferenceProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * 孤児ファイル清掃サービスの単体テスト（P8フォローアップ・提案10）。
 * 参照中ファイルの温存、安全マージン内の新しいファイルの温存、
 * 参照無し・安全マージン超過ファイルのみの削除を検証する。
 */
class FileCleanupServiceImplTest {

    private FileCleanupServiceImpl service;
    private EngineerMapper engineerMapper;
    private ProposalMapper proposalMapper;
    private Path baseDir;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        baseDir = tempDir;
        UploadProperties props = new UploadProperties();
        props.setBasePath(tempDir.toString());
        props.setCleanupSafetyHours(24);

        engineerMapper = Mockito.mock(EngineerMapper.class);
        proposalMapper = Mockito.mock(ProposalMapper.class);
        
        FileReferenceProvider p1 = Mockito.mock(FileReferenceProvider.class);
        FileReferenceProvider p2 = Mockito.mock(FileReferenceProvider.class);
        service = new FileCleanupServiceImpl(props, List.of(p1, p2));
        
        // Mock behaviors used in tests
        when(p1.referencedFileNames()).thenAnswer(inv -> engineerMapper.selectAllPhotoUrls() == null ? java.util.Collections.emptySet() : new java.util.HashSet<>(engineerMapper.selectAllPhotoUrls()));
        when(p2.referencedFileNames()).thenAnswer(inv -> proposalMapper.selectAllSkillSheetPaths() == null ? java.util.Collections.emptySet() : new java.util.HashSet<>(proposalMapper.selectAllSkillSheetPaths()));
    }

    private Path createFile(String name, Instant lastModified) throws IOException {
        Path file = baseDir.resolve(name);
        Files.writeString(file, "dummy");
        Files.setLastModifiedTime(file, FileTime.from(lastModified));
        return file;
    }

    @Test
    void cleanupOrphanFiles_参照中ファイルは削除しない() throws IOException {
        createFile("referenced.png", Instant.now().minus(48, ChronoUnit.HOURS));

        when(engineerMapper.selectAllPhotoUrls()).thenReturn(List.of("referenced.png"));
        when(proposalMapper.selectAllSkillSheetPaths()).thenReturn(List.of());

        int deleted = service.cleanupOrphanFiles();

        assertEquals(0, deleted);
        assertTrue(Files.exists(baseDir.resolve("referenced.png")));
    }

    @Test
    void cleanupOrphanFiles_安全マージン内の新しいファイルは削除しない() throws IOException {
        createFile("just-uploaded.png", Instant.now().minus(1, ChronoUnit.HOURS));

        when(engineerMapper.selectAllPhotoUrls()).thenReturn(List.of());
        when(proposalMapper.selectAllSkillSheetPaths()).thenReturn(List.of());

        int deleted = service.cleanupOrphanFiles();

        assertEquals(0, deleted, "アップロード直後で紐付け前の可能性があるため削除しない");
        assertTrue(Files.exists(baseDir.resolve("just-uploaded.png")));
    }

    @Test
    void cleanupOrphanFiles_参照無しかつ安全マージン超過は削除する() throws IOException {
        createFile("orphan.png", Instant.now().minus(48, ChronoUnit.HOURS));

        when(engineerMapper.selectAllPhotoUrls()).thenReturn(List.of());
        when(proposalMapper.selectAllSkillSheetPaths()).thenReturn(List.of());

        int deleted = service.cleanupOrphanFiles();

        assertEquals(1, deleted);
        assertFalse(Files.exists(baseDir.resolve("orphan.png")));
    }

    @Test
    void cleanupOrphanFiles_提案のスキルシートも参照として認識する() throws IOException {
        createFile("skillsheet.pdf", Instant.now().minus(48, ChronoUnit.HOURS));

        when(engineerMapper.selectAllPhotoUrls()).thenReturn(List.of());
        when(proposalMapper.selectAllSkillSheetPaths()).thenReturn(List.of("skillsheet.pdf"));

        int deleted = service.cleanupOrphanFiles();

        assertEquals(0, deleted);
        assertTrue(Files.exists(baseDir.resolve("skillsheet.pdf")));
    }

    @Test
    void cleanupOrphanFiles_ディレクトリが存在しない場合は何もしない() {
        UploadProperties props = new UploadProperties();
        props.setBasePath(baseDir.resolve("does-not-exist").toString());
        FileCleanupServiceImpl svc = new FileCleanupServiceImpl(props, List.of());

        assertEquals(0, svc.cleanupOrphanFiles());
    }
}
