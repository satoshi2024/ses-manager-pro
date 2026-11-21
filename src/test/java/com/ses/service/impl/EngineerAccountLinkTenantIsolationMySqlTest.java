package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.MySQLContainer;
import com.ses.test.MySqlTestImages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 実MySQLで要員アカウント連携のtenant境界とDB一意制約を検証する。 */
@SpringBootTest(properties = "app.security.oidc.tenant-id=")
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class EngineerAccountLinkTenantIsolationMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(MySqlTestImages.image())
            .withDatabaseName("ses_manager_engineer_link_tenant")
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

    @Autowired
    private EngineerAccountLinkService linkService;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    /**
     * 同時linkは、NF01 F2（細粒度epoch計画 §3.4）で 3 S{連携ユーザー} → 7 X{要員} の順に直列化される。後着は先着のcommit後に
     * 要員行のlockを得て、lock下の重複判定で 400 {@code error.engineerAccount.userAlreadyLinked} になる（DB一意制約まで
     * 届かない）。DB一意制約そのものは mapper の直接insertで確かめる。
     */
    @Test
    void tenant境界と同時linkの直列化とDB一意制約を検証する() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        Engineer engineerA = insertEngineer("mysql-link-a-" + suffix, "tenant-a");
        Engineer engineerB = insertEngineer("mysql-link-b-" + suffix, "tenant-b");
        SysUser userA = insertUser("mysql-link-user-a-" + suffix, "tenant-a");
        SysUser userB = insertUser("mysql-link-user-b-" + suffix, "tenant-b");

        AccountingTenantContextHolder.setTenantId("tenant-a");
        EngineerAccountLink created = linkService.link(engineerA.getId(), userA.getId(), null);
        assertThat(created.getTenantId()).isEqualTo("tenant-a");

        AccountingTenantContextHolder.setTenantId("tenant-b");
        assertThat(linkService.findEngineerIdByUserId(userA.getId())).isNull();
        assertThat(linkService.findByEngineerId(engineerA.getId())).isNull();
        linkService.unlinkByEngineerId(engineerA.getId());
        assertThatThrownBy(() -> linkService.link(engineerA.getId(), userB.getId(), null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> linkService.link(engineerB.getId(), userA.getId(), null))
                .isInstanceOf(BusinessException.class);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        Engineer concurrentEngineer = insertEngineer("mysql-link-concurrent-" + suffix, "tenant-a");
        SysUser concurrentUser = insertUser("mysql-link-concurrent-user-" + suffix, "tenant-a");
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                    assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
                    try {
                        AccountingTenantContextHolder.runWithTenant("tenant-a",
                                () -> linkService.link(concurrentEngineer.getId(), concurrentUser.getId(), null));
                        success.incrementAndGet();
                    } catch (BusinessException ex) {
                        // 後着は要員行のX lock下の重複判定で拒否される（並行のinsertはDB一意制約に届かない）。
                        assertThat(ex.getCode()).isEqualTo(400);
                        assertThat(ex.getMessageKey()).isEqualTo("error.engineerAccount.userAlreadyLinked");
                        conflict.incrementAndGet();
                    } finally {
                        AccountingTenantContextHolder.clear();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(success.get()).isEqualTo(1);
        assertThat(conflict.get()).isEqualTo(1);
        // データ上も1件だけが成立する。
        assertThat(engineerAccountLinkMapper.selectCount(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getEngineerId, concurrentEngineer.getId()))).isEqualTo(1L);
        assertThat(engineerAccountLinkMapper.selectCount(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, concurrentUser.getId()))).isEqualTo(1L);

        // DB一意制約（engineer_id・sys_user_id、V32）は最終防線として残っている。serviceの事前判定を経ない直接insertで確かめる。
        Engineer otherEngineer = insertEngineer("mysql-link-unique-" + suffix, "tenant-a");
        SysUser otherUser = insertUser("mysql-link-unique-user-" + suffix, "tenant-a");
        assertThatThrownBy(() -> engineerAccountLinkMapper.insert(rawLink(concurrentEngineer.getId(), otherUser.getId())))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> engineerAccountLinkMapper.insert(rawLink(otherEngineer.getId(), concurrentUser.getId())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    private static EngineerAccountLink rawLink(Long engineerId, Long sysUserId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setTenantId("tenant-a");
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        return link;
    }

    private Engineer insertEngineer(String name, String tenantId) {
        Engineer engineer = Engineer.builder().tenantId(tenantId).fullName(name)
                .employmentType("正社員").status("稼動中").build();
        engineerMapper.insert(engineer);
        return engineer;
    }

    private SysUser insertUser(String username, String tenantId) {
        String mysqlUsername = username.substring(0, Math.min(17, username.length()))
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 32);
        SysUser user = SysUser.builder().tenantId(tenantId).username(mysqlUsername).password("test")
                .realName(mysqlUsername).role("要員").status(1).build();
        sysUserMapper.insert(user);
        return user;
    }
}
