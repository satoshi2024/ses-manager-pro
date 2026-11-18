package com.ses.privacy.production.retention.trust;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.privacy.foundation.Ed25519PublicKeys;
import com.ses.privacy.foundation.HoldReleaseTrustAnchor;
import com.ses.privacy.foundation.RevocationStatus;
import com.ses.privacy.foundation.TrustedAuthority;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.bytes;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.document;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.ed25519;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.holdAnchor;
import static com.ses.privacy.production.retention.trust.KmsTrustTestDocuments.policyAnchor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * NF07-TRUST（D-03 = AWS KMS）の trust 文書の読み方（{@link KmsTrustDocument}）と、KMS の {@code GetPublicKey} の公開鍵の
 * 読み方（{@link KmsEd25519PublicKeys}）。鍵は test-only（実行のたびに memory 上で生成）。固定の公開鍵は RFC 8410 §10.1 の
 * 公開の例（秘密鍵は持たない）。
 */
class KmsTrustDocumentTest {

    private static final Instant NOW = Instant.parse("2026-11-18T03:00:00Z");
    private static final Instant FROM = NOW.minusSeconds(86_400);
    private static final Instant UNTIL = NOW.plusSeconds(86_400 * 30L);
    /** RFC 8410 §10.1 の Ed25519 公開鍵の例（SubjectPublicKeyInfo、44 bytes）。 */
    private static final String RFC8410_PUBLIC_KEY = "MCowBQYDK2VwAyEAGb9ECWmEzf6FQbrBZ9w7lshQhqowtrbLDFw4rXAxZuE=";

    // --- KMS の DER 公開鍵 ---

    @Test
    void KMSのGetPublicKeyと同じDERのSubjectPublicKeyInfoをEd25519の公開鍵として読む() {
        PublicKey fixed = KmsEd25519PublicKeys.parse(RFC8410_PUBLIC_KEY);
        assertThat(Ed25519PublicKeys.isEd25519(fixed)).isTrue();
        assertThat(HexFormat.of().formatHex(fixed.getEncoded())).isEqualTo(
                "302a300506032b6570032100" + "19bf44096984cdfe8541bac167dc3b96c85086aa30b6b6cb0c5c38ad703166e1");

        KeyPair generated = ed25519();
        assertThat(KmsEd25519PublicKeys.parse(KmsTrustTestDocuments.spki(generated))).isEqualTo(generated.getPublic());
    }

