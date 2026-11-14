package com.ses.privacy.foundation;

/** W2用port。authoritative journal ACK後にだけ証明を発行する実装を置く。 */
public interface DispatchFenceVerifier {
    VerifiedDispatchFence verifyAndMint(TransitionBinding binding, String authorizationId);
}
