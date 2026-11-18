package com.ses.service.ai.copilot.summary;

/** test sourceのsynthetic検証用receipt。本番usage署名サービスやprovider usageの証明には使用しない。 */
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
