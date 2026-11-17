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
    private static final String STARTER = "sys_user:2";
    private static final String ISSUER = "issuer-a";
    private static final String APPROVER = "approver-b";

    private HoldReleaseRules rules = HoldReleaseRules.strict();
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
                START_HASH, REQUESTER, STARTER);
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
                        null, -1, null, "sys_user:2"), HoldReleaseRejection.REQUESTER_MISMATCH),
                Arguments.of("holdStarter", (UnaryOperator<HoldReleaseBinding>) b -> copy(b, null, null, null, -1, null,
                        null, -1, null, null, "sys_user:3"), HoldReleaseRejection.HOLD_STARTER_MISMATCH));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bindingMismatches")
    void 提示された束縛が信頼できる現在値と違えば署名が正しくても拒否する(String field,
                                                UnaryOperator<HoldReleaseBinding> change,
                                                HoldReleaseRejection expected) {
        HoldReleaseBinding presented = change.apply(binding);
        // 提示された束縛に正しく署名していても、現在値（expected）と違えば拒否する
        assertRejected(() -> verifier.verify(presented, binding, "SUBJECT", rules,issuer(presented), approver(presented)),
                expected);
    }

    // --- trust anchor・署名 ---

    @Test
    void trust_anchorが未登録なら拒否する() {
        HoldReleaseVerifier empty = ProductionPrivacyComponents.holdReleaseVerifier(prod(), () -> java.util.Map.of(),
                ref -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC));
        assertRejected(() -> empty.verify(binding, binding, "SUBJECT", rules,issuer(binding), approver(binding)),
                HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN);

        HoldReleaseAuthorityClaim unknown = HoldReleaseTestAuthorities.signWithForeignKey("someone-else", Role.ISSUER,
                binding, NOW.minusSeconds(60), NOW.plusSeconds(3600));
        assertRejected(() -> verify(binding, unknown, approver(binding)), HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN);

        HoldReleaseVerifier failing = ProductionPrivacyComponents.holdReleaseVerifier(prod(), () -> {
            throw new IllegalStateException("registry down");
        }, ref -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC));
        assertRejected(() -> failing.verify(binding, binding, "SUBJECT", rules,issuer(binding), approver(binding)),
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
        assertRejected(() -> noProvider.verify(binding, binding, "SUBJECT", rules,issuer(binding), approver(binding)),
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

    // --- hold の開始者（D-05 の解除の承認者規則） ---

    @Test
    void 発行者か承認者がholdの開始者と同じ主体なら既定で拒否しD05が分離を求めなければ通す() {
        authorities.rebindPrincipal(ISSUER, STARTER);
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)),
                HoldReleaseRejection.SEPARATION_OF_DUTIES);
        authorities.rebindPrincipal(ISSUER, "sys_user:20");
        authorities.rebindPrincipal(APPROVER, STARTER);
        assertRejected(() -> verify(binding, issuer(binding), approver(binding)),
                HoldReleaseRejection.SEPARATION_OF_DUTIES);

        rules = new HoldReleaseRules(false, true, Duration.ZERO);
        assertThat(verify(binding, issuer(binding), approver(binding)).binding().holdStarterRef()).isEqualTo(STARTER);
    }

    @Test
    void 要求者はholdの開始者と同じでもよい() {
        HoldReleaseBinding byStarter = copy(binding, null, null, null, -1, null, null, -1, null, STARTER);

        assertThat(verify(byStarter, issuer(byStarter), approver(byStarter)).binding().requesterRef()).isEqualTo(STARTER);
    }

    @Test
    void 開始者が不明のholdは既定で拒否しD05が認めれば開始者との分離を判定せず通す() {
        HoldReleaseBinding unknown = copy(binding, null, null, null, -1, null, null, -1, null, null,
                HoldReleaseBinding.UNKNOWN_STARTER);
        assertRejected(() -> verify(unknown, issuer(unknown), approver(unknown)), HoldReleaseRejection.HOLD_STARTER_UNKNOWN);

        rules = new HoldReleaseRules(true, false, Duration.ZERO);
        assertThat(verify(unknown, issuer(unknown), approver(unknown)).binding().starterKnown()).isFalse();
    }

    @Test
    void 署名を確かめる前には主体の判定を返さない() {
        authorities.rebindPrincipal(ISSUER, REQUESTER);
        HoldReleaseAuthorityClaim forged = HoldReleaseTestAuthorities.signWithForeignKey(ISSUER, Role.ISSUER, binding,
                NOW.minusSeconds(60), NOW.plusSeconds(3600));

        assertRejected(() -> verify(binding, forged, approver(binding)), HoldReleaseRejection.SIGNATURE_INVALID);
    }

    // --- 主体の正規形・理由の hash・署名の正規形 ---

    @Test
    void trust_anchorの主体は正規形のsys_user_idだけを登録できる() {
        java.security.PublicKey key = HoldReleaseTestAuthorities.generate().getPublic();
        for (String bad : List.of("SYS_USER:20", "sys_user:020", "kms:alice", "sys_user:0", "sys_user:", " sys_user:20",
                "sys_user:20 ", "sys_user:-1", "sys_user:２０")) {
            assertThatThrownBy(() -> new HoldReleaseTrustAnchor("issuer-x", "k1", key, bad, Set.of(TENANT),
                    Set.of(Role.ISSUER), Set.of("SUBJECT"), HoldReleaseTestAuthorities.VALID_FROM,
                    HoldReleaseTestAuthorities.VALID_UNTIL)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(new HoldReleaseTrustAnchor("issuer-x", "k1", key, "sys_user:20", Set.of(TENANT), Set.of(Role.ISSUER),
                Set.of("SUBJECT"), HoldReleaseTestAuthorities.VALID_FROM, HoldReleaseTestAuthorities.VALID_UNTIL)
                .principalRef()).isEqualTo("sys_user:20");
    }

    @Test
    void 束縛の要求者と開始者は正規形だけを受け付ける() {
        for (String bad : List.of("SYS_USER:1", "sys_user:01", "sys_user:0", "kms:alice")) {
            assertThatThrownBy(() -> copy(binding, null, null, null, -1, null, null, -1, null, bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        for (String bad : List.of("SYS_USER:2", "sys_user:02", "kms:alice", "sys_user:")) {
            assertThatThrownBy(() -> copy(binding, null, null, null, -1, null, null, -1, null, null, bad))
                    .as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(copy(binding, null, null, null, -1, null, null, -1, null, null, HoldReleaseBinding.UNKNOWN_STARTER)
                .starterKnown()).isFalse();
    }

    @Test
    void 理由のhashは理由コードとticketと理由本文の全てを束縛する() {
        String base = HoldReleaseBinding.reasonHash("LITIGATION_CLOSED", "TICKET-2001", "理由");

        assertThat(HoldReleaseBinding.reasonHash("LITIGATION_ENDED", "TICKET-2001", "理由")).isNotEqualTo(base);
        assertThat(HoldReleaseBinding.reasonHash("LITIGATION_CLOSED", "TICKET-2002", "理由")).isNotEqualTo(base);
        assertThat(HoldReleaseBinding.reasonHash("LITIGATION_CLOSED", "TICKET-2001", "理由。")).isNotEqualTo(base);
        // field の境界をずらしても同じ hash にならない（長さ付き field）
        assertThat(HoldReleaseBinding.reasonHash("AB", "C", "x")).isNotEqualTo(HoldReleaseBinding.reasonHash("A", "BC", "x"));
        assertThatThrownBy(() -> HoldReleaseBinding.reasonHash(null, "TICKET-2001", "理由"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 対になっていないsurrogateは理由のhashの正規形に入れない() {
        assertThatThrownBy(() -> HoldReleaseBinding.reasonHash("LITIGATION_CLOSED", "TICKET-2001", "理由\uD800"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HoldReleaseBinding.reasonHash("LITIGATION_CLOSED", "TICKET-2001", "\uDC00理由"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(HoldReleaseBinding.isWellFormed("理由\uD800x")).isFalse();
        // 対になった surrogate（絵文字等）は Java と Web Crypto で同じ 4 byte になるので受け付ける
        assertThat(HoldReleaseBinding.isWellFormed("理由😀")).isTrue();
        assertThat(HoldReleaseBinding.reasonHash("LITIGATION_CLOSED", "TICKET-2001", "理由😀")).hasSize(64);
    }

    /** deploy-runbook §4.5 の test vector（Java とは独立に Node.js で計算した値）。 */
    @Test
    void 署名の正規形はrunbookのtest_vectorと一致する() {
        String reasonHash = HoldReleaseBinding.reasonHash("LITIGATION_CLOSED", "TICKET-2001",
                "訴訟が終結したため保全を解除する");
        assertThat(reasonHash).isEqualTo("8e3ccdd899ebae58d63b1319e0820aea0d7ff751194d7d57bee7d8873f5dacd5");

        HoldReleaseBinding vector = new HoldReleaseBinding("default", "ENGINEER:7", "ph-" + "a".repeat(32), 1,
                reasonHash, "hr-" + "b".repeat(32), 1, "c".repeat(64), "sys_user:1", "sys_user:2");
        assertThat(new String(vector.canonicalBytes(), StandardCharsets.UTF_8)).isEqualTo("28:NF07-HOLD-RELEASE-BINDING-V1"
                + "7:default10:ENGINEER:735:ph-" + "a".repeat(32) + "1:112:HOLD_RELEASE64:" + reasonHash
                + "35:hr-" + "b".repeat(32) + "1:164:" + "c".repeat(64) + "10:sys_user:110:sys_user:2");
        assertThat(vector.digest()).isEqualTo("cc4d333d9dd9ab7078a337c9464ae944da3204234bad2ac993e0b71109e1fb9a");

        String signed = new String(HoldReleaseAuthorityClaim.signedBytes(vector, Role.ISSUER, "issuer-a", "k1",
                Instant.parse("2026-11-16T02:00:00Z"), Instant.parse("2026-11-16T04:00:00Z")), StandardCharsets.UTF_8);
        assertThat(signed).isEqualTo("30:NF07-HOLD-RELEASE-AUTHORITY-V110:PRODUCTION12:HOLD_RELEASE6:ISSUER"
                + "8:issuer-a2:k120:2026-11-16T02:00:00Z20:2026-11-16T04:00:00Z64:" + vector.digest());
        assertThat(sha256(signed)).isEqualTo("549567a997a2a84a5ecc5bca965d7bb8f3b403b96c57444fedc935a6833daa18");
        // 時刻は Instant.toString() の正規形（小数秒が 0 なら付けない）。".000Z" で書いた値も同じ正規形になる
        assertThat(Instant.parse("2026-11-16T02:00:00.000Z").toString()).isEqualTo("2026-11-16T02:00:00Z");
    }

    @Test
    void 発行日時の未来は時計の許容ずれまで受け付け期限の側は広げない() {
        rules = new HoldReleaseRules(true, true, Duration.ofSeconds(60));
        HoldReleaseAuthorityClaim slightlyAhead = authorities.sign(ISSUER, Role.ISSUER, binding, NOW.plusSeconds(30),
                NOW.plusSeconds(3600));
        assertThat(verify(binding, slightlyAhead, approver(binding))).isNotNull();

        HoldReleaseAuthorityClaim tooFar = authorities.sign(ISSUER, Role.ISSUER, binding, NOW.plusSeconds(61),
                NOW.plusSeconds(3600));
        assertRejected(() -> verify(binding, tooFar, approver(binding)), HoldReleaseRejection.NOT_YET_VALID);

        HoldReleaseAuthorityClaim expired = authorities.sign(ISSUER, Role.ISSUER, binding, NOW.minusSeconds(120),
                NOW.minusSeconds(1));
        assertRejected(() -> verify(binding, expired, approver(binding)), HoldReleaseRejection.EXPIRED);
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
                released.get(1).eventHash(), REQUESTER, STARTER);
        VerifiedHoldRelease againProof = verify(atReleased, issuer(atReleased), approver(atReleased));
        assertThatThrownBy(() -> againProof.requireAppendable(head(released, false), released, NOW))
                .isInstanceOf(JournalConflictException.class).hasMessageContaining("有効ではありません");

        // head は一致するが、partition で有効な hold が proof の hold と別
        String otherHold = "ph-" + "9".repeat(32);
        List<JournalEvent> other = chain(start(1, otherHold, ZERO));
        HoldReleaseBinding forOther = new HoldReleaseBinding(TENANT, SUBJECT, HOLD, 1, binding.reasonHash(), COMMAND, 1,
                other.get(0).eventHash(), REQUESTER, STARTER);
        VerifiedHoldRelease wrongHold = verify(forOther, issuer(forOther), approver(forOther));
        assertThatThrownBy(() -> wrongHold.requireAppendable(head(other, true), other, NOW))
                .isInstanceOf(JournalConflictException.class).hasMessageContaining("ID");

        // 解除の後に同じ partition で開始した新しい hold（revision 3）に、古い hold の revision（1）の proof は使えない
        List<JournalEvent> restarted = chain(start(1, HOLD, ZERO));
        restarted.add(release(2, HOLD, restarted.get(0).eventHash()));
        restarted.add(start(3, HOLD, restarted.get(1).eventHash()));
        HoldReleaseBinding staleRevision = new HoldReleaseBinding(TENANT, SUBJECT, HOLD, 1, binding.reasonHash(), COMMAND,
                3, restarted.get(2).eventHash(), REQUESTER, STARTER);
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
    void claimのdigestを持たない解除eventはchainとして認めない() {
        List<JournalEvent> withoutClaims = chain(start(1, HOLD, ZERO));
        withoutClaims.add(releaseWithClaims(2, HOLD, withoutClaims.get(0).eventHash(), null));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(withoutClaims, false), withoutClaims))
                .isFalse();

        List<JournalEvent> malformed = chain(start(1, HOLD, ZERO));
        malformed.add(releaseWithClaims(2, HOLD, malformed.get(0).eventHash(), "not-a-digest"));
        assertThat(ProductionJournalChainVerification.verify(partition(), head(malformed, false), malformed)).isFalse();
    }

    // --- Journal に残す claim（REL review P3-6） ---

    @Test
    void proofのcommandは両claimのdigestをaction_idに持ちclaimのどの項目を変えてもdigestが変わる() {
        HoldReleaseAuthorityClaim issuer = issuer(binding);
        HoldReleaseAuthorityClaim approver = approver(binding);
        VerifiedHoldRelease proof = verify(binding, issuer, approver);
        String digest = HoldReleaseAuthorityClaim.claimsDigest(issuer, approver);

        assertThat(proof.claimsDigest()).isEqualTo(digest).matches("[0-9a-f]{64}");
        assertThat(proof.command().actionId()).isEqualTo(digest);
        assertThat(proof.command()).isEqualTo(VerifiedHoldRelease.command(binding, digest));
        // 正規形: domain と、発行者・承認者の順に 役割・authority・key・発行日時・期限・署名 hex の長さ付き field
        StringBuilder canonical = new StringBuilder();
        for (String value : List.of("NF07-HOLD-RELEASE-CLAIMS-V1", "ISSUER", ISSUER, "k1", issuer.issuedAt().toString(),
                issuer.expiresAt().toString(), HexFormat.of().formatHex(issuer.signature()), "APPROVER", APPROVER, "k1",
                approver.issuedAt().toString(), approver.expiresAt().toString(),
                HexFormat.of().formatHex(approver.signature()))) {
            canonical.append(value.getBytes(StandardCharsets.UTF_8).length).append(':').append(value);
        }
        assertThat(digest).isEqualTo(sha256(canonical.toString()));
        assertThat(issuer.signatureHex()).isEqualTo(HexFormat.of().formatHex(issuer.signature()));

        // 同じ束縛でも claim を署名し直せば（発行日時が違えば）digest も command も変わる
        HoldReleaseAuthorityClaim resigned = authorities.sign(ISSUER, Role.ISSUER, binding, NOW.minusSeconds(30),
                NOW.plus(Duration.ofHours(1)));
        assertThat(HoldReleaseAuthorityClaim.claimsDigest(resigned, approver)).isNotEqualTo(digest);
        assertThat(HoldReleaseAuthorityClaim.claimsDigest(approver, issuer)).as("順序も束縛する").isNotEqualTo(digest);
        assertThat(verify(binding, resigned, approver).command().payloadHash())
                .isNotEqualTo(proof.command().payloadHash());
        assertThatThrownBy(() -> HoldReleaseAuthorityClaim.claimsDigest(issuer, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- 開放の gate（REL review P3-9） ---

    @Test
    void 失効providerが2件以上のanchorをACTIVEと答えるときだけtrustReadyになる() {
        assertThat(verifier.trustReady()).isTrue();

        revocations = ref -> RevocationStatus.UNKNOWN;
        assertThat(verifier.trustReady()).as("常に UNKNOWN の provider").isFalse();
        revocations = ref -> ref.startsWith(APPROVER) ? RevocationStatus.UNKNOWN : RevocationStatus.ACTIVE;
        assertThat(verifier.trustReady()).as("発行者と承認者の2件が要る").isFalse();
        revocations = ref -> ref.startsWith(APPROVER) ? RevocationStatus.REVOKED : RevocationStatus.ACTIVE;
        assertThat(verifier.trustReady()).isFalse();
        revocations = ref -> {
            throw new IllegalStateException("ocsp down");
        };
        assertThat(verifier.trustReady()).isFalse();

        assertThat(ProductionPrivacyComponents.holdReleaseVerifier(prod(), () -> java.util.Map.of(),
                ref -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC)).trustReady()).isFalse();
        assertThat(ProductionPrivacyComponents.holdReleaseVerifier(prod(), authorities.registry(), null,
                Clock.fixed(NOW, ZoneOffset.UTC)).trustReady()).isFalse();
        assertThat(ProductionPrivacyComponents.holdReleaseVerifier(prod(), () -> {
            throw new IllegalStateException("registry down");
        }, ref -> RevocationStatus.ACTIVE, Clock.fixed(NOW, ZoneOffset.UTC)).trustReady()).isFalse();
    }

    // --- anchor の鍵の算法（REL review P3-8） ---

    @Test
    void trust_anchorはEd25519以外の公開鍵を登録の時点で拒否する() throws Exception {
        for (String algorithm : List.of("RSA", "EC", "Ed448")) {
            java.security.PublicKey key = java.security.KeyPairGenerator.getInstance(algorithm).generateKeyPair()
                    .getPublic();
            assertThat(Ed25519PublicKeys.isEd25519(key)).as(algorithm).isFalse();
            assertThatThrownBy(() -> new HoldReleaseTrustAnchor("issuer-x", "k1", key, "sys_user:40", Set.of(TENANT),
                    Set.of(Role.ISSUER), Set.of("SUBJECT"), NOW.minusSeconds(60), NOW.plusSeconds(60)))
                    .as(algorithm).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Ed25519");
        }
        java.security.PublicKey ed25519 = HoldReleaseTestAuthorities.generate().getPublic();
        assertThat(Ed25519PublicKeys.isEd25519(ed25519)).isTrue();
        assertThat(Ed25519PublicKeys.isEd25519(null)).isFalse();
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
        return verifier.verify(presented, presented, "SUBJECT", rules,issuer, approver);
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
                last.revision(), last.eventHash(), REQUESTER, STARTER);
    }

    private static void assertRejected(Runnable call, HoldReleaseRejection reason) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(HoldReleaseRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private static HoldReleaseBinding copy(HoldReleaseBinding b, String tenant, String subject, String hold,
                                           long holdRevision, String reasonHash, String command, long journalRevision,
                                           String journalHash, String requester) {
        return copy(b, tenant, subject, hold, holdRevision, reasonHash, command, journalRevision, journalHash, requester,
                null);
    }

    private static HoldReleaseBinding copy(HoldReleaseBinding b, String tenant, String subject, String hold,
                                           long holdRevision, String reasonHash, String command, long journalRevision,
                                           String journalHash, String requester, String starter) {
        return new HoldReleaseBinding(tenant == null ? b.tenantId() : tenant, subject == null ? b.subjectRef() : subject,
                hold == null ? b.holdId() : hold, holdRevision < 0 ? b.expectedHoldRevision() : holdRevision,
                reasonHash == null ? b.reasonHash() : reasonHash, command == null ? b.commandId() : command,
                journalRevision < 0 ? b.expectedJournalRevision() : journalRevision,
                journalHash == null ? b.expectedJournalHash() : journalHash, requester == null ? b.requesterRef() : requester,
                starter == null ? b.holdStarterRef() : starter);
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
        return event(revision, "hs-" + holdId.substring(3), CommandType.HOLD_STARTED, holdId, null, "1".repeat(64),
                previous);
    }

    /** 解除の event（claim の digest を action_id に持つ。REL review P3-6）。 */
    private static JournalEvent release(long revision, String holdId, String previous) {
        return releaseWithClaims(revision, holdId, previous, "3".repeat(64));
    }

    private static JournalEvent releaseWithClaims(long revision, String holdId, String previous, String claimsDigest) {
        return event(revision, "hr-" + Long.toString(revision).repeat(32).substring(0, 32), CommandType.HOLD_RELEASED,
                holdId, claimsDigest, "2".repeat(64), previous);
    }

    private static JournalEvent event(long revision, String commandId, CommandType type, String caseId, String actionId,
                                      String requestHash, String previous) {
        Instant createdAt = NOW.minusSeconds(100 - revision);
        String payload = PrivacyJournalPort.JournalCommand.create(commandId, partition(), revision - 1, type, caseId,
                actionId, requestHash, null, 0, null).payloadHash();
        JournalEvent draft = new JournalEvent(revision, commandId, payload, type, caseId, actionId, requestHash, null, 0,
                null, previous, null, createdAt);
        return new JournalEvent(revision, commandId, payload, type, caseId, actionId, requestHash, null, 0, null, previous,
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
