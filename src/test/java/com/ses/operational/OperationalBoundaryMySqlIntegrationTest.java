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
import org.testcontainers.containers.MySQLContainer;
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
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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
        Process holder = startChild("lock-holder", lockName, null, 0L);
        try (BufferedReader holderOutput = output(holder)) {
            String holderAcquired = holderOutput.readLine();
            assertTrue(holderAcquired != null && holderAcquired.startsWith("LOCK_ACQUIRED"),
                    "先行JVMがShedLockを取得すること: " + holderAcquired);

            Process contender = startChild("lock-contender", lockName, null,
                    System.currentTimeMillis() + 250L);
            List<String> contenderLines = readRemaining(contender, 15);
            assertProcessSucceeded(contender, contenderLines);
            assertTrue(contenderLines.stream().anyMatch(line -> line.startsWith("LOCK_NOT_ACQUIRED")),
                    "保持中の同一ShedLockを後発JVMが取得しないこと: " + contenderLines);
            assertTrue(contenderLines.stream().anyMatch(line -> line.contains("pid=")),
                    "競合結果に子JVMのpidを記録すること: " + contenderLines);

            List<String> holderLines = readRemaining(holderOutput, holder, 15);
            assertProcessSucceeded(holder, holderLines);
            assertTrue(holderLines.stream().anyMatch(line -> line.equals("LOCK_RELEASED")),
                    "先行JVMがShedLockを解放すること: " + holderLines);
        }

        Process afterRelease = startChild("lock-after-release", lockName, null, 0L);
        List<String> afterLines = readRemaining(afterRelease, 15);
        assertProcessSucceeded(afterRelease, afterLines);
        assertTrue(afterLines.stream().anyMatch(line -> line.startsWith("LOCK_ACQUIRED")),
                "解放後に後続JVMがShedLockを再取得すること: " + afterLines);
        assertTrue(afterLines.stream().anyMatch(line -> line.equals("LOCK_RELEASED")),
                "再取得したJVMがShedLockを解放すること: " + afterLines);
    }

    @Test
    void 独立JVM二つが同じoutbox行をclaimすると一つだけ成功する() throws Exception {
        String dedupeKey = "multi-jvm-claim-" + System.nanoTime();
        Notification notification = new Notification();
        notification.setType("SYSTEM");
        notification.setTitle("multi JVM claim test");
        notification.setMessage("loopback claim");
        notification.setLinkUrl("/approval/inbox");
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(LocalDateTime.now());
        notificationMapper.insert(notification);
        assertNotNull(notification.getId());

        NotificationOutbox outbox = NotificationOutbox.builder()
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
        Process first = startChild("claim", "-", outbox.getId(), startAt);
        Process second = startChild("claim", "-", outbox.getId(), startAt);
        List<String> firstLines = readRemaining(first, 15);
        List<String> secondLines = readRemaining(second, 15);
        assertProcessSucceeded(first, firstLines);
        assertProcessSucceeded(second, secondLines);

        List<String> all = new ArrayList<>();
        all.addAll(firstLines);
        all.addAll(secondLines);
        long claimed = all.stream().filter(line -> line.startsWith("CLAIM_RESULT=1")).count();
        long rejected = all.stream().filter(line -> line.startsWith("CLAIM_RESULT=0")).count();
        assertEquals(1L, claimed, "独立JVMのclaim成功は1件だけであること: " + all);
        assertEquals(1L, rejected, "独立JVMのclaim競合は0件更新になること: " + all);
        assertTrue(all.stream().map(this::pidFrom).distinct().count() == 2,
                "2つの異なる子JVMが実行されたこと: " + all);

        NotificationOutbox stored = outboxMapper.selectById(outbox.getId());
        assertEquals("PROCESSING", stored.getStatus());
        assertEquals(1, stored.getAttemptCount());
    }

    @Test
    void adapter適用後例外では実MySQLの承認action対象行requestがcommit前にrollbackされる() {
        String suffix = String.valueOf(System.nanoTime());
        Long applicantId = insertUser("rollback-applicant-" + suffix);
        Long approverId = insertUser("rollback-approver-" + suffix);

        Customer customer = Customer.builder().companyName("rollback-customer-" + suffix).build();
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
                .username(username)
                .password("x")
                .realName(username)
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private Process startChild(String mode, String lockName, Long outboxId, long startAt)
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
        return new ProcessBuilder(command).redirectErrorStream(true).start();
    }

    private String javaExecutable() {
        String executable = System.getProperty("java.home") + java.io.File.separator + "bin"
                + java.io.File.separator + (isWindows() ? "java.exe" : "java");
        return executable;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private BufferedReader output(Process process) {
        return new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    }

    private List<String> readRemaining(BufferedReader reader, Process process, int timeoutSeconds)
            throws IOException, InterruptedException {
        List<String> lines = readRemaining(reader, timeoutSeconds);
        assertTrue(process.waitFor(timeoutSeconds, TimeUnit.SECONDS),
                "子JVMが時間内に終了すること: " + lines);
        return lines;
    }

    private List<String> readRemaining(Process process, int timeoutSeconds)
            throws IOException, InterruptedException {
        try (BufferedReader reader = output(process)) {
            return readRemaining(reader, process, timeoutSeconds);
        }
    }

    private List<String> readRemaining(BufferedReader reader, int timeoutSeconds) throws IOException {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            lines.add(line);
        }
        return lines;
    }

    private void assertProcessSucceeded(Process process, List<String> lines) {
        assertEquals(0, process.exitValue(), "子JVMが成功終了すること: " + lines);
    }

    private String pidFrom(String line) {
        int index = line.indexOf("pid=");
        return index < 0 ? line : line.substring(index + 4).trim();
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
