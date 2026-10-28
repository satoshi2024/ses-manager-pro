package com.ses.service.ai.copilot.summary;

/** rate-card検証拒否の安全な理由コード。外部応答本文やsecretを保持しない。 */
final class RateCardVerificationException extends RuntimeException {
    private final String reasonCode;

    RateCardVerificationException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    String reasonCode() { return reasonCode; }
}
