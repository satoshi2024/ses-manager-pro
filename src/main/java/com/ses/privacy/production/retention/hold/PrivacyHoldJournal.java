package com.ses.privacy.production.retention.hold;

import com.ses.privacy.foundation.JournalUnavailableException;
import com.ses.privacy.foundation.PrivacyJournalPort;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * hold（開始・gate・reconciler）が使う Journal への入口（NF07-A1H。§3.4.6 の2〜4 を1か所で守る）。
 *
 * <ul>
 *   <li>profile に関係なく、起動時検査（{@link PrivacyJournalStartupVerifier#startupResult()}）が {@code AVAILABLE} で
 *       なければ {@link Availability#UNAVAILABLE}（設定が無ければ {@link Availability#NOT_CONFIGURED}）として扱う
 *       （A1J Review §3.3 の条件）。</li>
 *   <li>本番 adapter は {@link PrivacyJournalConnections#openJournalPort} だけから作る（prod profile 以外では生成が拒否され、
 *       {@code UNAVAILABLE} になる）。生成は初回の利用時に1回だけ行い、結果（失敗を含む）を保持する。生成時に DB へ触れない。</li>
 *   <li>principal は1つ（方式A）。gate・projection の読み取りも同じ pool を使う。</li>
 * </ul>
 * Spring の {@code PrivacyJournalPort} 型 Bean は作らない（本 holder の外で adapter を使わせない）。test は package-private の
 * constructor で port・索引を差し替える（Testcontainers の MySQL test は prod の {@code MockEnvironment} で本番 adapter を
 * 生成して渡す）。
 */
public class PrivacyHoldJournal {

    /** Journal の可用性。 */
    public enum Availability {
        /** 起動時検査に適合し、adapter と索引が使える。 */
        AVAILABLE,
        /** journal 設定が無い。 */
        NOT_CONFIGURED,
        /** 起動時検査の不適合・照会不能・adapter を生成できない（prod 以外を含む）。 */
        UNAVAILABLE
    }

    private static final Pattern JOURNAL_ID = Pattern.compile("[A-Za-z0-9._-]{4,64}");

    private final Supplier<PrivacyJournalStartupVerifier.Result> startupResult;
    private final Supplier<PrivacyJournalPort> portFactory;
    private final PrivacyHoldJournalIndex index;
    private final String journalId;
    private final Duration pendingTimeout;
    private final Duration appendInFlightUpperBound;
    private final Object portLock = new Object();
    private volatile boolean portResolved;
    private volatile PrivacyJournalPort port;

    PrivacyHoldJournal(Supplier<PrivacyJournalStartupVerifier.Result> startupResult,
                       Supplier<PrivacyJournalPort> portFactory, PrivacyHoldJournalIndex index, String journalId,
                       Duration pendingTimeout, Duration appendInFlightUpperBound) {
        this.startupResult = Objects.requireNonNull(startupResult, "startupResult");
        this.portFactory = Objects.requireNonNull(portFactory, "portFactory");
        this.index = index;
        this.journalId = journalId;
        this.pendingTimeout = pendingTimeout;
        this.appendInFlightUpperBound = Objects.requireNonNull(appendInFlightUpperBound, "appendInFlightUpperBound");
    }

    /** Spring 登録用。A1J の holder と起動時検査から作る（接続はしない）。 */
    static PrivacyHoldJournal create(PrivacyJournalConnections connections, PrivacyJournalStartupVerifier verifier,
                                     PrivacyJournalProperties properties, Environment environment, Clock clock) {
        Objects.requireNonNull(connections, "connections");
        Objects.requireNonNull(verifier, "verifier");
        PrivacyHoldJournalIndex index = connections.isPoolCreated()
                ? new JdbcPrivacyHoldJournalIndex(connections.dataSource()) : null;
        return new PrivacyHoldJournal(verifier::startupResult, () -> connections.openJournalPort(environment, clock),
                index, connections.journalId(), properties == null ? null : properties.getPendingTimeout(),
                connections.appendInFlightUpperBound());
    }

    /** 現在の可用性（起動時検査の結果・adapter の生成可否・索引の有無・journal ID の形式）。 */
    public Availability availability() {
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
