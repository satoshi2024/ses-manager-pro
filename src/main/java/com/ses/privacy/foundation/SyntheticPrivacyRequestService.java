package com.ses.privacy.foundation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 合成preview専用。bean/controller登録禁止。persistenceはharness発行の限定portを使う。 */
final class SyntheticPrivacyRequestService {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private final SyntheticPreviewStore store;
    private final SyntheticIdentityEvidenceVerifier identityVerifier;
    private final SyntheticPreviewScopeVerifier scopeVerifier;
    private final SyntheticPreviewWitnessPort witnesses;
    private final SyntheticPreviewProviderRegistry providers;
    private final Clock clock;
    private final Runnable beforeFinalRead;
    private final Runnable beforeCommit;

    SyntheticPrivacyRequestService(SyntheticPreviewStore store, SyntheticIdentityEvidenceVerifier identityVerifier,
            SyntheticPreviewScopeVerifier scopeVerifier, SyntheticPreviewWitnessPort witnesses,
            SyntheticPreviewProviderRegistry providers, Clock clock,
            Runnable beforeFinalRead, Runnable beforeCommit) {
        this.store=java.util.Objects.requireNonNull(store);
        this.identityVerifier=java.util.Objects.requireNonNull(identityVerifier);
        this.scopeVerifier=java.util.Objects.requireNonNull(scopeVerifier);
        this.witnesses=java.util.Objects.requireNonNull(witnesses);
        this.providers=java.util.Objects.requireNonNull(providers);
        this.clock=java.util.Objects.requireNonNull(clock);
        this.beforeFinalRead=java.util.Objects.requireNonNull(beforeFinalRead);
        this.beforeCommit=java.util.Objects.requireNonNull(beforeCommit);
    }

