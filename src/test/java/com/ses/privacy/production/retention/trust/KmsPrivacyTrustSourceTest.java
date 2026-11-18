package com.ses.privacy.production.retention.trust;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.privacy.foundation.CurrentPolicySnapshot;
import com.ses.privacy.foundation.HoldReleaseAuthorityClaim;
import com.ses.privacy.foundation.HoldReleaseBinding;
import com.ses.privacy.foundation.HoldReleaseRejectedException;
import com.ses.privacy.foundation.HoldReleaseRejection;
import com.ses.privacy.foundation.HoldReleaseVerifier;
import com.ses.privacy.foundation.PolicyEnforcementDecision;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.PolicyEnforcementReason;
import com.ses.privacy.foundation.PolicyEnforcementRequest;
import com.ses.privacy.foundation.PolicyFailureReason;
import com.ses.privacy.foundation.PolicyLookupKey;
import com.ses.privacy.foundation.PolicyRegistrationRejectedException;
import com.ses.privacy.foundation.PolicyRegistrationVerifier;
import com.ses.privacy.foundation.ProductionCatalogBinding;
import com.ses.privacy.foundation.ProductionPrivacyComponents;
import com.ses.privacy.foundation.RevocationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.ACTION;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.ELEMENT;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.MODULE;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.PROVIDER;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.bytes;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.claim;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.document;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.ed25519;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.holdAnchor;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.policyAnchor;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.signPolicy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07-TRUST の D-03 方式（AWS KMS 非対称署名、鍵 version 単位の失効）の adapter（{@link KmsPrivacyTrustSource}）。計画 §4 の
 * NF07-TRUST 行の対象限定試験（鍵 rotation の重複期間、失効の伝播、provider 障害で UNKNOWN、失効が即時に {@code POLICY_REVOKED}）
 * を、本番の verifier（{@link ProductionPrivacyComponents} の factory）と {@link PrivacyTrustSource} の遅延評価の包みを通して
 * 確かめる。文書は一時 directory の file で、置き直した次の判定で反映されること（再起動が要らないこと）を見る。鍵は test-only。
 */
class KmsPrivacyTrustSourceTest {

    private static final Instant T0 = Instant.parse("2026-11-18T03:00:00Z");
    private static final String TENANT = "default";
    private static final Duration DAY = Duration.ofDays(1);

    @TempDir
    Path directory;

    private Path file;
    private MutableClock clock;
    private KmsTrustProperties properties;
    private KmsPrivacyTrustSource source;
    private long modifiedSeconds = 1_700_000_000L;
    private final MockEnvironment prod = new MockEnvironment();

    private final KeyPair policyOld = ed25519();
    private final KeyPair policyNew = ed25519();
    private final KeyPair issuerOld = ed25519();
    private final KeyPair issuerNew = ed25519();
    private final KeyPair approver = ed25519();

    @BeforeEach
    void setUp() {
        prod.setActiveProfiles("prod");
        file = directory.resolve("nf07-trust.json").toAbsolutePath();
        clock = new MutableClock(T0);
        properties = new KmsTrustProperties();
        properties.setAnchorFile(file.toString());
        properties.setMaxDocumentAge(DAY);
        source = new KmsPrivacyTrustSource(properties, clock);
    }

    // --- 鍵 rotation の重複期間 ---

