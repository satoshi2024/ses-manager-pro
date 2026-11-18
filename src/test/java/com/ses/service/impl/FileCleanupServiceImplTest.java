package com.ses.service.impl;

import com.ses.config.UploadProperties;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.retention.hold.PrivacyHoldFileReferenceProvider;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
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
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
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
    void cleanupOrphanFiles_有効scanMetadataがある古いquarantineは再scan用に保持する() throws IOException {
        Path quarantine = Files.createDirectories(baseDir.resolve("quarantine"));
        Path file = quarantine.resolve("infected.txt");
        Files.writeString(file, "quarantined");
        Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(72, ChronoUnit.HOURS)));
        FileReferenceProvider metadataProvider = Mockito.mock(FileReferenceProvider.class);
        when(metadataProvider.referencedFileNames()).thenReturn(java.util.Set.of("infected.txt"));
        UploadProperties props = new UploadProperties();
        props.setBasePath(baseDir.toString());
        props.setCleanupSafetyHours(24);
        FileCleanupServiceImpl metadataAware = new FileCleanupServiceImpl(props, List.of(metadataProvider));

        assertEquals(0, metadataAware.cleanupOrphanFiles());
        assertTrue(Files.exists(file));
    }

    @Test
    void cleanupOrphanFiles_ディレクトリが存在しない場合は何もしない() {
        UploadProperties props = new UploadProperties();
        props.setBasePath(baseDir.resolve("does-not-exist").toString());
        FileCleanupServiceImpl svc = new FileCleanupServiceImpl(props, List.of());

        assertEquals(0, svc.cleanupOrphanFiles());
    }

    // ---- NF07 A1H-2 D10: PrivacyHoldFileReferenceProvider ----

    @Test
    void cleanupOrphanFiles_providerが例外ならその回は1件も消さず例外を返す() throws IOException {
        Path orphan = createFile("orphan.pdf", Instant.now().minus(48, ChronoUnit.HOURS));
        Files.createDirectories(baseDir.resolve("published"));
        Path published = baseDir.resolve("published").resolve("orphan-published.pdf");
        Files.writeString(published, "dummy");
        Files.setLastModifiedTime(published, FileTime.from(Instant.now().minus(48, ChronoUnit.HOURS)));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub()
                .tenant("tenant-a", PrivacyHoldTestGuards.unavailable());
        FileCleanupServiceImpl svc = new FileCleanupServiceImpl(props(), List.of(hold.fileReferenceProvider));

        assertThrows(PrivacyHoldFileReferenceProvider.PrivacyHoldCleanupAbortedException.class, svc::cleanupOrphanFiles);
        // 2つの scheduler はどちらも例外をそのまま返す（その回の清掃は中止）
        assertThrows(IllegalStateException.class, () -> new com.ses.service.scheduler.FileCleanupScheduler(svc).cleanupWeekly());
        assertThrows(IllegalStateException.class, () -> new DocumentArchiveFileCleanupScheduler(
                Mockito.mock(com.ses.service.storage.DocumentStorage.class), svc).cleanupOrphanStorageFiles());
        assertTrue(Files.exists(orphan));
        assertTrue(Files.exists(published));
    }

    @Test
    void cleanupOrphanFiles_NF07_holdの本人の実体は両schedulerで残し他の孤児は消す() throws IOException {
        createFile("held-version.pdf", Instant.now().minus(48, ChronoUnit.HOURS));
        createFile("held-resume.pdf", Instant.now().minus(48, ChronoUnit.HOURS));
        createFile("orphan-a.pdf", Instant.now().minus(48, ChronoUnit.HOURS));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub().tenant("tenant-a",
                PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7"), Set.of(), Map.of()));
        // F2 provider は論理削除済みの版も含めて解決する（文書の保存名は区切り付きで、清掃は file 名だけで照合する）
        when(hold.resolver.resolveStoredNames(eq("tenant-a"), eq(PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 7))))
                .thenReturn(Set.of("documents/2026/held-version.pdf", "held-resume.pdf"));
        FileCleanupServiceImpl svc = new FileCleanupServiceImpl(props(), List.of(hold.fileReferenceProvider));

        new DocumentArchiveFileCleanupScheduler(Mockito.mock(com.ses.service.storage.DocumentStorage.class), svc)
                .cleanupOrphanStorageFiles();
        assertFalse(Files.exists(baseDir.resolve("orphan-a.pdf")));
        createFile("orphan-b.pdf", Instant.now().minus(48, ChronoUnit.HOURS));
        new com.ses.service.scheduler.FileCleanupScheduler(svc).cleanupWeekly();

        assertTrue(Files.exists(baseDir.resolve("held-version.pdf")));
        assertTrue(Files.exists(baseDir.resolve("held-resume.pdf")));
        assertFalse(Files.exists(baseDir.resolve("orphan-a.pdf")));
        assertFalse(Files.exists(baseDir.resolve("orphan-b.pdf")));
    }

    /**
     * A1H-2 review P3-5: 本番の生成経路（{@code TenantAwareBatchRunner} 付きの constructor。tenant ごとに provider を呼ぶ）でも、
     * provider の例外は runner に飲まれず清掃のその回を中止させ、1件も消さない（runner に tenant ごとの catch が入ると
     * 「その tenant の参照を全て失って大量削除」になるため固定する）。
     */
    @Test
    void cleanupOrphanFiles_TenantAwareBatchRunner経由でもproviderの例外なら1件も消さない() throws IOException {
        Path orphan = createFile("orphan-runner.pdf", Instant.now().minus(48, ChronoUnit.HOURS));
        Path referenced = createFile("referenced-runner.pdf", Instant.now().minus(48, ChronoUnit.HOURS));
        com.ses.service.accounting.AccountingTenantInventoryProperties inventory =
                Mockito.mock(com.ses.service.accounting.AccountingTenantInventoryProperties.class);
        when(inventory.requireNormalizedIds()).thenReturn(new java.util.LinkedHashSet<>(List.of("tenant-a", "tenant-b")));
        com.ses.service.accounting.AccountingTimezoneResolver timezone =
                Mockito.mock(com.ses.service.accounting.AccountingTimezoneResolver.class);
        when(timezone.resolve(Mockito.anyString())).thenReturn(java.time.ZoneId.of("Asia/Tokyo"));
        com.ses.service.scheduler.TenantAwareBatchRunner runner =
                new com.ses.service.scheduler.TenantAwareBatchRunner(inventory, timezone);
        FileReferenceProvider other = Mockito.mock(FileReferenceProvider.class);
        when(other.referencedFileNames()).thenReturn(Set.of("referenced-runner.pdf"));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub()
                .tenant("tenant-b", PrivacyHoldTestGuards.unavailable());
        FileCleanupServiceImpl svc = new FileCleanupServiceImpl(props(), List.of(other, hold.fileReferenceProvider), runner);

        assertThrows(PrivacyHoldFileReferenceProvider.PrivacyHoldCleanupAbortedException.class, svc::cleanupOrphanFiles);

        assertTrue(Files.exists(orphan), "参照の無い file も、その回は消さない");
        assertTrue(Files.exists(referenced));
        assertEquals(1.0, hold.counter(PrivacyHoldFileReferenceProvider.ABORT_METRIC, "stage", "gate"));
        // 対照: hold を使っていなければ同じ runner 経由で参照の無い file だけを消す
        PrivacyHoldTestGuards.Stub unused = PrivacyHoldTestGuards.stub();
        assertEquals(1, new FileCleanupServiceImpl(props(), List.of(other, unused.fileReferenceProvider), runner)
                .cleanupOrphanFiles());
        assertFalse(Files.exists(orphan));
        assertTrue(Files.exists(referenced));
    }

    @Test
    void cleanupOrphanFiles_hold機構を使っていない環境では照会1回だけで参照を足さない() throws IOException {
        createFile("orphan.pdf", Instant.now().minus(48, ChronoUnit.HOURS));
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub();
        FileCleanupServiceImpl svc = new FileCleanupServiceImpl(props(), List.of(hold.fileReferenceProvider));

        assertEquals(1, svc.cleanupOrphanFiles());
        assertEquals(1, hold.gateCalls.get());
        Mockito.verifyNoInteractions(hold.resolver);
    }

    private UploadProperties props() {
        UploadProperties props = new UploadProperties();
        props.setBasePath(baseDir.toString());
        props.setCleanupSafetyHours(24);
        return props;
    }
}