    PreviewResult preview(Command command, SyntheticIdentityEnvelope evidence) {
        if (command == null || evidence == null) throw new IllegalArgumentException("requestとidentity proofが必要です");
        final SyntheticIdentityEvidenceVerifier.VerifiedIdentity identity;
        try {
            identity = verifyIdentity(command, evidence);
        } catch (SecurityException rejected) {
            return closed(command, "PREVIEW_STALE", "IDENTITY_PROOF_INVALID_OR_EXPIRED", null, 0);
        }
        try {
            SyntheticPreviewStore.PreviewSnapshot prior = store.find(command.tenantId(), command.requestId(), command.idempotencyKey());
            if (prior != null) {
                if (!prior.requestHash().equals(command.fingerprint()))
                    return closed(command,"PREVIEW_STALE","IDEMPOTENCY_CONFLICT",prior.requestId(),prior.caseRevision());
                if (!snapshotIntegrityValid(prior))
                    return closed(command,"PREVIEW_UNKNOWN","SNAPSHOT_INTEGRITY_UNKNOWN",prior.requestId(),prior.caseRevision());
                return validateView(command, evidence, identity, prior);
            }

            SyntheticPreviewWitnessPort.SourceSnapshot first = readWitness(identity, command);
            PreviewResult blocked = sourceClosed(command, first, 0, null);
            if (blocked != null) return blocked;
            long expectedRevision = parseRevision(first.values().get("caseRevision"));
            SyntheticPreviewStore.CaseHead initialHead = store.readCaseHead(command.tenantId(), command.caseRef());
            if (initialHead == null || initialHead.revision() != expectedRevision
                    || !PrivacyCaseState.SCOPED.name().equals(initialHead.state()))
                return closed(command,"PREVIEW_STALE","CASE_REVISION_WITNESS_MISMATCH",null,expectedRevision);
            SyntheticPreviewScopeVerifier.VerifiedScope scope = scopeVerifier.verify(identity, command, first);
            SyntheticPreviewPort provider = providers.require(scope);
            ensurePreviewAllowed(identity, first);
            List<SyntheticPreviewWitnessPort.SourceSnapshot> observations = new ArrayList<>();
            observations.add(first);
            List<PreviewResult.PreviewItem> items = new ArrayList<>();

            for (String targetId : scope.targetIds()) {
                ensureIdentityCurrent(command, evidence, identity);
                requireExpectedCase(command, expectedRevision, PrivacyCaseState.SCOPED.name());
                SyntheticPreviewWitnessPort.SourceSnapshot before = readWitness(identity, command);
                observations.add(before);
                blocked = sourceClosed(command, before, expectedRevision, null);
                if (blocked != null) return blocked;
                if (!first.bindingHash().equals(before.bindingHash()))
                    return closed(command,"PREVIEW_STALE","SOURCE_DRIFT_BEFORE_READ",null,expectedRevision);
                SyntheticPreviewPort.SyntheticTarget target = provider.read(scope, targetId);
                if (!validTarget(target, targetId, scope))
                    return closed(command,"PREVIEW_STALE","TARGET_SCOPE_MISMATCH",null,expectedRevision);
                SyntheticPreviewWitnessPort.SourceSnapshot after = readWitness(identity, command);
                observations.add(after);
                blocked = sourceClosed(command, after, expectedRevision, null);
                if (blocked != null) return blocked;
                if (!first.bindingHash().equals(after.bindingHash()))
                    return closed(command,"PREVIEW_STALE","SOURCE_DRIFT_DURING_READ",null,expectedRevision);
                requireExpectedCase(command, expectedRevision, PrivacyCaseState.SCOPED.name());
                items.add(new PreviewResult.PreviewItem(target.id(),target.version(),target.contentHash(),
                        target.observedAt(),null,"CANDIDATE"));
            }

            beforeFinalRead.run();
            ensureIdentityCurrent(command, evidence, identity);
            SyntheticPreviewWitnessPort.SourceSnapshot finalRead = readWitness(identity, command);
            observations.add(finalRead);
            blocked = sourceClosed(command, finalRead, expectedRevision, null);
            if (blocked != null) return blocked;
            if (!first.bindingHash().equals(finalRead.bindingHash()))
                return closed(command,"PREVIEW_STALE","SOURCE_DRIFT_BEFORE_FINAL_METADATA",null,expectedRevision);
            requireExpectedCase(command, expectedRevision, PrivacyCaseState.SCOPED.name());

            List<PreviewResult.PreviewItem> finalItems = new ArrayList<>();
            for (PreviewResult.PreviewItem item : items) {
                SyntheticPreviewPort.SyntheticTarget current = provider.describeCurrent(scope,item.syntheticTargetId());
                if (!validTarget(current,item.syntheticTargetId(),scope)
                        || !item.targetVersion().equals(current.version()) || !item.contentHash().equals(current.contentHash()))
                    return closed(command,"PREVIEW_STALE","TARGET_METADATA_DRIFT_BEFORE_COMMIT",null,expectedRevision);
                finalItems.add(new PreviewResult.PreviewItem(item.syntheticTargetId(),item.targetVersion(),item.contentHash(),
                        item.observedAt(),current.observedAt(),"CANDIDATE"));
            }
            ensurePreviewAllowed(identity, finalRead);
            ensureIdentityCurrent(command,evidence,identity);
            requireExpectedCase(command,expectedRevision,PrivacyCaseState.SCOPED.name());
            beforeCommit.run();

            long nextRevision = expectedRevision + 1;
            String witnessJson = JSON.writeValueAsString(observations.stream()
                    .map(SyntheticPreviewWitnessPort.SourceSnapshot::canonical).toList());
            String itemsJson = JSON.writeValueAsString(finalItems);
            Instant observedTo = observations.stream().map(SyntheticPreviewWitnessPort.SourceSnapshot::observedAt)
                    .max(Instant::compareTo).orElse(first.observedAt());
            TransitionBinding binding = new TransitionBinding(command.tenantId(),command.caseRef(),finalItems.get(0).syntheticTargetId(),
                    finalItems.get(0).targetVersion(),finalItems.get(0).contentHash(),finalRead.values().get("policyHash"),
                    Long.toString(expectedRevision),finalRead.values().get("policyVersion"),"SYNTHETIC_PREVIEW",scope.providerId());
            PrivacyCaseStateMachine.transition(PrivacyCaseState.SCOPED,PrivacyCaseState.PREVIEWED,binding,null,null,null,clock.instant());
            String snapshotHash = snapshotDigest(command,expectedRevision,nextRevision,identity.digest(),first.bindingHash(),
                    witnessJson,itemsJson,first.observedAt(),observedTo);
            SyntheticPreviewStore.PreviewDraft draft = new SyntheticPreviewStore.PreviewDraft(command.tenantId(),command.requestId(),
                    command.idempotencyKey(),command.fingerprint(),command.caseRef(),expectedRevision,nextRevision,identity.digest(),
                    first.bindingHash(),snapshotHash,witnessJson,itemsJson,first.observedAt(),observedTo,finalItems.get(0).syntheticTargetId(),
                    finalItems.get(0).targetVersion(),finalItems.get(0).contentHash(),finalRead.values().get("policyHash"),
                    finalRead.values().get("policyVersion"),scope.providerId());

            SyntheticPreviewStore.PersistOutcome outcome = store.persist(draft);
            if (outcome == SyntheticPreviewStore.PersistOutcome.FAILED)
                return closed(command,"PREVIEW_UNKNOWN","PERSISTENCE_FAILED",null,expectedRevision);
            SyntheticPreviewStore.PreviewSnapshot saved = store.find(command.tenantId(),command.requestId(),command.idempotencyKey());
            if (outcome == SyntheticPreviewStore.PersistOutcome.CAS_FAILED)
                return sameIdempotentReadback(saved,draft) && snapshotIntegrityValid(saved)
                        ? validateView(command,evidence,identity,saved)
                        : saved!=null&&saved.tenantId().equals(draft.tenantId())&&saved.requestId().equals(draft.requestId())
                                &&saved.idempotencyKey().equals(draft.idempotencyKey())
                                ? closed(command,"PREVIEW_STALE","IDEMPOTENCY_CONFLICT",saved.requestId(),saved.caseRevision())
                                : closed(command,"PREVIEW_STALE","CASE_CAS_FAILED",null,expectedRevision);
            if (outcome == SyntheticPreviewStore.PersistOutcome.DUPLICATE)
                return sameIdempotentReadback(saved,draft) && snapshotIntegrityValid(saved)
                        ? validateView(command,evidence,identity,saved)
                        : closed(command,"PREVIEW_STALE","IDEMPOTENCY_CONFLICT",null,expectedRevision);
            if (!matchesReadback(saved,draft,outcome == SyntheticPreviewStore.PersistOutcome.ACK_UNKNOWN))
                return closed(command,"PREVIEW_UNKNOWN","COMMIT_READBACK_UNCONFIRMED",null,expectedRevision);
            return validateView(command,evidence,identity,saved);
        } catch (IdentityExpiryException e) {
            return closed(command,"PREVIEW_STALE","IDENTITY_EXPIRED",null,0);
        } catch (PolicyExpiryException e) {
            return closed(command,"PREVIEW_STALE","POLICY_OUTSIDE_EFFECTIVE_RANGE",null,0);
        } catch (CaseDriftException e) {
            return closed(command,"PREVIEW_STALE","CASE_REVISION_DRIFT",null,0);
        } catch (SecurityException e) {
            return closed(command,"PREVIEW_STALE","IDENTITY_OR_SCOPE_REVALIDATION_FAILED",null,0);
        } catch (RuntimeException e) {
            return closed(command,"PREVIEW_UNKNOWN","SOURCE_OR_STORE_UNAVAILABLE",null,0);
        } catch (java.io.IOException e) {
            return closed(command,"PREVIEW_UNKNOWN","SNAPSHOT_SERIALIZATION_FAILED",null,0);
        }
    }

