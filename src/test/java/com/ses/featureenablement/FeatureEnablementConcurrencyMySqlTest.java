package com.ses.featureenablement;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.service.FeatureEnablementActor;
import com.ses.featureenablement.service.FeatureEnablementApprovalResult;
import com.ses.featureenablement.service.FeatureEnablementService;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FE-A05（design §5.1・§11.4）: 実 MySQL 8.4 の READ COMMITTED と行 lock で、機能の開放の書込みの並行を確かめる。
 *
 * <ul>
 *   <li>同じ申請の並行の承認は1件だけ成功し、もう1件は 409（世代の行の lock で直列になり、後の側は決まった申請を読む）。</li>
 *   <li>子の承認と親の閉鎖の並行は、どちらの順でも「親は手動で閉じ、子は開いていない」に落ち着く。</li>
 *   <li>申請・承認・閉鎖を混ぜた並行で deadlock も lock 待ちの timeout も起きない（世代の行を最初に lock する順のため）。</li>
 * </ul>
 * <p>各 test は別の開放単位を使い、commit した行は class 専用の container DB に残る（他の test の行は数えない）。</p>
 */
@SpringBootTest(properties = "app.feature-enablement.source=database")
@ActiveProfiles("test")
@Import(FeatureEnablementTestConfiguration.class)
@Tag("mysql")
@Testcontainers
class FeatureEnablementConcurrencyMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_feature_enablement_concurrency")
            .withUsername("root")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    private static final LocalDate SIGNED = LocalDate.of(2026, 11, 1);
    private static final LocalDate EXPIRES = LocalDate.of(2027, 11, 1);

    @Autowired
    private FeatureEnablementService service;
    @Autowired
    private com.ses.featureenablement.service.FeatureSignOffService signOffs;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private FeatureEnablementCatalog catalog;
    @Autowired
    private FeatureEnablementTestConfiguration.MutableTestClock clock;

    private FeatureEnablementH2Fixture fixture;

    @BeforeEach
    void setUp() {
        clock.reset();
        fixture = new FeatureEnablementH2Fixture(jdbc, catalog);
    }

    @Test
    void 同じ申請の並行の承認は1件だけ成功しもう1件は409() throws Exception {
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor first = fixture.admin();
        FeatureEnablementActor second = fixture.admin();
        FeatureEnablementRequest request = service.requestOpen(Feature.NF03, null, requester);

        List<Object> outcomes = race(
                () -> service.approve(request.getId(), 0, null, first),
                () -> service.approve(request.getId(), 0, null, second));

        assertThat(outcomes.stream().filter(outcome -> outcome instanceof FeatureEnablementApprovalResult result
                && result.approved()).count()).as("承認は1件だけ").isEqualTo(1);
        assertThat(outcomes.stream().filter(outcome -> outcome instanceof BusinessException failure
                && failure.getCode() == 409).count()).as("もう1件は 409").isEqualTo(1);
        assertNoLockFailure(outcomes);
        assertThat(fixture.requestStatus(request.getId())).isEqualTo("APPROVED");
        assertThat(fixture.state(Feature.NF03)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_feature_enablement_event WHERE tenant_id = 'default' "
                + "AND subject_type = 'FEATURE' AND event_type = 'APPROVED' AND subject_id = ?", Long.class,
                request.getId())).as("開いた履歴は1行").isEqualTo(1);
    }

    @Test
    void 子の承認と親の閉鎖の並行はどちらの順でも親が閉じ子は開いていない() throws Exception {
        fixture.confirmedSignOffs(Feature.NF03, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF03_CERTIFICATION_PII, SIGNED, EXPIRES);
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        FeatureEnablementActor closer = fixture.admin();
        ensureOpen(Feature.NF03, requester, approver);
        FeatureEnablementRequest child = service.requestOpen(Feature.NF03_CERTIFICATION_PII, null, requester);

        List<Object> outcomes = race(
                () -> service.approve(child.getId(), 0, null, approver),
                () -> {
                    service.close(Feature.NF03, "OWNER_DECISION", closer);
                    return "closed";
                });

        assertNoLockFailure(outcomes);
        assertThat(outcomes.get(1)).as("親の閉鎖は必ず成功する").isEqualTo("closed");
        assertThat(fixture.state(Feature.NF03)).isEqualTo("MANUALLY_CLOSED");
        assertThat(fixture.state(Feature.NF03_CERTIFICATION_PII)).as("子は開いていない")
                .isIn("CLOSED", "AUTO_CLOSED");
        assertThat(fixture.requestStatus(child.getId())).as("承認が先なら APPROVED、閉鎖が先なら CANCELLED")
                .isIn("APPROVED", "CANCELLED");
        if ("APPROVED".equals(fixture.requestStatus(child.getId()))) {
            assertThat(fixture.closedReason(Feature.NF03_CERTIFICATION_PII)).isEqualTo("PARENT_CLOSED");
        } else {
            assertThat(outcomes.get(0)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void 申請と承認と閉鎖を混ぜた並行でdeadlockもlock待ちのtimeoutも起きない() throws Exception {
        fixture.confirmedSignOffs(Feature.NF10, SIGNED, EXPIRES);
        fixture.confirmedSignOffs(Feature.NF01, SIGNED, EXPIRES);
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        FeatureEnablementActor closer = fixture.admin();
        ensureOpen(Feature.NF10, requester, approver);
        FeatureEnablementRequest nf01 = service.requestOpen(Feature.NF01, null, requester);

        List<Object> outcomes = race(
                () -> service.approve(nf01.getId(), 0, null, approver),
                () -> {
                    service.close(Feature.NF10, "OWNER_DECISION", closer);
                    return "closed";
                },
                () -> service.expirePendingRequests(),
                () -> service.autoClose(Feature.NF10, "TEST_RACE", false));

        assertNoLockFailure(outcomes);
        assertThat(fixture.state(Feature.NF01)).isEqualTo("OPEN");
        assertThat(fixture.state(Feature.NF10)).isIn("MANUALLY_CLOSED", "AUTO_CLOSED");
    }

    /** FE-A06: 署名の取消し × 承認。どちらの順でも、取り消した署名を要する開放単位は開いていない。 */
    @Test
    void 署名の取消しと承認の並行はどちらの順でも開放単位は開いていない() throws Exception {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        FeatureEnablementActor revoker = fixture.admin();
        ensureClosed(Feature.NF04, revoker);
        FeatureEnablementRequest request = service.requestOpen(Feature.NF04, null, requester);
        long privacy = fixture.confirmedId("NF04_PRIVACY");
        int version = signOffVersion(privacy);

        List<Object> outcomes = race(
                () -> service.approve(request.getId(), 0, null, approver),
                () -> {
                    signOffs.revoke(privacy, version, "SIGNER_WITHDREW", revoker);
                    return "revoked";
                });

        assertNoLockFailure(outcomes);
        assertThat(outcomes.get(1)).as("取消しは必ず成功する").isEqualTo("revoked");
        assertThat(fixture.state(Feature.NF04)).isNotIn("OPEN", "OPEN_PENDING_RESTART");
        FeatureEnablementApprovalResult result = (FeatureEnablementApprovalResult) outcomes.get(0);
        if (result.approved()) {
            assertThat(fixture.closedReason(Feature.NF04)).as("承認が先なら取消しが自動閉鎖する")
                    .isEqualTo("SIGN_OFF_REVOKED");
        } else {
            assertThat(result.outcome()).as("取消しが先なら承認は条件の不成立")
                    .isEqualTo(FeatureEnablementApprovalResult.Outcome.CONDITIONS_UNSATISFIED);
            assertThat(fixture.requestStatus(request.getId())).isEqualTo("PENDING");
            // 後の test の申請と衝突しないよう取り下げる
            service.withdraw(request.getId(), fixture.requestVersion(request.getId()), requester);
        }
    }

    /** FE-A06: 署名の差替え × 承認。差替えが先なら承認は STALE、承認が先なら成り立つ差替えで開いたまま。 */
    @Test
    void 署名の差替えと承認の並行は差替えが先ならSTALEで承認が先なら開いたまま() throws Exception {
        fixture.confirmedSignOffs(Feature.NF04, SIGNED, EXPIRES);
        FeatureEnablementActor requester = fixture.admin();
        FeatureEnablementActor approver = fixture.admin();
        FeatureEnablementActor confirmer = fixture.admin();
        ensureClosed(Feature.NF04, confirmer);
        FeatureEnablementRequest request = service.requestOpen(Feature.NF04, null, requester);
        long replacement = fixture.signOff("NF04_SRE", "REGISTERED", SIGNED, EXPIRES);

        List<Object> outcomes = race(
                () -> service.approve(request.getId(), 0, null, approver),
                () -> signOffs.confirm(replacement, 0, confirmer));

        assertNoLockFailure(outcomes);
        assertThat(jdbc.queryForObject("SELECT status FROM t_feature_sign_off WHERE id = ?", String.class, replacement))
                .isEqualTo("CONFIRMED");
        FeatureEnablementApprovalResult result = (FeatureEnablementApprovalResult) outcomes.get(0);
        if (result.approved()) {
            assertThat(fixture.state(Feature.NF04)).isEqualTo("OPEN");
        } else {
            assertThat(result.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.STALE);
            assertThat(fixture.requestStatus(request.getId())).as("STALE は commit される").isEqualTo("STALE");
        }
    }

    // ---------------------------------------------------------------- helper

    /** 開いていれば閉じる（前の test の状態を持ち越さない）。 */
    private void ensureClosed(Feature feature, FeatureEnablementActor closer) {
        if ("OPEN".equals(fixture.state(feature))) {
            service.close(feature, "TEST_RESET", closer);
        }
    }

    private int signOffVersion(long id) {
        return jdbc.queryForObject("SELECT version FROM t_feature_sign_off WHERE id = ?", Integer.class, id);
    }

    /** 開放単位を開く（前の test で開いていればそのまま）。 */
    private void ensureOpen(Feature feature, FeatureEnablementActor requester, FeatureEnablementActor approver) {
        if ("OPEN".equals(fixture.state(feature))) {
            return;
        }
        FeatureEnablementRequest request = service.requestOpen(feature, null, requester);
        assertThat(service.approve(request.getId(), 0, null, approver).approved()).isTrue();
    }

    /** 全ての処理を同時に始め、結果か例外を順に返す。 */
    @SafeVarargs
    private static List<Object> race(Callable<Object>... calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.length);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (Exception failure) {
                        return failure;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void assertNoLockFailure(List<Object> outcomes) {
        for (Object outcome : outcomes) {
            assertThat(outcome).isNotInstanceOf(DeadlockLoserDataAccessException.class)
                    .isNotInstanceOf(CannotAcquireLockException.class);
            if (outcome instanceof Exception failure) {
                assertThat(failure).as("想定する失敗は業務の 409 だけ").isInstanceOf(BusinessException.class);
                assertThat(((BusinessException) failure).getCode()).isEqualTo(409);
            }
        }
    }
}
