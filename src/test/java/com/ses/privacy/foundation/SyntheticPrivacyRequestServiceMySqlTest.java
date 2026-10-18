package com.ses.privacy.foundation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("mysql")
@Testcontainers
class SyntheticPrivacyRequestServiceMySqlTest {
    private static final String PASS=java.util.UUID.randomUUID().toString();
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.0")
            .withDatabaseName(JournalTestConnectionCapability.randomDatabase()).withUsername("root").withPassword(PASS);
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static Environment environment;
    private static JournalTestConnectionCapability.W3aSyntheticPreviewStore store;
    private static String containerId;
    private static final ThreadLocal<Fixture> ACTIVE_FIXTURE=new ThreadLocal<>();

    @BeforeAll static void start() {
        context=new AnnotationConfigApplicationContext(); context.getEnvironment().setActiveProfiles("test"); context.refresh();
        JournalTestConnectionCapability capability=JournalTestConnectionCapability.fromOwnedContainer(MYSQL,context);
        containerId=capability.containerId();
        var ds=capability.adminDataSource();
        store=capability.createW3aPreviewStore(); store.initializeSchema();
        assertThrows(SecurityException.class,capability::createW3aPreviewStore);
        assertThrows(SecurityException.class,store::initializeSchema);
        jdbc=new JdbcTemplate(ds); environment=capability.environment();
        System.out.printf("SF_W3A_MYSQL containerId=%s database=%s schema=privacy-journal/w3a-test-schema.sql%n",capability.containerId(),capability.database());
    }
    @AfterAll static void stop(){if(context!=null)context.close();}
    @AfterEach void evidence(){
        Fixture f=ACTIVE_FIXTURE.get();
        if(f!=null)System.out.printf("SF_W3A_EVIDENCE containerId=%s fixtureManifestHash=%s requestRef=%s providerRead=%d providerWrite=0 dispatch=0 disposition=0 crossScopeAccess=%d%n",
                containerId,f.manifestHash,ApprovalEnvelopeCanonicalizer.digest(f.command.requestId().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                f.provider.reads.get(),f.provider.crossScopeAttempts.get());
        ACTIVE_FIXTURE.remove();
    }

    @Test void exactRetryRevalidatesTargetMetadataWithoutRepeatingTargetRead() {
        Fixture f=new Fixture();
        PreviewResult first=f.service.preview(f.command,f.identity);
        assertEquals("AS_OF_PREVIEW_ONLY",first.status()); assertEquals(1,f.provider.reads.get()); assertEquals(2,f.provider.describes.get());
        PreviewResult retry=f.service.preview(f.command,f.identity);
        assertEquals("AS_OF_PREVIEW_ONLY",retry.status()); assertEquals(1,f.provider.reads.get()); assertEquals(3,f.provider.describes.get());
        assertEquals(first.snapshotRef(),retry.snapshotRef());
        f.witness.values.put("scopeHash","scope-changed");
        PreviewResult drift=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",drift.status()); assertTrue(drift.items().isEmpty());
        assertEquals(1,f.provider.reads.get()); assertEquals(3,f.provider.describes.get());
        assertEquals(0,f.provider.crossScopeAttempts.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    @Test void sameKeyWithDifferentRequestAndLocalCaseRevisionDriftFailClosed() {
        Fixture f=new Fixture();
        assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        int reads=f.provider.reads.get();
        SyntheticPrivacyRequestService.Command changedScope=new SyntheticPrivacyRequestService.Command(f.command.tenantId(),
                f.command.requesterId(),f.command.subjectRef(),f.command.requestId(),f.command.identityRevision(),f.command.caseRef(),
                "scope-other",f.command.idempotencyKey());
        PreviewResult conflict=f.service.preview(changedScope,f.identity);
        assertEquals("PREVIEW_STALE",conflict.status());
        assertEquals("IDEMPOTENCY_CONFLICT",conflict.reasonCode());
        assertTrue(conflict.items().isEmpty());
        assertEquals(reads,f.provider.reads.get());
        jdbc.update("UPDATE sf_w3a_case SET revision=revision+1 WHERE tenant_id=? AND case_ref=?",f.command.tenantId(),f.command.caseRef());
        PreviewResult drift=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",drift.status());
        assertEquals("CASE_REVISION_DRIFT",drift.reasonCode());
        assertTrue(drift.items().isEmpty());
        assertEquals(reads,f.provider.reads.get());
    }

    @Test void witnessRevisionMustMatchOwnedCaseHeadBeforeProviderRead() {
        Fixture f=new Fixture(); f.witness.values.put("caseRevision","5");
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status());
        assertEquals("CASE_REVISION_WITNESS_MISMATCH",result.reasonCode());
        assertEquals(0,f.provider.reads.get());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    @Test void scopeHashMustBindAllowlistedSyntheticTargetIds() {
        Fixture f=new Fixture(); f.witness.allowedTargetIds.add("target-synthetic-other-tenant");
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status());
        assertEquals(0,f.provider.reads.get());
        assertEquals(0,f.provider.crossScopeAttempts.get());
    }

    @Test void exactRetryLeavesCompleteImmutableAsOfWitnessAndCaseRevisionUntouched() {
        Fixture f=new Fixture();
        assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        String snapshotHash=jdbc.queryForObject("SELECT snapshot_hash FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",
                String.class,f.command.tenantId(),f.command.requestId());
        String witness=jdbc.queryForObject("SELECT witness_json FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",
                String.class,f.command.tenantId(),f.command.requestId());
        String items=jdbc.queryForObject("SELECT items_json FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",
                String.class,f.command.tenantId(),f.command.requestId());
        for(String source:SyntheticPreviewWitnessPort.SOURCE_NAMES)assertTrue(witness.contains("sourceObservedAt="+source+":"),source);
        assertTrue(items.contains("metadataObservedAt")); assertTrue(items.contains("observedAt"));
        assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        assertEquals(snapshotHash,jdbc.queryForObject("SELECT snapshot_hash FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",
                String.class,f.command.tenantId(),f.command.requestId()));
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM sf_w3a_case WHERE tenant_id=? AND case_ref=?",
                Long.class,f.command.tenantId(),f.command.caseRef()));
        assertEquals(1,f.provider.reads.get()); assertEquals(0,f.provider.crossScopeAttempts.get());
    }

