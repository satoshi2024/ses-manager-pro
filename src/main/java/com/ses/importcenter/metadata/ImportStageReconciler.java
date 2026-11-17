package com.ses.importcenter.metadata;

import com.ses.common.util.LogRedaction;
import com.ses.entity.Document;
import com.ses.entity.DocumentVersion;
import com.ses.service.DocumentService;
import com.ses.service.impl.StagedImportDocument;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * {@link ImportStageReconciliationLedger}に記録したstage objectの行方を確定し、未commitと確定したものを回収する。
 *
 * <p>判定はDB上の参照だけで行う。stageのstorage keyは乱数で、metadata commitが成功した場合に限り
 * {@code t_document_version.storage_key}として永続化される。そのため記録から{@link #SAFETY_WINDOW}以上経過した時点で
 * <ul>
 *   <li>同じstorage keyを参照する版が1件でもある（tenant・論理削除の有無を問わない）：commit済みと確定し、objectは
 *       文書台帳の管理下に残したまま台帳だけを外す。</li>
 *   <li>参照が無い：COMMITは成立しておらず以後も成立しない（透過再接続を禁止した直結構成が前提。
 *       {@link ImportJdbcReconnectGuard}）ため、objectを補償削除してから台帳を外す。</li>
 * </ul>
 * DB照会・削除に失敗した場合は台帳を残し、次回に再試行する。storage keyが既定の形式でない台帳は自動削除の対象にせず
 * 手作業の照合に回す（台帳fileの改ざんで任意objectを消させない）。
 *
 * <p>{@link #SAFETY_WINDOW}は、結果不明のCOMMITがserver側で確定（commitまたはrollback）するまでの時間より十分長く取る。
 *
 * <p>時間だけでは足りない場合（W2-NF06 review P3-1。COMMITがserver側で1時間を超えて止まり、その間にclient側の接続だけが
 * L4 LBに切られた）に備え、台帳にCOMMIT接続のserver側ID（MySQLの{@code CONNECTION_ID()}）がある結果不明の台帳は、
 * 参照が無くても、そのserver threadが文を実行している間は判定しない（pendingに数えて次回に回す）。接続が文を実行して
 * いないと確かめた後に参照件数を数え直してから回収する。接続IDの無い台帳（MySQL以外・ID取得の失敗・補償削除の失敗）は
 * 従来どおり時間だけで判定する。
 */
@Component
public class ImportStageReconciler {
    /** 記録からこの時間が経つまでは判定しない。 */
    static final Duration SAFETY_WINDOW = Duration.ofHours(1);
    /** 照合結果の件数。tag {@code result}は{@code reclaimed}（回収）/{@code committed}（commit済み）/{@code failed}。 */
    static final String RECONCILED_METRIC = "ses.import.stage.reconciled";
    /** DocumentServiceImpl#generateStorageKeyの形式（32桁hex/UUID）。 */
    private static final Pattern STORAGE_KEY = Pattern.compile(
            "[0-9a-f]{32}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImportStageReconciler.class);

    /** 1回の照合結果。 */
    record Result(int reclaimed, int committed, int pending, int failed, int manual) {
        boolean touched() {
            return reclaimed + committed + failed + manual > 0;
        }
    }

    private final ImportStageReconciliationLedger ledger;
    private final JdbcTemplate jdbc;
    private final DocumentService documents;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final CommitConnectionProbe commitConnections;

    @Autowired
    public ImportStageReconciler(ImportStageReconciliationLedger ledger, JdbcTemplate jdbc,
                                 DocumentService documents, ObjectProvider<MeterRegistry> meterRegistry) {
        this(ledger, jdbc, documents, meterRegistry.getIfAvailable(), Clock.systemUTC());
    }

    /** test用。時刻とmetricを差し替える。COMMIT接続の判定は実DBのprocess一覧を使う。 */
    ImportStageReconciler(ImportStageReconciliationLedger ledger, JdbcTemplate jdbc, DocumentService documents,
                          MeterRegistry meterRegistry, Clock clock) {
        this(ledger, jdbc, documents, meterRegistry, clock, processList(jdbc));
    }

    /** test用。COMMIT接続の判定も差し替える。 */
    ImportStageReconciler(ImportStageReconciliationLedger ledger, JdbcTemplate jdbc, DocumentService documents,
                          MeterRegistry meterRegistry, Clock clock, CommitConnectionProbe commitConnections) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.documents = Objects.requireNonNull(documents, "documents");
        this.meterRegistry = meterRegistry;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.commitConnections = Objects.requireNonNull(commitConnections, "commitConnections");
    }

    /** 期限を過ぎた台帳を一巡する。個別の失敗は台帳を残して次へ進み、例外は投げない。 */
    public Result reconcileDue() {
        Instant cutoff = clock.instant().minus(SAFETY_WINDOW);
        int reclaimed = 0;
        int committed = 0;
        int pending = 0;
        int failed = 0;
        int manual = 0;
        for (ImportStageReconciliationLedger.Entry entry : ledger.entries()) {
            if (entry.recordedAt().isAfter(cutoff)) {
                pending++;
                continue;
            }
            if (!STORAGE_KEY.matcher(entry.storageKey()).matches()) {
                manual++;
                log.error("[取込] stage照合台帳のstorage keyが既定形式でないため自動回収しません（手作業で照合してください）: "
                        + "operationId={} reason={}", entry.operationId(), entry.reason());
                continue;
            }
            try {
                int references = references(entry);
                if (references == 0 && entry.commitConnectionId() != null) {
                    // COMMITを送った接続のserver threadが文を実行中なら、COMMITはまだ確定していない（b-14）。
                    if (commitConnections.isExecuting(entry.commitConnectionId())) {
                        pending++;
                        log.info("[取込] 照合対象stageのCOMMIT接続がまだ実行中のため判定を見送ります: operationId={} "
                                + "commitConnectionId={}", entry.operationId(), entry.commitConnectionId());
                        continue;
                    }
                    // 参照件数を数えた後・接続を確かめる前にCOMMITが確定した場合を取りこぼさないよう、数え直す。
                    references = references(entry);
                }
                if (references > 0) {
                    ledger.remove(entry);
                    committed++;
                    increment("committed");
                    log.info("[取込] 照合対象stageはcommit済みと確定したため台帳だけを外します: operationId={} storageKey={} "
                            + "reason={}", entry.operationId(), entry.storageKey(), entry.reason());
                    continue;
                }
                documents.discardImportStage(asStage(entry));
                ledger.remove(entry);
                reclaimed++;
                increment("reclaimed");
                log.warn("[取込] 未commitと確定したstage objectを回収しました: operationId={} storageKey={} reason={}",
                        entry.operationId(), entry.storageKey(), entry.reason());
            } catch (IOException | RuntimeException failure) {
                failed++;
                increment("failed");
                log.warn("[取込] stage照合に失敗したため台帳を残して次回再試行します: operationId={} reason={} exceptionType={}",
                        entry.operationId(), entry.reason(), LogRedaction.exceptionType(failure));
            }
        }
        return new Result(reclaimed, committed, pending, failed, manual);
    }

    /** 同じstorage keyを参照する版の件数（tenant・論理削除を問わない。V200の索引で引く）。 */
    private int references(ImportStageReconciliationLedger.Entry entry) {
        Integer references = jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_document_version WHERE storage_key=?", Integer.class, entry.storageKey());
        if (references == null) throw new IllegalStateException("参照件数を取得できません");
        return references;
    }

    /** 結果不明のCOMMITを送った接続のserver threadが、まだ文を実行しているか。 */
    @FunctionalInterface
    interface CommitConnectionProbe {
        /** 判定できなければ例外を投げる（照合は失敗として台帳を残す）。 */
        boolean isExecuting(long connectionId);
    }

    /**
     * MySQLの{@code information_schema.PROCESSLIST}で判定する。同じDB accountのthreadはPROCESS権限なしで見える
     * （アプリは全instanceで同じaccountを使う）。{@code COMMAND='Sleep'}は文を実行していない（COMMITは完了したか、
     * clientが送る前に切れて以後も送られない）ため実行中に数えない。{@code Killed}は後始末中なので実行中に数える。
     * MySQL以外（照会できないDB）は例外になり、その台帳は失敗として残る。
     */
    static CommitConnectionProbe processList(JdbcTemplate jdbc) {
        return connectionId -> {
            Integer executing = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.PROCESSLIST "
                    + "WHERE ID = ? AND COMMAND <> 'Sleep'", Integer.class, connectionId);
            if (executing == null) throw new IllegalStateException("COMMIT接続の状態を取得できません");
            return executing > 0;
        };
    }

    /** DocumentService#discardImportStageの検査を満たす最小のstage表現を台帳から復元する。 */
    private static StagedImportDocument asStage(ImportStageReconciliationLedger.Entry entry) {
        Document document = new Document();
        document.setTenantId(entry.tenantId());
        document.setDocumentType("IMPORT");
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(entry.tenantId());
        version.setStorageKey(entry.storageKey());
        String operationId = entry.operationId() == null || entry.operationId().isBlank()
                ? "ledger-reconcile" : entry.operationId();
        return new StagedImportDocument(document, version, entry.storageKey(), operationId);
    }

    private void increment(String result) {
        if (meterRegistry == null) return;
        try {
            Counter.builder(RECONCILED_METRIC).description("NF-06取込stage照合の結果件数")
                    .tag("result", result).register(meterRegistry).increment();
        } catch (RuntimeException metricFailure) {
            log.warn("[取込] stage照合metricの記録に失敗しました: exceptionType={}",
                    LogRedaction.exceptionType(metricFailure));
        }
    }
}
