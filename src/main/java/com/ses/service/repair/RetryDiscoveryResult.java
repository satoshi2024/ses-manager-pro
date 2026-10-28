package com.ses.service.repair;

import java.util.List;

/**
 * 重試発見・再構成の判定結果
 */
public record RetryDiscoveryResult(
    boolean alreadyResolved,
    CarrierEvidence carrierEvidence,
    ScopeRepairQueueSnapshot carrierQueue,
    List<ScopeRepairQueueSnapshot> reconstructedQueues
) {
    public static RetryDiscoveryResult notResolved() {
        return new RetryDiscoveryResult(false, null, null, List.of());
    }

    public static RetryDiscoveryResult alreadyResolved(
        CarrierEvidence carrierEvidence,
        ScopeRepairQueueSnapshot carrierQueue,
        List<ScopeRepairQueueSnapshot> reconstructedQueues
    ) {
        return new RetryDiscoveryResult(true, carrierEvidence, carrierQueue, reconstructedQueues);
    }
}