    @Test void providerCannotSmuggleAnOutOfScopeTargetIntoImmutablePreview() {
        Fixture f=new Fixture(); f.provider.returnedTargetId="target-synthetic-other-tenant";
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status()); assertTrue(result.items().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
        assertEquals(0,f.provider.crossScopeAttempts.get());
        assertEquals(PrivacyCaseState.SCOPED.name(),jdbc.queryForObject("SELECT state FROM sf_w3a_case WHERE tenant_id=? AND case_ref=?",
                String.class,f.command.tenantId(),f.command.caseRef()));
    }

    @Test void driftAfterFinalReadCanPersistOnlyAsOfHistoryAndReturnsClosedView() {
        Fixture f=new Fixture();
        SyntheticPrivacyRequestService raced=f.serviceWithPreCommit(()->f.witness.values.put("policyHash","policy-drift"));
        PreviewResult result=raced.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status()); assertTrue(result.items().isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
        assertEquals(1,f.provider.reads.get()); assertEquals(1,f.provider.describes.get());
    }

    @Test void scopeDriftBeforeFinalReadPreventsPersistence() {
        Fixture f=new Fixture();
        SyntheticPrivacyRequestService raced=f.serviceWithBarriers(()->f.witness.values.put("scopeHash","scope-drift"),()->{});
        PreviewResult result=raced.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status());
        assertEquals("SOURCE_DRIFT_BEFORE_FINAL_METADATA",result.reasonCode());
        assertTrue(result.items().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
        assertEquals(0,f.provider.crossScopeAttempts.get());
    }

    @Test void commitAckUnknownUsesIndependentReadBackAndCurrentRevalidation() {
        Fixture f=new Fixture(); store.loseNextCommitAckAfterCommit();
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("AS_OF_PREVIEW_ONLY",result.status());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
        assertEquals(1,f.provider.reads.get()); assertEquals(2,f.provider.describes.get());
    }

