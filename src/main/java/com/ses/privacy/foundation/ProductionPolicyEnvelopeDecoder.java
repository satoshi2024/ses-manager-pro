package com.ses.privacy.foundation;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;

/**
 * 保存された policy envelope の canonical bytes（{@link ApprovalEnvelopeCanonicalizer} の v1 形式）を厳格に復号する。
 *
 * <p>復号後に同じ encoder で再 encoding し、保存 bytes と1 byte でも違えば拒否する（正規形でない表現・末尾の余分な
 * bytes・別kindの envelope を通さない）。失敗は全て {@link PolicySecurityException}（{@code POLICY_DRIFT} 群）。
 * package 外からは使えず、{@code ProductionPolicyReadonlyQueryService} だけが呼ぶ。
 */
final class ProductionPolicyEnvelopeDecoder {

    private static final String MAGIC = "SES-PRIVACY-APPROVAL";
    private static final String FORMAT_VERSION = "v1";
    private static final String KIND = "POLICY";
    /** 1 envelope の上限。現行の policy envelope は数KB程度で、これを超える保存値は改竄・誤登録として扱う。 */
    static final int MAX_CANONICAL_BYTES = 64 * 1024;
    private static final int MAX_SIGNATURE_BYTES = 1024;

    private ProductionPolicyEnvelopeDecoder() {
    }

    static PolicyApprovalEnvelope decode(byte[] canonical, byte[] signature) {
        if (canonical == null || canonical.length == 0 || canonical.length > MAX_CANONICAL_BYTES) {
            throw drift("保存されたpolicy envelopeの長さが不正です");
        }
        if (signature == null || signature.length == 0 || signature.length > MAX_SIGNATURE_BYTES) {
            throw drift("保存されたpolicy署名の長さが不正です");
        }
        PolicyApprovalEnvelope envelope;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(canonical))) {
            if (!MAGIC.equals(readString(in)) || !FORMAT_VERSION.equals(readString(in)) || !KIND.equals(readString(in))) {
                throw drift("policy envelopeの形式が一致しません");
            }
            ApprovalEnvelopeMetadata metadata = new ApprovalEnvelopeMetadata(
                    readString(in), readString(in), readString(in), readString(in), readString(in),
                    readString(in), readString(in), readString(in), readString(in), readString(in),
                    readInstant(in), readInstant(in), readInstant(in));
            envelope = new PolicyApprovalEnvelope(metadata, readString(in), readString(in), readString(in),
                    readString(in), readString(in), readString(in), readString(in), readString(in),
                    readString(in), signature);
            if (in.available() != 0) {
                throw drift("policy envelopeの末尾に余分なbytesがあります");
            }
        } catch (PolicySecurityException e) {
            throw e;
        } catch (IOException | RuntimeException malformed) {
            throw new PolicySecurityException(PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION,
                    "policy envelopeを復号できません", malformed);
        }
        if (!Arrays.equals(canonical, ApprovalEnvelopeCanonicalizer.canonicalBytes(envelope))) {
            throw new PolicySecurityException(PolicyFailureReason.DIGEST_MISMATCH,
                    "policy envelopeが正規形ではありません");
        }
        return envelope;
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) {
            throw drift("policy envelopeのfield長が不正です");
        }
        byte[] content = new byte[length];
        in.readFully(content);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new PolicySecurityException(PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION,
                    "policy envelopeに不正なUTF-8があります", invalid);
        }
    }

    private static Instant readInstant(DataInputStream in) throws IOException {
        long seconds = in.readLong();
        int nanos = in.readInt();
        if (nanos < 0 || nanos > 999_999_999) {
            throw drift("policy envelopeの時刻が不正です");
        }
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private static PolicySecurityException drift(String message) {
        return new PolicySecurityException(PolicyFailureReason.UNSUPPORTED_SCHEMA_VERSION, message);
    }
}
