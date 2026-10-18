package com.ses.privacy.foundation;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @BeforeAll static void start() {
        context=new AnnotationConfigApplicationContext(); context.getEnvironment().setActiveProfiles("test"); context.refresh();
        JournalTestConnectionCapability capability=JournalTestConnectionCapability.fromOwnedContainer(MYSQL,context);
        var ds=capability.adminDataSource();
        new ResourceDatabasePopulator(new ClassPathResource("privacy-journal/w3a-test-schema.sql")).execute(ds);
        jdbc=new JdbcTemplate(ds); environment=capability.environment();
        System.out.printf("SF_W3A_MYSQL containerId=%s database=%s schema=privacy-journal/w3a-test-schema.sql%n",capability.containerId(),capability.database());
    }
    @AfterAll static void stop(){if(context!=null)context.close();}

    @Test void exactRetryRevalidatesTargetMetadataWithoutRepeatingTargetRead() {
        Fixture f=new Fixture();
        PreviewResult first=f.service.preview(f.command,f.identity);
        assertEquals("AS_OF_PREVIEW_ONLY",first.status()); assertEquals(1,f.provider.reads.get()); assertEquals(1,f.provider.describes.get());
        PreviewResult retry=f.service.preview(f.command,f.identity);
        assertEquals("AS_OF_PREVIEW_ONLY",retry.status()); assertEquals(1,f.provider.reads.get()); assertEquals(2,f.provider.describes.get());
        assertEquals(first.snapshotRef(),retry.snapshotRef());
        f.witness.values.put("scopeHash","scope-changed");
        PreviewResult drift=f.service.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",drift.status()); assertTrue(drift.items().isEmpty());
        assertEquals(1,f.provider.reads.get()); assertEquals(2,f.provider.describes.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=?",Integer.class,"tenant-synthetic-w3a"));
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

    @Test void driftAfterFinalReadCanPersistOnlyAsOfHistoryAndReturnsClosedView() {
        Fixture f=new Fixture();
        SyntheticPrivacyRequestService raced=f.serviceWithPreCommit(()->f.witness.values.put("policyHash","policy-drift"));
        PreviewResult result=raced.preview(f.command,f.identity);
        assertEquals("PREVIEW_STALE",result.status()); assertTrue(result.items().isEmpty());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
        assertEquals(1,f.provider.reads.get()); assertEquals(0,f.provider.describes.get());
    }

    @Test void exactRetryClosesEachCurrentDriftClassWithoutOverwritingSnapshot() {
        Fixture f=new Fixture();
        assertEquals("AS_OF_PREVIEW_ONLY",f.service.preview(f.command,f.identity).status());
        int beforeReads=f.provider.reads.get();
        int beforeDescriptions=f.provider.describes.get();
        assertClosedAfterMutation(f,"scopeHash","scope-hash-r2","PREVIEW_STALE");
        f.reset("scopeHash","scope-hash-r1");
        assertClosedAfterMutation(f,"policyHash","policy-hash-r2","PREVIEW_STALE");
        f.reset("policyHash","policy-hash-r1");
        assertClosedAfterMutation(f,"holdState","ACTIVE","PREVIEW_BLOCKED");
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
        assertClosedAfterMutation(f,"caseRevision","9","PREVIEW_STALE");
        f.reset("caseRevision","0");
        assertClosedAfterMutation(f,"journalState","UNKNOWN","PREVIEW_UNKNOWN");
        assertEquals(beforeReads,f.provider.reads.get());
        assertEquals(beforeDescriptions+1,f.provider.describes.get());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM sf_w3a_preview WHERE tenant_id=? AND request_id=?",Integer.class,
                f.command.tenantId(),f.command.requestId()));
    }

    private static void assertClosedAfterMutation(Fixture f,String key,String value,String expected){
        f.witness.values.put(key,value);
        PreviewResult result=f.service.preview(f.command,f.identity);
        assertEquals(expected,result.status(),key);assertTrue(result.items().isEmpty(),key);
    }

    @Test void unverifiedIdentityIsRejectedBeforeAnyProviderOrDatabaseAccess() {
        Fixture f=new Fixture();
        SyntheticIdentityEnvelope invalid=new SyntheticIdentityEnvelope("tenant-synthetic-w3a","requester-w3a","subject-w3a",
                f.command.requestId(),"identity-r1",SyntheticIdentityFixtureSigner.KEY_ID,"SYNTHETIC",
                NOW.plusSeconds(3600),new byte[]{1,2,3});
        assertEquals("PREVIEW_STALE",f.service.preview(f.command,invalid).status());
        assertEquals(0,f.provider.reads.get()); assertEquals(0,f.witness.reads.get());
    }

    private static final Instant NOW=Instant.parse("2026-10-16T00:00:00Z");
    private final class Fixture {
        final SyntheticIdentityFixtureSigner signer=new SyntheticIdentityFixtureSigner();
        final TestProvider provider=new TestProvider();
        final TestWitness witness=new TestWitness();
        final SyntheticEnvironmentGuard guard=new SyntheticEnvironmentGuard(environment);
        final SyntheticIdentityEnvelope identity=signer.sign("tenant-synthetic-w3a","requester-w3a","subject-w3a",
                "request-w3a-"+java.util.UUID.randomUUID(),"identity-r1",NOW.plusSeconds(3600),"SYNTHETIC");
        final SyntheticPrivacyRequestService.Command command=new SyntheticPrivacyRequestService.Command("tenant-synthetic-w3a",
                "requester-w3a","subject-w3a",identity.requestId(),"identity-r1","case-w3a-"+java.util.UUID.randomUUID(),
                "scope-w3a","idem-w3a");
        final SyntheticIdentityEvidenceVerifier verifier=new SyntheticIdentityEvidenceVerifier(Map.of(
                SyntheticIdentityFixtureSigner.KEY_ID,signer.keyPair.getPublic()),Clock.fixed(NOW,ZoneOffset.UTC),guard);
        final SyntheticPreviewProviderRegistry registry=new SyntheticPreviewProviderRegistry(Map.of(provider.providerId(),provider),guard);
        final SyntheticPrivacyRequestService service=serviceWithPreCommit(()->{});
        Fixture(){witness.values.put("identityHash",identity.digest());witness.values.put("identityExpiresAt",identity.expiresAt().toString());}
        void reset(String key,String value){witness.values.put(key,value);}
        SyntheticPrivacyRequestService serviceWithPreCommit(Runnable hook){return new SyntheticPrivacyRequestService(jdbc.getDataSource(),environment,
                verifier,witness,registry,guard,Clock.fixed(NOW,ZoneOffset.UTC),hook);}
    }
    private static class TestProvider implements SyntheticPreviewPort {
        final AtomicInteger reads=new AtomicInteger(), describes=new AtomicInteger();
        volatile String version="target-r1";
        public String providerId(){return "synthetic-provider-w3a";} public String audience(){return "SYNTHETIC";}
        public List<String> registeredTargets(String tenant,String subject,String scope){return List.of("target-synthetic-01");}
        public SyntheticTarget read(String tenant,String subject,String target){reads.incrementAndGet();return current(target);}
        public SyntheticTarget describeCurrent(String tenant,String subject,String scope,String target){
            describes.incrementAndGet();
            if (!"tenant-synthetic-w3a".equals(tenant) || !"subject-w3a".equals(subject) || !"scope-w3a".equals(scope))
                throw new SecurityException("scope外metadata readは禁止です");
            return current(target);
        }
        private SyntheticTarget current(String id){return new SyntheticTarget(id,version,"sha256-synthetic-r1",NOW);}
    }
    private static class TestWitness implements SyntheticPreviewWitnessPort {
        final AtomicInteger reads=new AtomicInteger();
        final Map<String,String> values=new HashMap<>();
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
            return new SourceSnapshot(Map.copyOf(current),NOW.plusNanos(reads.get()));
        }
    }
}
