package com.ses.featureenablement.signoff;

import com.ses.entity.FeatureSignOff;
import com.ses.featureenablement.FeatureEnablementH2Fixture;
import com.ses.featureenablement.FeatureEnablementTestConfiguration;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementWriteSupport;
import com.ses.featureenablement.service.FeatureSignOffService;
import com.ses.featureenablement.state.FeatureEnablementTenant;
import com.ses.mapper.FeatureSignOffMapper;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.privacy.production.retention.hold.PrivacyHoldPathGuard;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
import com.ses.service.FileStorageService;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FE-A06（design §6.6、J-14、RB §4.4 の D17）: 終わった署名の3年後の消去。氏名・元の file 名・保存名を NULL にして PURGED にし、
 * commit の後に文書の実体と metadata を消す（SHA-256・参照・日付・役割は残す）。保全 hold の gate が止めれば消さない。
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Transactional
class FeatureSignOffPurgeSchedulerTest {

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);

    @Autowired
    private FeatureSignOffPurgeScheduler scheduler;
    @Autowired
    private PrivacyHoldPathGuard holdGuard;
    @Autowired
    private FeatureSignOffService signOffs;
    @Autowired
    private FeatureSignOffMapper signOffMapper;
    @Autowired
    private FeatureEnablementWriteSupport write;
    @Autowired
    private FileStorageService fileStorage;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;

    private FeatureEnablementH2Fixture fixture;
    private FeatureEnablementActor registrant;
    private FeatureEnablementActor reviewer;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
        registrant = fixture.admin();
        reviewer = fixture.admin();
    }

    @Test
    void 三年を過ぎた終わった署名の氏名と元のfile名と文書を消しhashと参照は残す() {
        FeatureSignOff rejected = signOffs.register(new FeatureSignOffService.Registration("NF04_SRE", "SRE-2026-1101",
                "SRE", "署名 花子", SIGNED, LocalDate.of(2027, 11, 1), Map.of()), pdf(), "署名 花子.pdf", registrant);
        String stored = rejected.getStoredName();
        signOffs.reject(rejected.getId(), 0, "CONTENT_MISMATCH", reviewer);
        assertThat(jdbc.queryForObject("SELECT purge_after FROM t_feature_sign_off WHERE id = ?", LocalDate.class,
                rejected.getId())).isEqualTo(LocalDate.of(2029, 11, 23));

        clock.set(Instant.parse("2029-11-22T01:00:00Z"));
        scheduler.purgeDue();
        assertThat(row(rejected.getId())).as("期日の前は消さない").containsEntry("STATUS", "REJECTED");

        clock.set(Instant.parse("2029-11-23T01:00:00Z"));
        FileStorageService storage = mock(FileStorageService.class);
        assertThat(scheduler(holdGuard, storage).purgeDue()).isEqualTo(1);

        Map<String, Object> purged = row(rejected.getId());
        assertThat(purged).containsEntry("STATUS", "PURGED").containsEntry("SIGNER_NAME", null)
                .containsEntry("ORIGINAL_NAME", null).containsEntry("STORED_NAME", null)
                .containsEntry("REFERENCE", "SRE-2026-1101").containsEntry("SIGNER_ROLE", "SRE")
                .containsEntry("CONTENT_SHA256", rejected.getContentSha256());
        assertThat(purged.get("PURGED_AT")).isNotNull();
        assertThat(fixture.countAudit("FEATURE_SIGN_OFF_PURGED")).isEqualTo(1);

        // commit の後に REQUIRES_NEW の transaction で文書の実体と metadata を消す（実際の削除は
        // FeatureEnablementNotificationDeliveryTest が commit する専用 DB で確かめる）
        verify(storage, org.mockito.Mockito.never()).delete(stored);
        FeatureEnablementH2Fixture.runAfterCommit();
        verify(storage).delete(stored);
    }

    @Test
    void 確認済みの署名は失効日が古くても消さない() {
        long confirmed = fixture.signOff("NF04_PRIVACY", "CONFIRMED", SIGNED, LocalDate.of(2026, 12, 1));
        clock.set(Instant.parse("2031-01-01T01:00:00Z"));

        scheduler.purgeDue();

        assertThat(row(confirmed)).containsEntry("STATUS", "CONFIRMED");
        assertThat(row(confirmed).get("STORED_NAME")).isNotNull();
    }

    @Test
    void 保全holdの対象か判定できなければその回は消さない() {
        long ended = fixture.signOff("NF04_SRE", "REJECTED", SIGNED, LocalDate.of(2027, 11, 1));
        jdbc.update("UPDATE t_feature_sign_off SET purge_after = ? WHERE id = ?", LocalDate.of(2026, 11, 1), ended);
        PrivacyHoldPathGuard guard = mock(PrivacyHoldPathGuard.class);
        PrivacyHoldPathGuard.Batch batch = mock(PrivacyHoldPathGuard.Batch.class);
        when(guard.openBatch(eq(PrivacyHoldPath.D17), anyString())).thenReturn(batch);
        when(batch.elementVerdict("FEATURE_SIGN_OFF_SIGNER_NAME")).thenReturn(PrivacyHoldGate.Verdict.ALLOW);
        when(batch.elementVerdict("FEATURE_SIGN_OFF_DOCUMENT")).thenReturn(PrivacyHoldGate.Verdict.HELD);

        assertThat(scheduler(guard, fileStorage).purgeDue()).isZero();

        assertThat(row(ended)).containsEntry("STATUS", "REJECTED");
        verify(batch).skip(eq(PrivacyHoldGate.Verdict.HELD), org.mockito.ArgumentMatchers.anyInt());
        verify(batch).close();
        // gate の Bean が無ければ何も消さない
        assertThat(scheduler(null, fileStorage).purgeDue()).isZero();
        assertThat(row(ended)).containsEntry("STATUS", "REJECTED");
    }

    /**
     * review rb-fe-a1 P2-1: 本物の guard（gate だけ差し替え）で、署名の文書の DATA_ELEMENT hold の間は消さず、SUBJECT hold だけなら
     * 帰属できない（NOT_ATTRIBUTABLE）署名の氏名・文書は消す（SUBJECT hold で守られない残余は D-05 の受理事項。RB §4.5）。
     */
    @Test
    void 署名の文書のDATA_ELEMENT_holdの間は消さずSUBJECT_holdだけなら消す() {
        long ended = fixture.signOff("NF04_SRE", "REJECTED", SIGNED, LocalDate.of(2027, 11, 1));
        jdbc.update("UPDATE t_feature_sign_off SET purge_after = ? WHERE id = ?", LocalDate.of(2026, 11, 1), ended);
        Map<String, SubjectAttribution> attribution = Map.of(
                "FEATURE_SIGN_OFF_SIGNER_NAME", SubjectAttribution.NOT_ATTRIBUTABLE,
                "FEATURE_SIGN_OFF_DOCUMENT", SubjectAttribution.NOT_ATTRIBUTABLE);
        PrivacyHoldTestGuards.Stub elementHold = PrivacyHoldTestGuards.stub().tenant(FeatureEnablementH2Fixture.TENANT,
                PrivacyHoldTestGuards.verified(Set.of(), Set.of("FEATURE_SIGN_OFF_DOCUMENT"), attribution));

        assertThat(scheduler(elementHold.guard, fileStorage).purgeDue()).isZero();
        assertThat(row(ended)).containsEntry("STATUS", "REJECTED");
        assertThat(elementHold.skipped(PrivacyHoldPath.D17, PrivacyHoldGate.Verdict.HELD)).isEqualTo(1.0);

        PrivacyHoldTestGuards.Stub subjectHold = PrivacyHoldTestGuards.stub().tenant(FeatureEnablementH2Fixture.TENANT,
                PrivacyHoldTestGuards.verified(Set.of("ENGINEER:990001"), Set.of(), attribution));
        assertThat(scheduler(subjectHold.guard, mock(FileStorageService.class)).purgeDue()).isEqualTo(1);
        assertThat(row(ended)).containsEntry("STATUS", "PURGED");
    }

    @Test
    void 定期の入口はvoidでSchedulerLockと日本時間のcronを持つ() throws Exception {
        Method method = FeatureSignOffPurgeScheduler.class.getMethod("purge");
        assertThat(method.getReturnType()).isEqualTo(void.class);
        assertThat(method.getAnnotation(SchedulerLock.class).name()).isEqualTo("featureSignOffPurge");
        assertThat(method.getAnnotation(Scheduled.class).zone()).isEqualTo("Asia/Tokyo");
        assertThat(PrivacyHoldPath.D17.dataElements()).containsExactlyElementsOf(FeatureSignOffPurgeScheduler.DATA_ELEMENTS);
    }

    // ---------------------------------------------------------------- helper

    @SuppressWarnings("unchecked")
    private FeatureSignOffPurgeScheduler scheduler(PrivacyHoldPathGuard guard, FileStorageService storage) {
        ObjectProvider<PrivacyHoldPathGuard> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);
        return new FeatureSignOffPurgeScheduler(signOffMapper, write, storage, provider,
                FeatureEnablementTenant.fixed(FeatureEnablementH2Fixture.TENANT), transactionManager, clock);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT * FROM t_feature_sign_off WHERE id = ?", id);
    }

    private static byte[] pdf() {
        return "%PDF-1.7\n1 0 obj<<>>endobj\n% purge\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    }
}
