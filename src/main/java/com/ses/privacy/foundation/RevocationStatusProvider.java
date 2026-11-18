package com.ses.privacy.foundation;

/** 信頼できる署名失効状態の参照境界。 */
@FunctionalInterface
public interface RevocationStatusProvider {
    RevocationStatus statusOf(String revocationReference);
}
