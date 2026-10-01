package com.ses.privacy.foundation;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class PrivacyCaseStateMachineTest {
    @Test
    void cannotSkipApprovalFenceOrReceiptToConfirmed() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        VerifiedApproval proof = fixture.verify();
        TransitionBinding binding = fixture.binding(proof);
        Instant now = SyntheticAuthorityFixture.NOW;

        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.REQUESTED, PrivacyCaseState.CONFIRMED, binding, proof, null, null, now));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.APPROVAL_PENDING, PrivacyCaseState.APPROVED, binding, null, null, null, now));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.APPROVED, PrivacyCaseState.CONFIRMED, binding, proof, null, null, now));
        VerifiedDispatchFence fence = new VerifiedDispatchFence(binding.tenantId(), binding.caseId(), "auth-1",
                binding.fingerprint(), now.plusSeconds(100));
        VerifiedActionReceipt receipt = new VerifiedActionReceipt(binding.tenantId(), binding.caseId(), "auth-1",
                "sha256:result", binding.fingerprint(), now.plusSeconds(50));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.APPROVED, PrivacyCaseState.CLAIMED, binding, null, fence, null, now));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.EXECUTING, PrivacyCaseState.CONFIRMED, binding, null, fence, receipt, now));
    }

    @Test
    void proofIsBoundToTenantCaseTargetVersionHashPolicyAndExpiry() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        VerifiedApproval proof = fixture.verify();
        TransitionBinding binding = fixture.binding(proof);
        assertEquals(PrivacyCaseState.APPROVED, PrivacyCaseStateMachine.transition(
                PrivacyCaseState.APPROVAL_PENDING, PrivacyCaseState.APPROVED, binding, proof, null, null,
                SyntheticAuthorityFixture.NOW));

        TransitionBinding changedTarget = new TransitionBinding(binding.tenantId(), binding.caseId(), binding.targetId(),
                "v8", binding.targetHash(), binding.policyDigest(), binding.caseRevision(), binding.policyRevision(),
                binding.action(), binding.providerId());
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.APPROVAL_PENDING, PrivacyCaseState.APPROVED, changedTarget, proof, null, null,
                SyntheticAuthorityFixture.NOW));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.APPROVAL_PENDING, PrivacyCaseState.APPROVED, binding, proof, null, null,
                proof.expiresAt()));
    }

    @Test
    void confirmationNeedsMatchingFenceAndReceiptProofs() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        VerifiedApproval approval = fixture.verify();
        TransitionBinding binding = fixture.binding(approval);
        VerifiedDispatchFence fence = new VerifiedDispatchFence(binding.tenantId(), binding.caseId(), "auth-1",
                binding.fingerprint(),
                SyntheticAuthorityFixture.NOW.plusSeconds(100));
        VerifiedActionReceipt receipt = new VerifiedActionReceipt(binding.tenantId(), binding.caseId(), "auth-1",
                "sha256:result", binding.fingerprint(),
                SyntheticAuthorityFixture.NOW.plusSeconds(50));

        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.EXECUTING, PrivacyCaseState.CONFIRMED, binding, approval, fence, null,
                SyntheticAuthorityFixture.NOW));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.EXECUTING, PrivacyCaseState.CONFIRMED, binding, approval, fence, receipt,
                receipt.expiresAt()));
        assertEquals(PrivacyCaseState.CONFIRMED, PrivacyCaseStateMachine.transition(
                PrivacyCaseState.EXECUTING, PrivacyCaseState.CONFIRMED, binding, approval, fence, receipt,
                SyntheticAuthorityFixture.NOW));

        TransitionBinding changed = new TransitionBinding(binding.tenantId(), binding.caseId(), binding.targetId(),
                binding.targetVersion(), "sha256:changed", binding.policyDigest(), binding.caseRevision(),
                binding.policyRevision(), binding.action(), binding.providerId());
        VerifiedDispatchFence wrongTarget = new VerifiedDispatchFence(binding.tenantId(), binding.caseId(), "auth-1",
                changed.fingerprint(),
                SyntheticAuthorityFixture.NOW.plusSeconds(100));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.EXECUTING, PrivacyCaseState.CONFIRMED, binding, approval, wrongTarget, receipt,
                SyntheticAuthorityFixture.NOW));
    }

    @Test
    void onlyDocumentedStateEdgesAreAllowed() {
        assertEquals(PrivacyCaseState.IDENTITY_VERIFIED, PrivacyCaseStateMachine.transition(
                PrivacyCaseState.REQUESTED, PrivacyCaseState.IDENTITY_VERIFIED, emptyBinding(), null, null, null,
                SyntheticAuthorityFixture.NOW));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.REQUESTED, PrivacyCaseState.APPROVAL_PENDING, emptyBinding(), null, null, null,
                SyntheticAuthorityFixture.NOW));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.CONFIRMED, PrivacyCaseState.EXECUTING, emptyBinding(), null, null, null,
                SyntheticAuthorityFixture.NOW));
    }

    @Test
    void dispatchAuthorizedCannotBeCancelledWithoutAuthoritativeCancellationReceipt() throws Exception {
        SyntheticAuthorityFixture fixture = new SyntheticAuthorityFixture();
        VerifiedApproval approval = fixture.verify();
        TransitionBinding binding = fixture.binding(approval);
        VerifiedDispatchFence fence = new VerifiedDispatchFence(binding.tenantId(), binding.caseId(), "auth-1",
                binding.fingerprint(), SyntheticAuthorityFixture.NOW.plusSeconds(100));
        VerifiedActionReceipt noCancellationReceipt = new VerifiedActionReceipt(binding.tenantId(), binding.caseId(),
                "auth-1", "sha256:ordinary-action-result", binding.fingerprint(), SyntheticAuthorityFixture.NOW.plusSeconds(50));
        TransitionBinding differentAction = new TransitionBinding(binding.tenantId(), binding.caseId(), binding.targetId(),
                binding.targetVersion(), binding.targetHash(), binding.policyDigest(), binding.caseRevision(),
                binding.policyRevision(), "DELETE", binding.providerId());
        VerifiedDispatchFence differentActionFence = new VerifiedDispatchFence(binding.tenantId(), binding.caseId(), "auth-2",
                differentAction.fingerprint(), SyntheticAuthorityFixture.NOW.plusSeconds(100));
        VerifiedActionReceipt differentActionReceipt = new VerifiedActionReceipt(binding.tenantId(), binding.caseId(),
                "auth-2", "sha256:different-action-result", differentAction.fingerprint(), SyntheticAuthorityFixture.NOW.plusSeconds(50));

        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.DISPATCH_AUTHORIZED, PrivacyCaseState.CANCELLED, binding, approval, fence, null,
                SyntheticAuthorityFixture.NOW));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.DISPATCH_AUTHORIZED, PrivacyCaseState.CANCELLED, binding, approval, fence,
                noCancellationReceipt, SyntheticAuthorityFixture.NOW));
        assertThrows(SecurityException.class, () -> PrivacyCaseStateMachine.transition(
                PrivacyCaseState.DISPATCH_AUTHORIZED, PrivacyCaseState.CANCELLED, binding, approval,
                differentActionFence, differentActionReceipt, SyntheticAuthorityFixture.NOW));
    }

    private static TransitionBinding emptyBinding() {
        return new TransitionBinding("tenant", "case", "target", "v1", "hash", "policy", "c1", "p1",
                "RESTRICT", "synthetic-store");
    }
}
