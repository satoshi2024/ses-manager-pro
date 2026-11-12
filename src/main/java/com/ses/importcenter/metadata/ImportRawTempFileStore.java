package com.ses.importcenter.metadata;

import com.ses.common.util.LogRedaction;
import com.ses.config.UploadProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * raw CSV ingressの一時ファイル置場。
 *
 * <p>upload rootの{@code quarantine}直下（FileStorageServiceImplの未scan領域と同じ）に作成し、どの
 * FileReferenceProviderにも登録しない。そのため削除に失敗しても、既存の孤児ファイル清理
 * {@code FileCleanupServiceImpl.cleanupOrphanFiles()}で回収される。この清理は次の二つのschedulerから呼ばれる。
 * <ul>
 *   <li>{@code DocumentArchiveFileCleanupScheduler}：毎日02:00</li>
 *   <li>{@code FileCleanupScheduler}：毎週日曜03:00</li>
 * </ul>
 * 最終更新から{@code cleanup-safety-hours}（既定24時間）経過したものだけが対象のため、残存は最長で
 * おおむね「safety時間＋1日」となる。いずれもShedLockで1instanceだけが実行するため、upload rootが
 * instanceごとのlocal directoryである多instance構成では、実行しなかったinstanceの残存は回収されない。
 *
 * <p>権限：POSIXでは{@code Files.createTempFile}によりowner限定（0600）で作成される。Windowsでは
 * POSIX権限が使えず、{@code quarantine} directoryのACLを継承するため、同directoryのACLを運用側で
 * owner（実行account）限定にしておく必要がある。
 *
 * <p>削除失敗は黙殺せず、日本語ログとmetricで通知し、可能ならraw内容を0 byteへ切り詰める。
 */
@Component
public class ImportRawTempFileStore {
    static final String PREFIX = "import-raw-";
    static final String SUFFIX = ".csv";
    static final String CLEANUP_FAILURE_METRIC = "ses.import.raw.temp.cleanup.failures";
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportRawTempFileStore.class);

    private final Path directory;
    private final MeterRegistry meterRegistry;
    private final PathDeleter deleter;

    @Autowired
    public ImportRawTempFileStore(UploadProperties uploadProperties, ObjectProvider<MeterRegistry> meterRegistry) {
        this(Paths.get(uploadProperties.getBasePath()).toAbsolutePath().normalize().resolve("quarantine"),
                meterRegistry.getIfAvailable(), Files::deleteIfExists);
    }

    /** test用。削除処理を差し替えて削除失敗分岐を再現する。 */
    ImportRawTempFileStore(Path directory, MeterRegistry meterRegistry, PathDeleter deleter) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.meterRegistry = meterRegistry;
        this.deleter = Objects.requireNonNull(deleter, "deleter");
    }

    /**
     * 孤児清理の走査対象（upload root直下のquarantine、非再帰）へ一時ファイルを作る。
     * POSIXではowner限定権限、Windowsではdirectory ACLの継承となる。file名は乱数由来でPIIを含まない。
     */
    Path create() throws IOException {
        Files.createDirectories(directory);
        return Files.createTempFile(directory, PREFIX, SUFFIX);
    }

    /**
     * 一時ファイルを削除する。例外は投げず、呼出元の成功結果や元の失敗を隠さない。
     *
     * @return 削除できた（または元々存在しない）場合true。失敗時はfalseで、孤児清理による回収待ちとなる。
     */
    boolean deleteOrReport(Path file) {
        if (file == null) return true;
        Throwable failure;
        try {
            deleter.delete(file);
            return true;
        } catch (IOException | RuntimeException deleteFailure) {
            failure = deleteFailure;
        }
        boolean truncated = truncateQuietly(file);
        if (meterRegistry != null) {
            try {
                Counter.builder(CLEANUP_FAILURE_METRIC)
                        .description("raw CSV取込一時ファイルの削除失敗件数（孤児ファイル清理で回収）")
                        .register(meterRegistry)
                        .increment();
            } catch (RuntimeException metricFailure) {
                log.warn("[取込] raw CSV一時ファイル削除失敗metricの記録に失敗しました: exceptionType={}",
                        LogRedaction.exceptionType(metricFailure));
            }
        }
        // 例外messageには絶対pathが入るため出さない。file名は乱数由来でPIIを含まない。
        log.error("[取込] raw CSV一時ファイルの削除に失敗しました。孤児ファイル清理で回収されるまで残存します: "
                        + "tempFile={} contentTruncated={} exceptionType={}",
                file.getFileName(), truncated, LogRedaction.exceptionType(failure));
        return false;
    }

    Path directory() {
        return directory;
    }

    private static boolean truncateQuietly(Path file) {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(0);
            channel.force(true);
            return true;
        } catch (IOException | RuntimeException ignored) {
            // 切詰め不能でも削除失敗の通知・孤児清理による回収は変わらない。
            return false;
        }
    }

    @FunctionalInterface
    interface PathDeleter {
        void delete(Path file) throws IOException;
    }
}