    @Test
    void 鍵のrotationの重複期間は新旧の鍵の両方で検証が通り旧鍵の期限の後は新鍵だけが通りgateは開いたまま() {
        Instant oldUntil = T0.plus(Duration.ofDays(10));
        Instant newFrom = T0.plus(Duration.ofDays(5));
        Instant newUntil = T0.plus(Duration.ofDays(400));
        Map<String, String> envelopes = Map.of("rev-old", "ACTIVE", "rev-new", "ACTIVE");
        List<ObjectNode> policies = List.of(
                policyAnchor("policy-issuer", "kms-2026a", policyOld, "ACTIVE", T0.minus(Duration.ofDays(10)),
                        oldUntil, TENANT),
                policyAnchor("policy-issuer", "kms-2026b", policyNew, "ACTIVE", newFrom, newUntil, TENANT));
        List<ObjectNode> holds = List.of(
                holdAnchor("issuer-a", "kms-2026a", issuerOld, "sys_user:20", "ACTIVE", T0.minus(Duration.ofDays(10)),
                        oldUntil, TENANT),
                holdAnchor("issuer-a", "kms-2026b", issuerNew, "sys_user:20", "ACTIVE", newFrom, newUntil, TENANT),
                holdAnchor("approver-b", "kms-2026a", approver, "sys_user:30", "ACTIVE", T0.minus(Duration.ofDays(10)),
                        newUntil, TENANT));
        Instant issued = T0.plus(Duration.ofDays(6));
        KmsTrustTestDocuments.SignedEnvelope byOld = signPolicy(policyOld, "policy-issuer", "kms-2026a", TENANT,
                "policy-old", "rev-old", issued, issued.plus(Duration.ofDays(60)), "TEST_ONLY_RULE", true);
        KmsTrustTestDocuments.SignedEnvelope byNew = signPolicy(policyNew, "policy-issuer", "kms-2026b", TENANT,
                "policy-new", "rev-new", issued, issued.plus(Duration.ofDays(60)), "TEST_ONLY_RULE", true);
        PolicyRegistrationVerifier registration = registrationVerifier();
        HoldReleaseVerifier release = releaseVerifier();

        // 重複期間（新鍵の validFrom の後・旧鍵の validUntil の前）: 新旧どちらの鍵の署名も通る
        clock.set(T0.plus(Duration.ofDays(7)));
        write(document(clock.instant(), policies, holds, envelopes));
        assertThat(registration.verify(TENANT, byOld.canonical(), byOld.signature()).digest()).isEqualTo(byOld.digest());
        assertThat(registration.verify(TENANT, byNew.canonical(), byNew.signature()).digest()).isEqualTo(byNew.digest());
        assertThat(registration.trustReady(TENANT)).isTrue();
        assertThat(releaseWith(release, issuerOld, "kms-2026a")).isNotNull();
        assertThat(releaseWith(release, issuerNew, "kms-2026b")).isNotNull();
        assertThat(release.trustReady(TENANT)).isTrue();

        // 旧鍵の validUntil の後: 旧鍵の署名は期限切れ、新鍵だけが通る。gate は新鍵で開いたまま
        clock.set(oldUntil.plusSeconds(60));
        write(document(clock.instant(), policies, holds, envelopes));
        assertThatThrownBy(() -> registration.verify(TENANT, byOld.canonical(), byOld.signature()))
                .isInstanceOfSatisfying(PolicyRegistrationRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(PolicyFailureReason.AUTHORITY_VALIDITY_EXPIRED));
        assertThat(registration.verify(TENANT, byNew.canonical(), byNew.signature()).digest()).isEqualTo(byNew.digest());
        assertThat(registration.trustReady(TENANT)).isTrue();
        assertThatThrownBy(() -> releaseWith(release, issuerOld, "kms-2026a"))
                .isInstanceOfSatisfying(HoldReleaseRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(HoldReleaseRejection.EXPIRED));
        assertThat(releaseWith(release, issuerNew, "kms-2026b")).isNotNull();
        assertThat(release.trustReady(TENANT)).isTrue();
    }

    // --- 失効の伝播と、即時の POLICY_REVOKED ---

