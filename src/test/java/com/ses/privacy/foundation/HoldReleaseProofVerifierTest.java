package com.ses.privacy.foundation;

import com.ses.privacy.foundation.HoldReleaseAuthorityClaim.Role;
import com.ses.privacy.foundation.PrivacyJournalPort.CommandType;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalEvent;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalHead;
import com.ses.privacy.foundation.PrivacyJournalPort.JournalPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * hold 解除 proof の verifier・Journal の append 時の判定・解除を含む chain の検証（NF07-REL、sf-w2-protocol.md §11.1）。
 * 鍵は {@link HoldReleaseTestAuthorities} が実行のたびに memory 上で生成する test-only 鍵だけを使う。
 */
class HoldReleaseProofVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private static final String TENANT = "default";
    private static final String SUBJECT = "ENGINEER:7";
    private static final String HOLD = "ph-" + "a".repeat(32);
    private static final String COMMAND = "hr-" + "b".repeat(32);
    private static final String START_HASH = "c".repeat(64);
    private static final String REQUESTER = "sys_user:1";
    private static final String ISSUER = "issuer-a";
    private static final String APPROVER = "approver-b";

    private HoldReleaseTestAuthorities authorities;
    private volatile RevocationStatusProvider revocations;
    private HoldReleaseVerifier verifier;
    private HoldReleaseBinding binding;

    @BeforeEach
    void setUp() {
        authorities = new HoldReleaseTestAuthorities();
        authorities.register(ISSUER, "sys_user:20", TENANT);
        authorities.register(APPROVER, "sys_user:30", TENANT);
        revocations = ref -> RevocationStatus.ACTIVE;
        verifier = ProductionPrivacyComponents.holdReleaseVerifier(prod(), authorities.registry(),
                ref -> revocations.statusOf(ref), Clock.fixed(NOW, ZoneOffset.UTC));
        binding = new HoldReleaseBinding(TENANT, SUBJECT, HOLD, 1, sha256("訴訟終了のため解除する"), COMMAND, 1,
                START_HASH, REQUESTER);
    }

    // --- 発行と束縛 ---

    @Test
    void 二者の署名が揃えばproofを発行しcommandは束縛から一意に決まる() {
        VerifiedHoldRelease proof = verify(binding, issuer(binding), approver(binding));

        assertThat(proof.binding()).isEqualTo(binding);
        assertThat(proof.command().type()).isEqualTo(CommandType.HOLD_RELEASED);
        assertThat(proof.command().caseId()).isEqualTo(HOLD);
        assertThat(proof.command().requestHash()).isEqualTo(binding.digest());
        assertThat(proof.command().expectedRevision()).isEqualTo(1);
        assertThat(proof.command().partition()).isEqualTo(new JournalPartition(TENANT, SUBJECT));
        assertThat(proof.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
        assertThat(proof.issuerAnchorId()).isEqualTo(ISSUER + ":k1");
        assertThat(proof.approverAnchorId()).isEqualTo(APPROVER + ":k1");
    }

    @Test
    void proofの有効期限は両claimと両anchorの期限の最小値() {
        HoldReleaseAuthorityClaim shortIssuer = authorities.sign(ISSUER, Role.ISSUER, binding, NOW.minusSeconds(60),
                NOW.plusSeconds(90));

        assertThat(verify(binding, shortIssuer, approver(binding)).expiresAt()).isEqualTo(NOW.plusSeconds(90));
    }

    static Stream<Arguments> bindingMismatches() {
        return Stream.of(
                Arguments.of("tenant", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, "tenant-b", null, null, -1, null,
                        null, -1, null, null), HoldReleaseRejection.TENANT_MISMATCH),
                Arguments.of("subject", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, "ENGINEER:8", null, -1,
                        null, null, -1, null, null), HoldReleaseRejection.SUBJECT_MISMATCH),
                Arguments.of("hold", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, null, "ph-" + "d".repeat(32),
                        -1, null, null, -1, null, null), HoldReleaseRejection.HOLD_MISMATCH),
                Arguments.of("holdRevision", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, null, null, 2, null,
                        null, 3, null, null), HoldReleaseRejection.HOLD_REVISION_MISMATCH),
                Arguments.of("journalRevision", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, null, null, -1,
                        null, null, 2, null, null), HoldReleaseRejection.JOURNAL_HEAD_MISMATCH),
                Arguments.of("journalHash", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, null, null, -1, null,
                        null, -1, "e".repeat(64), null), HoldReleaseRejection.JOURNAL_HEAD_MISMATCH),
                Arguments.of("command", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, null, null, -1, null,
                        "hr-" + "f".repeat(32), -1, null, null), HoldReleaseRejection.COMMAND_MISMATCH),
                Arguments.of("reasonHash", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, null, null, -1,
                        sha256("別の理由"), null, -1, null, null), HoldReleaseRejection.REASON_HASH_MISMATCH),
                Arguments.of("requester", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, null, null, -1, null,
                        null, -1, null, "sys_user:2"), HoldReleaseRejection.REQUESTER_MISMATCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindingMismatches")
    void 提示された束縛が信頼できる現在値と違えば署名が正しくても拒否する(String field,
                                                UnaryOperator<HoldReleaseBinding> change,
                                                HoldReleaseRejection expected) {
        HoldReleaseBinding presented = change.apply(binding);
        // 提示された束縛に正しく署名していても、現在値（expected）と違えば拒否する
        assertRejected(() -> verifier.verify(presented, binding, "SUBJECT", issuer(presented), approver(presented)),
                expected);
    }

    // --- trust anchor・署名 ---

    @Test
    void trust_anchorが未登録なら拒否する() {
        HoldReleaseVerifier empty = ProductionPrivacyComponents.holdReleaseVerifier(prod(), () -> java.util.Map.of(),
                ref -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC));
        assertRejected(() -> empty.verify(binding, binding, "SUBJECT", issuer(binding), approver(binding)),
                HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN);

        HoldReleaseAuthorityClaim unknown = HoldReleaseTestAuthorities.signWithForeignKey("someone-else", Role.ISSUER,
                binding, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertRejected(() -> verify(binding, unknown, approver(binding)), HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN);

        HoldReleaseVerifier failing = ProductionPrivacyComponents.holdReleaseVerifier(prod(), () -> {
            throw new IllegalStateException("registry down");
        }, ref -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC));
        assertRejected(() -> failing.verify(binding, binding, "SUBJECT", issuer(binding), approver(binding)),
                HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN);
    }

    @Test
    void 登録されていない鍵の署名と改竄した署名は拒否する() {
        HoldReleaseAuthorityClaim foreign = HoldReleaseTestAuthorities.signWithForeignKey(ISSUER, Role.ISSUER, binding,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertRejected(() -> verify(binding, foreign, approver(binding)), HoldReleaseRejection.SIGNATURE_INVALID);

        HoldReleaseAuthorityClaim good = approver(binding);
        byte[] tampered = good.signature();
        tampered[0] ^= 0x01;
        HoldReleaseAuthorityClaim broken = new HoldReleaseAuthorityClaim(good.role(), good.authorityId(), good.keyId(),
                good.issuedAt(), good.expiresAt(), tampered);
        assertRejected(() -> verify(binding, issuer(binding), broken), HoldReleaseRejection.SIGNATURE_INVALID);
    }

    @Test
    void 別の束縛への署名は流用できない() {
        HoldReleaseBinding other = copy(binding, null, null, null, -1, sha256("別の理由"), null, -1, null, null);
        HoldReleaseAuthorityClaim signedForOther = authorities.sign(ISSUER, Role.ISSUER, other, NOW.minusSeconds(60),
                NOW.plusSeconds(3600));

        assertRejected(() -> verify(binding, signedForOther, approver(binding)), HoldReleaseRejection.SIGNATURE_INVALID);
    }

    @Test
    void 署名の期限を延ばした値は署名と一致せず拒否する() {
        HoldReleaseAuthorityClaim good = issuer(binding);
        HoldReleaseAuthorityClaim extended = new HoldReleaseAuthorityClaim(good.role(), good.authorityId(), good.keyId(),
                good.issuedAt(), good.expiresAt().plus(Duration.ofDays(30)), good.signature());

        assertRejected(() -> verify(binding, extended, approver(binding)), HoldReleaseRejection.SIGNATURE_INVALID);
    }

    // --- 失効 ---

    @Test
    void 失効状態がUNKNOWN_照会不能_providerなしなら拒否する() {
        revocations = ref -> RevocationStatus.UNKNOWN;
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)), HoldReleaseRejection.REVOCATION_UNKNOWN);

        revocations = ref -> {
            throw new IllegalStateException("ocsp down");
        };
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)), HoldReleaseRejection.REVOCATION_UNKNOWN);

        HoldReleaseVerifier noProvider = ProductionPrivacyComponents.holdReleaseVerifier(prod(), authorities.registry(),
                null, Clock.fixed(NOW, ZoneOffset.UTC));
        assertRejected(() -> noProvider.verify(binding, binding, "SUBJECT", issuer(binding), approver(binding)),
                HoldReleaseRejection.REVOCATION_UNKNOWN);
    }

    @Test
    void どちらかの鍵が失効していれば拒否する() {
        revocations = ref -> ref.startsWith(APPROVER) ? RevocationStatus.REVOKED : RevocationStatus.ACTIVE;

        assertRejected(() -> verify(binding, issuer(binding), approver(binding)), HoldReleaseRejection.REVOKED);
    }

    // --- 期限 ---

    @Test
    void claimの期限切れと期限が発行日時以前のclaimは拒否する() {
        HoldReleaseAuthorityClaim expired = authorities.sign(ISSUER, Role.ISSUER, binding, NOW.minusSeconds(7200),
                NOW.minusSeconds(1));
        assertRejected(() -> verify(binding, expired, approver(binding)), HoldReleaseRejection.EXPIRED);

        HoldReleaseAuthorityClaim atNow = authorities.sign(APPROVER, Role.APPROVER, binding, NOW.minusSeconds(60), NOW);
        assertRejected(() -> verify(binding, issuer(binding), atNow), HoldReleaseRejection.EXPIRED);

        HoldReleaseAuthorityClaim inverted = authorities.sign(ISSUER, Role.ISSUER, binding, NOW.minusSeconds(10),
                NOW.minusSeconds(20));
        assertRejected(() -> verify(binding, inverted, approver(binding)), HoldReleaseRejection.EXPIRED);
    }

    @Test
    void trust_anchorの有効期間外は拒否する() {
        authorities.register(ISSUER, "sys_user:20", Set.of(TENANT), Set.of(Role.ISSUER), Set.of("SUBJECT"),
                NOW.minus(Duration.ofDays(30)), NOW);
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)), HoldReleaseRejection.EXPIRED);

        authorities.register(ISSUER, "sys_user:20", Set.of(TENANT), Set.of(Role.ISSUER), Set.of("SUBJECT"),
                NOW.plusSeconds(60), NOW.plus(Duration.ofDays(30)));
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)), HoldReleaseRejection.NOT_YET_VALID);
    }

    @Test
    void 発行日時が未来のclaimと鍵の有効期間より前に発行したclaimは拒否する() {
        HoldReleaseAuthorityClaim future = authorities.sign(ISSUER, Role.ISSUER, binding, NOW.plusSeconds(60),
                NOW.plusSeconds(3600));
        assertRejected(() -> verify(binding, future, approver(binding)), HoldReleaseRejection.NOT_YET_VALID);

        HoldReleaseAuthorityClaim beforeKey = authorities.sign(ISSUER, Role.ISSUER, binding,
                HoldReleaseTestAuthorities.VALID_FROM.minusSeconds(1), NOW.plusSeconds(3600));
        assertRejected(() -> verify(binding, beforeKey, approver(binding)), HoldReleaseRejection.NOT_YET_VALID);
    }

    // --- 三方の分離 ---

    @Test
    void 要求者が発行者または承認者と同じ主体なら拒否する() {
        authorities.rebindPrincipal(ISSUER, REQUESTER);
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)),
                HoldReleaseRejection.SEPARATION_OF_DUTIES);

        authorities.rebindPrincipal(ISSUER, "sys_user:20");
        authorities.rebindPrincipal(APPROVER, REQUESTER);
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)),
                HoldReleaseRejection.SEPARATION_OF_DUTIES);
    }

    @Test
    void 発行者と承認者が同じ主体または同じauthorityなら拒否する() {
        authorities.rebindPrincipal(APPROVER, "sys_user:20");
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)),
                HoldReleaseRejection.SEPARATION_OF_DUTIES);

        authorities.rebindPrincipal(APPROVER, "sys_user:30");
        // 同じ authority が両方の役割で署名する（anchor は両方の役割を許していても一人二役は拒否する）
        HoldReleaseAuthorityClaim selfApproval = authorities.sign(ISSUER, Role.APPROVER, binding, NOW.minusSeconds(60),
                NOW.plusSeconds(3600));
        assertRejected(() -> verify(binding, issuer(binding), selfApproval), HoldReleaseRejection.SEPARATION_OF_DUTIES);
    }

    // --- 範囲・役割 ---

    @Test
    void trust_anchorの範囲外のtenant_役割_対象種別は拒否する() {
        authorities.register(ISSUER, "sys_user:20", Set.of("tenant-b"), Set.of(Role.ISSUER), Set.of("SUBJECT"),
                HoldReleaseTestAuthorities.VALID_FROM, HoldReleaseTestAuthorities.VALID_UNTIL);
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)), HoldReleaseRejection.SCOPE_MISMATCH);

        authorities.register(ISSUER, "sys_user:20", Set.of(TENANT), Set.of(Role.APPROVER), Set.of("SUBJECT"),
                HoldReleaseTestAuthorities.VALID_FROM, HoldReleaseTestAuthorities.VALID_UNTIL);
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)), HoldReleaseRejection.SCOPE_MISMATCH);

        authorities.register(ISSUER, "sys_user:20", Set.of(TENANT), Set.of(Role.ISSUER), Set.of("DATA_ELEMENT"),
                HoldReleaseTestAuthorities.VALID_FROM, HoldReleaseTestAuthorities.VALID_UNTIL);
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)), HoldReleaseRejection.SCOPE_MISMATCH);
    }

    @Test
    void 役割の取り違えとclaimの欠落は拒否する() {
        HoldReleaseAuthorityClaim approverAsIssuer = authorities.sign(APPROVER, Role.APPROVER, binding,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        HoldReleaseAuthorityClaim issuerAsApprover = authorities.sign(ISSUER, Role.ISSUER, binding,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertRejected(() -> verify(binding, approverAsIssuer, issuerAsApprover), HoldReleaseRejection.ROLE_MISMATCH);

        assertRejected(() -> verify(binding, null, approver(binding)), HoldReleaseRejection.CLAIM_MISSING);
        assertRejected(() -> verify(binding, issuer(binding), null), HoldReleaseRejection.CLAIM_MISSING);
    }

    @Test
    void prod以外ではverifierを生成できない() {
        for (String[] active : List.of(new String[]{"test"}, new String[]{}, new String[]{"dev"})) {
            MockEnvironment environment = new MockEnvironment();
            environment.setActiveProfiles(active);
            assertThatThrownBy(() -> ProductionPrivacyComponents.holdReleaseVerifier(environment, authorities.registry(),
                    ref -> RevocationStatus.ACTIVE, Clock.systemUTC())).isInstanceOf(SecurityException.class);
        }
        MockEnvironment defaultProd = new MockEnvironment();
        defaultProd.setDefaultProfiles("prod");
        assertThatThrownBy(() -> ProductionPrivacyComponents.holdReleaseVerifier(defaultProd, authorities.registry(),
                ref -> RevocationStatus.ACTIVE, Clock.systemUTC())).isInstanceOf(SecurityException.class);
        assertThat(VerifiedHoldRelease.class.getConstructors()).as("proof に public constructor が無い").isEmpty();
    }

    // --- Journal の append 時の判定（head と hold revision の CAS） ---

    @Test
    void 一致するheadとholdならappendできる() {
        List<JournalEvent> events = List.of(start(1, HOLD, ZERO));
        HoldReleaseBinding current = bindingFor(events);
        VerifiedHoldRelease proof = verify(current, issuer(current), approver(current));

        proof.requireAppendable(head(events, true), events, NOW);
    }

    @Test
    void headのrevisionかhashが期待値と違えばappendしない() {
        List<JournalEvent> chain = new ArrayList<>(List.of(start(1, HOLD, ZERO)));
        HoldReleaseBinding atRevision1 = bindingFor(chain);
        VerifiedHoldRelease proof = verify(atRevision1, issuer(atRevision1), approver(atRevision1));
        JournalHead moved = new JournalHead(new JournalPartition(TENANT, SUBJECT), 2, "9".repeat(64), true, 0, null, null);

        assertThatThrownBy(() -> proof.requireAppendable(moved, chain, NOW)).isInstanceOf(JournalConflictException.class);
        JournalHead otherHash = new JournalHead(new JournalPartition(TENANT, SUBJECT), 1, "9".repeat(64), true, 0, null,
                null);
        assertThatThrownBy(() -> proof.requireAppendable(otherHash, chain, NOW))
                .isInstanceOf(JournalConflictException.class);
    }

    @Test
    void 有効なholdが無い_別のholdID_holdのrevisionが違えばappendしない() {
        List<JournalEvent> released = chain(start(1, HOLD, ZERO));
        released.add(release(2, HOLD, released.get(0).eventHash()));
        HoldReleaseBinding atReleased = new HoldReleaseBinding(TENANT, SUBJECT, HOLD, 1, binding.reasonHash(), COMMAND, 2,
                released.get(1).eventHash(), REQUESTER);
        VerifiedHoldRelease againProof = verify(atReleased, issuer(atReleased), approver(atReleased));
        assertThatThrownBy(() -> againProof.requireAppendable(head(released, false), released, NOW))
                .isInstanceOf(JournalConflictException.class).hasMessageContaining("有効ではありません");

        // head は一致するが、partition で有効な hold が proof の hold と別
        String otherHold = "ph-" + "9".repeat(32);
        List<JournalEvent> other = chain(start(1, otherHold, ZERO));
        HoldReleaseBinding forOther = new HoldReleaseBinding(TENANT, SUBJECT, HOLD, 1, binding.reasonHash(), COMMAND, 1,
                other.get(0).eventHash(), REQUESTER);
        VerifiedHoldRelease wrongHold = verify(forOther, issuer(forOther), approver(forOther));
        assertThatThrownBy(() -> wrongHold.requireAppendable(head(other, true), other, NOW))
                .isInstanceOf(JournalConflictException.class).hasMessageContaining("ID");

        // 解除の後に同じ partition で開始した新しい hold（revision 3）に、古い hold の revision（1）の proof は使えない
        List<JournalEvent> restarted = chain(start(1, HOLD, ZERO));
        restarted.add(release(2, HOLD, restarted.get(0).eventHash()));
        restarted.add(start(3, HOLD, restarted.get(1).eventHash()));
        HoldReleaseBinding staleRevision = new HoldReleaseBinding(TENANT, SUBJECT, HOLD, 1, binding.reasonHash(), COMMAND,
                3, restarted.get(2).eventHash(), REQUESTER);
        VerifiedHoldRelease stale = verify(staleRevision, issuer(staleRevision), approver(staleRevision));
        assertThatThrownBy(() -> stale.requireAppendable(head(restarted, true), restarted, NOW))
                .isInstanceOf(JournalConflictException.class).hasMessageContaining("revision");
    }

    @Test
    void append時点でproofの期限を過ぎていれば拒否する() {
        List<JournalEvent> events = List.of(start(1, HOLD, ZERO));
        HoldReleaseBinding current = bindingFor(events);
        HoldReleaseAuthorityClaim shortIssuer = authorities.sign(ISSUER, Role.ISSUER, current, NOW.minusSeconds(60),
                NOW.plusSeconds(5));
        VerifiedHoldRelease proof = verify(current, shortIssuer, approver(current));
        proof.requireAppendable(head(events, true), events, NOW.plusSeconds(4));

        assertThatThrownBy(() -> proof.requireAppendable(head(events, true), events, NOW.plusSeconds(5)))
                .isInstanceOfSatisfying(HoldReleaseRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(HoldReleaseRejection.EXPIRED));
    }

    // --- 解除を含む chain の検証（projection の前の foundation 側の検証） ---

    @Test
    void 開始と解除のchainと解除後の再開始を検証できる() {
        List<JournalEvent> events = chain(start(1, HOLD, ZERO));
        events.add(release(2, HOLD, events.get(0).eventHash()));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(events, false), events)).isTrue();

        String next = "ph-" + "8".repeat(32);
        events.add(start(3, next, events.get(1).eventHash()));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(events, true), events)).isTrue();
        assertThat(ProductionJournalChainVerification.verify(partition(), head(events, false), events))
                .as("head の held が chain と違えば不整合").isFalse();
    }

    @Test
    void 有効なholdに対応しない解除と改竄した解除はchainとして認めない() {
        List<JournalEvent> withoutStart = chain(release(1, HOLD, ZERO));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(withoutStart, false), withoutStart)).isFalse();

        List<JournalEvent> wrongHold = chain(start(1, HOLD, ZERO));
        wrongHold.add(release(2, "ph-" + "7".repeat(32), wrongHold.get(0).eventHash()));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(wrongHold, false), wrongHold)).isFalse();

        List<JournalEvent> twice = chain(start(1, HOLD, ZERO));
        twice.add(release(2, HOLD, twice.get(0).eventHash()));
        twice.add(release(3, HOLD, twice.get(1).eventHash()));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(twice, false), twice)).isFalse();

        List<JournalEvent> tampered = chain(start(1, HOLD, ZERO));
        JournalEvent good = release(2, HOLD, tampered.get(0).eventHash());
        tampered.add(new JournalEvent(good.revision(), good.commandId(), good.payloadHash(), good.type(), good.caseId(),
                good.actionId(), "0".repeat(64), good.workerId(), good.expectedLeaseGeneration(), good.leaseUntil(),
                good.previousHash(), good.eventHash(), good.createdAt()));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(tampered, false), tampered)).isFalse();
    }

    @Test
    void 解除を含まないchainは従来どおりfoundationの検証で決まる() {
        List<JournalEvent> events = chain(start(1, HOLD, ZERO));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(events, true), events)).isTrue();
        JournalHead wrong = new JournalHead(partition(), 1, "1".repeat(64), true, 0, null, null);
        assertThat(ProductionJournalChainVerification.verify(partition(), wrong, events)).isFalse();
    }

    // --- helpers ---

    private static final String ZERO = "0".repeat(64);

    private VerifiedHoldRelease verify(HoldReleaseBinding presented, HoldReleaseAuthorityClaim issuer,
                                       HoldReleaseAuthorityClaim approver) {
        return verifier.verify(presented, presented, "SUBJECT", issuer, approver);
    }

    private HoldReleaseAuthorityClaim issuer(HoldReleaseBinding target) {
        return authorities.sign(ISSUER, Role.ISSUER, target, NOW.minusSeconds(60), NOW.plus(Duration.ofHours(1)));
    }

    private HoldReleaseAuthorityClaim approver(HoldReleaseBinding target) {
        return authorities.sign(APPROVER, Role.APPROVER, target, NOW.minusSeconds(60), NOW.plus(Duration.ofHours(2)));
    }

    private HoldReleaseBinding bindingFor(List<JournalEvent> events) {
        JournalEvent last = events.get(events.size() - 1);
        JournalEvent active = VerifiedHoldRelease.activeHoldStart(events);
        return new HoldReleaseBinding(TENANT, SUBJECT, active.caseId(), active.revision(), binding.reasonHash(), COMMAND,
                last.revision(), last.eventHash(), REQUESTER);
    }

    private static void assertRejected(Runnable call, HoldReleaseRejection reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(HoldReleaseRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static HoldReleaseBinding copy(HoldReleaseBinding b, String tenant, String subject, String hold,
                                           long holdRevision, String reasonHash, String command, long journalRevision,
                                           String journalHash, String requester) {
        return new HoldReleaseBinding(tenant == null ? b.tenantId() : tenant, subject == null ? b.subjectRef() : subject,
                hold == null ? b.holdId() : hold, holdRevision < 0 ? b.expectedHoldRevision() : holdRevision,
                reasonHash == null ? b.reasonHash() : reasonHash, command == null ? b.commandId() : command,
                journalRevision < 0 ? b.expectedJournalRevision() : journalRevision,
                journalHash == null ? b.expectedJournalHash() : journalHash, requester == null ? b.requesterRef() : requester);
    }

    private static JournalPartition partition() {
        return new JournalPartition(TENANT, SUBJECT);
    }

    private static List<JournalEvent> chain(JournalEvent first) {
        List<JournalEvent> events = new ArrayList<>();
        events.add(first);
        return events;
    }

    private static JournalHead head(List<JournalEvent> events, boolean held) {
        JournalEvent last = events.get(events.size() - 1);
        return new JournalHead(partition(), last.revision(), last.eventHash(), held, 0, null, null);
    }

    private static JournalEvent start(long revision, String holdId, String previous) {
        return event(revision, "hs-" + holdId.substring(3), CommandType.HOLD_STARTED, holdId, "1".repeat(64), previous);
    }

    private static JournalEvent release(long revision, String holdId, String previous) {
        return event(revision, "hr-" + Long.toString(revision).repeat(32).substring(0, 32), CommandType.HOLD_RELEASED,
                holdId, "2".repeat(64), previous);
    }

    private static JournalEvent event(long revision, String commandId, CommandType type, String caseId,
                                      String requestHash, String previous) {
        Instant createdAt = NOW.minusSeconds(100 - revision);
        String payload = PrivacyJournalPort.JournalCommand.create(commandId, partition(), revision - 1, type, caseId,
                null, requestHash, null, 0, null).payloadHash();
        JournalEvent draft = new JournalEvent(revision, commandId, payload, type, caseId, null, requestHash, null, 0, null,
                previous, null, createdAt);
        return new JournalEvent(revision, commandId, payload, type, caseId, null, requestHash, null, 0, null, previous,
                eventHash(draft, previous), createdAt);
    }

    /** foundation・本番 adapter と同じ event hash の正規形。 */
    private static String eventHash(JournalEvent event, String previous) {
        StringBuilder bytes = new StringBuilder();
        field(bytes, "SES-SF-W2-EVENT-V1");
        field(bytes, TENANT);
        field(bytes, SUBJECT);
        field(bytes, Long.toString(event.revision()));
        field(bytes, event.commandId());
        field(bytes, event.payloadHash());
        field(bytes, event.type().name());
        field(bytes, event.caseId());
        field(bytes, event.actionId());
        field(bytes, event.requestHash());
        field(bytes, previous);
        field(bytes, event.workerId());
        field(bytes, Long.toString(event.expectedLeaseGeneration()));
        field(bytes, null);
        field(bytes, event.createdAt().toString());
        return sha256(bytes.toString());
    }

    private static void field(StringBuilder target, String value) {
        if (value == null) {
            target.append("-1:");
        } else {
            target.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockEnvironment prod() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }
}
