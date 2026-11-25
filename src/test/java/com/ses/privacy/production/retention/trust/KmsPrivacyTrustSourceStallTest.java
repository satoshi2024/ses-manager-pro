package com.ses.privacy.production.retention.trust;

import com.ses.privacy.foundation.PolicyRegistrationVerifier;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.bytes;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.document;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.ed25519;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.policyAnchor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * trust 文書の file の I/O が止まったとき（EFS・NFS の hard mount で file system に届かない）に、trust の判定が要求を止めないこと
 * （RB-NF07-TRUST-KMS delta review P2-A）。stat・読込みを {@link CountDownLatch} で止める file の読み方で模擬し、止まった
 * thread 以外の判定が {@link KmsPrivacyTrustSource#LOCK_WAIT} 程度で返り、最後に file を確かめた時刻から {@code reload-interval}
 * の内は直前の内容、外は閉じた結果（anchor 0件・UNKNOWN）になること、I/O が戻れば元に戻ること、起動時の検査が上限で打ち切られ
 * NF07 の flag が ON の起動を止めることを確かめる。鍵は test-only。
 */
class KmsPrivacyTrustSourceStallTest {

    private static final Instant T0 = Instant.parse("2026-11-18T03:00:00Z");
    private static final String TENANT = "default";
    private static final String ANCHOR = "policy-issuer:kms-2026a";
    /** 止まった I/O を待たないことの判定に使う上限（{@link KmsPrivacyTrustSource#LOCK_WAIT} に十分な余裕を足す）。 */
    private static final long BOUND_MILLIS = 2_000;

    @TempDir
    Path directory;

    private final BlockingFiles files = new BlockingFiles();
    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final KmsPrivacyTrustSourceTest.MutableClock clock = new KmsPrivacyTrustSourceTest.MutableClock(T0);
    private final KeyPair key = ed25519();
    private KmsTrustProperties properties;
    private Path file;

    @BeforeEach
    void setUp() throws IOException {
        file = directory.resolve("nf07-trust.json").toAbsolutePath();
        Files.write(file, bytes(document(T0, List.of(policyAnchor("policy-issuer", "kms-2026a", key, "ACTIVE",
                T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(30)), TENANT)), List.of(), Map.of())));
        properties = new KmsTrustProperties();
        properties.setAnchorFile(file.toString());
        properties.setMaxDocumentAge("24h");
    }

    @AfterEach
    void tearDown() {
        files.release();
        background.shutdownNow();
    }

    @Test
    void statが止まっても他の判定は待たずに再読込の間隔の内はcacheを使い外なら閉じI_Oが戻れば元に戻る() throws Exception {
        KmsPrivacyTrustSource source = source(Duration.ofSeconds(20));
        PolicyRegistrationVerifier registration = registrationVerifier(source);
        assertThat(status(source)).isEqualTo(RevocationStatus.ACTIVE);
        assertThat(registration.trustReady(TENANT)).isTrue();

        // 次の stat を止め、別の thread（lock を持つ）をそこで止める
        files.block();
        Future<RevocationStatus> stuck = background.submit(() -> status(source));
        assertThat(files.awaitBlocked()).as("lock を持つ thread が stat で止まった").isTrue();

        // 最後に file を確かめた時刻（T0）から reload-interval（30秒）の内: 待たずに直前の内容を使う
        long started = System.nanoTime();
        assertThat(status(source)).isEqualTo(RevocationStatus.ACTIVE);
        assertThat(PrivacyTrustSource.policyAnchorsOf(source).anchors()).containsOnlyKeys(ANCHOR);
        assertThat(elapsedMillis(started)).as("lock の待ちは LOCK_WAIT 程度").isLessThan(BOUND_MILLIS);
        // I/O が止まっていると分かった後は待たない（10回の判定でも LOCK_WAIT の10倍にならない）
        started = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            assertThat(status(source)).isEqualTo(RevocationStatus.ACTIVE);
        }
        assertThat(elapsedMillis(started)).isLessThan(BOUND_MILLIS);

        // reload-interval の外: 閉じる（anchor 0件・UNKNOWN、gate も閉じる）
        clock.set(T0.plusSeconds(31));
        started = System.nanoTime();
        assertThat(status(source)).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(PrivacyTrustSource.policyAnchorsOf(source).anchors()).isEmpty();
        assertThat(registration.trustReady(TENANT)).isFalse();
        assertThat(elapsedMillis(started)).isLessThan(BOUND_MILLIS);
        assertThat(stuck.isDone()).as("止まった thread だけが止まったまま").isFalse();

        // I/O が戻れば、次の判定で読み直して元に戻る
        files.release();
        stuck.get(5, TimeUnit.SECONDS);
        assertThat(status(source)).isEqualTo(RevocationStatus.ACTIVE);
        assertThat(registration.trustReady(TENANT)).isTrue();
    }

    @Test
    void 最初の読込みが止まればcacheが無いので他の判定は待たずに閉じる() throws Exception {
        KmsPrivacyTrustSource source = source(Duration.ofSeconds(20));
        files.block();
        Future<RevocationStatus> stuck = background.submit(() -> status(source));
        assertThat(files.awaitBlocked()).isTrue();

        long started = System.nanoTime();
        assertThat(status(source)).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(PrivacyTrustSource.policyAnchorsOf(source).anchors()).isEmpty();
        assertThat(PrivacyTrustSource.holdReleaseAnchorsOf(source).anchors()).isEmpty();
        assertThat(elapsedMillis(started)).isLessThan(BOUND_MILLIS);

        files.release();
        assertThat(stuck.get(5, TimeUnit.SECONDS)).isEqualTo(RevocationStatus.ACTIVE);
        assertThat(status(source)).isEqualTo(RevocationStatus.ACTIVE);
    }

    @Test
    void 停止から戻ればLOADEDを記し二度目の停止でもWARNを出す() throws Exception {
        ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(KmsPrivacyTrustSource.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            KmsPrivacyTrustSource source = source(Duration.ofSeconds(20));
            assertThat(status(source)).isEqualTo(RevocationStatus.ACTIVE);

            // 1回目の停止（reload-interval の外で閉じる）→ 戻る
            files.block();
            Future<RevocationStatus> first = background.submit(() -> status(source));
            assertThat(files.awaitBlocked()).isTrue();
            clock.set(T0.plusSeconds(31));
            assertThat(status(source)).isEqualTo(RevocationStatus.UNKNOWN);
            files.release();
            first.get(5, TimeUnit.SECONDS);
            assertThat(status(source)).isEqualTo(RevocationStatus.ACTIVE);

            // 2回目の停止
            files.block();
            Future<RevocationStatus> second = background.submit(() -> status(source));
            assertThat(files.awaitBlocked()).isTrue();
            clock.set(T0.plusSeconds(62));
            assertThat(status(source)).isEqualTo(RevocationStatus.UNKNOWN);
            files.release();
            second.get(5, TimeUnit.SECONDS);

            List<String> states = appender.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .filter(message -> message.contains("state="))
                    .map(message -> message.contains("document.stalled") ? "STALLED"
                            : message.contains("state=LOADED") ? "LOADED" : "OTHER")
                    .toList();
            assertThat(states).containsSubsequence("LOADED", "STALLED", "LOADED", "STALLED");
            assertThat(states.stream().filter("STALLED"::equals).count()).as("停止ごとに1回").isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void fileが変わった後の読込みが止まれば他の判定は古い内容を使わずに閉じる() throws Exception {
        KmsPrivacyTrustSource source = source(Duration.ofSeconds(20));
        assertThat(status(source)).isEqualTo(RevocationStatus.ACTIVE);

        // 鍵を失効させた文書に置き換える（大きさと最終更新日時が変わる）。stat は返り、読込みが止まる
        Files.write(file, bytes(document(T0.plusSeconds(1), List.of(policyAnchor("policy-issuer", "kms-2026a", key,
                "REVOKED", T0.minus(Duration.ofDays(1)), T0.plus(Duration.ofDays(30)), TENANT)), List.of(), Map.of())));
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(T0.plusSeconds(3600)));
        files.blockReads();
        Future<RevocationStatus> stuck = background.submit(() -> status(source));
        assertThat(files.awaitBlocked()).isTrue();

        // reload-interval の内でも、変わった file の読込みが止まっている間は古い内容（ACTIVE）を返さない
        long started = System.nanoTime();
        assertThat(status(source)).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(PrivacyTrustSource.policyAnchorsOf(source).anchors()).isEmpty();
        assertThat(elapsedMillis(started)).isLessThan(BOUND_MILLIS);

        files.release();
        assertThat(stuck.get(5, TimeUnit.SECONDS)).isEqualTo(RevocationStatus.REVOKED);
        assertThat(status(source)).isEqualTo(RevocationStatus.REVOKED);
    }

    @Test
    void 起動時の検査はI_Oが止まれば上限で打ち切り構造の不足として起動を続ける() {
        KmsPrivacyTrustSource source = source(Duration.ofMillis(200));
        files.block();

        long started = System.nanoTime();
        KmsPrivacyTrustSource.StartupReport report = source.inspect();
        assertThat(report.structural()).contains("trust.kms.document.timeout");
        assertThat(report.fresh()).isFalse();
        assertThat(elapsedMillis(started)).isLessThan(BOUND_MILLIS);

        // 構造の不足として状態に置き、起動は止めない（副入口 REL・REG だけが閉じる。FE-C04）
        PrivacyTrustStructureState state = new PrivacyTrustStructureState();
        new PrivacyTrustStartupValidator(source, state).validate();
        assertThat(state.sound()).isFalse();
        assertThat(String.join(",", state.structuralViolations())).contains("trust.kms.document.");

        // I/O が戻れば検査は通る
        files.release();
        KmsPrivacyTrustSource recovered = source(Duration.ofSeconds(20));
        assertThat(recovered.inspect().structural()).isEmpty();
    }

    private KmsPrivacyTrustSource source(Duration inspectTimeout) {
        return new KmsPrivacyTrustSource(properties, clock, files, inspectTimeout);
    }

    private PolicyRegistrationVerifier registrationVerifier(KmsPrivacyTrustSource source) {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        return ProductionPrivacyComponents.policyRegistrationVerifier(prod, PrivacyTrustSource.policyAnchorsOf(source),
                PrivacyTrustSource.revocationsOf(source), clock);
    }

    private static RevocationStatus status(KmsPrivacyTrustSource source) {
        return PrivacyTrustSource.revocationsOf(source).statusOf(ANCHOR);
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    /** stat・読込みを止められる file の読み方（EFS に届かない hard mount の模擬）。 */
    private static final class BlockingFiles implements KmsPrivacyTrustSource.TrustFileAccess {
        private volatile CountDownLatch gate;
        private volatile CountDownLatch blocked = new CountDownLatch(1);
        private volatile boolean statBlocks = true;

        /** 次の stat（と読込み）を止める。 */
        void block() {
            statBlocks = true;
            blocked = new CountDownLatch(1);
            gate = new CountDownLatch(1);
        }

        /** stat は返し、次の読込みだけを止める。 */
        void blockReads() {
            statBlocks = false;
            blocked = new CountDownLatch(1);
            gate = new CountDownLatch(1);
        }

        void release() {
            CountDownLatch current = gate;
            gate = null;
            if (current != null) {
                current.countDown();
            }
        }

        boolean awaitBlocked() throws InterruptedException {
            return blocked.await(5, TimeUnit.SECONDS);
        }

        @Override
        public BasicFileAttributes attributes(Path path) throws IOException {
            if (statBlocks) {
                pause();
            }
            return KmsPrivacyTrustSource.TrustFileAccess.SYSTEM.attributes(path);
        }

        @Override
        public byte[] read(Path path) throws IOException {
            pause();
            return KmsPrivacyTrustSource.TrustFileAccess.SYSTEM.read(path);
        }

        private void pause() throws IOException {
            CountDownLatch current = gate;
            if (current == null) {
                return;
            }
            blocked.countDown();
            try {
                // release されるか、別の block() で gate が替わるまで待つ（試験の後始末で必ず release する）。本物の hard mount と
                // 違い interrupt では IOException で戻る（executor の shutdownNow で後始末できるようにするため）
                while (!current.await(100, TimeUnit.MILLISECONDS)) {
                    if (gate != current) {
                        return;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }
    }
}