    private SyntheticIdentityEvidenceVerifier.VerifiedIdentity verifyIdentity(Command command, SyntheticIdentityEnvelope evidence) {
        return identityVerifier.verify(evidence,command.tenantId(),command.requesterId(),command.subjectRef(),
                command.requestId(),command.identityRevision());
    }
    private void ensureIdentityCurrent(Command command, SyntheticIdentityEnvelope evidence,
                                       SyntheticIdentityEvidenceVerifier.VerifiedIdentity identity) {
        if(!identity.expiresAt().isAfter(clock.instant()))throw new IdentityExpiryException();
        SyntheticIdentityEvidenceVerifier.VerifiedIdentity now = verifyIdentity(command,evidence);
        if (!now.digest().equals(identity.digest())) throw new SecurityException("identity proofが変化しました");
    }
    private SyntheticPreviewWitnessPort.SourceSnapshot readWitness(SyntheticIdentityEvidenceVerifier.VerifiedIdentity identity,
                                                                    Command command) {
        SyntheticPreviewWitnessPort.SourceSnapshot snapshot = witnesses.readCurrent(identity.tenantId(),identity.requesterId(),
                identity.subjectRef(),identity.requestId(),command.caseRef(),command.scopeId());
        assertSourceBinding(identity,command,snapshot);
        return snapshot;
    }
    private void ensurePreviewAllowed(SyntheticIdentityEvidenceVerifier.VerifiedIdentity identity,
                                      SyntheticPreviewWitnessPort.SourceSnapshot source) {
        Instant now=clock.instant();
        if (!identity.expiresAt().isAfter(now)) throw new IdentityExpiryException();
        Map<String,String> v=source.values();
        if (Instant.parse(v.get("policyEffective")).isAfter(now) || !Instant.parse(v.get("policyExpiresAt")).isAfter(now))
            throw new PolicyExpiryException();
    }
    private void requireExpectedCase(Command command,long revision,String state) {
        SyntheticPreviewStore.CaseHead head=store.readCaseHead(command.tenantId(),command.caseRef());
        if(head==null||head.revision()!=revision||!state.equals(head.state()))
            throw new CaseDriftException();
    }
    private static boolean validTarget(SyntheticPreviewPort.SyntheticTarget target,String id,
                                       SyntheticPreviewScopeVerifier.VerifiedScope scope) {
        return target!=null && id.equals(target.id()) && target.version()!=null && target.contentHash()!=null
                && target.observedAt()!=null && scope.targetIds().contains(id);
    }
    private static void assertSourceBinding(SyntheticIdentityEvidenceVerifier.VerifiedIdentity identity,Command command,
                                             SyntheticPreviewWitnessPort.SourceSnapshot source) {
        Map<String,String> v=source.values();
        if(!identity.tenantId().equals(v.get("tenantId"))||!identity.requesterId().equals(v.get("requesterId"))
                ||!identity.subjectRef().equals(v.get("subjectRef"))||!identity.requestId().equals(v.get("requestId"))
                ||!command.caseRef().equals(v.get("caseRef"))||!command.scopeId().equals(v.get("scopeId")))
            throw new SecurityException("source witness bindingが一致しません");
    }

