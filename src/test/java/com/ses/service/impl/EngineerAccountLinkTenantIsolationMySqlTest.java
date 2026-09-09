package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.config.LoginUser;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
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

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void tenant境界と同時linkのDB一意制約を検証する() throws Exception {
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
                        assertThat(ex.getCode()).isEqualTo(409);
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
