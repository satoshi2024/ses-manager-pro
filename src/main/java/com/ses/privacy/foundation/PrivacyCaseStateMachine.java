package com.ses.privacy.foundation;

import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/** 純粋な遷移関数。approval、journal fence、receiptにはverifier発行証明を要求する。 */
public final class PrivacyCaseStateMachine {
    private static final Map<PrivacyCaseState, EnumSet<PrivacyCaseState>> ALLOWED = allowedTransitions();

    private PrivacyCaseStateMachine() { }

    public static PrivacyCaseState transition(PrivacyCaseState current, PrivacyCaseState next,
                                              TransitionBinding binding, VerifiedApproval approval,
                                              VerifiedDispatchFence fence, VerifiedActionReceipt receipt,
                                              Instant now) {
        if (current == null || next == null || binding == null || now == null) {
            throw new IllegalArgumentException("state、binding、nowが必要です");
        }
        if (!ALLOWED.getOrDefault(current, EnumSet.noneOf(PrivacyCaseState.class)).contains(next)) {
            throw new SecurityException("許可されていないcase遷移です: " + current + " -> " + next);
        }
        if (requiresApproval(next) && (approval == null || !approval.matches(binding, now))) {
            throw new SecurityException("署名検証済みpolicy/case承認または最新bindingがありません");
        }
        if ((next == PrivacyCaseState.CLAIMED || next == PrivacyCaseState.DISPATCH_AUTHORIZED
                || next == PrivacyCaseState.EXECUTING)
                && (fence == null || !fence.matches(binding, now))) {
            throw new SecurityException("信頼できるdispatch fenceがありません");
        }
        if (next == PrivacyCaseState.CONFIRMED
                && (receipt == null || fence == null || !fence.matches(binding, now)
                || !receipt.matches(binding, fence, now))) {
            throw new SecurityException("fence済みactionに対応する検証済みreceiptがありません");
        }
        return next;
    }

    private static boolean requiresApproval(PrivacyCaseState state) {
        return state == PrivacyCaseState.APPROVED || state == PrivacyCaseState.CLAIMED
                || state == PrivacyCaseState.DISPATCH_AUTHORIZED || state == PrivacyCaseState.EXECUTING
                || state == PrivacyCaseState.CONFIRMED;
    }

    private static Map<PrivacyCaseState, EnumSet<PrivacyCaseState>> allowedTransitions() {
        Map<PrivacyCaseState, EnumSet<PrivacyCaseState>> transitions = new EnumMap<>(PrivacyCaseState.class);
        transitions.put(PrivacyCaseState.REQUESTED, EnumSet.of(PrivacyCaseState.IDENTITY_VERIFIED,
                PrivacyCaseState.BLOCKED, PrivacyCaseState.UNKNOWN, PrivacyCaseState.CANCELLED));
        transitions.put(PrivacyCaseState.IDENTITY_VERIFIED, EnumSet.of(PrivacyCaseState.SCOPED,
                PrivacyCaseState.BLOCKED, PrivacyCaseState.UNKNOWN, PrivacyCaseState.CANCELLED));
        transitions.put(PrivacyCaseState.SCOPED, EnumSet.of(PrivacyCaseState.PREVIEWED,
                PrivacyCaseState.BLOCKED, PrivacyCaseState.UNKNOWN, PrivacyCaseState.CANCELLED));
        transitions.put(PrivacyCaseState.PREVIEWED, EnumSet.of(PrivacyCaseState.APPROVAL_PENDING,
                PrivacyCaseState.BLOCKED, PrivacyCaseState.UNKNOWN, PrivacyCaseState.CANCELLED));
        transitions.put(PrivacyCaseState.APPROVAL_PENDING, EnumSet.of(PrivacyCaseState.APPROVED,
                PrivacyCaseState.BLOCKED, PrivacyCaseState.UNKNOWN, PrivacyCaseState.CANCELLED));
        transitions.put(PrivacyCaseState.APPROVED, EnumSet.of(PrivacyCaseState.CLAIMED,
                PrivacyCaseState.BLOCKED, PrivacyCaseState.UNKNOWN, PrivacyCaseState.CANCELLED));
        transitions.put(PrivacyCaseState.CLAIMED, EnumSet.of(PrivacyCaseState.DISPATCH_AUTHORIZED,
                PrivacyCaseState.BLOCKED, PrivacyCaseState.UNKNOWN, PrivacyCaseState.CANCELLED));
        transitions.put(PrivacyCaseState.DISPATCH_AUTHORIZED, EnumSet.of(PrivacyCaseState.EXECUTING,
                PrivacyCaseState.BLOCKED, PrivacyCaseState.UNKNOWN, PrivacyCaseState.CANCELLED));
        transitions.put(PrivacyCaseState.EXECUTING, EnumSet.of(PrivacyCaseState.CONFIRMED,
                PrivacyCaseState.UNKNOWN, PrivacyCaseState.PARTIAL_FAILED));
        return transitions;
    }
}
