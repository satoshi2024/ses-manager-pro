package com.ses.service.lifecycle;

import com.ses.dto.lifecycle.ResignationGateResultDto;
import com.ses.entity.*;
import com.ses.mapper.*;
import com.ses.service.*;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.provider.ExternalAccountProviderClient;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static com.ses.service.ExternalAccountRevocationStates.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** M10 / §5.13: 実MySQLの完了commit、全法人登録、送信CAS、再入社とpoll除外。Runner専用。 */
@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {"app.features.nf01.enabled=true", "app.security.oidc.tenant-id="})
@ActiveProfiles("test")
class ResignationRevocationOutboxMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_revoke_outbox").withUsername("root").withPassword("ses");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }
    @Autowired LifecycleCaseService lifecycle;
    @Autowired ExternalAccountRevocationOutbox outbox;
    @Autowired ExternalAccountRevocationDispatcher dispatcher;
    @Autowired ExternalAccountService accounts;
    @Autowired ExternalAccountReferenceMapper references;
    @Autowired ExternalAccountSystemMapper systems;
    @Autowired EngineerMapper engineers;
    @Autowired SysUserMapper users;
    @Autowired OrganizationUnitMapper organizations;
    @Autowired UserOrganizationMapper assignments;
    @Autowired LifecycleCaseMapper cases;
    @Autowired PlatformTransactionManager manager;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockBean ExternalAccountProviderClient provider;
    // 完了とoutboxのcommit境界を対象とし、個別の退社ゲート項目はU10/既存回帰で検証する。
    @MockBean ResignationGateChecker gate;
    @MockBean com.ses.service.security.LegalEntityContextService legalContext;
    String tenant;
    Engineer engineer;
    ExternalAccountSystem system;
    SysUser actor;

    @BeforeEach void setup() {
        tenant = "outbox-mysql-" + UUID.randomUUID();
        AccountingTenantContextHolder.setTenantId(tenant);
        lenient().when(legalContext.requireTenantId()).thenReturn(tenant);
        lenient().when(legalContext.requireCurrentLegalEntityId()).thenReturn(1L);
        actor = SysUser.builder().tenantId(tenant).username("out-" + UUID.randomUUID()).password("N/A")
                .realName("失効要求検証").role("HR").status(1).build(); users.insert(actor);
        OrganizationUnit organization = OrganizationUnit.builder().tenantId(1L).legalEntityId(1L)
                .code("OUT-" + UUID.randomUUID()).name("法人A").type("法人")
                .validFrom(LocalDate.now().minusYears(1)).status("有効").version(0).build(); organizations.insert(organization);
        assignments.insert(UserOrganization.builder().tenantId(tenant).userId(actor.getId()).organizationId(organization.getId())
                .primaryFlag(1).validFrom(LocalDate.now().minusYears(1)).version(0).build());
        engineer = Engineer.builder().tenantId(tenant).legalEntityId(2L).fullName("失効要求検証").employmentType("正社員").status("Bench").build(); engineers.insert(engineer);
        system = ExternalAccountSystem.builder().systemCode("OUT-" + UUID.randomUUID()).systemName("検証用").systemType("IDP").build(); systems.insert(system);
        when(gate.evaluate(any(), any())).thenReturn(ResignationGateResultDto.builder().passed(true).build());
        lenient().when(provider.requestRevoke(any())).thenReturn(true);
    }
    @AfterEach void clear() { AccountingTenantContextHolder.clear(); }

    @Test void 外側transactionなしの完了経路は実connectionでREAD_COMMITTEDを使う() {
        ExternalAccountReference row = account(2L);
        LifecycleCase resignation = createCase("RESIGNATION", "ACTIVE");
        when(gate.evaluate(any(), any())).thenAnswer(invocation -> {
            assertThat(jdbc.queryForObject("SELECT @@transaction_isolation", String.class)).isEqualTo("READ-COMMITTED");
            verify(provider, never()).requestRevoke(any());
            return ResignationGateResultDto.builder().passed(true).build();
        });
        lifecycle.completeCase(resignation.getId(), actor.getId());
        assertThat(references.selectById(row.getId()).getExternalSyncStatus()).isEqualTo(SENT);
    }

    @Test void 完了と全法人の要求登録は同じcommitに入り送信はcommit後だけ行う() {
        ExternalAccountReference a = account(1L), b = account(2L);
        LifecycleCase resignation = createCase("RESIGNATION", "ACTIVE");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            lifecycle.completeCase(resignation.getId(), actor.getId());
            assertThat(cases.selectById(resignation.getId()).getStatus()).isEqualTo("COMPLETED");
            assertThat(references.selectById(a.getId()).getExternalSyncStatus()).isEqualTo(REQUESTED);
            assertThat(references.selectById(b.getId()).getExternalSyncStatus()).isEqualTo(REQUESTED);
            verify(provider, never()).requestRevoke(any());
        });
        verify(provider, times(2)).requestRevoke(any());
        assertThat(references.selectById(a.getId()).getLegalEntityId()).isEqualTo(1L);
        assertThat(references.selectById(b.getId()).getExternalSyncStatus()).isEqualTo(SENT);
    }

    @Test void 完了rollbackは要求と履歴も取り消し送信しない() {
        ExternalAccountReference row = account(2L);
        LifecycleCase resignation = createCase("RESIGNATION", "ACTIVE");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            lifecycle.completeCase(resignation.getId(), actor.getId());
            status.setRollbackOnly();
        });
        assertThat(cases.selectById(resignation.getId()).getStatus()).isEqualTo("ACTIVE");
        assertThat(references.selectById(row.getId()).getIdempotencyKey()).isNull();
        verify(provider, never()).requestRevoke(any());
    }

    @Test void 同じ候補を二接続で送信してもCAS取得者だけがproviderを呼ぶ() throws Exception {
        ExternalAccountReference row = account(2L);
        seedQueue(row, REQUESTED, "PENDING_CONFIRMATION", LocalDateTime.now().minusSeconds(1));
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(provider.requestRevoke(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return true;
        });
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = pool.submit(() -> AccountingTenantContextHolder.runWithTenant(tenant, () -> dispatcher.dispatchPending()));
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            Future<Integer> second = pool.submit(() -> AccountingTenantContextHolder.runWithTenant(tenant, () -> dispatcher.dispatchPending()));
            assertThat(second.get(20, TimeUnit.SECONDS)).isZero();
            release.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            verify(provider, times(1)).requestRevoke(any());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void 登録後送信前の停止は回収するが非冪等providerのACK前停止はUNKNOWNにする() {
        ExternalAccountReference requested = account(1L), sending = account(2L);
        seedQueue(requested, REQUESTED, "PENDING_CONFIRMATION", LocalDateTime.now().minusMinutes(1));
        seedQueue(sending, SENDING, "PENDING_CONFIRMATION", LocalDateTime.now().minusMinutes(1));
        dispatcher.dispatchPending();
        assertThat(references.selectById(requested.getId()).getExternalSyncStatus()).isEqualTo(SENT);
        assertThat(references.selectById(sending.getId()).getExternalSyncStatus()).isEqualTo(UNKNOWN);
        verify(provider, times(1)).requestRevoke(argThat(r -> requested.getId().equals(r.getId())));
        verify(provider, never()).requestRevoke(argThat(r -> sending.getId().equals(r.getId())));
    }

    @Test void 同じ未送信snapshotの同時CASは一方だけが成功する() throws Exception {
        ExternalAccountReference row = account(2L);
        seedQueue(row, REQUESTED, "PENDING_CONFIRMATION", LocalDateTime.now().minusMinutes(1));
        ExternalAccountReference snapshot = references.selectById(row.getId());
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> claimant = () -> AccountingTenantContextHolder.runWithTenant(tenant, () -> {
                try { start.await(20, TimeUnit.SECONDS); }
                catch (Exception ex) { throw new IllegalStateException(ex); }
                ExternalAccountReference claimed = outbox.claim(snapshot, LocalDateTime.now().plusMinutes(1));
                if (claimed == null) return 0;
                provider.requestRevoke(claimed);
                return 1;
            });
            Future<Integer> first = pool.submit(claimant), second = pool.submit(claimant);
            assertThat(first.get(20, TimeUnit.SECONDS) + second.get(20, TimeUnit.SECONDS)).isEqualTo(1);
            verify(provider, times(1)).requestRevoke(any());
            assertThat(references.selectById(row.getId()).getExternalSyncStatus()).isEqualTo(SENDING);
        } finally { pool.shutdownNow(); }
    }

    @Test void pollの三SQLは未送信状態を変更しない() {
        for (String state : List.of(REQUESTED, SENDING, FAILED)) {
            ExternalAccountReference row = account(1L);
            seedQueue(row, state, "PENDING_CONFIRMATION", LocalDateTime.now().minusMinutes(1));
            var stored = references.selectById(row.getId());
            assertThat(references.selectPendingForTenant(tenant, LocalDateTime.now())).noneMatch(r -> r.getId().equals(row.getId()));
            assertThat(references.claimRevokePoll(row.getId(), stored.getVersion(), LocalDateTime.now(),
                    LocalDateTime.now().plusMinutes(1), tenant, 1L)).isZero();
            assertThat(references.completeRevokePollWithCas(row.getId(), "UNKNOWN", 1, LocalDateTime.now(),
                    "SYNC_FAILED", "synthetic", tenant, 1L, stored.getVersion())).isZero();
            assertThat(references.selectById(row.getId()).getExternalSyncStatus()).isEqualTo(state);
        }
    }

    @Test void 再入社後の事後登録を撤権せずUNKNOWNの再要求は新しいgenerationになる() {
        // このテストの登録操作だけ対象法人Bを選択する。完了テストのHRは法人Aに所属する。
        when(legalContext.requireCurrentLegalEntityId()).thenReturn(2L);
        LifecycleCase resignation = createCase("RESIGNATION", "COMPLETED");
        assertThat(cases.selectLatestCompletedResignationWithoutReentry(tenant, engineer.getId()).getId()).isEqualTo(resignation.getId());
        ExternalAccountReference late = accounts.registerAccountReference(system.getId(), "synthetic-late@example.invalid",
                "ENGINEER", engineer.getId(), "MEMBER", actor.getId());
        assertThat(references.selectById(late.getId()).getIdempotencyKey()).endsWith(":" + late.getId() + ":g1");
        createCase("JOIN", "DRAFT");
        ExternalAccountReference reentry = accounts.registerAccountReference(system.getId(), "synthetic-reentry@example.invalid",
                "ENGINEER", engineer.getId(), "MEMBER", actor.getId());
        assertThat(references.selectById(reentry.getId()).getIdempotencyKey()).isNull();
        ExternalAccountReference unknown = account(1L);
        seedQueue(unknown, UNKNOWN, "UNKNOWN", LocalDateTime.now().plusDays(1));
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            outbox.reRequestUnknown(unknown.getId(), tenant, 1L, actor.getId());
            assertThat(references.selectById(unknown.getId()).getIdempotencyKey())
                    .isEqualTo("lifecycle-resign:100:" + unknown.getId() + ":g2");
            status.setRollbackOnly();
        });
    }

    private ExternalAccountReference account(Long legal) {
        ExternalAccountReference row = ExternalAccountReference.builder().tenantId(tenant).legalEntityId(legal).systemId(system.getId())
                .assigneeType("ENGINEER").assigneeId(engineer.getId()).status("ACTIVE").accountIdentifier("synthetic@example.invalid").build();
        references.insert(row); return row;
    }
    private void seedQueue(ExternalAccountReference row, String state, String status, LocalDateTime next) {
        references.update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ExternalAccountReference>()
                .eq(ExternalAccountReference::getId, row.getId()).set(ExternalAccountReference::getIdempotencyKey, "lifecycle-resign:100:" + row.getId() + ":g1")
                .set(ExternalAccountReference::getStatus, status).set(ExternalAccountReference::getExternalSyncStatus, state)
                .set(ExternalAccountReference::getNextRetryAt, next));
    }
    private LifecycleCase createCase(String type, String status) {
        LifecycleCase row = LifecycleCase.builder().tenantId(tenant).caseNo("OUT-" + UUID.randomUUID()).lifecycleType(type)
                .engineerId(engineer.getId()).templateId(1L).templateVersion(1).anchorDate(LocalDate.now()).status(status)
                .title("失効要求検証").applicantUserId(actor.getId()).engineerSnapshotJson("{}").build();
        cases.insert(row); return row;
    }
}