    @Test
    void envelopeと鍵の失効は文書を置き直した次の照会からPOLICY_REVOKEDになり再起動は要らず戻した文書でも失効のまま() {
        List<ObjectNode> active = List.of(policyAnchor("policy-issuer", "kms-2026a", policyOld, "ACTIVE",
                T0.minus(DAY), T0.plus(Duration.ofDays(30)), TENANT));
        List<ObjectNode> revoked = List.of(policyAnchor("policy-issuer", "kms-2026a", policyOld, "REVOKED",
                T0.minus(DAY), T0.plus(Duration.ofDays(30)), TENANT));
        KmsTrustTestDocuments.SignedEnvelope envelope = signPolicy(policyOld, "policy-issuer", "kms-2026a", TENANT,
                "policy-test-1", "rev-1", T0.minusSeconds(3600), T0.plus(Duration.ofDays(20)), "TEST_ONLY_RULE",
                true);
        PolicyEnforcementPort enforcement = enforcement(envelope);
        PolicyRegistrationVerifier registration = registrationVerifier();

        write(document(T0, active, List.of(), Map.of("rev-1", "ACTIVE")));
        assertThat(enforcement.check(request(envelope)).permitted()).isTrue();

        // envelope の参照の失効
        write(document(T0.plusSeconds(1), active, List.of(), Map.of("rev-1", "REVOKED")));
        assertDenied(enforcement.check(request(envelope)), PolicyFailureReason.ENVELOPE_REVOKED);
        assertThatThrownBy(() -> registration.verify(TENANT, envelope.canonical(), envelope.signature()))
                .isInstanceOfSatisfying(PolicyRegistrationRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(PolicyFailureReason.ENVELOPE_REVOKED));

        // 鍵（鍵 version）の失効: その鍵で署名した全ての envelope が POLICY_REVOKED、gate は閉じる
        write(document(T0.plusSeconds(2), revoked, List.of(), Map.of("rev-1", "ACTIVE")));
        assertDenied(enforcement.check(request(envelope)), PolicyFailureReason.KEY_REVOKED);
        assertThat(registration.trustReady(TENANT)).isFalse();
        assertThat(PrivacyTrustSource.revocationsOf(source).statusOf("policy-issuer:kms-2026a"))
                .isEqualTo(RevocationStatus.REVOKED);

        // 失効は戻さない: 同じ process では、ACTIVE と書いた文書を置いても REVOKED のまま
        write(document(T0.plusSeconds(3), active, List.of(), Map.of("rev-1", "ACTIVE")));
        assertDenied(enforcement.check(request(envelope)), PolicyFailureReason.KEY_REVOKED);
        assertThat(PrivacyTrustSource.revocationsOf(source).statusOf("rev-1")).isEqualTo(RevocationStatus.REVOKED);
    }

    @Test
    void 解除の鍵の失効とanchorの削除は次の判定でgateを閉じ検証はREVOKEDとTRUST_ANCHOR_UNKNOWNになる() {
        List<ObjectNode> both = List.of(
                holdAnchor("issuer-a", "kms-2026a", issuerOld, "sys_user:20", "ACTIVE", T0.minus(DAY),
                        T0.plus(Duration.ofDays(30)), TENANT),
                holdAnchor("approver-b", "kms-2026a", approver, "sys_user:30", "ACTIVE", T0.minus(DAY),
                        T0.plus(Duration.ofDays(30)), TENANT));
        HoldReleaseVerifier release = releaseVerifier();
        write(document(T0, List.of(), both, Map.of()));
        assertThat(release.trustReady(TENANT)).isTrue();
        assertThat(releaseWith(release, issuerOld, "kms-2026a")).isNotNull();

        // 承認者の anchor を消す
        write(document(T0.plusSeconds(1), List.of(), List.of(both.get(0)), Map.of()));
        assertThat(release.trustReady(TENANT)).isFalse();
        assertThatThrownBy(() -> releaseWith(release, issuerOld, "kms-2026a"))
                .isInstanceOfSatisfying(HoldReleaseRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(HoldReleaseRejection.TRUST_ANCHOR_UNKNOWN));

        // 戻した後、発行者の鍵を失効させる
        ObjectNode revokedIssuer = both.get(0).deepCopy();
        revokedIssuer.put("status", "REVOKED");
        write(document(T0.plusSeconds(2), List.of(), List.of(revokedIssuer, both.get(1)), Map.of()));
        assertThat(release.trustReady(TENANT)).isFalse();
        assertThatThrownBy(() -> releaseWith(release, issuerOld, "kms-2026a"))
                .isInstanceOfSatisfying(HoldReleaseRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(HoldReleaseRejection.REVOKED));
    }

    // --- provider（文書の読込み）の障害で UNKNOWN ---

    @Test
    void 文書が消えた_壊れた_古すぎる_未来の作成日時_鮮度の上限が無い間は失効状態がUNKNOWNで入口は閉じる() throws IOException {
        List<ObjectNode> policies = List.of(policyAnchor("policy-issuer", "kms-2026a", policyOld, "ACTIVE",
                T0.minus(DAY), T0.plus(Duration.ofDays(30)), TENANT));
        KmsTrustTestDocuments.SignedEnvelope envelope = signPolicy(policyOld, "policy-issuer", "kms-2026a", TENANT,
                "policy-test-1", "rev-1", T0.minusSeconds(3600), T0.plus(Duration.ofDays(20)), "TEST_ONLY_RULE",
                true);
        PolicyRegistrationVerifier registration = registrationVerifier();
        write(document(T0, policies, List.of(), Map.of("rev-1", "ACTIVE")));
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.ACTIVE);
        assertThat(registration.trustReady(TENANT)).isTrue();
        // 文書に無い参照と null は UNKNOWN（ACTIVE を既定にしない）
        assertThat(status("rev-unlisted")).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(status("policy-issuer:kms-2026z")).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(status(null)).isEqualTo(RevocationStatus.UNKNOWN);