    @Test
    void Ed25519以外の公開鍵と正規形でないDERやbase64は拒否する() throws Exception {
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
        ec.initialize(new ECGenParameterSpec("secp256r1"));
        byte[] valid = Base64.getDecoder().decode(RFC8410_PUBLIC_KEY);
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] truncated = Arrays.copyOf(valid, valid.length - 1);
        byte[] x25519Der = KeyPairGenerator.getInstance("X25519").generateKeyPair().getPublic().getEncoded();
        assertThat(x25519Der).hasSize(KmsEd25519PublicKeys.SPKI_LENGTH);
        assertThat(HexFormat.of().formatHex(x25519Der)).startsWith("302a300506032b656e032100");
        String x25519 = Base64.getEncoder().encodeToString(x25519Der);
        List<String> rejected = List.of(
                Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(ec.generateKeyPair().getPublic().getEncoded()),
                Base64.getEncoder().encodeToString(KeyPairGenerator.getInstance("Ed448").generateKeyPair()
                        .getPublic().getEncoded()),
                // X25519（同じ 44 bytes で OID だけが 1.3.101.110。鍵交換用で署名には使えない。review P3-8）
                x25519,
                Base64.getEncoder().encodeToString(trailing),
                Base64.getEncoder().encodeToString(truncated),
                // 生の32 bytes（SubjectPublicKeyInfo でない）
                Base64.getEncoder().encodeToString(Arrays.copyOfRange(valid, 12, 44)),
                "-----BEGIN PUBLIC KEY-----\n" + RFC8410_PUBLIC_KEY + "\n-----END PUBLIC KEY-----",
                RFC8410_PUBLIC_KEY.replace("=", ""),
                RFC8410_PUBLIC_KEY.substring(0, 20) + "\n" + RFC8410_PUBLIC_KEY.substring(20),
                // base64url の文字（標準の alphabet ではない）
                RFC8410_PUBLIC_KEY.replace('A', '-'),
                "");
        for (String value : rejected) {
            assertThatThrownBy(() -> KmsEd25519PublicKeys.parse(value)).as(value)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- 文書の読み方 ---

    @Test
    void 文書のanchorはregistryのkeyがauthorityId_keyIdと一致し失効状態を鍵とenvelopeで答える() {
        KeyPair policyKey = ed25519();
        KeyPair holdKey = ed25519();
        KmsTrustDocument parsed = KmsTrustDocument.parse(bytes(document(NOW,
                List.of(policyAnchor("policy-issuer", "kms-2026a", policyKey, "ACTIVE", FROM, UNTIL, "default")),
                List.of(holdAnchor("issuer-a", "kms-2026a", holdKey, "sys_user:20", "REVOKED", FROM, UNTIL,
                        "default")),
                Map.of("envelope-rev-1", "ACTIVE", "envelope-rev-2", "REVOKED"))));

        assertThat(parsed.generatedAt()).isEqualTo(NOW);
        Map<String, TrustedAuthority> policies = parsed.policyRegistry().anchors();
        assertThat(policies).containsOnlyKeys("policy-issuer:kms-2026a");
        TrustedAuthority policy = policies.get("policy-issuer:kms-2026a");
        assertThat(policy.publicKey()).isEqualTo(policyKey.getPublic());
        assertThat(policy.envelopeKinds()).containsExactly("POLICY");
        assertThat(policy.audiences()).containsExactly("PRODUCTION");
        assertThat(policy.tenants()).containsExactly("default");
        Map<String, HoldReleaseTrustAnchor> holds = parsed.holdReleaseRegistry().anchors();
        assertThat(holds).containsOnlyKeys("issuer-a:kms-2026a");
        assertThat(holds.get("issuer-a:kms-2026a").principalRef()).isEqualTo("sys_user:20");
        // 失効した鍵の anchor も registry に残す（照会が POLICY_REVOKED を返せるように）
        assertThat(parsed.keyStatusOf("policy-issuer:kms-2026a")).isEqualTo(RevocationStatus.ACTIVE);
        assertThat(parsed.keyStatusOf("issuer-a:kms-2026a")).isEqualTo(RevocationStatus.REVOKED);
        assertThat(parsed.envelopeStatusOf("envelope-rev-1")).isEqualTo(RevocationStatus.ACTIVE);
        assertThat(parsed.envelopeStatusOf("envelope-rev-2")).isEqualTo(RevocationStatus.REVOKED);
        assertThat(parsed.keyStatusOf("other:k1")).isNull();
        assertThat(parsed.envelopeStatusOf("envelope-unknown")).isNull();
    }

    @Test
    void keySpecと署名算法はKMSのEd25519の純粋な署名だけを受け付けEd25519phは拒否する() {
        assertRejected(anchor -> anchor.put("keySpec", "ECC_NIST_P256"), "anchor.keySpec");
        assertRejected(anchor -> anchor.put("keySpec", "RSA_2048"), "anchor.keySpec");
        assertRejected(anchor -> anchor.put("signingAlgorithm", "ED25519_PH_SHA_512"), "anchor.signingAlgorithm");
        assertRejected(anchor -> anchor.put("signingAlgorithm", "ECDSA_SHA_256"), "anchor.signingAlgorithm");
        assertRejected(anchor -> anchor.put("publicKey", Base64.getEncoder().encodeToString(
                KmsTrustTestDocuments.ed25519().getPublic().getEncoded()).replace("=", "")), "anchor.publicKey");
    }

    @Test
    void authorityIdとkeyIdは区切りの無い識別子だけでKMSのkeyのARNだけを受け付ける() {
        assertRejected(anchor -> anchor.put("authorityId", "policy:issuer"), "anchor.authorityId");
        assertRejected(anchor -> anchor.put("keyId", "arn:aws:kms:ap-northeast-1:111122223333:key/x"), "anchor.keyId");
        assertRejected(anchor -> anchor.put("keyId", "k".repeat(65)), "anchor.keyId");
        assertRejected(anchor -> anchor.put("kmsKeyArn", "arn:aws:kms:ap-northeast-1:111122223333:alias/nf07-policy"),
                "anchor.kmsKeyArn");
        assertRejected(anchor -> anchor.put("kmsKeyArn", "arn:aws:kms:ap-northeast-1:1111:key/"
                + "00000000-0000-0000-0000-000000000000"), "anchor.kmsKeyArn");
        // multi-Region key の key ID は受け付ける
        KmsTrustDocument.parse(bytes(withPolicyAnchor(anchor -> anchor.put("kmsKeyArn",
                "arn:aws:kms:ap-northeast-1:111122223333:key/mrk-" + "0".repeat(32)))));
    }

    @Test
    void 同じanchorIDと同じKMS鍵と同じ公開鍵の重複を拒否する() {
        KeyPair key = ed25519();
        ObjectNode first = policyAnchor("policy-issuer", "k1", key, "ACTIVE", FROM, UNTIL, "default");
        ObjectNode sameId = policyAnchor("policy-issuer", "k1", ed25519(), "ACTIVE", FROM, UNTIL, "default");
        assertCode(document(NOW, List.of(first, sameId), List.of(), Map.of()), "anchor.duplicate");
        // policy と hold 解除をまたいでも同じ anchor ID は不可（失効状態の照会の名前空間が1つ）
        assertCode(document(NOW, List.of(first), List.of(holdAnchor("policy-issuer", "k1", ed25519(), "sys_user:20",
                "ACTIVE", FROM, UNTIL, "default")), Map.of()), "anchor.duplicate");
        ObjectNode sameArn = policyAnchor("policy-issuer", "k2", ed25519(), "ACTIVE", FROM, UNTIL, "default");
        sameArn.put("kmsKeyArn", first.get("kmsKeyArn").textValue());
        assertCode(document(NOW, List.of(first, sameArn), List.of(), Map.of()), "anchor.kmsKeyArn.duplicate");
        ObjectNode sameKey = policyAnchor("policy-issuer", "k3", key, "ACTIVE", FROM, UNTIL, "default");
        assertCode(document(NOW, List.of(first, sameKey), List.of(), Map.of()), "anchor.publicKey.duplicate");
    }

    @Test
    void 未知のfield_重複したfield_欠落_型違い_形式の版_末尾のtokenを拒否する() {
        assertRejected(anchor -> anchor.put("note", "x"), "document.field.unknown");
        assertRejected(anchor -> anchor.remove("validUntil"), "document.field.missing");
        assertRejected(anchor -> anchor.put("status", "SUSPENDED"), "status.invalid");
        assertRejected(anchor -> anchor.put("validUntil", FROM.toString()), "anchor.validity");
        assertRejected(anchor -> anchor.putArray("tenants"), "anchor.tenants");
        assertRejected(anchor -> anchor.putArray("tenants").add("default").add("default"), "anchor.tenants");
        assertRejected(anchor -> anchor.put("validFrom", "2026-11-18"), "document.instant");

        ObjectNode wrongFormat = withPolicyAnchor(anchor -> { });
        wrongFormat.put("format", "ses-nf07-kms-trust-v0");
        assertCode(wrongFormat, "document.format");
        ObjectNode extra = withPolicyAnchor(anchor -> { });
        extra.put("comment", "x");
        assertCode(extra, "document.field.unknown");

        String json = new String(bytes(withPolicyAnchor(anchor -> { })), StandardCharsets.UTF_8);
        assertThatThrownBy(() -> KmsTrustDocument.parse(("{\"format\":\"a\"," + json.substring(1))
                .getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(KmsTrustDocument.InvalidDocumentException.class,
                        e -> assertThat(e.code()).isEqualTo("document.notJson"));
        assertThatThrownBy(() -> KmsTrustDocument.parse((json + "{}").getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(KmsTrustDocument.InvalidDocumentException.class,
                        e -> assertThat(e.code()).isEqualTo("document.notJson"));
        assertThatThrownBy(() -> KmsTrustDocument.parse("[]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOfSatisfying(KmsTrustDocument.InvalidDocumentException.class,
                        e -> assertThat(e.code()).isEqualTo("document.notObject"));
    }

    @Test
    void 解除のanchorは主体の正規形と役割と対象種別を検査しenvelopeの参照はコロンを含めない() {
        ObjectNode badPrincipal = holdAnchor("issuer-a", "k1", ed25519(), "sys_user:020", "ACTIVE", FROM, UNTIL,
                "default");
        assertCode(document(NOW, List.of(), List.of(badPrincipal), Map.of()), "anchor.principal");
        ObjectNode badRole = holdAnchor("issuer-a", "k1", ed25519(), "sys_user:20", "ACTIVE", FROM, UNTIL, "default");
        badRole.putArray("roles").add("REQUESTER");
        assertCode(document(NOW, List.of(), List.of(badRole), Map.of()), "anchor.roles");
        ObjectNode badTarget = holdAnchor("issuer-a", "k1", ed25519(), "sys_user:20", "ACTIVE", FROM, UNTIL, "default");
        badTarget.putArray("targetTypes").add("ALL");
        assertCode(document(NOW, List.of(), List.of(badTarget), Map.of()), "anchor.targetTypes");

        ObjectNode anchor = holdAnchor("issuer-a", "k1", ed25519(), "sys_user:20", "ACTIVE", FROM, UNTIL, "default");
        // 鍵の ID（authorityId:keyId）と区別できないため、':' を含む参照は anchor ID と同じでも違っても受け付けない（review P2-1）
        assertCode(document(NOW, List.of(), List.of(anchor), Map.of("issuer-a:k1", "ACTIVE")), "envelope.reference");
        assertCode(document(NOW, List.of(), List.of(anchor), Map.of("urn:nf07:policy-1", "ACTIVE")),
                "envelope.reference");
        assertCode(document(NOW, List.of(), List.of(anchor), Map.of("envelope-1", "UNKNOWN")), "status.invalid");
        assertCode(document(NOW, List.of(), List.of(anchor), Map.of(" envelope-1", "ACTIVE")), "envelope.reference");
    }

    @Test
    void 拒否の理由は固定のcodeと位置だけで公開鍵やARNの値を含めない() {
        KeyPair key = ed25519();
        ObjectNode anchor = policyAnchor("policy-issuer", "k1", key, "ACTIVE", FROM, UNTIL, "default");
        anchor.put("signingAlgorithm", "ED25519_PH_SHA_512");
        assertThatThrownBy(() -> KmsTrustDocument.parse(bytes(document(NOW, List.of(anchor), List.of(), Map.of()))))
                .isInstanceOfSatisfying(KmsTrustDocument.InvalidDocumentException.class, e -> {
                    assertThat(e.getMessage()).isEqualTo("anchor.signingAlgorithm at $.policyAnchors[0].signingAlgorithm");
                    assertThat(e.getMessage()).doesNotContain(KmsTrustTestDocuments.spki(key))
                            .doesNotContain("111122223333").doesNotContain("ED25519_PH");
                });
    }

    private static ObjectNode withPolicyAnchor(Consumer<ObjectNode> change) {
        ObjectNode anchor = policyAnchor("policy-issuer", "k1", ed25519(), "ACTIVE", FROM, UNTIL, "default");
        change.accept(anchor);
        return document(NOW, List.of(anchor), List.of(), Map.of());
    }

    private static void assertRejected(Consumer<ObjectNode> change, String code) {
        assertCode(withPolicyAnchor(change), code);
    }

    private static void assertCode(ObjectNode document, String code) {
        assertThatThrownBy(() -> KmsTrustDocument.parse(bytes(document)))
                .isInstanceOfSatisfying(KmsTrustDocument.InvalidDocumentException.class,
                        e -> assertThat(e.code()).isEqualTo(code));
    }
}
