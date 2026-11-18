package com.ses.privacy.production.retention.trust;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.privacy.foundation.ApprovalEnvelopeCanonicalizer;
import com.ses.privacy.foundation.ApprovalEnvelopeMetadata;
import com.ses.privacy.foundation.HoldReleaseAuthorityClaim;
import com.ses.privacy.foundation.HoldReleaseBinding;
import com.ses.privacy.foundation.PolicyApprovalEnvelope;
import com.ses.privacy.foundation.PolicyEnvelopeSigningStatement;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * NF07-TRUST（D-03 = AWS KMS）の試験用の trust 文書と署名。鍵は全て test-only（実行のたびに memory 上で生成）で、KMS key ARN は
 * AWS の文書の例の account（{@code 111122223333}）と名前から作った UUID の合成値（実在しない）。envelope の値は合成値で、法的な
 * 値ではない。KMS の署名（{@code ED25519_SHA_512}・{@code MessageType=RAW}）は純粋な Ed25519 なので、JDK の Ed25519 で同じ
 * byte 列を署名して代える。
 */
final class KmsTrustTestDocuments {

    static final String MODULE = "NF08_AI_SUMMARY";
    static final String ELEMENT = "AI_PROMPT_LOG";
    static final String ACTION = "EXTERNAL_DISPATCH";
    static final String PROVIDER = "gemini-adapter";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KmsTrustTestDocuments() {
    }

    static KeyPair ed25519() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** GetPublicKey の PublicKey と同じ形（SubjectPublicKeyInfo の DER を base64）。 */
    static String spki(KeyPair key) {
        return Base64.getEncoder().encodeToString(key.getPublic().getEncoded());
    }

    /** 合成の KMS key ARN（実在しない account と、名前から作った UUID）。 */
    static String arn(String name) {
        return "arn:aws:kms:ap-northeast-1:111122223333:key/"
                + UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }

    static ObjectNode policyAnchor(String authorityId, String keyId, KeyPair key, String status, Instant validFrom,
                                   Instant validUntil, String... tenants) {
        ObjectNode node = common(authorityId, keyId, key, status, validFrom, validUntil, tenants);
        node.set("scopes", array(MODULE));
        node.set("dataElements", array(ELEMENT));
        node.set("actions", array(ACTION));
        node.set("providers", array(PROVIDER));
        return node;
    }

    static ObjectNode holdAnchor(String authorityId, String keyId, KeyPair key, String principal, String status,
                                 Instant validFrom, Instant validUntil, String... tenants) {
        ObjectNode node = common(authorityId, keyId, key, status, validFrom, validUntil, tenants);
        node.put("principal", principal);
        node.set("roles", array("ISSUER", "APPROVER"));
        node.set("targetTypes", array("SUBJECT", "DATA_ELEMENT"));
        return node;
    }

    static ObjectNode document(Instant generatedAt, List<ObjectNode> policies, List<ObjectNode> holds,
                               Map<String, String> envelopes) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("format", KmsTrustDocument.FORMAT);
        root.put("generatedAt", generatedAt.toString());
        ArrayNode policyArray = root.putArray("policyAnchors");
        policies.forEach(policyArray::add);
        ArrayNode holdArray = root.putArray("holdReleaseAnchors");
        holds.forEach(holdArray::add);
        ObjectNode envelopeNode = root.putObject("envelopeStatuses");
        envelopes.forEach(envelopeNode::put);
        return root;
    }

    static byte[] bytes(ObjectNode document) {
        try {
            return MAPPER.writeValueAsBytes(document);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 署名済みの policy envelope（canonical bytes と署名）。 */
    record SignedEnvelope(String policyId, String revision, byte[] canonical, byte[] signature, String digest) {
    }

    /**
     * policy envelope に KMS の手順で署名する（digest 声明 {@link PolicyEnvelopeSigningStatement} を RAW で署名。本番の verifier は
     * この形だけを受け付ける）。
     */
    static SignedEnvelope signPolicy(KeyPair key, String authorityId, String keyId, String tenant, String policyId,
                                     String revocationReference, Instant issuedAt, Instant expiresAt,
                                     String retentionRule) {
        ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata("1", authorityId, keyId, tenant, MODULE,
                "PRODUCTION", "r1", "requester-test", "approver-test", revocationReference, issuedAt, issuedAt,
                expiresAt);
        PolicyApprovalEnvelope unsigned = new PolicyApprovalEnvelope(metadata, policyId, "v1", ELEMENT, ACTION,
                PROVIDER, "TEST_PURPOSE", "TEST_TRIGGER", retentionRule, "TEST_ONLY_DISPOSITION", new byte[]{0});
        byte[] canonical = ApprovalEnvelopeCanonicalizer.canonicalBytes(unsigned);
        return new SignedEnvelope(policyId, "r1", canonical, sign(key, PolicyEnvelopeSigningStatement.of(canonical)),
                ApprovalEnvelopeCanonicalizer.digest(canonical));
    }

    /** hold 解除の claim（{@link HoldReleaseAuthorityClaim#signedBytes} を RAW で署名）。 */
    static HoldReleaseAuthorityClaim claim(KeyPair key, HoldReleaseAuthorityClaim.Role role, String authorityId,
                                           String keyId, HoldReleaseBinding binding, Instant issuedAt,
                                           Instant expiresAt) {
        byte[] message = HoldReleaseAuthorityClaim.signedBytes(binding, role, authorityId, keyId, issuedAt, expiresAt);
        return new HoldReleaseAuthorityClaim(role, authorityId, keyId, issuedAt, expiresAt, sign(key, message));
    }

    static HoldReleaseBinding binding(String tenant) {
        return new HoldReleaseBinding(tenant, "ENGINEER:7", "ph-" + "a".repeat(32), 1, "d".repeat(64),
                "hr-" + "b".repeat(32), 1, "c".repeat(64), "sys_user:1", "sys_user:2");
    }

    static byte[] sign(KeyPair key, byte[] message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key.getPrivate());
            signer.update(message);
            return signer.sign();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ObjectNode common(String authorityId, String keyId, KeyPair key, String status, Instant validFrom,
                                     Instant validUntil, String... tenants) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("authorityId", authorityId);
        node.put("keyId", keyId);
        node.put("kmsKeyArn", arn(authorityId + ":" + keyId));
        node.put("keySpec", KmsTrustDocument.KEY_SPEC);
        node.put("signingAlgorithm", KmsTrustDocument.SIGNING_ALGORITHM);
        node.put("publicKey", spki(key));
        node.put("status", status);
        node.put("validFrom", validFrom.toString());
        node.put("validUntil", validUntil.toString());
        node.set("tenants", array(tenants));
        return node;
    }

    private static ArrayNode array(String... values) {
        ArrayNode array = MAPPER.createArrayNode();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
