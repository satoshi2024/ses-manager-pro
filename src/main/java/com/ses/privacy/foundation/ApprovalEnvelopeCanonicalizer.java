package com.ses.privacy.foundation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 署名とpayload digestに使う、版管理された曖昧性のないbinary encoding。 */
public final class ApprovalEnvelopeCanonicalizer {
    private static final String MAGIC = "SES-PRIVACY-APPROVAL";

    private ApprovalEnvelopeCanonicalizer() { }

    public static byte[] canonicalBytes(PolicyApprovalEnvelope envelope) {
        return encode("POLICY", envelope.metadata(), out -> {
            write(out, envelope.policyId());
            write(out, envelope.policyVersion());
            write(out, envelope.dataElementId());
            write(out, envelope.action());
            write(out, envelope.providerId());
            write(out, envelope.purpose());
            write(out, envelope.trigger());
            write(out, envelope.retentionRule());
            write(out, envelope.disposition());
        });
    }

    public static byte[] canonicalBytes(CaseApprovalEnvelope envelope) {
        return encode("CASE", envelope.metadata(), out -> {
            write(out, envelope.caseId());
            write(out, envelope.subjectReference());
            write(out, envelope.targetId());
            write(out, envelope.targetVersion());
            write(out, envelope.targetHash());
            write(out, envelope.policyDigest());
            write(out, envelope.action());
            write(out, envelope.providerId());
            writeInstant(out, envelope.identityVerifiedAt());
        });
    }

    public static String digest(byte[] canonicalBytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDKにSHA-256がありません", impossible);
        }
    }

    public static byte[] canonicalBindingBytes(TransitionBinding binding) {
        return encode("FSM-BINDING", new ApprovalEnvelopeMetadata("1", "internal-proof", "internal-proof",
                binding.tenantId(), "FSM", "IN_PROCESS", binding.caseRevision(), "system", "system", "none",
                java.time.Instant.EPOCH, java.time.Instant.EPOCH, java.time.Instant.MAX), out -> {
            write(out, binding.caseId());
            write(out, binding.targetId());
            write(out, binding.targetVersion());
            write(out, binding.targetHash());
            write(out, binding.policyDigest());
            write(out, binding.caseRevision());
            write(out, binding.policyRevision());
            write(out, binding.action());
            write(out, binding.providerId());
        });
    }

    private static byte[] encode(String kind, ApprovalEnvelopeMetadata metadata, PayloadWriter payloadWriter) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            write(out, MAGIC);
            write(out, "v1");
            write(out, kind);
            write(out, metadata.schemaVersion());
            write(out, metadata.authorityId());
            write(out, metadata.keyId());
            write(out, metadata.tenantId());
            write(out, metadata.scope());
            write(out, metadata.audience());
            write(out, metadata.revision());
            write(out, metadata.requesterId());
            write(out, metadata.approverId());
            write(out, metadata.revocationReference());
            writeInstant(out, metadata.issuedAt());
            writeInstant(out, metadata.effectiveAt());
            writeInstant(out, metadata.expiresAt());
            payloadWriter.write(out);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossibleForMemoryStream) {
            throw new IllegalStateException("承認envelopeをencodingできません", impossibleForMemoryStream);
        }
    }

    private static void writeInstant(DataOutputStream out, java.time.Instant instant) throws IOException {
        out.writeLong(instant.getEpochSecond());
        out.writeInt(instant.getNano());
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        final ByteBuffer encoded;
        try {
            encoded = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(java.nio.CharBuffer.wrap(value));
        } catch (CharacterCodingException invalidUnicode) {
            throw new IllegalArgumentException("承認fieldに不正なUnicodeがあります", invalidUnicode);
        }
        out.writeInt(encoded.remaining());
        byte[] content = new byte[encoded.remaining()];
        encoded.get(content);
        out.write(content);
    }

    @FunctionalInterface
    private interface PayloadWriter {
        void write(DataOutputStream out) throws IOException;
    }
}
