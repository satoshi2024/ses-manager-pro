package com.ses.operational;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.entity.ApprovalAction;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.Customer;
import com.ses.entity.Notification;
import com.ses.entity.NotificationOutbox;
import com.ses.entity.Quotation;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalActionMapper;
import com.ses.mapper.ApprovalDelegationMapper;
import com.ses.mapper.ApprovalDelegationTypeMapper;
import com.ses.mapper.ApprovalParticipantMapper;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.NotificationOutboxMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.NotificationService;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.approval.ApprovalNotificationService;
import com.ses.service.approval.ApprovalRequestCommand;
import com.ses.service.approval.ApprovalSnapshot;
import com.ses.service.approval.ApprovalTargetAdapter;
import com.ses.service.approval.ResolvedRoute;
import com.ses.service.approval.RouteResolverService;
import com.ses.service.approval.RouteStepGroup;
import com.ses.service.impl.ApprovalEngineServiceImpl;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.ses.test.MySQLContainer;
import com.ses.test.EnableDefaultTenantTestContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 実MySQLと別JVMを共有する、#9の運用境界回帰。
 *
 * <p>H2の同一JVM/thread競合ではなく、独立子JVMから同じMySQLへ接続し、ShedLockの共有行と
 * outbox claimの条件付きUPDATEを競合させる。さらに承認engineのadapter適用後例外を
 * 実MySQL transactionで発生させ、action・request・対象行がまとめてrollbackされることを確認する。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@EnableDefaultTenantTestContext
class OperationalBoundaryMySqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_operational_boundary")
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
        registry.add("app.scheduler.lock.use-db-time", () -> "true");
    }

    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private NotificationOutboxMapper outboxMapper;
    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;
    @Autowired
    private ApprovalActionMapper approvalActionMapper;
    @Autowired
    private ApprovalDelegationMapper approvalDelegationMapper;
    @Autowired
    private ApprovalDelegationTypeMapper approvalDelegationTypeMapper;
    @Autowired
    private ApprovalParticipantMapper approvalParticipantMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private CustomerMapper customerMapper;
    @Autowired
    private QuotationMapper quotationMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void 独立JVM二つが共有MySQLのShedLockを競合させ解放後に再取得できる() throws Exception {
        String lockName = "multi-jvm-shedlock-" + System.nanoTime();
        ChildOutput holder = startChild("lock-holder", lockName, null, 0L);
        try {
            String holderAcquired = readLine(holder, 15);
            assertTrue(holderAcquired != null
                            && holderAcquired.matches("LOCK_ACQUIRED pid=" + holder.process.pid()),
                    "先行JVMがShedLockを取得し、pidがProcess.pidと一致すること: "
                            + childEvidence(holder, List.of(String.valueOf(holderAcquired))));

            ChildOutput contender = startChild("lock-contender", lockName, null,
                    System.currentTimeMillis() + 250L);
            try {
                List<String> contenderLines = finishChild(contender, 15);
                assertTrue(contenderLines.stream().anyMatch(line -> line.startsWith("LOCK_NOT_ACQUIRED")),
                        "保持中の同一ShedLockを後発JVMが取得しないこと: " + childEvidence(contender, contenderLines));
                assertEquals(1, contenderLines.size(),
                        "競合childがプロトコル結果を1行だけ出力すること: " + childEvidence(contender, contenderLines));
                assertTrue(contenderLines.get(0).matches("LOCK_NOT_ACQUIRED pid=" + contender.process.pid()),
                        "競合childのpidが実際のProcess.pidと一致すること: " + childEvidence(contender, contenderLines));
            } finally {
                terminateChild(contender);
            }

            List<String> holderLines = finishChild(holder, 15);
            holderLines.add(0, holderAcquired);
            assertEquals(2, holderLines.size(), "先行childが取得・解放の2行だけを出力すること: "
                    + childEvidence(holder, holderLines));
            assertEquals("LOCK_RELEASED", holderLines.get(1), "先行JVMがShedLockを解放すること: "
                    + childEvidence(holder, holderLines));
        } finally {
            terminateChild(holder);
        }

        ChildOutput afterRelease = startChild("lock-after-release", lockName, null, 0L);
        List<String> afterLines;
        try {
            afterLines = finishChild(afterRelease, 15);
        } finally {
            terminateChild(afterRelease);
        }
        assertEquals(2, afterLines.size(), "再取得childが取得・解放の2行だけを出力すること: "
                + childEvidence(afterRelease, afterLines));
        assertTrue(afterLines.get(0).matches("LOCK_ACQUIRED pid=" + afterRelease.process.pid()),
                "解放後に後続JVMがShedLockを再取得し、pidがProcess.pidと一致すること: "
                        + childEvidence(afterRelease, afterLines));
        assertEquals("LOCK_RELEASED", afterLines.get(1), "再取得したJVMがShedLockを解放すること: "
                + childEvidence(afterRelease, afterLines));
    }

    @Test
    void 独立JVM二つが同じoutbox行をclaimすると一つだけ成功する() throws Exception {
        String dedupeKey = "multi-jvm-claim-" + System.nanoTime();
        Notification notification = new Notification();
        notification.setTenantId("default");
        notification.setType("SYSTEM");
        notification.setTitle("multi JVM claim test");
        notification.setMessage("loopback claim");
        notification.setLinkUrl("/approval/inbox");
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        assertNotNull(notification.getId());

        NotificationOutbox outbox = NotificationOutbox.builder()
                .tenantId("default")
                .notificationId(notification.getId())
                .type(notification.getType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .linkUrl(notification.getLinkUrl())
                .dedupeKey(dedupeKey)
                .status("PENDING")
                .attemptCount(0)
                .nextAttemptAt(LocalDateTime.now().plusHours(1))
                .createdAt(LocalDateTime.now())
                .build();
        outboxMapper.insert(outbox);
        assertNotNull(outbox.getId());

        long startAt = System.currentTimeMillis() + 1500L;
        ChildOutput first = startChild("claim", "-", outbox.getId(), startAt);
        ChildOutput second = null;
        List<String> firstLines;
        List<String> secondLines;
        try {
            second = startChild("claim", "-", outbox.getId(), startAt);
            firstLines = finishChild(first, 15);
            secondLines = finishChild(second, 15);
        } finally {
            terminateChild(first);
            terminateChild(second);
        }

        List<String> all = new ArrayList<>();
        all.addAll(firstLines);
        all.addAll(secondLines);
        long claimed = all.stream().filter(line -> line.startsWith("CLAIM_RESULT=1")).count();
        long rejected = all.stream().filter(line -> line.startsWith("CLAIM_RESULT=0")).count();
        assertEquals(1L, claimed, "独立JVMのclaim成功は1件だけであること: " + all + " / "
                + childEvidence(first, firstLines) + " / " + childEvidence(second, secondLines));
        assertEquals(1L, rejected, "独立JVMのclaim競合は0件更新になること: " + all + " / "
                + childEvidence(first, firstLines) + " / " + childEvidence(second, secondLines));
        assertEquals(1, firstLines.size(), "先行子JVMがプロトコル結果を1行だけ出力すること: " + childEvidence(first, firstLines));
        assertEquals(1, secondLines.size(), "後続子JVMがプロトコル結果を1行だけ出力すること: " + childEvidence(second, secondLines));
        assertTrue(firstLines.get(0).matches("CLAIM_RESULT=[01] pid=" + first.process.pid()),
                "先行childのpidが実際のProcess.pidと一致すること: " + childEvidence(first, firstLines));
        assertTrue(secondLines.get(0).matches("CLAIM_RESULT=[01] pid=" + second.process.pid()),
                "後続childのpidが実際のProcess.pidと一致すること: " + childEvidence(second, secondLines));
        assertTrue(first.process.pid() != second.process.pid(),
                "2つの異なる子JVMが実行されたこと: " + childEvidence(first, firstLines) + " / "
                        + childEvidence(second, secondLines));

        NotificationOutbox stored = outboxMapper.selectById(outbox.getId());
        assertEquals("PROCESSING", stored.getStatus());
        assertEquals(1, stored.getAttemptCount());
    }

    @Test
    void adapter適用後例外では実MySQLの承認action対象行requestがcommit前にrollbackされる() {
        String suffix = String.valueOf(System.nanoTime());
        Long applicantId = insertUser("rollback-applicant-" + suffix);
        Long approverId = insertUser("rollback-approver-" + suffix);

        Customer customer = Customer.builder().companyName("rollback-customer-" + suffix)
                .tenantId("default").build();
        customerMapper.insert(customer);
        Quotation quotation = new Quotation();
        quotation.setQuotationNo("Q-RB-" + suffix);
        quotation.setCustomerId(customer.getId());
        quotation.setTitle("rollback quotation");
        quotation.setUnitPrice(BigDecimal.valueOf(100000));
        quotation.setStatus("下書き");
        quotation.setVersion(0);
        quotationMapper.insert(quotation);
        assertNotNull(quotation.getId());

        RouteResolverService routeResolver = mock(RouteResolverService.class);
        when(routeResolver.resolve(eq("rollback.mysql"), any(), any(), eq(applicantId), any()))
                .thenReturn(new ResolvedRoute(1L, 1, null,
                        List.of(new RouteStepGroup(1, null, List.of(approverId)))));
        NotificationService notificationService = mock(NotificationService.class);
        ApprovalNotificationService approvalNotificationService = mock(ApprovalNotificationService.class);

        ApprovalTargetAdapter failingAdapter = new ApprovalTargetAdapter() {
            @Override
            public String requestType() {
                return "rollback.mysql";
            }

            @Override
            public ApprovalSnapshot snapshot(Long targetId, Map<String, Object> command) {
                return new ApprovalSnapshot(0L, BigDecimal.valueOf(100000), null, command, Map.of());
            }

            @Override
            public long currentVersion(Long targetId) {
                Quotation current = quotationMapper.selectByIdForUpdate(targetId);
                return current.getVersion() == null ? 0L : current.getVersion();
            }

            @Override
            public void validateBeforeRequest(ApprovalSnapshot snapshot) {
            }

            @Override
            public void applyApproved(ApprovalRequest request) {
                Quotation current = quotationMapper.selectByIdForUpdate(request.getTargetId());
                current.setStatus("受注");
                quotationMapper.updateById(current);
                throw new IllegalStateException("意図的なcommit前例外");
            }
        };

        ApprovalEngineServiceImpl engine = new ApprovalEngineServiceImpl(
                approvalRequestMapper, approvalActionMapper, approvalDelegationMapper,
                approvalDelegationTypeMapper, approvalParticipantMapper, sysUserMapper,
                routeResolver, notificationService, approvalNotificationService, objectMapper,
                List.of(failingAdapter));
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        ApprovalRequest request = transaction.execute(status -> engine.request(new ApprovalRequestCommand(
                "rollback.mysql", "QUOTATION", quotation.getId(), 0L, applicantId, null,
                BigDecimal.valueOf(100000), Map.of("status", "受注"), null,
                "rollback-request-" + suffix)));
        assertNotNull(request);

        assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status ->
                engine.approve(request.getId(), approverId, "実DB rollback")));

        ApprovalRequest storedRequest = approvalRequestMapper.selectById(request.getId());
        Quotation storedQuotation = quotationMapper.selectById(quotation.getId());
        long actionCount = approvalActionMapper.selectCount(new LambdaQueryWrapper<ApprovalAction>()
                .eq(ApprovalAction::getRequestId, request.getId()));

        assertEquals("in_review", storedRequest.getStatus(), "承認request状態がrollbackされること");
        assertEquals("下書き", storedQuotation.getStatus(), "adapterが変更した対象行がrollbackされること");
        assertEquals(0L, actionCount, "同一transaction内のapproval actionがrollbackされること");
    }

    private Long insertUser(String username) {
        SysUser user = SysUser.builder()
                .tenantId("default")
                .username(username)
                .password("x")
                .realName(username)
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private ChildOutput startChild(String mode, String lockName, Long outboxId, long startAt)
            throws IOException {
        String javaExecutable = javaExecutable();
        List<String> command = new ArrayList<>(List.of(
                javaExecutable,
                "-cp",
                System.getProperty("java.class.path"),
                ChildProcess.class.getName(),
                mode,
                MYSQL.getJdbcUrl(),
                MYSQL.getUsername(),
                MYSQL.getPassword(),
                lockName,
                outboxId == null ? "-" : String.valueOf(outboxId),
                String.valueOf(startAt)));
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        return new ChildOutput(process);
    }

    private String javaExecutable() {
        String executable = System.getProperty("java.home") + java.io.File.separator + "bin"
                + java.io.File.separator + (isWindows() ? "java.exe" : "java");
        return executable;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private String readLine(ChildOutput child, int timeoutSeconds) throws Exception {
        Object event = child.stdout.poll(timeoutSeconds, TimeUnit.SECONDS);
        if (event == null) {
            throw new AssertionError("子JVMの標準出力が時間内に届かないこと: " + childEvidence(child, List.of()));
        }
        if (ChildOutput.EOF == event) {
            return null;
        }
        return (String) event;
    }

    private List<String> finishChild(ChildOutput child, int timeoutSeconds) throws Exception {
        List<String> lines = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new AssertionError("子JVMの標準出力が時間内に終了しないこと: " + childEvidence(child, lines));
            }
            Object event = child.stdout.poll(remaining, TimeUnit.NANOSECONDS);
            if (event == null) {
                throw new AssertionError("子JVMの標準出力が時間内に終了しないこと: " + childEvidence(child, lines));
            }
            if (ChildOutput.EOF == event) {
                break;
            }
            lines.add((String) event);
        }
        if (!child.process.waitFor(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
            throw new AssertionError("子JVMが時間内に終了すること: " + childEvidence(child, lines));
        }
        assertEquals(0, child.process.exitValue(), "子JVMが成功終了すること: " + childEvidence(child, lines));
        return lines;
    }

    private void terminateChild(ChildOutput child) {
        if (child != null && child.process.isAlive()) {
            child.process.destroyForcibly();
            try {
                child.process.waitFor(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private String childEvidence(ChildOutput child, List<String> lines) {
        List<String> diagnostics;
        try {
            diagnostics = child.stderr.get(200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            diagnostics = List.of("stderr未完了: " + e.getClass().getSimpleName());
        }
        return "pid=" + child.process.pid() + ", protocol=" + lines + ", stderr=" + diagnostics;
    }

    private static final class ChildOutput {
        private static final Object EOF = new Object();
        private final Process process;
        private final BlockingQueue<Object> stdout = new LinkedBlockingQueue<>();
        private final CompletableFuture<List<String>> stderr;

        private ChildOutput(Process process) {
            this.process = process;
            CompletableFuture.runAsync(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stdout.put(line);
                    }
                } catch (Exception e) {
                    stdout.offer("STDOUT_READER_ERROR=" + e);
                } finally {
                    stdout.offer(EOF);
                }
            });
            stderr = CompletableFuture.supplyAsync(() -> {
                List<String> lines = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        lines.add(line);
                    }
                } catch (IOException e) {
                    lines.add("STDERR_READER_ERROR=" + e);
                }
                return lines;
            });
        }
    }

    /** 独立JVMでShedLock providerまたはclaim SQLを実行するchild entrypoint。 */
    public static final class ChildProcess {
        private ChildProcess() {
        }

        public static void main(String[] args) throws Exception {
            String mode = args[0];
            String jdbcUrl = args[1];
            String username = args[2];
            String password = args[3];
            String lockName = args[4];
            long outboxId = "-".equals(args[5]) ? -1L : Long.parseLong(args[5]);
            long startAt = Long.parseLong(args[6]);
            waitUntil(startAt);
            String pid = String.valueOf(ProcessHandle.current().pid());

            if (mode.startsWith("lock-")) {
                runLock(mode, jdbcUrl, username, password, lockName, pid);
            } else if ("claim".equals(mode)) {
                runClaim(jdbcUrl, username, password, outboxId, pid);
            } else {
                throw new IllegalArgumentException("unknown child mode: " + mode);
            }
        }

        private static void runLock(String mode, String jdbcUrl, String username,
                                     String password, String lockName, String pid) throws Exception {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, username, password);
            JdbcTemplateLockProvider provider = new JdbcTemplateLockProvider(
                    JdbcTemplateLockProvider.Configuration.builder()
                            .withJdbcTemplate(new JdbcTemplate(dataSource))
                            .usingDbTime()
                            .build());
            LockConfiguration configuration = new LockConfiguration(
                    Instant.now(), lockName, Duration.ofSeconds(20), Duration.ZERO);
            Optional<SimpleLock> acquired = provider.lock(configuration);
            if (acquired.isEmpty()) {
                System.out.println("LOCK_NOT_ACQUIRED pid=" + pid);
                return;
            }
            SimpleLock lock = acquired.orElseThrow();
            System.out.println("LOCK_ACQUIRED pid=" + pid);
            System.out.flush();
            if ("lock-holder".equals(mode)) {
                Thread.sleep(4000L);
            }
            lock.unlock();
            System.out.println("LOCK_RELEASED");
        }

        private static void runClaim(String jdbcUrl, String username, String password,
                                     long outboxId, String pid) throws Exception {
            try (Connection connection = java.sql.DriverManager.getConnection(jdbcUrl, username, password);
                 PreparedStatement statement = connection.prepareStatement(
                         "UPDATE t_notification_outbox SET status='PROCESSING', "
                                 + "locked_at=CURRENT_TIMESTAMP, attempt_count=COALESCE(attempt_count,0)+1 "
                                 + "WHERE id=? AND status IN ('PENDING','RETRY')")) {
                statement.setLong(1, outboxId);
                int updated = statement.executeUpdate();
                System.out.println("CLAIM_RESULT=" + updated + " pid=" + pid);
            }
        }

        private static void waitUntil(long startAt) throws InterruptedException {
            while (System.currentTimeMillis() < startAt) {
                Thread.sleep(Math.min(10L, startAt - System.currentTimeMillis()));
            }
        }
    }
}
