package com.ses.privacy.production.retention.trust;

import com.ses.privacy.foundation.HoldReleaseTrustAnchor;
import com.ses.privacy.foundation.HoldReleaseTrustRegistry;
import com.ses.privacy.foundation.ProductionPolicyTrustRegistry;
import com.ses.privacy.foundation.RevocationReferences;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.RevocationStatusProvider;
import com.ses.privacy.foundation.TrustedAuthority;
import com.ses.privacy.production.retention.hold.ConfiguredHoldReleaseTrustRegistry;
import com.ses.privacy.production.retention.policy.ConfiguredPolicyTrustRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * D-03（AWS KMS 非対称署名、鍵 version 単位の失効。2026-11-18 決定）の {@link PrivacyTrustSource} の adapter（NF07-TRUST）。
 *
 * <p>application は AWS を呼ばない（SDK も持たない）。署名は D-03 owner が KMS で行い（key spec {@code ECC_NIST_EDWARDS25519}、
 * 署名算法 {@code ED25519_SHA_512}、{@code MessageType=RAW}）、application は {@code GetPublicKey} の公開鍵を trust 文書
 * （{@link KmsTrustDocument}）から読み、JDK の Ed25519 で検証する。文書は {@code privacy.trust.kms.anchor-file} の file で、
 * anchor と、鍵（{@code authorityId:keyId}）・policy envelope の {@code revocationReference} の失効状態を同じ文書が持つ。
 *
 * <p>{@link PrivacyTrustSource} の契約との対応:
 * <ul>
 *   <li>呼出しのたびに読む: 判定のたびに file の最終更新日時と大きさを確かめ、変わっていれば読み直す。変わらなくても
 *       {@code reload-interval}（既定30秒、最長5分）を過ぎれば読み直す。anchor を消した文書を置けば次の検証から
 *       {@code UNKNOWN_TRUST_ANCHOR}、鍵を {@code REVOKED} にすれば次の照会から {@code POLICY_REVOKED}（再起動は要らない）。</li>
 *   <li>key は {@code authorityId:keyId}、鍵は Ed25519 だけ、解除の主体は {@code sys_user:<id>}: 文書の検査と
 *       {@code Configured*TrustRegistry}・{@link HoldReleaseTrustAnchor} の constructor が検査する。</li>
 *   <li>{@code statusOf} は、文書が読めて鮮度の上限（{@code max-document-age}）の内にあり、参照が文書に {@code ACTIVE} と
 *       書かれているときだけ {@code ACTIVE}。文書に無い参照・読めない・形式が違う・古すぎる・未来の作成日時・鮮度の上限の
 *       設定が無い・範囲外は全て {@code UNKNOWN}（{@code ACTIVE} を既定にしない）。{@code :} を含む参照は鍵の状態だけ、
 *       含まない参照は envelope の状態だけを引く（名前空間を文法で分ける。{@code RevocationReferences}、review P2-1）。</li>
 *   <li>読めない・形式が違う文書（実行中に壊れた場合を含む）、および鮮度の上限の外（古すぎる・作成日時が未来・鮮度の上限の
 *       設定が無い・範囲外）の文書では anchor を0件として返す（入口は閉じ、検証は {@code UNKNOWN_TRUST_ANCHOR}。review P3-1 の
 *       縦深防御: 失効状態の UNKNOWN と二重に閉じる）。直前に読めた内容に戻らない（file が正本で、古い内容を使い続けない）。</li>
 *   <li>rotation: 新しい key ID の anchor を足し、有効期間を重ねる（旧鍵の {@code validUntil} まで両方が有効）。</li>
 * </ul>
 *
 * <p>失効を戻さない: 同じ process で一度 {@code REVOKED} と読んだ鍵・envelope の参照は、後の文書が {@code ACTIVE} と書いても
 * {@code REVOKED} として答える（WARN を出す）。文書の作成日時が、同じ process で読んだ文書の作成日時より前なら、古い版への
 * 巻戻しとして文書全体を受け付けない。どちらも process の中だけの防護で、再起動の後は文書の値に従う（そのため運用では
 * {@code REVOKED} を戻さず、古い版を置かない。deploy-runbook §4.6）。
 *
 * <p>file の I/O で要求を止めない（RB-NF07-TRUST-KMS delta review P2-A）: 文書は EFS（NFS、Fargate では hard mount の見込み）に
 * 置くため、file system に届かないと stat・読込みが返らない。判定は1つの lock で直列化するが、lock は最長
 * {@link #LOCK_WAIT}（0.3秒）しか待たず、lock を持つ thread の I/O が {@link #LOCK_WAIT} を超えて続いていれば待たない。lock を
 * 取れなかった判定は I/O をせず、最後に file を確かめた時刻（stat・読込みが成功した時刻）から {@code reload-interval} の内なら
 * 直前に読めた内容を使い、外なら閉じる（anchor 0件・UNKNOWN、WARN {@code UNAVAILABLE reason=document.stalled} を状態が
 * 変わったときに1回）。止まるのは I/O 中の1 thread だけで、他の要求の待ちは最長 {@link #LOCK_WAIT}。起動時の検査は
 * {@link #DEFAULT_INSPECT_TIMEOUT}（20秒）で打ち切り、構造的な不足（{@code trust.kms.document.timeout}）として扱う。
 *
 * <p>状態の変化は WARN / INFO の log（{@value #LOG_CODE_STATE}）に固定の code だけを出す（公開鍵・ARN・参照の値は出さない）。
 * thread-safe。
 */
public final class KmsPrivacyTrustSource implements PrivacyTrustSource {

    private static final Logger log = LoggerFactory.getLogger(KmsPrivacyTrustSource.class);

    /** 文書の状態の変化を示す固定コード（監視の log 検索用）。 */
    public static final String LOG_CODE_STATE = "NF07_TRUST_DOCUMENT_STATE";

    /** 文書の大きさの上限。 */
    static final long MAX_DOCUMENT_BYTES = 1024L * 1024L;

    /** 文書の作成日時が判定の時刻より未来でも受け付ける幅（作成した host の時計の進み）。 */
    static final Duration GENERATED_AT_FUTURE_TOLERANCE = Duration.ofMinutes(5);

    /**
     * lock を待つ上限。stat・読込みは通常 µs〜ms（EFS でも同じ AZ の往復は ms 程度）で、その100倍以上の余裕があり平常時に
     * 誤って閉じることは無い。一方で、利用者の要求1件が trust を数回照会しても止まる時間は1秒程度に収まる（NF08 の送信は
     * 1件で6回以上照会するが、I/O が止まっていると分かった後は待たない）。固定値（設定にしない）。
     */
    static final Duration LOCK_WAIT = Duration.ofMillis(300);

    /** 起動時の検査（{@link #inspect()}）の上限。正常な起動の file の読込みより十分長く、ECS の起動の猶予より短い。 */
    static final Duration DEFAULT_INSPECT_TIMEOUT = Duration.ofSeconds(20);

    private static final ConfiguredPolicyTrustRegistry NO_POLICY_ANCHORS = ConfiguredPolicyTrustRegistry.empty();
    private static final ConfiguredHoldReleaseTrustRegistry NO_HOLD_RELEASE_ANCHORS =
            ConfiguredHoldReleaseTrustRegistry.empty();

    private final Path path;
    private final List<String> propertyViolations;
    private final Duration maxDocumentAge;
    private final Duration reloadInterval;
    private final Clock clock;
    private final TrustFileAccess files;
    private final Duration inspectTimeout;

    private final ReentrantLock lock = new ReentrantLock();
    private final Set<String> revokedSeen = ConcurrentHashMap.newKeySet();
    /** 直前の結果（lock を持つ thread が書き、lock を取れなかった判定が読む）。 */
    private volatile Snapshot cached;
    /** 最後に file を確かめた（stat・読込みが成功した）時刻。 */
    private volatile Instant checkedAt;
    /** lock を持つ thread が I/O を始めた時刻（{@link System#nanoTime()}。I/O 中でなければ 0）。 */
    private volatile long ioStartedNanos;
    private Instant lastAcceptedGeneratedAt;
    private volatile String lastState;

    /**
     * 構築時には file を読まない（読むのは判定のたび。起動時の検査は {@link PrivacyTrustStartupValidator}）。
     *
     * @param properties {@code privacy.trust.kms.*}（{@link KmsTrustProperties#isConfigured()} が true のもの）
     * @param clock      verifier と同じ時計
     */
    public KmsPrivacyTrustSource(KmsTrustProperties properties, Clock clock) {
        this(properties, clock, TrustFileAccess.SYSTEM, DEFAULT_INSPECT_TIMEOUT);
    }

    /** file の読み方と起動時の検査の上限を指定する（試験で I/O の停止を模擬する）。 */
    KmsPrivacyTrustSource(KmsTrustProperties properties, Clock clock, TrustFileAccess files, Duration inspectTimeout) {
        Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.files = Objects.requireNonNull(files, "files");
        this.inspectTimeout = Objects.requireNonNull(inspectTimeout, "inspectTimeout");
        this.path = properties.anchorPath();
        this.propertyViolations = List.copyOf(properties.violations());
        this.maxDocumentAge = properties.maxDocumentAgeValue();
        this.reloadInterval = properties.reloadIntervalValue();
    }

    @Override
    public ProductionPolicyTrustRegistry policyAnchors() {
        KmsTrustDocument document = usable(snapshot().document());
        return document == null ? NO_POLICY_ANCHORS : document.policyRegistry();
    }

    @Override
    public HoldReleaseTrustRegistry holdReleaseAnchors() {
        KmsTrustDocument document = usable(snapshot().document());
        return document == null ? NO_HOLD_RELEASE_ANCHORS : document.holdReleaseRegistry();
    }

    @Override
    public RevocationStatusProvider revocations() {
        Snapshot snapshot = snapshot();
        return reference -> statusOf(snapshot.document(), reference, clock.instant());
    }

    /**
     * 起動時の検査（{@link PrivacyTrustStartupValidator}）。構造的な違反と、時間要因の状態を返す。file の I/O が止まって
     * {@code inspectTimeout} の内に終わらなければ、{@code trust.kms.document.timeout} を構造的な違反として返す（読めないのと
     * 同じ扱い。I/O を続ける daemon thread は残るが、NF07 の flag が ON の起動はこれで止まる）。
     */
    StartupReport inspect() {
        FutureTask<StartupReport> task = new FutureTask<>(this::inspectNow);
        Thread worker = new Thread(task, "nf07-trust-startup-inspect");
        worker.setDaemon(true);
        worker.start();
        try {
            return task.get(inspectTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            return failedReport("trust.kms.document.timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failedReport("trust.kms.document.timeout");
        } catch (ExecutionException e) {
            return failedReport("trust.kms.document.unreadable");
        }
    }

    private StartupReport failedReport(String code) {
        List<String> structural = new ArrayList<>(propertyViolations);
        structural.add(code);
        return new StartupReport(structural, false, 0, 0);
    }

    private StartupReport inspectNow() {
        List<String> structural = new ArrayList<>(propertyViolations);
        Snapshot snapshot = snapshot();
        if (snapshot.document() == null) {
            structural.add("trust.kms." + snapshot.failureDetail());
            return new StartupReport(structural, false, 0, 0);
        }
        Instant now = clock.instant();
        KmsTrustDocument document = snapshot.document();
        int policy = 0;
        for (Map.Entry<String, TrustedAuthority> entry : document.policyRegistry().anchors().entrySet()) {
            TrustedAuthority anchor = entry.getValue();
            if (within(anchor.validFrom(), anchor.validUntil(), now)
                    && document.keyStatusOf(entry.getKey()) == RevocationStatus.ACTIVE
                    && !revokedSeen.contains(entry.getKey())) {
                policy++;
            }
        }
        int hold = 0;
        for (Map.Entry<String, HoldReleaseTrustAnchor> entry : document.holdReleaseRegistry().anchors().entrySet()) {
            HoldReleaseTrustAnchor anchor = entry.getValue();
            if (within(anchor.validFrom(), anchor.validUntil(), now)
                    && document.keyStatusOf(entry.getKey()) == RevocationStatus.ACTIVE
                    && !revokedSeen.contains(entry.getKey())) {
                hold++;
            }
        }
        return new StartupReport(structural, fresh(document.generatedAt(), now), policy, hold);
    }

    /**
     * 起動時の検査の結果。
     *
     * @param structural         構造的な違反（設定・文書。空でなければ NF07 の flag が ON の起動を止める）
     * @param fresh              文書が鮮度の上限の内にある
     * @param usablePolicyAnchors 有効期間内で ACTIVE の policy の anchor の数
     * @param usableHoldReleaseAnchors 有効期間内で ACTIVE の解除の anchor の数
     */
    record StartupReport(List<String> structural, boolean fresh, int usablePolicyAnchors,
                         int usableHoldReleaseAnchors) {
        StartupReport {
            structural = List.copyOf(structural);
        }
    }

    /** 鮮度の上限の内の文書だけを返す（外なら null。anchor も0件にする）。 */
    private KmsTrustDocument usable(KmsTrustDocument document) {
        if (document == null) {
            return null;
        }
        if (!fresh(document.generatedAt(), clock.instant())) {
            noteState("STALE", false);
            return null;
        }
        return document;
    }

    private RevocationStatus statusOf(KmsTrustDocument document, String reference, Instant now) {
        if (document == null || reference == null) {
            return RevocationStatus.UNKNOWN;
        }
        if (!fresh(document.generatedAt(), now)) {
            noteState("STALE", false);
            return RevocationStatus.UNKNOWN;
        }
        // ':' を含む参照は鍵（authorityId:keyId）、含まない参照は envelope だけを引く（名前空間を文法で分ける。review P2-1）
        RevocationStatus status = RevocationReferences.isKeyReference(reference)
                ? document.keyStatusOf(reference)
                : RevocationReferences.isEnvelopeReference(reference) ? document.envelopeStatusOf(reference) : null;
        if (status == null) {
            return RevocationStatus.UNKNOWN;
        }
        if (status == RevocationStatus.ACTIVE && revokedSeen.contains(reference)) {
            return RevocationStatus.REVOKED;
        }
        return status;
    }

    private boolean fresh(Instant generatedAt, Instant now) {
        return maxDocumentAge != null && generatedAt != null
                && !generatedAt.isAfter(now.plus(GENERATED_AT_FUTURE_TOLERANCE))
                && now.isBefore(generatedAt.plus(maxDocumentAge));
    }

    private static boolean within(Instant validFrom, Instant validUntil, Instant now) {
        return !now.isBefore(validFrom) && now.isBefore(validUntil);
    }

    /**
     * 現在の文書。lock は最長 {@link #LOCK_WAIT} しか待たず、lock を持つ thread の I/O が {@link #LOCK_WAIT} を超えて続いて
     * いれば待たない（{@link #stalled()}）。
     */
    private Snapshot snapshot() {
        boolean locked;
        try {
            locked = lock.tryLock();
            if (!locked && !ioStalled()) {
                locked = lock.tryLock(LOCK_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            return stalled();
        }
        try {
            return refresh();
        } finally {
            lock.unlock();
        }
    }

    /** lock を持つ thread の I/O が {@link #LOCK_WAIT} を超えて続いているか。 */
    private boolean ioStalled() {
        long started = ioStartedNanos;
        return started != 0 && System.nanoTime() - started > LOCK_WAIT.toNanos();
    }

    /**
     * lock を取れなかった（file の I/O が止まっている）判定の結果。I/O はしない。最後に file を確かめた時刻から
     * {@code reload-interval} の内なら直前に読めた内容、外なら閉じる（anchor 0件・UNKNOWN）。
     */
    private Snapshot stalled() {
        Instant now = clock.instant();
        Snapshot current = cached;
        Instant checked = checkedAt;
        if (current != null && current.document() != null && checked != null
                && now.isBefore(checked.plus(reloadInterval))) {
            return current;
        }
        noteState("UNAVAILABLE reason=document.stalled", false);
        return Snapshot.failed("document.stalled", null, now);
    }

    /** lock を持って file を確かめ、必要なら読み直す。 */
    private Snapshot refresh() {
        Instant now = clock.instant();
        if (path == null) {
            return remember(Snapshot.failed("anchorFile.notAbsolute", null, now));
        }
        BasicFileAttributes attributes;
        ioStartedNanos = System.nanoTime();
        try {
            attributes = files.attributes(path);
        } catch (IOException | RuntimeException e) {
            return remember(Snapshot.failed("document.unreadable", null, now));
        } finally {
            ioStartedNanos = 0;
        }
        Snapshot current = cached;
        if (current != null && current.sameFile(attributes)) {
            // file は変わっていない（直前の内容を確かめた）
            checkedAt = now;
            if (now.isBefore(current.loadedAt().plus(reloadInterval))) {
                if (current.document() != null) {
                    noteRecovered();
                }
                return current;
            }
        } else {
            // file が変わった（失効を書いた文書を置いた等）。読込みが止まっている間に、lock を取れなかった判定が古い内容を
            // 使わないよう、読む前に確かめた時刻を消す（delta review 2 P3-2）
            checkedAt = null;
        }
        Snapshot loaded = load(attributes, now);
        if (loaded.document() != null) {
            checkedAt = now;
        }
        return remember(loaded);
    }

    /**
     * 停止・読めない状態（{@code UNAVAILABLE}）から読めた状態に戻ったことを記す（delta review 2 P3-1。記さないと、次の停止の
     * WARN が状態の重複として出なくなる）。鮮度の上限の外（{@code STALE}）は戻ったことにしない（読めても使えないため）。
     */
    private void noteRecovered() {
        String state = lastState;
        if (state == null || state.startsWith("UNAVAILABLE")) {
            noteState("LOADED", true);
        }
    }

    private Snapshot load(BasicFileAttributes attributes, Instant now) {
        if (!attributes.isRegularFile()) {
            return Snapshot.failed("document.notRegularFile", attributes, now);
        }
        if (attributes.size() > MAX_DOCUMENT_BYTES) {
            return Snapshot.failed("document.tooLarge", attributes, now);
        }
        byte[] bytes;
        ioStartedNanos = System.nanoTime();
        try {
            bytes = files.read(path);
        } catch (IOException | RuntimeException e) {
            return Snapshot.failed("document.unreadable", attributes, now);
        } finally {
            ioStartedNanos = 0;
        }
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            return Snapshot.failed("document.tooLarge", attributes, now);
        }
        KmsTrustDocument document;
        try {
            document = KmsTrustDocument.parse(bytes);
        } catch (KmsTrustDocument.InvalidDocumentException e) {
            // message は固定の code と文書の中の位置だけ（値を含めない）
            return Snapshot.failed(e.code(), e.getMessage(), attributes, now);
        }
        if (lastAcceptedGeneratedAt != null && document.generatedAt().isBefore(lastAcceptedGeneratedAt)) {
            // 同じ process で読んだ文書より前に作られた文書（古い版への巻戻し）は受け付けない
            return Snapshot.failed("document.generatedAtRollback", attributes, now);
        }
        if (!document.generatedAt().isAfter(now.plus(GENERATED_AT_FUTURE_TOLERANCE))) {
            lastAcceptedGeneratedAt = document.generatedAt();
        }
        Set<String> reverted = new HashSet<>(document.activeReferences());
        reverted.retainAll(revokedSeen);
        if (!reverted.isEmpty() && (cached == null || !cached.sameFile(attributes))) {
            // 同じ file を再読込の間隔で読み直すたびには出さない（file が変わったときだけ）
            log.warn("{}: REVOKED と読んだ鍵・envelope の参照を ACTIVE と書いた文書です。この process では REVOKED のまま"
                    + "扱います: count={}", LOG_CODE_STATE, reverted.size());
        }
        revokedSeen.addAll(document.revokedReferences());
        return Snapshot.loaded(document, attributes, now);
    }

    private Snapshot remember(Snapshot snapshot) {
        Snapshot previous = cached;
        cached = snapshot;
        if (snapshot.document() == null) {
            noteState("UNAVAILABLE reason=" + snapshot.failureDetail(), false);
        } else if (previous == null || previous.document() == null || !previous.sameAs(snapshot)) {
            KmsTrustDocument document = snapshot.document();
            noteState("LOADED", true);
            log.info("{}: NF07のtrust文書を読みました: generatedAt={} policyAnchors={} holdReleaseAnchors={}",
                    LOG_CODE_STATE, document.generatedAt(), document.policyRegistry().anchors().size(),
                    document.holdReleaseRegistry().anchors().size());
        } else {
            // 同じ file を読み直した（停止から戻った場合を含む）
            noteRecovered();
        }
        return snapshot;
    }

    private void noteState(String state, boolean info) {
        if (state.equals(lastState)) {
            return;
        }
        lastState = state;
        if (info) {
            log.info("{}: state={}", LOG_CODE_STATE, state);
        } else {
            log.warn("{}: state={}（hold解除とpolicyの登録・有効化の入口は閉じ、policyの照会はUNKNOWNになります）",
                    LOG_CODE_STATE, state);
        }
    }

    /** file の読み方（試験で I/O の停止を模擬するための境界）。 */
    interface TrustFileAccess {

        /** 実際の file system。 */
        TrustFileAccess SYSTEM = new TrustFileAccess() {
            @Override
            public BasicFileAttributes attributes(Path path) throws IOException {
                return Files.readAttributes(path, BasicFileAttributes.class);
            }

            @Override
            public byte[] read(Path path) throws IOException {
                return Files.readAllBytes(path);
            }
        };

        BasicFileAttributes attributes(Path path) throws IOException;

        byte[] read(Path path) throws IOException;
    }

    /**
     * 読んだ結果（文書か、読めなかった理由）と、読んだ時点の file の属性。{@code failureDetail} は固定の code と文書の中の位置
     * （値を含めない）。
     */
    private record Snapshot(KmsTrustDocument document, String failureCode, String failureDetail, FileTime modified,
                            long size, Object fileKey, Instant loadedAt) {

        static Snapshot loaded(KmsTrustDocument document, BasicFileAttributes attributes, Instant now) {
            return new Snapshot(document, null, null, attributes.lastModifiedTime(), attributes.size(),
                    attributes.fileKey(), now);
        }

        static Snapshot failed(String code, BasicFileAttributes attributes, Instant now) {
            return failed(code, code, attributes, now);
        }

        static Snapshot failed(String code, String detail, BasicFileAttributes attributes, Instant now) {
            return attributes == null
                    ? new Snapshot(null, code, detail, null, -1, null, now)
                    : new Snapshot(null, code, detail, attributes.lastModifiedTime(), attributes.size(),
                    attributes.fileKey(), now);
        }

        boolean sameFile(BasicFileAttributes attributes) {
            return modified != null && modified.equals(attributes.lastModifiedTime()) && size == attributes.size()
                    && Objects.equals(fileKey, attributes.fileKey());
        }

        boolean sameAs(Snapshot other) {
            return other != null && Objects.equals(modified, other.modified) && size == other.size
                    && Objects.equals(fileKey, other.fileKey);
        }
    }
}
