package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.JournalUnavailableException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * hold（開始・gate・reconciler）が使う Journal への入口（NF07-A1H。§3.4.6 の2〜4・8 を1か所で守る）。
 *
 * <ul>
 *   <li>profile に関係なく、起動時検査の hold の効力の結果（{@link PrivacyJournalStartupVerifier#protectionResult()}。reader・appender の
 *       どちらの principal でも可。再検査の失敗では下がらない）が {@code AVAILABLE} でなければ {@link Availability#UNAVAILABLE}
 *       （設定が無ければ {@link Availability#NOT_CONFIGURED}）として扱う（A1J Review §3.3 の条件）。機能の開閉は読まない（hold は
 *       保持・保全を閉じた後も保護を続ける。feature-enablement-console design §8.5 の 3）。</li>
 *   <li>hold の開始（append）は {@link #appendAvailability()}（加えて開放の条件の結果 {@link PrivacyJournalStartupVerifier#enablementResult()}
 *       が appender の権限で {@code AVAILABLE}）で判定する。</li>
 *   <li>本番 adapter は {@link PrivacyJournalConnections#openJournalPort} だけから作る（prod profile 以外では生成が拒否され、
 *       {@code UNAVAILABLE} になる）。生成は初回の利用時に1回だけ行い、結果（失敗を含む）を保持する。生成時に DB へ触れない。</li>
 *   <li>principal は1つ（方式A）。gate・projection の読み取りも同じ pool を使う。</li>
 *   <li>実行中の誤接続の検出（§3.4.6 の8、Review P3-8）: Journal と application DB の {@code @@server_uuid} を比べ、同じ
 *       （同じ instance を指している）か比べられなければ {@code UNAVAILABLE} とし、固定 code の WARN を出す（gate の理由は、同じなら
 *       {@code JOURNAL_SAME_INSTANCE}、比べられなければ {@code JOURNAL_UNAVAILABLE}。A1H-1 delta review P3-N5）。比較は reconciler の
 *       実行ごと（{@link #refreshInstanceCheck}）と、各 process で前回から {@link #INSTANCE_CHECK_INTERVAL} を過ぎた最初の利用時に
 *       行う（ShedLock で reconciler を動かさない process の gate も同じ判定を持つため）。権限の再検査は起動時だけとする。</li>
 *   <li>比較は process の中で同時に1つだけ行う（single-flight。A1H-1 delta review P3-N1）。期限が来たとき、前回の結果がある間は
 *       比較中の thread を待たずに前回の結果を使い、Journal pool の接続と application DB の照会を重ねない。前回の結果が無い最初の
 *       比較だけは、比較中の thread の結果を待つ（根拠の無いまま AVAILABLE にも UNAVAILABLE にもしない）。reconciler の比べ直しも
 *       同じ lock を取り、比較中の thread が終わってから比べ直す。</li>
 * </ul>
 * Spring の {@code PrivacyJournalPort} 型 Bean は作らない（本 holder の外で adapter を使わせない）。test は package-private の
 * constructor で port・索引・application DB の {@code @@server_uuid} を差し替える（Testcontainers の MySQL test は prod の
 * {@code MockEnvironment} で本番 adapter を生成して渡す）。
 */
public class PrivacyHoldJournal {

    private static final Logger log = LoggerFactory.getLogger(PrivacyHoldJournal.class);

    /** Journal と application DB が同じ instance を指していることを示す固定 log code。 */
    public static final String LOG_CODE_SAME_INSTANCE = "NF07_PRIVACY_JOURNAL_SAME_INSTANCE";
    /** {@code @@server_uuid} を比べられなかったことを示す固定 log code。 */
    public static final String LOG_CODE_INSTANCE_UNVERIFIABLE = "NF07_PRIVACY_JOURNAL_INSTANCE_UNVERIFIABLE";
    /** 各 process が {@code @@server_uuid} を比べ直すまでの間隔。 */
    static final Duration INSTANCE_CHECK_INTERVAL = Duration.ofMinutes(1);

    /** Journal の可用性。 */
    public enum Availability {
        /** 起動時検査に適合し、adapter と索引が使え、application DB と別の instance である。 */
        AVAILABLE,
        /** journal 設定が無い。 */
        NOT_CONFIGURED,
        /** 起動時検査の不適合・照会不能・adapter を生成できない（prod 以外を含む）・application DB と同じ instance。 */
        UNAVAILABLE
    }

    /** {@code @@server_uuid} の比較結果。 */
    enum InstanceCheck { DISTINCT, SAME, UNVERIFIABLE }

    /** 直近の比較の結果と時刻（1つの参照で読み書きし、結果と時刻の組が食い違わないようにする）。 */
    private record InstanceCheckResult(InstanceCheck check, Instant checkedAt) {
    }

    private static final Pattern JOURNAL_ID = Pattern.compile("[A-Za-z0-9._-]{4,64}");

    private final Supplier<PrivacyJournalStartupVerifier.Result> startupResult;
    private final Supplier<PrivacyJournalStartupVerifier.Result> enablementResult;
    private final Supplier<PrivacyJournalPort> portFactory;
    private final PrivacyHoldJournalIndex index;
    private final String journalId;
    private final Duration pendingTimeout;
    private final Duration appendInFlightUpperBound;
    private final Supplier<String> applicationServerUuid;
    private final Clock clock;
    private final Object portLock = new Object();
    /** 比較を1つの thread に限る lock（同じ package の test が待ち合わせの有無を確かめるため package-private）。 */
    final ReentrantLock instanceCheckLock = new ReentrantLock();
    private volatile boolean portResolved;
    private volatile PrivacyJournalPort port;
    private volatile InstanceCheckResult lastInstanceCheck;

    /** hold の効力の結果を開放の条件の結果にも使う（試験用。appender で検査した結果を渡す）。 */
    PrivacyHoldJournal(Supplier<PrivacyJournalStartupVerifier.Result> startupResult,
                       Supplier<PrivacyJournalPort> portFactory, PrivacyHoldJournalIndex index, String journalId,
                       Duration pendingTimeout, Duration appendInFlightUpperBound,
                       Supplier<String> applicationServerUuid, Clock clock) {
        this(startupResult, startupResult, portFactory, index, journalId, pendingTimeout, appendInFlightUpperBound,
                applicationServerUuid, clock);
    }

    /**
     * @param startupResult    hold の効力の結果（gate・reconciler・解除が読む）
     * @param enablementResult 開放の条件の結果（hold の開始が読む）
     */
    PrivacyHoldJournal(Supplier<PrivacyJournalStartupVerifier.Result> startupResult,
                       Supplier<PrivacyJournalStartupVerifier.Result> enablementResult,
                       Supplier<PrivacyJournalPort> portFactory, PrivacyHoldJournalIndex index, String journalId,
                       Duration pendingTimeout, Duration appendInFlightUpperBound,
                       Supplier<String> applicationServerUuid, Clock clock) {
        this.startupResult = Objects.requireNonNull(startupResult, "startupResult");
        this.enablementResult = Objects.requireNonNull(enablementResult, "enablementResult");
        this.portFactory = Objects.requireNonNull(portFactory, "portFactory");
        this.index = index;
        this.journalId = journalId;
        this.pendingTimeout = pendingTimeout;
        this.appendInFlightUpperBound = Objects.requireNonNull(appendInFlightUpperBound, "appendInFlightUpperBound");
        this.applicationServerUuid = Objects.requireNonNull(applicationServerUuid, "applicationServerUuid");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Spring 登録用。A1J の holder と起動時検査から作る（接続はしない）。 */
    static PrivacyHoldJournal create(PrivacyJournalConnections connections, PrivacyJournalStartupVerifier verifier,
                                     PrivacyJournalProperties properties, Environment environment, Clock clock,
                                     Supplier<String> applicationServerUuid) {
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(verifier, "verifier");
        PrivacyHoldJournalIndex index = connections.isPoolCreated()
                ? new JdbcPrivacyHoldJournalIndex(connections.dataSource()) : null;
        return new PrivacyHoldJournal(verifier::protectionResult, verifier::enablementResult,
                () -> connections.openJournalPort(environment, clock),
                index, connections.journalId(), properties == null ? null : properties.getPendingTimeout(),
                connections.appendInFlightUpperBound(), applicationServerUuid, clock);
    }

    /** 現在の可用性（起動時検査の結果・adapter の生成可否・索引の有無・journal ID の形式・instance の別）。 */
    public Availability availability() {
        Availability base = baseAvailability();
        if (base != Availability.AVAILABLE) {
            return base;
        }
        InstanceCheckResult last = lastInstanceCheck;
        if (due(last)) {
            last = checkInstanceSingleFlight(last);
        }
        return last.check() == InstanceCheck.DISTINCT ? Availability.AVAILABLE : Availability.UNAVAILABLE;
    }

    /**
     * hold の開始（append）に使えるか: {@link #availability()} が {@code AVAILABLE} で、開放の条件の結果（appender の権限）も
     * {@code AVAILABLE}。reader の principal では {@code UNAVAILABLE}（hold の開始は 503。既存の hold の gate は {@link #availability()}
     * のまま動く）。
     */
    public Availability appendAvailability() {
        Availability base = availability();
        if (base != Availability.AVAILABLE) {
            return base;
        }
        PrivacyJournalStartupVerifier.Result result;
        try {
            result = enablementResult.get();
        } catch (RuntimeException e) {
            return Availability.UNAVAILABLE;
        }
        return result != null && result.availability() == PrivacyJournalStartupVerifier.Availability.AVAILABLE
                ? Availability.AVAILABLE : Availability.UNAVAILABLE;
    }

    /**
     * reconciler の実行ごとに {@code @@server_uuid} を比べ直す（Journal が使える前提が揃わなければ何もしない）。比較中の thread が
     * あればその終了を待ってから比べ直す（同時に2つ比べない。A1H-1 delta review P3-N1）。
     */
    void refreshInstanceCheck() {
        if (baseAvailability() != Availability.AVAILABLE) {
            return;
        }
        instanceCheckLock.lock();
        try {
            checkInstance();
        } finally {
            instanceCheckLock.unlock();
        }
    }

    /** 直近の比較で application DB と同じ instance だったか。 */
    boolean sameInstanceDetected() {
        InstanceCheckResult last = lastInstanceCheck;
        return last != null && last.check() == InstanceCheck.SAME;
    }

    /** adapter。{@link #availability()} が {@code AVAILABLE} でなければ {@link JournalUnavailableException}。 */
    PrivacyJournalPort port() {
        if (availability() != Availability.AVAILABLE) {
            throw new JournalUnavailableException("journalを利用できません", null);
        }
        return resolvePort();
    }

    /** 索引。{@link #availability()} が {@code AVAILABLE} でなければ {@link JournalUnavailableException}。 */
    PrivacyHoldJournalIndex index() {
        if (availability() != Availability.AVAILABLE) {
            throw new JournalUnavailableException("journalを利用できません", null);
        }
        return index;
    }

    /** 設定の journal ID（{@code t_privacy_projection_state.journal_id}）。 */
    String journalId() {
        return journalId;
    }

    /** {@code privacy.journal.pending-timeout}（未設定なら null）。 */
    Duration pendingTimeout() {
        return pendingTimeout;
    }

    /** append の在途時間の上限（A1J）。 */
    Duration appendInFlightUpperBound() {
        return appendInFlightUpperBound;
    }

    /** instance の比較を除いた可用性。 */
    private Availability baseAvailability() {
        PrivacyJournalStartupVerifier.Result result;
        try {
            result = startupResult.get();
        } catch (RuntimeException e) {
            return Availability.UNAVAILABLE;
        }
        if (result == null) {
            return Availability.UNAVAILABLE;
        }
        if (result.availability() == PrivacyJournalStartupVerifier.Availability.NOT_CONFIGURED) {
            return Availability.NOT_CONFIGURED;
        }
        if (result.availability() != PrivacyJournalStartupVerifier.Availability.AVAILABLE) {
            return Availability.UNAVAILABLE;
        }
        if (index == null || journalId == null || !JOURNAL_ID.matcher(journalId).matches() || resolvePort() == null) {
            return Availability.UNAVAILABLE;
        }
        return Availability.AVAILABLE;
    }

    /** 前回の比較が無いか、前回から {@link #INSTANCE_CHECK_INTERVAL} を過ぎたか。 */
    private boolean due(InstanceCheckResult last) {
        return last == null || !clock.instant().isBefore(last.checkedAt().plus(INSTANCE_CHECK_INTERVAL));
    }

    /**
     * 期限の来た比較を1つの thread だけが行う（A1H-1 delta review P3-N1）。前回の結果があれば、比較中の thread が居るときは待たずに
     * 前回の結果を返す。前回の結果が無い最初の比較だけは lock を待ち、比較中の thread の結果を使う。lock を取れた thread も、取る前に
     * 他の thread が比べ終えていればその結果を使い、重ねて比べない。
     */
    private InstanceCheckResult checkInstanceSingleFlight(InstanceCheckResult observed) {
        if (observed != null) {
            if (!instanceCheckLock.tryLock()) {
                return observed;
            }
        } else {
            instanceCheckLock.lock();
        }
        try {
            InstanceCheckResult current = lastInstanceCheck;
            return due(current) ? checkInstance() : current;
        } finally {
            instanceCheckLock.unlock();
        }
    }

    /** {@code @@server_uuid} を比べて結果を保持する（{@link #instanceCheckLock} を持つ thread だけが呼ぶ）。 */
    private InstanceCheckResult checkInstance() {
        InstanceCheck result;
        try {
            String journalUuid = index.serverUuid();
            String applicationUuid = applicationServerUuid.get();
            if (journalUuid == null || journalUuid.isBlank() || applicationUuid == null || applicationUuid.isBlank()) {
                result = InstanceCheck.UNVERIFIABLE;
            } else {
                result = journalUuid.equals(applicationUuid) ? InstanceCheck.SAME : InstanceCheck.DISTINCT;
            }
        } catch (RuntimeException e) {
            result = InstanceCheck.UNVERIFIABLE;
        }
        if (result == InstanceCheck.SAME) {
            log.warn("[privacy-journal] Journalとapplication DBが同じinstanceを指しています。holdの判定をUNAVAILABLEにします: code={}",
                    LOG_CODE_SAME_INSTANCE);
        } else if (result == InstanceCheck.UNVERIFIABLE) {
            log.warn("[privacy-journal] Journalとapplication DBのinstanceを比べられません。holdの判定をUNAVAILABLEにします: code={}",
                    LOG_CODE_INSTANCE_UNVERIFIABLE);
        }
        InstanceCheckResult checked = new InstanceCheckResult(result, clock.instant());
        lastInstanceCheck = checked;
        return checked;
    }

    private PrivacyJournalPort resolvePort() {
        if (portResolved) {
            return port;
        }
        synchronized (portLock) {
            if (!portResolved) {
                PrivacyJournalPort created;
                try {
                    created = portFactory.get();
                } catch (RuntimeException e) {
                    // prod 以外（SecurityException）・pool なし（JournalUnavailableException）。理由は固定で、message は出さない
                    created = null;
                }
                port = created;
                portResolved = true;
            }
            return port;
        }
    }
}
