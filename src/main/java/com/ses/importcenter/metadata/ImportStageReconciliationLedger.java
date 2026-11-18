package com.ses.importcenter.metadata;

import com.ses.common.util.LogRedaction;
import com.ses.config.UploadProperties;
import com.ses.service.impl.StagedImportDocument;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 後から行方を照合しなければならないraw CSV stage object（文書storage上の取込原本）の台帳。
 *
 * <p>対象は次の二種類で、いずれもmetadata commitの成否と無関係に文書storageへobjectが残り得る。
 * <ul>
 *   <li>{@link Reason#OUTCOME_UNKNOWN}：COMMIT処理自体が失敗し結果が確定せず、readbackでも本stageの行が見えないため
 *       照合用に保全したstage（A-NF06-2）。</li>
 *   <li>{@link Reason#DISCARD_FAILED}：未commitが確定しているのに補償削除が失敗したstage。別winnerを返す途中の
 *       失敗は500で返す（B-NF06-5）が、object自体は本台帳で回収対象にする。</li>
 * </ul>
 *
 * <p>DDLを追加しないため、upload root直下の専用directory {@value #DIRECTORY} へstage一件につき小さなproperties
 * fileを一つ置く。内容はtenant ID・storage key・operation ID・理由・記録時刻と、結果不明のCOMMITを送った接続の
 * server側ID（取得できた場合だけ）で、CSV本文・file名・利用者情報は
 * 含めない。{@code FileCleanupServiceImpl}の孤児清掃はupload root・quarantine・publishedの直下だけを非再帰で走査し
 * directoryを対象にしないため、台帳fileは清掃されない。台帳fileはdownload APIから参照されない（参照名を持たない）ため、
 * FileReferenceProvider／FileScopeValidationServiceへの登録対象でもない。
 *
 * <p>記録は応答や元の失敗を上書きしないよう例外を投げない。書込みに失敗した場合も日本語ログと
 * metric {@value #LEDGER_FAILURE_METRIC} で通知する（その場合の回収は運用runbookの手作業になる）。
 */
@Component
public class ImportStageReconciliationLedger {
    static final String DIRECTORY = "import-stage-ledger";
    static final String SUFFIX = ".properties";
    /** COMMIT結果不明で保全したstage件数。増加は照合待ちの発生を意味する（アラート対象）。 */
    static final String RETAINED_METRIC = "ses.import.stage.retained";
    /**
     * stage補償削除の失敗件数。tag {@code path}は{@code winner}（成功応答の途中、500）、{@code failure}、
     * {@code stage}（stage処理の途中失敗時の補償。N6-2）。
     */
    static final String DISCARD_FAILURE_METRIC = "ses.import.stage.discard.failures";
    /** 台帳fileの書込失敗件数。増加時は自動回収されないため手作業の照合が必要（アラート対象）。 */
    static final String LEDGER_FAILURE_METRIC = "ses.import.stage.ledger.failures";
    private static final Pattern OPERATION_ID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ImportStageReconciliationLedger.class);

    /** 台帳へ記録する理由。値はfileへそのまま書くため変更しない。 */
    enum Reason { OUTCOME_UNKNOWN, DISCARD_FAILED }

    /**
     * 台帳の一件。{@code file}は削除用で、内容は他の項目に限る。{@code commitConnectionId}は結果不明のCOMMITを送った接続の
     * server側ID、{@code commitServerUuid}はそのserverの{@code @@server_uuid}（どちらも取得できた場合だけ。b-14）で、
     * 照合は同じserverが記録後に再起動していなければ、そのserver threadが文を実行している間は判定しない。
     */
    record Entry(Path file, String tenantId, String storageKey, String operationId, Reason reason,
                 Instant recordedAt, Long commitConnectionId, String commitServerUuid) {
        Entry(Path file, String tenantId, String storageKey, String operationId, Reason reason, Instant recordedAt) {
            this(file, tenantId, storageKey, operationId, reason, recordedAt, null, null);
        }
    }

    /** 台帳fileのCOMMIT接続IDの項目名。 */
    static final String COMMIT_CONNECTION_ID = "commitConnectionId";
    /** 台帳fileのCOMMIT接続のserver識別（{@code @@server_uuid}）の項目名。 */
    static final String COMMIT_SERVER_UUID = "commitServerUuid";

    private final Path directory;
    private final MeterRegistry meterRegistry;
    private final Clock clock;

    @Autowired
    public ImportStageReconciliationLedger(UploadProperties uploadProperties,
                                           ObjectProvider<MeterRegistry> meterRegistry) {
        this(Paths.get(uploadProperties.getBasePath()).toAbsolutePath().normalize().resolve(DIRECTORY),
                meterRegistry.getIfAvailable(), Clock.systemUTC());
    }

    /** test用。台帳directory・metric・時刻を差し替える。 */
    ImportStageReconciliationLedger(Path directory, MeterRegistry meterRegistry, Clock clock) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.meterRegistry = meterRegistry;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** COMMIT結果不明で保全したstageを記録する。例外は投げない。 */
    boolean recordRetained(String tenantId, StagedImportDocument staged) {
        return recordRetained(tenantId, staged, null, null);
    }

    /** COMMIT結果不明で保全したstageを、COMMIT接続のserver側ID（無ければnull）と一緒に記録する。例外は投げない。 */
    boolean recordRetained(String tenantId, StagedImportDocument staged, Long commitConnectionId) {
        return recordRetained(tenantId, staged, commitConnectionId, null);
    }

    /**
     * COMMIT結果不明で保全したstageを、COMMITを送った接続のserver側IDとそのserverの{@code @@server_uuid}
     * （どちらも無ければnull）と一緒に記録する。例外は投げない。
     */
    boolean recordRetained(String tenantId, StagedImportDocument staged, Long commitConnectionId, String commitServerUuid) {
        increment(RETAINED_METRIC, null);
        return write(tenantId, staged, Reason.OUTCOME_UNKNOWN, commitConnectionId, commitServerUuid);
    }

    /**
     * 未commit確定のstageで補償削除に失敗したことを記録する。例外は投げない。
     *
     * @param path {@code winner}（別winnerを返す成功応答の途中）、{@code failure}（元の失敗を返す途中）、
     *             または{@code stage}（stage処理の途中失敗時の補償）
     */
    boolean recordDiscardFailure(String tenantId, StagedImportDocument staged, String path) {
        increment(DISCARD_FAILURE_METRIC, path);
        // 未commitが確定しているため、COMMIT接続の終了を待つ必要は無い（接続IDを持たない）。
        return write(tenantId, staged, Reason.DISCARD_FAILED, null, null);
    }

    /** 台帳の全件を記録時刻順に返す。読めないfileはログに残して飛ばす（削除しない）。 */
    List<Entry> entries() {
        if (!Files.isDirectory(directory)) return List.of();
        List<Entry> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                Entry entry = read(file);
                if (entry != null) result.add(entry);
            }
        } catch (IOException | RuntimeException failure) {
            log.error("[取込] stage照合台帳の走査に失敗しました: exceptionType={}",
                    LogRedaction.exceptionType(failure));
            return List.copyOf(result);
        }
        result.sort(Comparator.comparing(Entry::recordedAt));
        return List.copyOf(result);
    }

    /** 照合を終えた一件を台帳から外す。 */
    void remove(Entry entry) throws IOException {
        if (entry == null || entry.file() == null) return;
        Path file = entry.file().toAbsolutePath().normalize();
        if (!directory.equals(file.getParent())) {
            throw new IOException("stage照合台帳の外にあるfileは削除しません");
        }
        Files.deleteIfExists(file);
    }

    Path directory() {
        return directory;
    }

    private boolean write(String tenantId, StagedImportDocument staged, Reason reason, Long commitConnectionId,
                          String commitServerUuid) {
        if (staged == null || staged.storageKey() == null || staged.storageKey().isBlank()) {
            log.error("[取込] storage keyの無いstageは照合台帳へ記録できません: reason={}", reason);
            increment(LEDGER_FAILURE_METRIC, null);
            return false;
        }
        String operationId = staged.operationId();
        String fileId = operationId != null && OPERATION_ID.matcher(operationId).matches()
                ? operationId : UUID.randomUUID().toString();
        Properties properties = new Properties();
        properties.setProperty("tenantId", tenantId == null ? "" : tenantId);
        properties.setProperty("storageKey", staged.storageKey());
        properties.setProperty("operationId", operationId == null ? "" : operationId);
        properties.setProperty("reason", reason.name());
        properties.setProperty("recordedAtEpochMillis", Long.toString(clock.millis()));
        if (commitConnectionId != null && commitConnectionId > 0) {
            properties.setProperty(COMMIT_CONNECTION_ID, Long.toString(commitConnectionId));
            if (commitServerUuid != null && ImportCommitConnectionIdentity.SERVER_UUID.matcher(commitServerUuid).matches()) {
                properties.setProperty(COMMIT_SERVER_UUID, commitServerUuid.toLowerCase(java.util.Locale.ROOT));
            }
        }
        Path temp = null;
        try {
            Files.createDirectories(directory);
            temp = Files.createTempFile(directory, "ledger-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temp)) {
                properties.store(output, null);
            }
            Path target = directory.resolve(fileId + SUFFIX);
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            temp = null;
            log.warn("[取込] stageを照合台帳へ記録しました。自動照合で回収またはcommit済みと確定するまで残ります: "
                    + "operationId={} storageKey={} reason={}", operationId, staged.storageKey(), reason);
            return true;
        } catch (IOException | RuntimeException failure) {
            increment(LEDGER_FAILURE_METRIC, null);
            // 例外messageには絶対pathが入るため出さない。storage keyは乱数由来でPIIを含まない。
            log.error("[取込] stage照合台帳への記録に失敗しました。手作業の照合が必要です: "
                            + "operationId={} storageKey={} reason={} exceptionType={}", operationId,
                    staged.storageKey(), reason, LogRedaction.exceptionType(failure));
            return false;
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 一時fileは*.tmpのため照合対象にならない。次回以降の手作業での清掃に委ねる。
                }
            }
        }
    }

    private Entry read(Path file) {
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            String storageKey = properties.getProperty("storageKey", "");
            String recordedAt = properties.getProperty("recordedAtEpochMillis", "");
            Reason reason = Reason.valueOf(properties.getProperty("reason", ""));
            if (storageKey.isBlank()) throw new IllegalArgumentException("storageKey");
            // 接続IDの項目が壊れていれば読めないfileとして扱い、自動回収の対象にしない（時間だけで消さない）。
            String connection = properties.getProperty(COMMIT_CONNECTION_ID);
            Long commitConnectionId = connection == null ? null : Long.valueOf(Long.parseLong(connection.trim()));
            if (commitConnectionId != null && commitConnectionId < 1) {
                throw new IllegalArgumentException(COMMIT_CONNECTION_ID);
            }
            // server識別が壊れていても同じ扱い（別serverと誤って判定し、時間だけで消さない）。
            String serverUuid = properties.getProperty(COMMIT_SERVER_UUID);
            if (serverUuid != null && (commitConnectionId == null
                    || !ImportCommitConnectionIdentity.SERVER_UUID.matcher(serverUuid.trim()).matches())) {
                throw new IllegalArgumentException(COMMIT_SERVER_UUID);
            }
            return new Entry(file, properties.getProperty("tenantId", ""), storageKey,
                    properties.getProperty("operationId", ""), reason,
                    Instant.ofEpochMilli(Long.parseLong(recordedAt)), commitConnectionId,
                    serverUuid == null ? null : serverUuid.trim().toLowerCase(java.util.Locale.ROOT));
        } catch (IOException | RuntimeException failure) {
            log.error("[取込] stage照合台帳のfileを読めないため飛ばします（削除しません）: file={} exceptionType={}",
                    file.getFileName(), LogRedaction.exceptionType(failure));
            return null;
        }
    }

    private void increment(String metric, String path) {
        if (meterRegistry == null) return;
        try {
            Counter.Builder builder = Counter.builder(metric).description("NF-06取込stageの照合・補償に関する件数");
            if (path != null) builder.tag("path", path);
            builder.register(meterRegistry).increment();
        } catch (RuntimeException metricFailure) {
            log.warn("[取込] stage照合metricの記録に失敗しました: metric={} exceptionType={}", metric,
                    LogRedaction.exceptionType(metricFailure));
        }
    }
}
