package com.ses.privacy.foundation;

/** W3用port。fence済みactionに対応するprovider receiptを検証する実装を置く。 */
public interface ActionReceiptVerifier {
    VerifiedActionReceipt verifyAndMint(TransitionBinding binding, VerifiedDispatchFence fence, String resultHash);
}
