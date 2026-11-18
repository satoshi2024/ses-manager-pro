package com.ses.privacy.foundation;

import java.util.Objects;

/** 署名済みcanonical checkpointの秘密情報を含まないopaque envelope。 */
record SignedWatermark(WatermarkCheckpoint checkpoint, byte[] canonicalPayload, byte[] signature,
                       String checkpointHash, String signatureDigest) {
    SignedWatermark {
        Objects.requireNonNull(checkpoint, "checkpoint");
        canonicalPayload = Objects.requireNonNull(canonicalPayload, "canonicalPayload").clone();
        signature = Objects.requireNonNull(signature, "signature").clone();
        if (signature.length != 64 || !isHash(checkpointHash) || !isHash(signatureDigest))
            throw new IllegalArgumentException("署名envelopeが不正です");
    }
    @Override public byte[] canonicalPayload() { return canonicalPayload.clone(); }
    @Override public byte[] signature() { return signature.clone(); }
    private static boolean isHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
}