    private PreviewResult validateView(Command command,SyntheticIdentityEnvelope evidence,
                                       SyntheticIdentityEvidenceVerifier.VerifiedIdentity identity,
                                       SyntheticPreviewStore.PreviewSnapshot snapshot) {
        try {
            if(!identity.expiresAt().isAfter(clock.instant()))
                return closed(command,"PREVIEW_STALE","IDENTITY_EXPIRED",snapshot.requestId(),snapshot.caseRevision());
            SyntheticIdentityEvidenceVerifier.VerifiedIdentity currentIdentity=verifyIdentity(command,evidence);
            if(!currentIdentity.digest().equals(identity.digest())) return closed(command,"PREVIEW_STALE","IDENTITY_PROOF_DRIFT",snapshot.requestId(),snapshot.caseRevision());
            SyntheticPreviewStore.CaseHead head=store.readCaseHead(command.tenantId(),command.caseRef());
            if(head==null||head.revision()!=snapshot.caseRevision()||!PrivacyCaseState.PREVIEWED.name().equals(head.state()))
                return closed(command,"PREVIEW_STALE","CASE_REVISION_DRIFT",snapshot.requestId(),snapshot.caseRevision());
            SyntheticPreviewWitnessPort.SourceSnapshot current=readWitness(identity,command);
            PreviewResult closed=sourceClosed(command,current,snapshot.caseRevision(),snapshot.requestId());
            if(closed!=null)return closed;
            if(!identity.revision().equals(current.values().get("identityRevision"))
                    ||!identity.digest().equals(current.values().get("identityHash"))
                    ||!identity.keyId().equals(current.values().get("keyId"))
                    ||!identity.audience().equals(current.values().get("audience"))
                    ||!identity.expiresAt().toString().equals(current.values().get("identityExpiresAt")))
                return closed(command,"PREVIEW_STALE","IDENTITY_WITNESS_DRIFT",snapshot.requestId(),snapshot.caseRevision());
            if(!Long.toString(snapshot.baseCaseRevision()).equals(current.values().get("caseRevision")))
                return closed(command,"PREVIEW_STALE","CASE_WITNESS_DRIFT",snapshot.requestId(),snapshot.caseRevision());
            if(!snapshot.witnessHash().equals(current.bindingHash()))
                return closed(command,"PREVIEW_STALE","SOURCE_WITNESS_DRIFT",snapshot.requestId(),snapshot.caseRevision());
            if(!snapshotIntegrityValid(snapshot))
                return closed(command,"PREVIEW_UNKNOWN","SNAPSHOT_INTEGRITY_UNKNOWN",snapshot.requestId(),snapshot.caseRevision());
            SyntheticPreviewScopeVerifier.VerifiedScope scope=scopeVerifier.verify(identity,command,current);
            SyntheticPreviewPort provider=providers.require(scope);
            List<PreviewResult.PreviewItem> items=JSON.readValue(snapshot.itemsJson(),new TypeReference<>(){});
            for(PreviewResult.PreviewItem item:items){
                SyntheticPreviewPort.SyntheticTarget target=provider.describeCurrent(scope,item.syntheticTargetId());
                if(!validTarget(target,item.syntheticTargetId(),scope)||!item.targetVersion().equals(target.version())
                        ||!item.contentHash().equals(target.contentHash()))
                    return closed(command,"PREVIEW_STALE","TARGET_METADATA_DRIFT",snapshot.requestId(),snapshot.caseRevision());
            }
            SyntheticPreviewWitnessPort.SourceSnapshot finalCurrent=readWitness(identity,command);
            closed=sourceClosed(command,finalCurrent,snapshot.caseRevision(),snapshot.requestId());
            if(closed!=null)return closed;
            if(!snapshot.witnessHash().equals(finalCurrent.bindingHash()))
                return closed(command,"PREVIEW_STALE","SOURCE_DRIFT_DURING_RESPONSE_VALIDATION",snapshot.requestId(),snapshot.caseRevision());
            head=store.readCaseHead(command.tenantId(),command.caseRef());
            if(head==null||head.revision()!=snapshot.caseRevision()||!PrivacyCaseState.PREVIEWED.name().equals(head.state()))
                return closed(command,"PREVIEW_STALE","CASE_REVISION_DRIFT",snapshot.requestId(),snapshot.caseRevision());
            Instant validatedAt=clock.instant();
            if(!identity.expiresAt().isAfter(validatedAt))
                return closed(command,"PREVIEW_STALE","IDENTITY_EXPIRED_DURING_REVALIDATION",snapshot.requestId(),snapshot.caseRevision());
            Instant policyEffective=Instant.parse(current.values().get("policyEffective"));
            Instant policyExpires=Instant.parse(current.values().get("policyExpiresAt"));
            if(policyEffective.isAfter(validatedAt)||!policyExpires.isAfter(validatedAt))
                return closed(command,"PREVIEW_STALE","POLICY_EXPIRED_DURING_REVALIDATION",snapshot.requestId(),snapshot.caseRevision());
            return new PreviewResult(command.requestId(),snapshot.caseRevision(),"AS_OF_PREVIEW_ONLY",null,snapshot.requestId(),
                    items,snapshot.observedAt(),snapshot.observedTo(),validatedAt,"PREVIEW_ONLY");
        }catch(Exception failure){
            return closed(command,"PREVIEW_UNKNOWN","REVALIDATION_UNAVAILABLE",snapshot.requestId(),snapshot.caseRevision());
        }
    }
    private PreviewResult sourceClosed(Command command,SyntheticPreviewWitnessPort.SourceSnapshot source,long revision,String ref){
        Map<String,String> v=source.values();
        if("ACTIVE".equals(v.get("holdState")))return closed(command,"PREVIEW_BLOCKED","HOLD_ACTIVE",ref,revision);
        if(!"CLEAR".equals(v.get("holdState")))return closed(command,"PREVIEW_UNKNOWN","HOLD_STATE_UNKNOWN",ref,revision);
        if(!"KNOWN".equals(v.get("policyState"))||!"KNOWN".equals(v.get("journalState"))
                ||!"KNOWN".equals(v.get("auditState"))||!"ACTIVE".equals(v.get("witnessKeyState")))
            return closed(command,"PREVIEW_UNKNOWN","CURRENT_STATE_UNKNOWN",ref,revision);
        if("REVOKED".equals(v.get("identityRevocation"))||"REVOKED".equals(v.get("scopeState"))
                ||"REVOKED".equals(v.get("policyRevocation")))
            return closed(command,"PREVIEW_STALE","CURRENT_BINDING_REVOKED",ref,revision);
        if(!"ACTIVE".equals(v.get("identityRevocation"))||!"ACTIVE".equals(v.get("scopeState"))
                ||!"ACTIVE".equals(v.get("policyRevocation")))
            return closed(command,"PREVIEW_UNKNOWN","REVOCATION_STATE_UNKNOWN",ref,revision);
        return null;
    }
    private boolean matchesReadback(SyntheticPreviewStore.PreviewSnapshot saved,SyntheticPreviewStore.PreviewDraft draft,
                                    boolean exactHashRequired){
        return sameIdempotentReadback(saved,draft)&&saved.identityHash().equals(draft.identityHash())
                &&saved.witnessHash().equals(draft.witnessHash())
                &&(!exactHashRequired||saved.snapshotHash().equals(draft.snapshotHash()))&&snapshotIntegrityValid(saved);
    }
    private boolean sameIdempotentReadback(SyntheticPreviewStore.PreviewSnapshot saved,SyntheticPreviewStore.PreviewDraft draft){
        return saved!=null&&saved.tenantId().equals(draft.tenantId())&&saved.requestId().equals(draft.requestId())
                &&saved.idempotencyKey().equals(draft.idempotencyKey())&&saved.requestHash().equals(draft.requestHash())
                &&saved.caseRef().equals(draft.caseRef())&&saved.baseCaseRevision()==draft.baseCaseRevision()
                &&saved.caseRevision()==draft.caseRevision();
    }
    private boolean snapshotIntegrityValid(SyntheticPreviewStore.PreviewSnapshot snapshot){
        String expected=snapshotDigest(snapshot.tenantId(),snapshot.requestId(),snapshot.idempotencyKey(),snapshot.requestHash(),
                snapshot.caseRef(),snapshot.baseCaseRevision(),snapshot.caseRevision(),snapshot.identityHash(),snapshot.witnessHash(),
                snapshot.witnessJson(),snapshot.itemsJson(),snapshot.observedAt(),snapshot.observedTo());
        return expected.equals(snapshot.snapshotHash());
    }
    private String snapshotDigest(Command command,long base,long revision,String identityHash,String witnessHash,
                                  String witnessJson,String itemsJson,Instant from,Instant to){
        return snapshotDigest(command.tenantId(),command.requestId(),command.idempotencyKey(),command.fingerprint(),command.caseRef(),
                base,revision,identityHash,witnessHash,witnessJson,itemsJson,from,to);
    }
    private String snapshotDigest(String tenant,String request,String key,String requestHash,String caseRef,long base,long revision,
                                  String identityHash,String witnessHash,String witnessJson,String itemsJson,Instant from,Instant to){
        byte[] canonical=SyntheticCanonicalizer.encode("SES-SYNTHETIC-PREVIEW-SNAPSHOT","v1",tenant,request,key,requestHash,
                caseRef,Long.toString(base),Long.toString(revision),identityHash,witnessHash,witnessJson,itemsJson,from.toString(),to.toString());
        return ApprovalEnvelopeCanonicalizer.digest(canonical);
    }
    private long parseRevision(String value){try{return Long.parseLong(value);}catch(NumberFormatException e){throw new IllegalStateException("case revision witnessが不正です",e);}}
    private PreviewResult closed(Command c,String status,String reason,String ref,long revision){
        return PreviewResult.closed(c.requestId(),revision,status,reason,ref);
    }
    record Command(String tenantId,String requesterId,String subjectRef,String requestId,String identityRevision,
                   String caseRef,String scopeId,String idempotencyKey) {
        Command {
            for(String value:List.of(tenantId,requesterId,subjectRef,requestId,identityRevision,caseRef,scopeId,idempotencyKey))
                if(value.isBlank()||value.length()>128)throw new IllegalArgumentException("request bindingが不正です");
        }
        String fingerprint(){
            return ApprovalEnvelopeCanonicalizer.digest(SyntheticCanonicalizer.encode("SES-SYNTHETIC-PRIVACY-REQUEST","v1",
                    tenantId,requesterId,subjectRef,requestId,identityRevision,caseRef,scopeId,idempotencyKey));
        }
    }
    private static final class CaseDriftException extends RuntimeException { }
    private static final class IdentityExpiryException extends RuntimeException { }
    private static final class PolicyExpiryException extends RuntimeException { }
}