    @Test void driftAfterCommitAcknowledgementReturnsClosedViewAndPreservesHistory() {
        Fixture f=new Fixture(); JournalTestConnectionCapability.W3aSyntheticPreviewStore storeRef=store;
        storeRef.loseNextCommitAckAfterCommit();
        storeRef.afterNextCommit(()->f.witness.values.put("policyHash","policy-after-ack"));
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status());assertEquals("SOURCE_WITNESS_DRIFT",result.reasonCode());assertTrue(result.items().isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    @Test void differentRequestCannotCommitAgainstAlreadyAdvancedCaseRevision() {
        Fixture f=new Fixture();
        assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        SyntheticPrivacyRequestService.Command competing=new SyntheticPrivacyRequestService.Command(f.command.tenantId(),
                f.command.requesterId(),f.command.subjectRef(),"request-compete-"+java.util.UUID.randomUUID(),f.command.identityRevision(),
                f.command.caseRef(),f.command.scopeId(),"idem-compete-"+java.util.UUID.randomUUID());
        SyntheticIdentityEnvelope identity=f.signer.sign(f.command.tenantId(),f.command.requesterId(),f.command.subjectRef(),
                competing.requestId(),competing.identityRevision(),NOW.plusSeconds(3600),"SYNTHETIC");
        PreviewResult result=f.service.preview(competing,identity);
        assertEquals("PREVIEW_STALE",result.status()); assertTrue(result.items().isEmpty());
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM sf_w3a_case WHERE tenant_id=? AND case_ref=?",Long.class,
                competing.tenantId(),competing.caseRef()));
    }

    @Test void differentRequestsCompeteOnTheSameObservedCaseRevision() throws Exception {
        Fixture f=new Fixture();
        SyntheticPrivacyRequestService.Command otherCommand=new SyntheticPrivacyRequestService.Command(f.command.tenantId(),
                f.command.requesterId(),f.command.subjectRef(),"request-race-"+java.util.UUID.randomUUID(),f.command.identityRevision(),
                f.command.caseRef(),f.command.scopeId(),"idem-race-"+java.util.UUID.randomUUID());
        SyntheticIdentityEnvelope otherIdentity=f.signer.sign(f.command.tenantId(),f.command.requesterId(),f.command.subjectRef(),
                otherCommand.requestId(),otherCommand.identityRevision(),NOW.plusSeconds(3600),"SYNTHETIC");
        SyntheticIdentityEvidenceVerifier.VerifiedIdentity verified=f.verifier.verify(otherIdentity,otherCommand.tenantId(),
                otherCommand.requesterId(),otherCommand.subjectRef(),otherCommand.requestId(),otherCommand.identityRevision());
        TestWitness otherWitness=new TestWitness();
        otherWitness.values.putAll(f.witness.values);otherWitness.values.put("identityHash",otherIdentity.digest());
        otherWitness.values.put("identityExpiresAt",otherIdentity.expiresAt().toString());
        String otherScopeHash=SyntheticPreviewScopeVerifier.computeScopeHash(verified,otherCommand,"scope-r1",otherWitness.allowedTargetIds);
        otherWitness.values.put("scopeHash",otherScopeHash);
        TestProvider otherProvider=new TestProvider();
        otherProvider.bind(otherCommand.requestId(),otherIdentity.digest(),otherScopeHash,otherCommand.caseRef());
        otherProvider.expectedClock=f.clock;
        SyntheticPreviewProviderRegistry otherRegistry=new SyntheticPreviewProviderRegistry(Map.of(otherProvider.providerId(),otherProvider),f.guard);
        SyntheticPrivacyRequestService otherService=new SyntheticPrivacyRequestService(store,f.verifier,new SyntheticPreviewScopeVerifier(),
                otherWitness,otherRegistry,f.clock,()->{},()->{});
        CyclicBarrier barrier=new CyclicBarrier(2);
        Runnable meet=()->{try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}};
        SyntheticPrivacyRequestService left=f.serviceWithBarriers(()->{},meet);
        SyntheticPrivacyRequestService right=new SyntheticPrivacyRequestService(store,f.verifier,new SyntheticPreviewScopeVerifier(),
                otherWitness,otherRegistry,f.clock,()->{},meet);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<PreviewResult> a=workers.submit(()->left.preview(f.command,f.identity));
            Future<PreviewResult> b=workers.submit(()->right.preview(otherCommand,otherIdentity));
            List<String> statuses=List.of(a.get(10,TimeUnit.SECONDS).status(),b.get(10,TimeUnit.SECONDS).status());
            assertEquals(1,statuses.stream().filter("AS_OF_PREVIEW_ONLY"::equals).count());
            assertEquals(1,statuses.stream().filter("PREVIEW_STALE"::equals).count());
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS),"worker停止");}
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM sf_w3a_case WHERE tenant_id=? AND case_ref=?",Long.class,
                f.command.tenantId(),f.command.caseRef()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND case_ref=?",Integer.class,
                f.command.tenantId(),f.command.caseRef()));
        assertEquals(0,f.provider.crossScopeAttempts.get());assertEquals(0,otherProvider.crossScopeAttempts.get());
    }

    @Test void observationVectorUsesMicrosecondPrecisionAndIncludesLateTargetMetadata() {
        Fixture f=new Fixture();
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("AS_OF_PREVIEW_ONLY",result.status());
        assertTrue(!result.validatedAt().isBefore(result.observedTo()),"validatedAtはtarget metadata観測後であること");
        assertEquals(0,result.observedFrom().getNano()%1_000);
        assertEquals(0,result.observedTo().getNano()%1_000);
        assertTrue(result.observedTo().isAfter(result.items().get(0).observedAt()));
        assertEquals(result.items().get(0).metadataObservedAt(),result.observedTo());
        assertEquals(result.observedTo(),store.find(f.command.tenantId(),f.command.requestId(),f.command.idempotencyKey()).observedTo());
    }

    @Test void describeCurrentExpiryDuringCallIsClosedAndDoesNotPersist() {
        Fixture f=new Fixture();
        f.provider.onDescribe=()->f.clock.set(NOW.plusSeconds(3601));
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status()); assertTrue(result.items().isEmpty());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    @Test void exactRetryClosesWhenIdentityExpiresInsideDescribeCurrent() {
        Fixture f=new Fixture();
        assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        int reads=f.provider.reads.get();
        f.provider.onDescribe=()->f.clock.set(NOW.plusSeconds(3601));
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status()); assertTrue(result.items().isEmpty());
        assertEquals(reads,f.provider.reads.get());
    }

    @Test void metadataDriftBeforeFinalMetadataPreventsPersistence() {
        Fixture f=new Fixture();
        f.provider.onDescribe=()->f.provider.version="target-r2";
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status()); assertEquals("TARGET_METADATA_DRIFT_BEFORE_COMMIT",result.reasonCode());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    @Test void unavailableAndMismatchedStoreReadbackAreClosed() {
        for(JournalTestConnectionCapability.W3aSyntheticPreviewStore.ReadbackFault fault:JournalTestConnectionCapability.W3aSyntheticPreviewStore.ReadbackFault.values()) {
            if(fault==JournalTestConnectionCapability.W3aSyntheticPreviewStore.ReadbackFault.NONE) continue;
            Fixture f=new Fixture(); store.faultNextReadback(fault);store.loseNextCommitAckAfterCommit();
            PreviewResult result=f.service.preview(f.command,f.identity);
            assertTrue(result.items().isEmpty());
            assertEquals("PREVIEW_UNKNOWN",result.status(),fault.name());
            assertEquals(fault==JournalTestConnectionCapability.W3aSyntheticPreviewStore.ReadbackFault.UNREADABLE
                    ?"SOURCE_OR_STORE_UNAVAILABLE":"COMMIT_READBACK_UNCONFIRMED",result.reasonCode(),fault.name());
        }
    }

    @Test void twoWorkersUseOneCaseCasAndLoserReadsBackSameIdempotentPreview() throws Exception {
        Fixture f=new Fixture(); CyclicBarrier barrier=new CyclicBarrier(2);
        Runnable meet=()->{try{barrier.await(5,TimeUnit.SECONDS);}catch(Exception e){throw new IllegalStateException(e);}};
        SyntheticPrivacyRequestService left=f.serviceWithBarriers(()->{},meet);
        SyntheticPrivacyRequestService right=f.serviceWithBarriers(()->{},meet);
        ExecutorService workers=Executors.newFixedThreadPool(2);
        try {
            Future<PreviewResult> a=workers.submit(()->left.preview(f.command,f.identity));
            Future<PreviewResult> b=workers.submit(()->right.preview(f.command,f.identity));
            assertEquals("AS_OF_PREVIEW_ONLY",a.get(10,TimeUnit.SECONDS).status()); assertEquals("AS_OF_PREVIEW_ONLY",b.get(10,TimeUnit.SECONDS).status());
        } finally {workers.shutdownNow();assertTrue(workers.awaitTermination(5,TimeUnit.SECONDS),"worker停止");}
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM sf_w3a_case WHERE tenant_id=? AND case_ref=?",Long.class,
                f.command.tenantId(),f.command.caseRef()));
        assertEquals(0,f.provider.crossScopeAttempts.get());
    }

    @Test void identityCanonicalFieldsCannotCollideAcrossNewlineBoundaries() {
        Fixture f=new Fixture();
        SyntheticIdentityEnvelope left=f.signer.sign("tenant-a\nb","requester-c","subject-w3a",f.command.requestId(),
                "identity-r1",NOW.plusSeconds(3600),"SYNTHETIC");
        SyntheticIdentityEnvelope right=f.signer.sign("tenant-a","b\nrequester-c","subject-w3a",f.command.requestId(),
                "identity-r1",NOW.plusSeconds(3600),"SYNTHETIC");
        assertNotEquals(left.digest(),right.digest());
        assertFalse(java.util.Arrays.equals(left.canonicalBytes(),right.canonicalBytes()));
        SyntheticIdentityEnvelope signatureSwap=new SyntheticIdentityEnvelope(right.tenantId(),right.requesterId(),right.opaqueSubjectRef(),
                right.requestId(),right.revision(),right.keyId(),right.audience(),right.issuedAt(),right.expiresAt(),left.signature());
        assertThrows(SecurityException.class,()->f.verifier.verify(signatureSwap,right.tenantId(),right.requesterId(),
                right.opaqueSubjectRef(),right.requestId(),right.revision()));
        assertNotEquals(commandFingerprint("tenant-a\nb","requester-c"),commandFingerprint("tenant-a","b\nrequester-c"));
    }
    private static String commandFingerprint(String tenant,String requester){
        return new SyntheticPrivacyRequestService.Command(tenant,requester,"subject","request","identity-r1","case","scope","idem").fingerprint();
    }

    @Test void productionGuardPreventsAnySyntheticAccessOrPersistence() {
        Fixture f=new Fixture();
        MockEnvironment prodEnvironment=new MockEnvironment(); prodEnvironment.setActiveProfiles("prod");
        SyntheticEnvironmentGuard prodGuard=new SyntheticEnvironmentGuard(prodEnvironment);
        SyntheticIdentityEvidenceVerifier prodVerifier=new SyntheticIdentityEvidenceVerifier(Map.of(
                SyntheticIdentityFixtureSigner.KEY_ID,f.signer.keyPair.getPublic()),Clock.fixed(NOW,ZoneOffset.UTC),prodGuard);
        SyntheticPreviewProviderRegistry prodRegistry=new SyntheticPreviewProviderRegistry(Map.of(f.provider.providerId(),f.provider),prodGuard);
        SyntheticPrivacyRequestService prodService=new SyntheticPrivacyRequestService(store,prodVerifier,new SyntheticPreviewScopeVerifier(),
                f.witness,prodRegistry,Clock.fixed(NOW,ZoneOffset.UTC),()->{},()->{});
        assertEquals("PREVIEW_STALE",prodService.preview(f.command,f.identity).status());
        assertEquals(0,f.witness.reads.get()); assertEquals(0,f.provider.reads.get());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    @Test void exactRetryClosesEachCurrentDriftClassWithoutOverwritingSnapshot() {
        Fixture f=new Fixture();
        assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        int beforeReads=f.provider.reads.get();
        int beforeDescriptions=f.provider.describes.get();
        assertClosedAfterMutation(f,"scopeHash","scope-hash-r2","PREVIEW_STALE");
        f.reset("scopeHash",f.scopeHash);
        assertClosedAfterMutation(f,"scopeRevision","scope-r2","PREVIEW_STALE"); f.reset("scopeRevision","scope-r1");
        assertClosedAfterMutation(f,"providerId","provider-other","PREVIEW_STALE"); f.reset("providerId","synthetic-provider-w3a");
        assertClosedAfterMutation(f,"providerAudience","OTHER","PREVIEW_STALE"); f.reset("providerAudience","SYNTHETIC");
        assertClosedAfterMutation(f,"identityRevocation","REVOKED","PREVIEW_STALE"); f.reset("identityRevocation","ACTIVE");
        assertClosedAfterMutation(f,"witnessKeyState","UNKNOWN","PREVIEW_UNKNOWN"); f.reset("witnessKeyState","ACTIVE");
        assertClosedAfterMutation(f,"witnessEpoch","epoch-r2","PREVIEW_STALE"); f.reset("witnessEpoch","epoch-r1");
        assertClosedAfterMutation(f,"witnessRevision","witness-r2","PREVIEW_STALE"); f.reset("witnessRevision","witness-r1");
        assertClosedAfterMutation(f,"checkpointHash","checkpoint-r2","PREVIEW_STALE"); f.reset("checkpointHash","checkpoint-hash-r1");
        assertClosedAfterMutation(f,"holdRevision","hold-r2","PREVIEW_STALE"); f.reset("holdRevision","hold-r1");
        assertClosedAfterMutation(f,"policyHash","policy-hash-r2","PREVIEW_STALE");
        f.reset("policyHash","policy-hash-r1");
        assertClosedAfterMutation(f,"holdState","ACTIVE","PREVIEW_BLOCKED");
        f.reset("holdState","CLEAR");
        assertClosedAfterMutation(f,"holdState","UNKNOWN","PREVIEW_UNKNOWN");
        f.reset("holdState","CLEAR");
        assertClosedAfterMutation(f,"policyRevocation","REVOKED","PREVIEW_STALE");
        f.reset("policyRevocation","ACTIVE");
        assertClosedAfterMutation(f,"scopeState","REVOKED","PREVIEW_STALE");
        f.reset("scopeState","ACTIVE");
        assertClosedAfterMutation(f,"journalRevision","journal-r2","PREVIEW_STALE");
        f.reset("journalRevision","journal-r1");
        f.provider.version="target-r2";
        assertEquals("PREVIEW_STALE",f.service.preview(f.command,f.identity).status());
        f.provider.version="target-r1";
        f.provider.contentHash="sha256-synthetic-r2";
        assertEquals("PREVIEW_STALE",f.service.preview(f.command,f.identity).status());
        f.provider.contentHash="sha256-synthetic-r1";
        assertClosedAfterMutation(f,"caseRevision","9","PREVIEW_STALE");
        f.reset("caseRevision","0");
        assertClosedAfterMutation(f,"journalState","UNKNOWN","PREVIEW_UNKNOWN");
        assertEquals(beforeReads,f.provider.reads.get());
        assertEquals(beforeDescriptions+2,f.provider.describes.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    @Test void successfulExactRetryRechecksIdentityAndPolicyExpiryAtFinalClockRead() {
        Fixture f=new Fixture();
        f.witness.values.put("policyExpiresAt",NOW.plusSeconds(1800).toString());
        assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        int reads=f.provider.reads.get();
        f.clock.set(NOW.plusSeconds(2700));
        PreviewResult policyExpired=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",policyExpired.status()); assertTrue(policyExpired.items().isEmpty());
        assertEquals(reads,f.provider.reads.get());
        f.clock.set(NOW.plusSeconds(3601));
        PreviewResult identityExpired=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",identityExpired.status()); assertTrue(identityExpired.items().isEmpty());
        assertEquals(reads,f.provider.reads.get());
    }

    private static void assertClosedAfterMutation(Fixture f,String key,String value,String expected){
        f.witness.values.put(key,value);
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals(expected,result.status(),key);assertTrue(result.items().isEmpty(),key);
    }

    @Test void currentWitnessTenantAndSubjectMismatchAreClassifiedAsClosedBindingRejection() {
        Fixture f=new Fixture();assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        int reads=f.provider.reads.get();
        for(String field:List.of("tenantId","subjectRef")){
            String original=f.witness.values.get(field);f.witness.values.put(field,"synthetic-other-binding");
            PreviewResult result=f.service.preview(f.command,f.identity);
            assertEquals("PREVIEW_STALE",result.status(),field);assertEquals("CURRENT_BINDING_REJECTED",result.reasonCode(),field);
            assertTrue(result.items().isEmpty(),field);f.witness.values.put(field,original);
        }
        assertEquals(reads,f.provider.reads.get());
        assertEquals(1L,jdbc.queryForObject("SELECT revision FROM sf_w3a_case WHERE tenant_id=? AND case_ref=?",Long.class,
                f.command.tenantId(),f.command.caseRef()));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    @Test void unverifiedIdentityIsRejectedBeforeAnyProviderOrDatabaseAccess() {
        Fixture f=new Fixture();
        SyntheticIdentityEnvelope invalid=new SyntheticIdentityEnvelope("tenant-synthetic-w3a","requester-w3a","subject-w3a",
                f.command.requestId(),"identity-r1",SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",
                NOW.minusSeconds(10),NOW.plusSeconds(3600),new byte[]{1,2,3});
        assertEquals("PREVIEW_STALE",f.service.preview(f.command,invalid).status());
        assertEquals(0,f.provider.reads.get()); assertEquals(0,f.witness.reads.get());
    }

    @Test void everyIdentityBindingFieldAndTimeBoundaryIsVerifiedBeforeWitnessOrProviderRead() {
        Fixture f=new Fixture();
        List<SyntheticIdentityEnvelope> variants=List.of(
                f.signer.signCustom("tenant-other","requester-w3a","subject-w3a",f.command.requestId(),"identity-r1",
                        SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",NOW.minusSeconds(60),NOW.plusSeconds(3600)),
                f.signer.signCustom("tenant-synthetic-w3a","requester-other","subject-w3a",f.command.requestId(),"identity-r1",
                        SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",NOW.minusSeconds(60),NOW.plusSeconds(3600)),
                f.signer.signCustom("tenant-synthetic-w3a","requester-w3a","subject-other",f.command.requestId(),"identity-r1",
                        SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",NOW.minusSeconds(60),NOW.plusSeconds(3600)),
                f.signer.signCustom("tenant-synthetic-w3a","requester-w3a","subject-w3a","request-other","identity-r1",
                        SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",NOW.minusSeconds(60),NOW.plusSeconds(3600)),
                f.signer.signCustom("tenant-synthetic-w3a","requester-w3a","subject-w3a",f.command.requestId(),"identity-r2",
                        SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",NOW.minusSeconds(60),NOW.plusSeconds(3600)),
                f.signer.signCustom("tenant-synthetic-w3a","requester-w3a","subject-w3a",f.command.requestId(),"identity-r1",
                        "unregistered-key","SYNTHETIC",NOW.minusSeconds(60),NOW.plusSeconds(3600)),
                f.signer.signCustom("tenant-synthetic-w3a","requester-w3a","subject-w3a",f.command.requestId(),"identity-r1",
                        SyntheticIdentityFixtureSigner.KEY_ID,"PRODUCTION",NOW.minusSeconds(60),NOW.plusSeconds(3600)),
                f.signer.signCustom("tenant-synthetic-w3a","requester-w3a","subject-w3a",f.command.requestId(),"identity-r1",
                        SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",NOW.plusSeconds(1),NOW.plusSeconds(3600)),
                f.signer.signCustom("tenant-synthetic-w3a","requester-w3a","subject-w3a",f.command.requestId(),"identity-r1",
                        SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",NOW.minusSeconds(3600),NOW.minusSeconds(1)));
        for(SyntheticIdentityEnvelope variant:variants){
            assertEquals("PREVIEW_STALE",f.service.preview(f.command,variant).status());
            assertEquals(0,f.witness.reads.get()); assertEquals(0,f.provider.reads.get());
        }
    }

    @Test void verifiedCapabilitiesHavePrivateConstructorsAndServiceHasNoAuthorityDependencies() {
        assertTrue(java.lang.reflect.Modifier.isPrivate(SyntheticIdentityEvidenceVerifier.VerifiedIdentity.class
                .getDeclaredConstructors()[0].getModifiers()));
        assertTrue(java.lang.reflect.Modifier.isPrivate(SyntheticPreviewScopeVerifier.VerifiedScope.class
                .getDeclaredConstructors()[0].getModifiers()));
        assertFalse(java.util.Arrays.stream(SyntheticPrivacyRequestService.class.getDeclaredFields())
                .anyMatch(field->field.getType()==VerifiedApproval.class||field.getType()==VerifiedDispatchFence.class
                        ||field.getType()==VerifiedActionReceipt.class));
        assertFalse(java.util.Arrays.stream(SyntheticPrivacyRequestService.class.getDeclaredFields())
                .anyMatch(field->javax.sql.DataSource.class.isAssignableFrom(field.getType())));
        assertFalse(java.util.Arrays.stream(SyntheticPrivacyRequestService.class.getDeclaredConstructors())
                .flatMap(constructor->java.util.Arrays.stream(constructor.getParameterTypes()))
                .anyMatch(type->javax.sql.DataSource.class.isAssignableFrom(type)));
        assertEquals(0,java.util.Arrays.stream(SyntheticPreviewPort.class.getMethods())
                .filter(method->method.getName().toLowerCase(java.util.Locale.ROOT).matches(".*(write|delete|dispatch|execute).*" )).count());
        assertTrue(java.util.Arrays.stream(JournalTestConnectionCapability.W3aSyntheticPreviewStore.class.getDeclaredConstructors())
                .noneMatch(c->java.util.Arrays.stream(c.getParameterTypes()).anyMatch(javax.sql.DataSource.class::isAssignableFrom)));
        assertFalse(java.util.Arrays.stream(JournalTestConnectionCapability.W3aSyntheticPreviewStore.class.getDeclaredMethods())
                .anyMatch(m->m.getName().equals("fromOwnedContainer")));
    }

    private static final Instant NOW=Instant.parse("2026-10-16T00:00:00Z");
    private final class Fixture {
        final SyntheticIdentityFixtureSigner signer=new SyntheticIdentityFixtureSigner();
        final TestProvider provider=new TestProvider();
        final TestWitness witness=new TestWitness();
        final SyntheticEnvironmentGuard guard=new SyntheticEnvironmentGuard(environment);
        final MutableClock clock=new MutableClock(NOW);
        final SyntheticIdentityEnvelope identity=signer.sign("tenant-synthetic-w3a","requester-w3a","subject-w3a",
                "request-w3a-"+java.util.UUID.randomUUID(),"identity-r1",NOW.plusSeconds(3600),"SYNTHETIC");
        final SyntheticPrivacyRequestService.Command command=new SyntheticPrivacyRequestService.Command("tenant-synthetic-w3a",
                "requester-w3a","subject-w3a",identity.requestId(),"identity-r1","case-w3a-"+java.util.UUID.randomUUID(),
                "scope-w3a","idem-w3a");
        final SyntheticIdentityEvidenceVerifier verifier=new SyntheticIdentityEvidenceVerifier(Map.of(
                SyntheticIdentityFixtureSigner.KEY_ID,signer.keyPair.getPublic()),clock,guard);
        final SyntheticPreviewProviderRegistry registry=new SyntheticPreviewProviderRegistry(Map.of(provider.providerId(),provider),guard);
        final SyntheticPrivacyRequestService service;
        String manifestHash;
        Fixture(){
            witness.values.put("identityHash",identity.digest());witness.values.put("identityExpiresAt",identity.expiresAt().toString());
            scopeHash=SyntheticPreviewScopeVerifier.computeScopeHash(verifier.verify(identity,command.tenantId(),command.requesterId(),
                    command.subjectRef(),command.requestId(),command.identityRevision()),command,"scope-r1",witness.allowedTargetIds);
            witness.values.put("scopeHash",scopeHash);
            List<String> manifestFields=new java.util.ArrayList<>(List.of(command.fingerprint(),identity.digest()));
            witness.values.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->manifestFields.add(e.getKey()+"="+e.getValue()));
            witness.allowedTargetIds.stream().sorted().forEach(manifestFields::add);
            manifestHash=ApprovalEnvelopeCanonicalizer.digest(SyntheticCanonicalizer.encode("SF-W3A-GENERATED-FIXTURE","v1",
                    manifestFields.toArray(String[]::new)));
            provider.bind(command.requestId(),identity.digest(),scopeHash,command.caseRef());
            provider.expectedClock=clock;
            store.seedScopedCase(command.tenantId(),command.caseRef(),0);
            ACTIVE_FIXTURE.set(this);
            service=serviceWithBarriers(()->{},()->{});
        }
        String scopeHash;
        void reset(String key,String value){witness.values.put(key,value);}
        SyntheticPrivacyRequestService serviceWithPreCommit(Runnable hook){return serviceWithBarriers(()->{},hook);}
        SyntheticPrivacyRequestService serviceWithBarriers(Runnable beforeFinal,Runnable beforeCommit){return new SyntheticPrivacyRequestService(
                store,verifier,new SyntheticPreviewScopeVerifier(),witness,registry,clock,beforeFinal,beforeCommit);}
    }
    private static class TestProvider implements SyntheticPreviewPort {
        final AtomicInteger reads=new AtomicInteger(), describes=new AtomicInteger();
        volatile String version="target-r1";
        volatile String contentHash="sha256-synthetic-r1";
        volatile String returnedTargetId;
        volatile String expectedRequestId,expectedIdentityHash,expectedScopeHash,expectedCaseRef;
        volatile MutableClock expectedClock;
        public String providerId(){return "synthetic-provider-w3a";} public String audience(){return "SYNTHETIC";}
        final AtomicInteger crossScopeAttempts=new AtomicInteger();
        volatile Runnable onDescribe=()->{};
        void bind(String request,String identityHash,String scopeHash,String caseRef){
            expectedRequestId=request;expectedIdentityHash=identityHash;expectedScopeHash=scopeHash;expectedCaseRef=caseRef;
        }
        public SyntheticTarget read(SyntheticPreviewScopeVerifier.VerifiedScope scope,String target){
            reads.incrementAndGet();
            if(!scope.permits("tenant-synthetic-w3a","requester-w3a","subject-w3a",expectedRequestId,"identity-r1",expectedIdentityHash,
                    "scope-w3a","scope-r1",expectedScopeHash,expectedCaseRef,"synthetic-provider-w3a","SYNTHETIC",target)){
                crossScopeAttempts.incrementAndGet();throw new SecurityException("scope外readは禁止です");
            }
            return current(returnedTargetId==null?target:returnedTargetId);
        }
        public SyntheticTarget describeCurrent(SyntheticPreviewScopeVerifier.VerifiedScope scope,String target){
            describes.incrementAndGet();
            onDescribe.run();
            if(!scope.permits("tenant-synthetic-w3a","requester-w3a","subject-w3a",expectedRequestId,"identity-r1",expectedIdentityHash,
                    "scope-w3a","scope-r1",expectedScopeHash,expectedCaseRef,"synthetic-provider-w3a","SYNTHETIC",target)){
                crossScopeAttempts.incrementAndGet();throw new SecurityException("scope外metadata readは禁止です");
            }
            Instant metadataAt=NOW.plusSeconds(3).plusNanos(describes.get());
            if(expectedClock!=null){
                Instant clockFloor=expectedClock.instant();
                Instant metadataFloor=metadataAt.plusSeconds(1);
                expectedClock.set(clockFloor.isAfter(metadataFloor)?clockFloor:metadataFloor);
            }
            return new SyntheticTarget(target,version,contentHash,metadataAt);
        }
        private SyntheticTarget current(String id){return new SyntheticTarget(id,version,contentHash,NOW);}
    }
    private static class TestWitness implements SyntheticPreviewWitnessPort {
        final AtomicInteger reads=new AtomicInteger();
        final Map<String,String> values=new HashMap<>();
        final List<String> allowedTargetIds=new java.util.concurrent.CopyOnWriteArrayList<>(List.of("target-synthetic-01"));
        TestWitness(){
            values.put("identityRevision","identity-r1");values.put("identityHash","pending");values.put("keyId",SyntheticIdentityFixtureSigner.KEY_ID);
            values.put("audience","SYNTHETIC");values.put("identityExpiresAt",NOW.plusSeconds(3600).toString());values.put("identityRevocation","ACTIVE");
            values.put("scopeRevision","scope-r1");values.put("scopeHash","scope-hash-r1");values.put("providerId","synthetic-provider-w3a");values.put("providerAudience","SYNTHETIC");
            values.put("policyVersion","policy-r1");values.put("policyHash","policy-hash-r1");values.put("policyEffective",NOW.minusSeconds(60).toString());values.put("policyExpiresAt",NOW.plusSeconds(3600).toString());values.put("policyRevocation","ACTIVE");values.put("policyState","KNOWN");
            values.put("holdRevision","hold-r1");values.put("holdHash","hold-hash-r1");values.put("holdState","CLEAR");values.put("auditRevision","audit-r1");values.put("auditHash","audit-hash-r1");values.put("auditState","KNOWN");
            values.put("blockerRevision","blocker-r1");values.put("blockerHash","blocker-hash-r1");values.put("journalRevision","journal-r1");values.put("journalHash","journal-hash-r1");values.put("journalState","KNOWN");
            values.put("witnessEpoch","epoch-r1");values.put("witnessRevision","witness-r1");values.put("checkpointHash","checkpoint-hash-r1");values.put("witnessKeyState","ACTIVE");values.put("caseRevision","0");
            values.put("tenantId","tenant-synthetic-w3a");values.put("subjectRef","subject-w3a");values.put("scopeState","ACTIVE");
        }
        public SourceSnapshot readCurrent(String tenant,String requester,String subject,String request,String caseRef,String scope){
            reads.incrementAndGet(); Map<String,String> current=new HashMap<>(values);current.put("requesterId",requester);
            current.put("requestId",request);current.put("caseRef",caseRef);current.put("scopeId",scope);
            Instant observed=NOW.plusNanos(reads.get()*1_234L);
            Map<String,Instant> sourceTimes=new HashMap<>();
            SyntheticPreviewWitnessPort.SOURCE_NAMES.forEach(name->sourceTimes.put(name,observed));
            return new SourceSnapshot(Map.copyOf(current),allowedTargetIds,sourceTimes,observed);
        }
    }
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        MutableClock(Instant initial){now=new AtomicReference<>(initial);}
        void set(Instant value){now.set(value);}
        @Override public ZoneOffset getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(java.time.ZoneId zone){return this;}
        @Override public Instant instant(){return now.get();}
    }
}
