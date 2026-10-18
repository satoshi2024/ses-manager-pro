package com.ses.privacy.foundation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 合成preview専用。bean/controller登録禁止、test profile以外では構築できない。 */
final class SyntheticPrivacyRequestService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final DataSource database;
    private final SyntheticIdentityEvidenceVerifier identityVerifier;
    private final SyntheticPreviewWitnessPort witnesses;
    private final SyntheticPreviewProviderRegistry providers;
    private final SyntheticEnvironmentGuard guard;
    private final Clock clock;
    private final Runnable beforeCommit;

    SyntheticPrivacyRequestService(DataSource database, Environment environment,
            SyntheticIdentityEvidenceVerifier identityVerifier, SyntheticPreviewWitnessPort witnesses,
            SyntheticPreviewProviderRegistry providers, SyntheticEnvironmentGuard guard, Clock clock) {
        this(database, environment, identityVerifier, witnesses, providers, guard, clock, () -> { });
    }

    SyntheticPrivacyRequestService(DataSource database, Environment environment,
            SyntheticIdentityEvidenceVerifier identityVerifier, SyntheticPreviewWitnessPort witnesses,
            SyntheticPreviewProviderRegistry providers, SyntheticEnvironmentGuard guard, Clock clock, Runnable beforeCommit) {
        if (environment == null || !environment.acceptsProfiles(Profiles.of("test"))
                || environment.acceptsProfiles(Profiles.of("prod"))) throw new SecurityException("test profile専用serviceです");
        this.database=java.util.Objects.requireNonNull(database); this.identityVerifier=identityVerifier;
        this.witnesses=witnesses; this.providers=providers; this.guard=guard; this.clock=clock;
        this.beforeCommit=java.util.Objects.requireNonNull(beforeCommit);
        if (providers == null) throw new SecurityException("synthetic provider registryが必要です");
    }

    PreviewResult preview(Command command, SyntheticIdentityEnvelope evidence) {
        if (command == null || evidence == null) throw new IllegalArgumentException("requestとidentity proofが必要です");
        final VerifiedSyntheticIdentity identity;
        try {
            identity = identityVerifier.verify(evidence, command.tenantId(), command.requesterId(),
                    command.subjectRef(), command.requestId(), command.identityRevision());
        } catch (SecurityException rejected) {
            return PreviewResult.closed(command.requestId(),0,"PREVIEW_STALE","IDENTITY_PROOF_INVALID_OR_EXPIRED",null);
        }
        try {
            Snapshot prior = find(command);
            if (prior != null) {
                if (!prior.requestHash().equals(command.fingerprint())) return PreviewResult.closed(command.requestId(), prior.caseRevision(), "PREVIEW_STALE", "IDEMPOTENCY_CONFLICT", prior.ref());
                return validateView(command, identity, evidence, prior);
            }
            SyntheticPreviewWitnessPort.SourceSnapshot first = witnesses.readCurrent(identity.tenantId(), identity.requesterId(), identity.subjectRef(),
                    identity.requestId(), command.caseRef(), command.scopeId());
            assertSourceBinding(identity, command, first);
            PreviewResult firstClosed=sourceClosed(command,first,null);
            if(firstClosed!=null)return firstClosed;
            List<SyntheticPreviewWitnessPort.SourceSnapshot> observations=new ArrayList<>();observations.add(first);
            VerifiedSyntheticPreviewScope scope = scope(identity, command, first);
            List<PreviewResult.PreviewItem> items = new ArrayList<>();
            for (String targetId : scope.targetIds()) {
                if (!identity.expiresAt().isAfter(clock.instant())) return closed(command, "PREVIEW_STALE", "IDENTITY_EXPIRED", null);
                SyntheticPreviewWitnessPort.SourceSnapshot before = witnesses.readCurrent(identity.tenantId(), identity.requesterId(), identity.subjectRef(),
                        identity.requestId(), command.caseRef(), command.scopeId());
                assertSourceBinding(identity, command, before);
                observations.add(before);
                PreviewResult beforeClosed=sourceClosed(command,before,null);
                if(beforeClosed!=null)return beforeClosed;
                if (!first.bindingHash().equals(before.bindingHash())) return closed(command, "PREVIEW_STALE", "SOURCE_DRIFT_BEFORE_READ", null);
                SyntheticPreviewPort provider=providers.require(scope.providerId(),scope.providerAudience());
                SyntheticPreviewPort.SyntheticTarget target = provider.read(scope.tenantId(), scope.subjectRef(), targetId);
                if (target == null || !target.id().equals(targetId) || target.version() == null || target.contentHash() == null
                        || target.observedAt() == null || !scope.targetIds().contains(target.id())) {
                    return closed(command, "PREVIEW_STALE", "TARGET_SCOPE_MISMATCH", null);
                }
                SyntheticPreviewWitnessPort.SourceSnapshot after = witnesses.readCurrent(identity.tenantId(), identity.requesterId(), identity.subjectRef(),
                        identity.requestId(), command.caseRef(), command.scopeId());
                assertSourceBinding(identity, command, after);
                observations.add(after);
                PreviewResult afterClosed=sourceClosed(command,after,null);
                if(afterClosed!=null)return afterClosed;
                if (!first.bindingHash().equals(after.bindingHash())) return closed(command, "PREVIEW_STALE", "SOURCE_DRIFT_DURING_READ", null);
                items.add(new PreviewResult.PreviewItem(target.id(), target.version(), target.contentHash(), target.observedAt(), "CANDIDATE"));
            }
            SyntheticPreviewWitnessPort.SourceSnapshot finalRead = witnesses.readCurrent(identity.tenantId(), identity.requesterId(), identity.subjectRef(),
                    identity.requestId(), command.caseRef(), command.scopeId());
            assertSourceBinding(identity, command, finalRead);
            observations.add(finalRead);
            PreviewResult finalClosed=sourceClosed(command,finalRead,null);
            if(finalClosed!=null)return finalClosed;
            if (!first.bindingHash().equals(finalRead.bindingHash())) return closed(command, "PREVIEW_STALE", "SOURCE_DRIFT_FINAL_READ", null);
            beforeCommit.run();
            Snapshot saved = persist(command, identity, first, observations, items);
            if (saved == null) {
                Snapshot raced = find(command);
                return raced == null ? closed(command, "PREVIEW_UNKNOWN", "CAS_OR_ACK_UNKNOWN", null)
                        : validateView(command, identity, evidence, raced);
            }
            return validateView(command, identity, evidence, saved);
        } catch (RuntimeException e) {
            return closed(command, "PREVIEW_UNKNOWN", "SOURCE_OR_STORE_UNAVAILABLE", null);
        }
    }

    private VerifiedSyntheticPreviewScope scope(VerifiedSyntheticIdentity identity, Command command, SyntheticPreviewWitnessPort.SourceSnapshot source) {
        Map<String,String> v = source.values();
        if (!identity.tenantId().equals(v.get("tenantId")) || !identity.subjectRef().equals(v.get("subjectRef")))
            throw new SecurityException("scope binding不一致です");
        SyntheticPreviewPort provider=providers.require(v.get("providerId"),v.get("providerAudience"));
        if (!identity.revision().equals(v.get("identityRevision")) || !identity.digest().equals(v.get("identityHash"))
                || !identity.keyId().equals(v.get("keyId")) || !identity.audience().equals(v.get("audience"))
                || !identity.expiresAt().toString().equals(v.get("identityExpiresAt"))
                || !"ACTIVE".equals(v.get("identityRevocation")) || !"ACTIVE".equals(v.get("scopeState"))) {
            throw new SecurityException("identity/scope current witnessが一致しません");
        }
        if (!"ACTIVE".equals(v.get("policyRevocation"))) throw new SecurityException("synthetic policyが失効しています");
        if (!"CLEAR".equals(v.get("holdState")) || !"KNOWN".equals(v.get("policyState"))
                || !"KNOWN".equals(v.get("journalState")) || !"KNOWN".equals(v.get("auditState"))) {
            throw new SecurityException("hold/policy/journal状態がpreview可能ではありません");
        }
        Instant now=clock.instant();
        if (Instant.parse(v.get("policyEffective")).isAfter(now) || !Instant.parse(v.get("policyExpiresAt")).isAfter(now))
            throw new SecurityException("synthetic policy有効期間外です");
        List<String> ids = provider.registeredTargets(identity.tenantId(), identity.subjectRef(), command.scopeId());
        if (ids == null || ids.isEmpty() || ids.stream().anyMatch(id -> !id.matches("target-synthetic-[a-zA-Z0-9-]+"))) {
            throw new SecurityException("synthetic scope内のtargetがありません");
        }
        return new VerifiedSyntheticPreviewScope(identity.tenantId(), identity.subjectRef(), command.scopeId(),
                provider.providerId(), provider.audience(), ids, VerifiedSyntheticPreviewScope.TOKEN);
    }

    private static void assertSourceBinding(VerifiedSyntheticIdentity identity, Command command, SyntheticPreviewWitnessPort.SourceSnapshot source) {
        Map<String,String> v=source.values();
        if (!identity.tenantId().equals(v.get("tenantId")) || !identity.requesterId().equals(v.get("requesterId"))
                || !identity.subjectRef().equals(v.get("subjectRef")) || !identity.requestId().equals(v.get("requestId"))
                || !command.caseRef().equals(v.get("caseRef")) || !command.scopeId().equals(v.get("scopeId")))
            throw new SecurityException("source witness request/case/scope binding不一致です");
    }

    private PreviewResult validateView(Command command, VerifiedSyntheticIdentity identity,
                                       SyntheticIdentityEnvelope evidence, Snapshot snapshot) {
        try {
            VerifiedSyntheticIdentity currentIdentity = identityVerifier.verify(evidence, command.tenantId(), command.requesterId(),
                    command.subjectRef(), command.requestId(), command.identityRevision());
            if (!currentIdentity.digest().equals(identity.digest()))
                return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_STALE", "IDENTITY_PROOF_DRIFT", snapshot.ref());
            CaseHead caseHead = readCaseHead(command);
            if (caseHead.revision() != snapshot.caseRevision() || !PrivacyCaseState.PREVIEWED.name().equals(caseHead.state()))
                return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_STALE", "CASE_REVISION_DRIFT", snapshot.ref());
            SyntheticPreviewWitnessPort.SourceSnapshot current = witnesses.readCurrent(identity.tenantId(), identity.requesterId(), identity.subjectRef(),
                    identity.requestId(), command.caseRef(), command.scopeId());
            assertSourceBinding(identity,command,current);
            PreviewResult currentClosed=sourceClosed(command,current,snapshot);
            if(currentClosed!=null)return currentClosed;
            if (!identity.expiresAt().isAfter(clock.instant()) || !"ACTIVE".equals(current.values().get("identityRevocation")))
                return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_STALE", "IDENTITY_EXPIRED_OR_REVOKED", snapshot.ref());
            if (!identity.revision().equals(current.values().get("identityRevision"))
                    || !identity.digest().equals(current.values().get("identityHash"))
                    || !identity.keyId().equals(current.values().get("keyId"))
                    || !identity.audience().equals(current.values().get("audience"))
                    || !identity.expiresAt().toString().equals(current.values().get("identityExpiresAt")))
                return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_STALE", "IDENTITY_WITNESS_DRIFT", snapshot.ref());
            if (!"ACTIVE".equals(current.values().get("scopeState")))
                return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_STALE", "SCOPE_REVOKED", snapshot.ref());
            if (!"ACTIVE".equals(current.values().get("policyRevocation")))
                return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_STALE", "POLICY_REVOKED", snapshot.ref());
            Instant policyStart=Instant.parse(current.values().get("policyEffective"));
            Instant policyEnd=Instant.parse(current.values().get("policyExpiresAt"));
            if (policyStart.isAfter(clock.instant()) || !policyEnd.isAfter(clock.instant()))
                return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_STALE", "POLICY_OUTSIDE_EFFECTIVE_RANGE", snapshot.ref());
            if (!snapshot.witnessHash().equals(current.bindingHash())) return PreviewResult.closed(command.requestId(), snapshot.caseRevision(),
                    "PREVIEW_STALE", "SOURCE_WITNESS_DRIFT", snapshot.ref());
            List<PreviewResult.PreviewItem> savedItems = JSON.readValue(snapshot.itemsJson(), new TypeReference<>() { });
            // Exact retry does not repeat target search/read; current target version/hash metadata is checked independently.
            for (PreviewResult.PreviewItem item : savedItems) {
                SyntheticPreviewPort provider=providers.require(current.values().get("providerId"),current.values().get("providerAudience"));
                SyntheticPreviewPort.SyntheticTarget now = provider.describeCurrent(identity.tenantId(), identity.subjectRef(),
                        command.scopeId(), item.syntheticTargetId());
                if (now == null || !item.syntheticTargetId().equals(now.id()) || now.version() == null || now.contentHash() == null
                        || now.observedAt() == null
                        || !item.targetVersion().equals(now.version()) || !item.contentHash().equals(now.contentHash()))
                    return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_STALE", "TARGET_METADATA_DRIFT", snapshot.ref());
            }
            Instant validated = clock.instant();
            return new PreviewResult(command.requestId(), snapshot.caseRevision(), "AS_OF_PREVIEW_ONLY", null, snapshot.ref(),
                    savedItems, snapshot.observedAt(), snapshot.observedTo(), validated, "PREVIEW_ONLY");
        } catch (Exception e) {
            return PreviewResult.closed(command.requestId(), snapshot.caseRevision(), "PREVIEW_UNKNOWN", "REVALIDATION_UNAVAILABLE", snapshot.ref());
        }
    }

    private PreviewResult sourceClosed(Command command,SyntheticPreviewWitnessPort.SourceSnapshot source,Snapshot snapshot){
        Map<String,String> values=source.values();
        long revision=snapshot==null?0:snapshot.caseRevision();String ref=snapshot==null?null:snapshot.ref();
        if("ACTIVE".equals(values.get("holdState")))return PreviewResult.closed(command.requestId(),revision,"PREVIEW_BLOCKED","HOLD_ACTIVE",ref);
        if(!"KNOWN".equals(values.get("policyState"))||!"KNOWN".equals(values.get("journalState"))
                ||!"KNOWN".equals(values.get("auditState"))||!"ACTIVE".equals(values.get("witnessKeyState")))
            return PreviewResult.closed(command.requestId(),revision,"PREVIEW_UNKNOWN","CURRENT_STATE_UNKNOWN",ref);
        if (!"ACTIVE".equals(values.get("identityRevocation")) || !"ACTIVE".equals(values.get("scopeState"))
                || !"ACTIVE".equals(values.get("policyRevocation")))
            return PreviewResult.closed(command.requestId(),revision,"PREVIEW_STALE","CURRENT_BINDING_REVOKED",ref);
        return null;
    }

    private Snapshot find(Command command) {
        try (Connection c=database.getConnection(); PreparedStatement p=c.prepareStatement("SELECT request_hash,case_revision,witness_hash,witness_json,items_json,observed_at,observed_to FROM sf_w3a_preview WHERE tenant_id=? AND request_id=? AND idempotency_key=?")) {
            p.setString(1,command.tenantId()); p.setString(2,command.requestId()); p.setString(3,command.idempotencyKey());
            try(ResultSet r=p.executeQuery()) { return r.next()?new Snapshot(command.requestId(),r.getString(1),r.getLong(2),r.getString(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant(),r.getTimestamp(7).toInstant()):null; }
        } catch(SQLException e) { throw new IllegalStateException(e); }
    }
    private CaseHead readCaseHead(Command command) {
        try (Connection c=database.getConnection(); PreparedStatement p=c.prepareStatement(
                "SELECT revision,state FROM sf_w3a_case WHERE tenant_id=? AND case_ref=?")) {
            p.setString(1, command.tenantId()); p.setString(2, command.caseRef());
            try (ResultSet r=p.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("caseがありません");
                return new CaseHead(r.getLong(1), r.getString(2));
            }
        } catch (SQLException e) { throw new IllegalStateException("case headを再検証できません", e); }
    }
    private Snapshot persist(Command cmd, VerifiedSyntheticIdentity identity, SyntheticPreviewWitnessPort.SourceSnapshot witness,
                             List<SyntheticPreviewWitnessPort.SourceSnapshot> observations, List<PreviewResult.PreviewItem> items) {
        if(items.isEmpty())return null;
        TransitionBinding binding=new TransitionBinding(cmd.tenantId(),cmd.caseRef(),items.get(0).syntheticTargetId(),
                items.get(0).targetVersion(),items.get(0).contentHash(),witness.values().get("policyHash"),
                witness.values().get("caseRevision"),witness.values().get("policyVersion"),"SYNTHETIC_PREVIEW",
                witness.values().get("providerId"));
        PrivacyCaseStateMachine.transition(PrivacyCaseState.SCOPED,PrivacyCaseState.PREVIEWED,binding,null,null,null,clock.instant());
        try(Connection c=database.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(PreparedStatement p=c.prepareStatement("INSERT INTO sf_w3a_case(tenant_id,case_ref,revision) VALUES(?,?,0) ON DUPLICATE KEY UPDATE case_ref=VALUES(case_ref)")) {p.setString(1,cmd.tenantId());p.setString(2,cmd.caseRef());p.executeUpdate();}
                long current;String state;
                try(PreparedStatement p=c.prepareStatement("SELECT revision,state FROM sf_w3a_case WHERE tenant_id=? AND case_ref=? FOR UPDATE")){p.setString(1,cmd.tenantId());p.setString(2,cmd.caseRef());try(ResultSet r=p.executeQuery()){if(!r.next())throw new SQLException("case missing");current=r.getLong(1);state=r.getString(2);}}
                if(!PrivacyCaseState.SCOPED.name().equals(state))throw new SQLException("case stateがSCOPEDではありません");
                long next=current+1;
                try(PreparedStatement p=c.prepareStatement("UPDATE sf_w3a_case SET revision=?,state='PREVIEWED' WHERE tenant_id=? AND case_ref=? AND revision=? AND state='SCOPED'")){p.setLong(1,next);p.setString(2,cmd.tenantId());p.setString(3,cmd.caseRef());p.setLong(4,current);if(p.executeUpdate()!=1)throw new SQLException("case CAS failed");}
                String itemsJson=JSON.writeValueAsString(items);
                String witnessJson=JSON.writeValueAsString(observations.stream().map(SyntheticPreviewWitnessPort.SourceSnapshot::canonical).toList());
                Instant observedTo=observations.stream().map(SyntheticPreviewWitnessPort.SourceSnapshot::observedAt).max(Instant::compareTo).orElse(witness.observedAt());
                try(PreparedStatement p=c.prepareStatement("INSERT INTO sf_w3a_preview(tenant_id,request_id,idempotency_key,request_hash,case_ref,case_revision,identity_hash,witness_hash,witness_json,items_json,observed_at,observed_to) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    p.setString(1,cmd.tenantId());p.setString(2,cmd.requestId());p.setString(3,cmd.idempotencyKey());p.setString(4,cmd.fingerprint());p.setString(5,cmd.caseRef());p.setLong(6,next);p.setString(7,identity.digest());p.setString(8,witness.bindingHash());p.setString(9,witnessJson);p.setString(10,itemsJson);p.setTimestamp(11,java.sql.Timestamp.from(witness.observedAt()));p.setTimestamp(12,java.sql.Timestamp.from(observedTo));p.executeUpdate();
                }
                c.commit();
                return new Snapshot(cmd.requestId(),cmd.fingerprint(),next,witness.bindingHash(),witnessJson,itemsJson,witness.observedAt(),observedTo);
            } catch(Exception e) { try{c.rollback();}catch(SQLException x){e.addSuppressed(x);} if(e instanceof RuntimeException re)throw re; throw new IllegalStateException(e); }
        } catch(SQLException e) { return null; }
    }
    private PreviewResult closed(Command c,String status,String reason,String ref){ return PreviewResult.closed(c.requestId(),0,status,reason,ref); }
    record Command(String tenantId,String requesterId,String subjectRef,String requestId,String identityRevision,
                   String caseRef,String scopeId,String idempotencyKey) {
        String fingerprint() {
            String canonical=String.join("\n",tenantId,requesterId,subjectRef,requestId,identityRevision,caseRef,scopeId,idempotencyKey);
            return ApprovalEnvelopeCanonicalizer.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    private record Snapshot(String ref,String requestHash,long caseRevision,String witnessHash,String witnessJson,String itemsJson,Instant observedAt,Instant observedTo) { }
    private record CaseHead(long revision, String state) { }
}