        // file が消えた: anchor 0件・UNKNOWN
        Files.delete(file);
        assertThat(PrivacyTrustSource.policyAnchorsOf(source).anchors()).isEmpty();
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(registration.trustReady(TENANT)).isFalse();
        assertRejected(registration, envelope, PolicyFailureReason.UNKNOWN_TRUST_ANCHOR);

        // 壊れた文書（書きかけ等）: 直前の内容に戻らず anchor 0件・UNKNOWN
        writeRaw("{\"format\":\"ses-nf07-kms-trust-v1\",".getBytes(StandardCharsets.UTF_8));
        assertThat(PrivacyTrustSource.policyAnchorsOf(source).anchors()).isEmpty();
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.UNKNOWN);
        assertRejected(registration, envelope, PolicyFailureReason.UNKNOWN_TRUST_ANCHOR);

        // 置き直せば再起動せずに戻る
        write(document(T0, policies, List.of(), Map.of("rev-1", "ACTIVE")));
        assertThat(registration.verify(TENANT, envelope.canonical(), envelope.signature())).isNotNull();

        // 鮮度の上限を過ぎた: anchor は残るが失効状態を確認できない
        clock.set(T0.plus(DAY));
        assertThat(PrivacyTrustSource.policyAnchorsOf(source).anchors()).hasSize(1);
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(registration.trustReady(TENANT)).isFalse();
        assertRejected(registration, envelope, PolicyFailureReason.REVOCATION_STATUS_UNKNOWN);

        // 作成日時が未来（許容の5分を超える）
        write(document(clock.instant().plus(Duration.ofMinutes(10)), policies, List.of(), Map.of("rev-1", "ACTIVE")));
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.UNKNOWN);
        write(document(clock.instant().plus(Duration.ofMinutes(4)), policies, List.of(), Map.of("rev-1", "ACTIVE")));
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.ACTIVE);

        // 鮮度の上限の設定が無い・範囲外なら常に UNKNOWN
        for (Duration invalid : new Duration[]{null, Duration.ofMinutes(1), Duration.ofDays(32)}) {
            KmsTrustProperties misconfigured = new KmsTrustProperties();
            misconfigured.setAnchorFile(file.toString());
            misconfigured.setMaxDocumentAge(invalid);
            KmsPrivacyTrustSource other = new KmsPrivacyTrustSource(misconfigured, clock);
            assertThat(PrivacyTrustSource.revocationsOf(other).statusOf("policy-issuer:kms-2026a"))
                    .as(String.valueOf(invalid)).isEqualTo(RevocationStatus.UNKNOWN);
        }
    }

    @Test
    void 文書の作成日時が同じprocessで読んだ文書より前なら古い版への巻戻しとして受け付けない() {
        List<ObjectNode> policies = List.of(policyAnchor("policy-issuer", "kms-2026a", policyOld, "ACTIVE",
                T0.minus(DAY), T0.plus(Duration.ofDays(30)), TENANT));
        write(document(T0, policies, List.of(), Map.of()));
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.ACTIVE);

        write(document(T0.minusSeconds(1), policies, List.of(), Map.of()));
        assertThat(PrivacyTrustSource.policyAnchorsOf(source).anchors()).isEmpty();
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.UNKNOWN);

        write(document(T0, policies, List.of(), Map.of()));
        assertThat(status("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.ACTIVE);
    }

    @Test
    void 最終更新日時と大きさが同じ間は再読込の間隔まで読んだ内容を使い変われば次の判定で読み直す() throws IOException {
        List<ObjectNode> policies = List.of(policyAnchor("policy-issuer", "kms-2026a", policyOld, "ACTIVE",
                T0.minus(DAY), T0.plus(Duration.ofDays(30)), TENANT));
        byte[] first = bytes(document(T0, policies, List.of(), Map.of("rev-1", "ACTIVE")));
        byte[] second = bytes(document(T0, policies, List.of(), Map.of("rev-1", "REVOKED")));
        // 大きさを揃える（REVOKED は ACTIVE より1文字長い）
        byte[] padded = new byte[first.length + 1];
        System.arraycopy(first, 0, padded, 0, first.length);
        padded[first.length] = ' ';
        assertThat(padded).hasSameSizeAs(second);

        writeRaw(padded);
        FileTime fixed = Files.getLastModifiedTime(file);
        assertThat(status("rev-1")).isEqualTo(RevocationStatus.ACTIVE);

        Files.write(file, second);
        Files.setLastModifiedTime(file, fixed);
        clock.set(T0.plusSeconds(29));
        assertThat(status("rev-1")).as("属性が同じで再読込の間隔の内は cache").isEqualTo(RevocationStatus.ACTIVE);
        clock.set(T0.plusSeconds(30));
        assertThat(status("rev-1")).as("間隔を過ぎれば読み直す").isEqualTo(RevocationStatus.REVOKED);

        // 最終更新日時が変われば間隔の内でも次の判定で読み直す
        KmsPrivacyTrustSource fresh = new KmsPrivacyTrustSource(properties, clock);
        writeRaw(padded);
        assertThat(PrivacyTrustSource.revocationsOf(fresh).statusOf("rev-1")).isEqualTo(RevocationStatus.ACTIVE);
        writeRaw(second);
        assertThat(PrivacyTrustSource.revocationsOf(fresh).statusOf("rev-1")).isEqualTo(RevocationStatus.REVOKED);
    }

    // --- gate の有効期間と tenant（RB-NF07-REG delta review P3-1） ---

    @Test
    void 唯一のanchorと組が期限切れか授権tenantの外ならgateは開かない() {
        Instant until = T0.plus(Duration.ofDays(30));
        write(document(T0, List.of(policyAnchor("policy-issuer", "kms-2026a", policyOld, "ACTIVE", T0.minus(DAY),
                until, "other-tenant")), List.of(
                holdAnchor("issuer-a", "kms-2026a", issuerOld, "sys_user:20", "ACTIVE", T0.minus(DAY), until, TENANT),
                holdAnchor("approver-b", "kms-2026a", approver, "sys_user:30", "ACTIVE", T0.minus(DAY), until,
                        "other-tenant")), Map.of()));
        assertThat(registrationVerifier().trustReady(TENANT)).as("policy の anchor が tenant 外").isFalse();
        assertThat(registrationVerifier().trustReady("other-tenant")).isTrue();
        assertThat(releaseVerifier().trustReady(TENANT)).as("承認者が tenant 外").isFalse();

        write(document(T0.plusSeconds(1), List.of(policyAnchor("policy-issuer", "kms-2026a", policyOld, "ACTIVE",
                T0.minus(Duration.ofDays(30)), T0, TENANT)), List.of(
                holdAnchor("issuer-a", "kms-2026a", issuerOld, "sys_user:20", "ACTIVE", T0.minus(DAY), until, TENANT),
                holdAnchor("approver-b", "kms-2026a", approver, "sys_user:30", "ACTIVE", T0.plusSeconds(60), until,
                        TENANT)), Map.of()));
        assertThat(registrationVerifier().trustReady(TENANT)).as("policy の anchor が validUntil 当時刻").isFalse();
        assertThat(releaseVerifier().trustReady(TENANT)).as("承認者が validFrom 前").isFalse();
        clock.set(T0.plusSeconds(60));
        assertThat(releaseVerifier().trustReady(TENANT)).as("承認者の validFrom から開く").isTrue();
    }

    // --- Bean の切替 ---

    @Test
    void 設定が無ければ第1期のtrustで設定があればKMSのadapterを選び構築時にfileを読まない() {
        PrivacyTrustConfiguration configuration = new PrivacyTrustConfiguration();
        assertThat(configuration.privacyTrustSource(new KmsTrustProperties(), null))
                .isInstanceOf(UnconfiguredPrivacyTrustSource.class);
        KmsTrustProperties blank = new KmsTrustProperties();
        blank.setAnchorFile("  ");
        assertThat(configuration.privacyTrustSource(blank, null)).isInstanceOf(UnconfiguredPrivacyTrustSource.class);

        // file がまだ無くても構築は失敗しない（読むのは判定のたび）
        PrivacyTrustSource configured = configuration.privacyTrustSource(properties, null);
        assertThat(configured).isInstanceOf(KmsPrivacyTrustSource.class);
        assertThat(PrivacyTrustSource.policyAnchorsOf(configured).anchors()).isEmpty();
        assertThat(PrivacyTrustSource.holdReleaseAnchorsOf(configured).anchors()).isEmpty();
        assertThat(PrivacyTrustSource.revocationsOf(configured).statusOf("policy-issuer:kms-2026a"))
                .isEqualTo(RevocationStatus.UNKNOWN);
    }

    // --- helpers ---

    private RevocationStatus status(String reference) {
        return PrivacyTrustSource.revocationsOf(source).statusOf(reference);
    }

    private PolicyRegistrationVerifier registrationVerifier() {
        return ProductionPrivacyComponents.policyRegistrationVerifier(prod, PrivacyTrustSource.policyAnchorsOf(source),
                PrivacyTrustSource.revocationsOf(source), clock);
    }

    private HoldReleaseVerifier releaseVerifier() {
        return ProductionPrivacyComponents.holdReleaseVerifier(prod, PrivacyTrustSource.holdReleaseAnchorsOf(source),
                PrivacyTrustSource.revocationsOf(source), clock);
    }

    private Object releaseWith(HoldReleaseVerifier verifier, KeyPair issuerKey, String issuerKeyId) {
        HoldReleaseBinding binding = KmsTrustTestDocuments.binding(TENANT);
        Instant now = clock.instant();
        HoldReleaseAuthorityClaim issuer = claim(issuerKey, HoldReleaseAuthorityClaim.Role.ISSUER, "issuer-a",
                issuerKeyId, binding, now.minusSeconds(60), now.plusSeconds(3600));
        HoldReleaseAuthorityClaim approverClaim = claim(approver, HoldReleaseAuthorityClaim.Role.APPROVER,
                "approver-b", "kms-2026a", binding, now.minusSeconds(60), now.plusSeconds(3600));
        return verifier.verify(binding, binding, "SUBJECT", null, issuer, approverClaim);
    }

    private PolicyEnforcementPort enforcement(KmsTrustTestDocuments.SignedEnvelope envelope) {
        ProductionCatalogBinding binding = new ProductionCatalogBinding(envelope.policyId(), MODULE,
                ProductionCatalogBinding.TENANT_WIDE_ACTOR_BINDING, ELEMENT, ACTION, PROVIDER,
                PolicyLookupKey.TENANT_WIDE);
        return ProductionPrivacyComponents.productionPolicyEnforcement(prod, () -> true, key -> Optional.of(binding),
                (key, ignored) -> CurrentPolicySnapshot.current(envelope.policyId(), envelope.revision(),
                        envelope.digest(), envelope.canonical(), envelope.signature()),
                PrivacyTrustSource.policyAnchorsOf(source), PrivacyTrustSource.revocationsOf(source), clock);
    }

    private PolicyEnforcementRequest request(KmsTrustTestDocuments.SignedEnvelope envelope) {
        return new PolicyEnforcementRequest(TENANT, MODULE, ELEMENT, ACTION, PROVIDER, envelope.policyId(),
                clock.instant());
    }

    private static void assertDenied(PolicyEnforcementDecision decision, PolicyFailureReason detail) {
        assertThat(decision.permitted()).isFalse();
        assertThat(decision.reason()).isEqualTo(PolicyEnforcementReason.POLICY_REVOKED);
        assertThat(decision.detail()).isEqualTo(detail);
    }

    private static void assertRejected(PolicyRegistrationVerifier verifier,
                                       KmsTrustTestDocuments.SignedEnvelope envelope, PolicyFailureReason reason) {
        assertThatThrownBy(() -> verifier.verify(TENANT, envelope.canonical(), envelope.signature()))
                .isInstanceOfSatisfying(PolicyRegistrationRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(reason));
    }

    private void write(ObjectNode document) {
        writeRaw(bytes(document));
    }

    /** 文書を置き直し、最終更新日時を毎回進める（file system の時刻の粒度に左右されないため）。 */
    private void writeRaw(byte[] content) {
        try {
            Files.write(file, content);
            modifiedSeconds += 10;
            Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(modifiedSeconds)));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 試験の時計（判定のたびに読む時刻を進められる）。 */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
