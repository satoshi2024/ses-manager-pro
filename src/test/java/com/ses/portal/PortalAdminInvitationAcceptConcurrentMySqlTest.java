package com.ses.portal;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityHashUtil;
import com.ses.dto.portal.PortalAcceptInvitationRequest;
import com.ses.service.portal.PortalAuthService;
import com.ses.test.InnoDbRecordLockWaits;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * RB-PRERUN 3: 同じ組織へ発行済みの2件のADMIN招待を同時に受諾しても、組織管理者は1人にしかならないことを実MySQL 8
 * （既定のREPEATABLE READ）で確かめる。
 *
 * <p>先行の受諾（transactionをcommit前で止める）が組織行のX lockを持つ間、後続の受諾は同じ組織行のlockを待つ
 * （{@link InnoDbRecordLockWaits#waitEdgesOwnedBy} で待機辺を確認する）。先行のcommit後、後続は組織管理者の有無を
 * locking readで最新の確定値から読むため、先行が付与したorg.adminを見て409 {@code error.portal.invite.orgAdminExists}
 * で拒否される。lockが無い、または通常のSELECTで確かめる実装では、後続は自分のread viewで組織管理者を見られず、
 * 2人目の組織管理者を作る。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers
class PortalAdminInvitationAcceptConcurrentMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_rb_prerun_portal_admin_invite")
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

    private static final long WAIT_SECONDS = 30;
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PortalAuthService portalAuthService;

    @Test
    void 同じ組織への2件のADMIN招待を同時に受諾しても組織管理者は1人だけになる() throws Exception {
        long orgId = createCustomerOrg();
        String firstEmail = "concurrent-first-" + SEQUENCE.incrementAndGet() + "@example.com";
        String secondEmail = "concurrent-second-" + SEQUENCE.incrementAndGet() + "@example.com";
        String firstToken = invite(orgId, firstEmail);
        String secondToken = invite(orgId, secondEmail);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstAccepted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicLong firstConnection = new AtomicLong();
        try {
            Future<?> first = executor.submit(() -> new TransactionTemplate(transactionManager)
                    .executeWithoutResult(status -> {
                        portalAuthService.acceptInvitation(request(firstToken, firstEmail),
                                new MockHttpServletRequest());
                        firstConnection.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                        firstAccepted.countDown();
                        awaitOrFail(releaseFirst, "先行受諾のcommit待ち");
                    }));
            if (!firstAccepted.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                fail("先行の受諾がcommit前の状態に到達しませんでした");
            }

            Future<BusinessException> second = executor.submit(() -> {
                try {
                    portalAuthService.acceptInvitation(request(secondToken, secondEmail),
                            new MockHttpServletRequest());
                    return null;
                } catch (BusinessException rejected) {
                    return rejected;
                }
            });

            List<InnoDbRecordLockWaits.WaitEdge> edges = awaitWait(firstConnection.get(), second);
            assertThat(edges).as("後続の受諾は先行が持つ組織行のlockを待つこと").isNotEmpty();

            releaseFirst.countDown();
            first.get(WAIT_SECONDS, TimeUnit.SECONDS);
            BusinessException rejected = second.get(WAIT_SECONDS, TimeUnit.SECONDS);

            assertThat(rejected).as("後続のADMIN招待は受諾されないこと").isNotNull();
            assertThat(rejected.getCode()).isEqualTo(409);
            assertThat(rejected.getMessageKey()).isEqualTo("error.portal.invite.orgAdminExists");
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_portal_user_permission p "
                        + "JOIN t_portal_user u ON u.id = p.user_id WHERE u.portal_org_id = ? AND p.permission_key = 'org.admin'",
                Integer.class, orgId)).as("組織管理者は1人だけ").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_portal_user WHERE email = ?", Integer.class,
                secondEmail)).as("後続の受諾者は作られない").isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM t_portal_invitation WHERE token_hash = ? "
                + "AND used_at IS NULL", Integer.class, SecurityHashUtil.sha256(secondToken)))
                .as("後続の招待は未使用のまま残る").isEqualTo(1);
    }

    private List<InnoDbRecordLockWaits.WaitEdge> awaitWait(long blockerConnection, Future<?> requester)
            throws InterruptedException, ExecutionException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (requester.isDone()) {
                fail("後続の受諾が先行のcommit前に終わりました（組織行のlockを待っていない）: " + requester.get());
            }
            List<InnoDbRecordLockWaits.WaitEdge> edges = InnoDbRecordLockWaits.waitEdgesOwnedBy(jdbc,
                    blockerConnection, "m_portal_organization", "PRIMARY");
            if (!edges.isEmpty()) {
                return edges;
            }
            Thread.sleep(50);
        }
        return List.of();
    }

    private static void awaitOrFail(CountDownLatch latch, String label) {
        try {
            if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException(label + "が期限切れになりました");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + "が中断されました", interrupted);
        }
    }

    private long createCustomerOrg() {
        String name = "同時受諾検証顧客-" + UUID.randomUUID();
        long customerId = insert("INSERT INTO m_customer (company_name, tenant_id) VALUES (?, 'default')", name);
        return insert("INSERT INTO m_portal_organization (tenant_id, type, customer_id) VALUES ('default', 'CUSTOMER', ?)",
                customerId);
    }

    private String invite(long orgId, String email) {
        String rawToken = "concurrent-token-" + UUID.randomUUID();
        insert("INSERT INTO t_portal_invitation (portal_org_id, email, role, token_hash, expires_at) "
                        + "VALUES (?, ?, 'ADMIN', ?, ?)", orgId, email, SecurityHashUtil.sha256(rawToken),
                Timestamp.valueOf(LocalDateTime.now().plusHours(72)));
        return rawToken;
    }

    private static PortalAcceptInvitationRequest request(String token, String email) {
        PortalAcceptInvitationRequest request = new PortalAcceptInvitationRequest();
        request.setToken(token);
        request.setEmail(email);
        request.setDisplayName("同時受諾者");
        request.setPassword("password123");
        return request;
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
}
