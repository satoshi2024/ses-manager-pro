package com.ses.service.ai.copilot.summary;

/** signatureが無いDTOからは検証済みusage capabilityを作れない未信頼receipt。 */
final class SignedUsageReceipt {
    private final RateCardExecutionInput input;
    private final String keyId;
    private final byte[] signature;

    SignedUsageReceipt(RateCardExecutionInput input, String keyId, byte[] signature) {
        this.input = input;
        this.keyId = keyId;
        this.signature = signature == null ? null : signature.clone();
    }

    RateCardExecutionInput input() { return input; }
    String keyId() { return keyId; }
    byte[] signature() { return signature == null ? null : signature.clone(); }
}
