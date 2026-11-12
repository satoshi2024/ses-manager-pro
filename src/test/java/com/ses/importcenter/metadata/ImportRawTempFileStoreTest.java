package com.ses.importcenter.metadata;

import com.ses.config.UploadProperties;
import com.ses.service.FileReferenceProvider;
import com.ses.service.impl.FileCleanupServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** raw CSV一時ファイルが既存の孤児ファイル清理で回収できる場所・命名であることを確認する。 */
class ImportRawTempFileStoreTest {
    @TempDir Path uploadRoot;

    @Test
    void springConstructorPlacesTempFilesInTheOrphanCleanupQuarantineDirectory() throws Exception {
        ImportRawTempFileStore store = springStore();

        Path file = store.create();

        assertEquals(uploadRoot.toAbsolutePath().normalize().resolve("quarantine"), store.directory());
        assertEquals(store.directory(), file.getParent());
        assertTrue(file.getFileName().toString().startsWith(ImportRawTempFileStore.PREFIX));
        assertTrue(file.getFileName().toString().endsWith(ImportRawTempFileStore.SUFFIX));
        assertTrue(store.deleteOrReport(file));
        assertFalse(Files.exists(file));
    }

    @Test
    void undeletedLeftoverOlderThanSafetyWindowIsReclaimedByExistingOrphanCleanup() throws Exception {
        ImportRawTempFileStore store = springStore();
        Path leftover = store.create();
        Files.write(leftover, "externalKey,companyName\nC-SYN-1,Synthetic\n".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(leftover, FileTime.from(Instant.now().minus(25, ChronoUnit.HOURS)));

        int deleted = orphanCleanup().cleanupOrphanFiles();

        assertEquals(1, deleted);
        assertFalse(Files.exists(leftover));
    }

    @Test
    void inFlightTempFileIsNotReclaimedBeforeSafetyWindow() throws Exception {
        ImportRawTempFileStore store = springStore();
        Path inFlight = store.create();

        int deleted = orphanCleanup().cleanupOrphanFiles();

        assertEquals(0, deleted);
        assertTrue(Files.exists(inFlight));
        assertTrue(store.deleteOrReport(inFlight));
    }

    @Test
    void alreadyMissingFileIsTreatedAsDeleted() {
        ImportRawTempFileStore store = springStore();

        assertTrue(store.deleteOrReport(store.directory().resolve(ImportRawTempFileStore.PREFIX + "missing.csv")));
        assertTrue(store.deleteOrReport(null));
    }

    @Test
    void deleteFailureWithoutTruncationOrMeterRegistryIsStillReportedWithoutThrowing() {
        Path directory = uploadRoot.resolve("quarantine");
        ImportRawTempFileStore store = new ImportRawTempFileStore(directory, null, file -> {
            throw new java.nio.file.AccessDeniedException(file.toString());
        });
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ImportRawTempFileStore.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            // 存在しないfileは切詰めもできない（contentTruncated=false）。MeterRegistryがなくても例外にしない。
            boolean deleted = store.deleteOrReport(directory.resolve(ImportRawTempFileStore.PREFIX + "gone.csv"));

            assertFalse(deleted);
            String text = logs.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + right + "\n");
            assertTrue(text.contains("contentTruncated=false"));
            assertTrue(text.contains(ImportRawTempFileStore.PREFIX + "gone.csv"));
            assertFalse(text.contains(uploadRoot.toString()), "絶対pathをログへ出さない");
        } finally {
            logger.detachAppender(logs);
        }
    }

    private ImportRawTempFileStore springStore() {
        @SuppressWarnings("unchecked")
        ObjectProvider<MeterRegistry> registry = mock(ObjectProvider.class);
        return new ImportRawTempFileStore(uploadProperties(), registry);
    }

    private FileCleanupServiceImpl orphanCleanup() {
        List<FileReferenceProvider> providers = List.of();
        return new FileCleanupServiceImpl(uploadProperties(), providers);
    }

    private UploadProperties uploadProperties() {
        UploadProperties properties = new UploadProperties();
        properties.setBasePath(uploadRoot.toString());
        properties.setCleanupSafetyHours(24);
        return properties;
    }
}
