package com.ses.privacy.foundation;

import java.util.List;
import java.util.Map;

/** Current witnessを検証したinstanceだけが完全bindingのscope capabilityを発行する。 */
final class SyntheticPreviewScopeVerifier {
    VerifiedScope verify(SyntheticIdentityEvidenceVerifier.VerifiedIdentity identity,
                         SyntheticPrivacyRequestService.Command command,
                         SyntheticPreviewWitnessPort.SourceSnapshot snapshot) {
        Map<String, String> v = snapshot.values();
        if (!identity.tenantId().equals(v.get("tenantId")) || !identity.requesterId().equals(v.get("requesterId"))
                || !identity.subjectRef().equals(v.get("subjectRef")) || !identity.requestId().equals(v.get("requestId"))
                || !command.caseRef().equals(v.get("caseRef")) || !command.scopeId().equals(v.get("scopeId"))
                || !identity.revision().equals(v.get("identityRevision")) || !identity.digest().equals(v.get("identityHash"))
                || !identity.keyId().equals(v.get("keyId")) || !identity.audience().equals(v.get("audience"))
                || !identity.expiresAt().toString().equals(v.get("identityExpiresAt"))
                || !"ACTIVE".equals(v.get("identityRevocation")) || !"ACTIVE".equals(v.get("scopeState")))
            throw new SecurityException("identity/scope request bindingが不一致です");
        if (!"CLEAR".equals(v.get("holdState")) || !"KNOWN".equals(v.get("policyState"))
                || !"ACTIVE".equals(v.get("policyRevocation")) || !"KNOWN".equals(v.get("journalState"))
                || !"KNOWN".equals(v.get("auditState")) || !"ACTIVE".equals(v.get("witnessKeyState")))
            throw new SecurityException("hold/policy/journal witnessがpreview可能ではありません");
        if (v.get("scopeRevision").isBlank() || !computeScopeHash(identity,command,v.get("scopeRevision"),snapshot.allowedTargetIds()).equals(v.get("scopeHash"))
                || !v.get("providerId").matches("synthetic-provider-[a-zA-Z0-9-]+")
                || !"SYNTHETIC".equals(v.get("providerAudience")))
            throw new SecurityException("scope/provider witnessが不正です");
        return new VerifiedScope(identity.tenantId(), identity.requesterId(), identity.subjectRef(), identity.requestId(),
                identity.revision(), identity.digest(), command.caseRef(), command.scopeId(), v.get("scopeRevision"),
                v.get("scopeHash"), v.get("providerId"), v.get("providerAudience"), snapshot.allowedTargetIds());
    }

    static String computeScopeHash(SyntheticIdentityEvidenceVerifier.VerifiedIdentity identity,
                                   SyntheticPrivacyRequestService.Command command,String scopeRevision,List<String> targetIds){
        List<String> fields=new java.util.ArrayList<>(List.of(identity.tenantId(),identity.requesterId(),identity.subjectRef(),
                identity.requestId(),identity.revision(),identity.digest(),command.caseRef(),command.scopeId(),scopeRevision));
        targetIds.stream().sorted().forEach(fields::add);
        return ApprovalEnvelopeCanonicalizer.digest(SyntheticCanonicalizer.encode("SES-SYNTHETIC-SCOPE","v1",
                fields.toArray(String[]::new)));
    }

    static final class VerifiedScope {
        private final String tenantId, requesterId, subjectRef, requestId, identityRevision, identityHash,
                caseRef, scopeId, scopeRevision, scopeHash, providerId, providerAudience;
        private final List<String> targetIds;
        private VerifiedScope(String tenantId, String requesterId, String subjectRef, String requestId,
                              String identityRevision, String identityHash, String caseRef, String scopeId,
                              String scopeRevision, String scopeHash, String providerId, String providerAudience,
                              List<String> targetIds) {
            this.tenantId=tenantId; this.requesterId=requesterId; this.subjectRef=subjectRef; this.requestId=requestId;
            this.identityRevision=identityRevision; this.identityHash=identityHash; this.caseRef=caseRef; this.scopeId=scopeId;
            this.scopeRevision=scopeRevision; this.scopeHash=scopeHash; this.providerId=providerId;
            this.providerAudience=providerAudience; this.targetIds=List.copyOf(targetIds);
        }
        String tenantId(){return tenantId;} String requesterId(){return requesterId;} String subjectRef(){return subjectRef;}
        String requestId(){return requestId;} String identityRevision(){return identityRevision;} String identityHash(){return identityHash;}
        String caseRef(){return caseRef;} String scopeId(){return scopeId;} String scopeRevision(){return scopeRevision;}
        String scopeHash(){return scopeHash;} String providerId(){return providerId;} String providerAudience(){return providerAudience;}
        List<String> targetIds(){return targetIds;}
        boolean permits(String tenant, String requester, String subject, String request, String identityRevision,
                        String identityHash, String scope, String scopeRevision, String scopeHash,
                        String caseRef, String provider, String providerAudience, String target) {
            return tenantId.equals(tenant) && requesterId.equals(requester) && subjectRef.equals(subject)
                    && requestId.equals(request) && this.identityRevision.equals(identityRevision)
                    && this.identityHash.equals(identityHash) && scopeId.equals(scope)
                    && this.scopeRevision.equals(scopeRevision) && this.scopeHash.equals(scopeHash)
                    && this.caseRef.equals(caseRef)
                    && providerId.equals(provider) && this.providerAudience.equals(providerAudience) && targetIds.contains(target);
        }
    }
}
