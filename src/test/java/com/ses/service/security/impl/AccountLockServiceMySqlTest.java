package com.ses.service.security.impl;

import com.ses.service.security.AccountLockService;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 実MySQLの行ロック下でアカウント失敗回数とロック境界を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class AccountLockServiceMySqlTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
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

    @Autowired private AccountLockService accountLockService;
    @Autowired private JdbcTemplate jdbc;

    private String username;
    private Long userId;

    @BeforeEach
    void createUser() {
        username = "account-lock-" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO sys_user (tenant_id, username, password, role, status, failed_count, deleted_flag) "
                + "VALUES ('lock-test', ?, 'unused', '要員', 1, 0, 0)", username);
        userId = jdbc.queryForObject("SELECT id FROM sys_user WHERE username = ?", Long.class, username);
    }

    @AfterEach
    void deleteUser() {
        if (userId != null) jdbc.update("DELETE FROM sys_user WHERE id = ?", userId);
    }

    @Test
    void 失敗1回目から4回目までは未ロックで5回目にロックし6回目も期限を維持する() {
        for (int failure = 1; failure <= 4; failure++) {
            accountLockService.onLoginFailure(username);
            assertThat(failedCount()).isEqualTo(failure);
            assertThat(lockedUntil()).isNull();
        }

        accountLockService.onLoginFailure(username);
        assertThat(failedCount()).isZero();
        LocalDateTime originalLock = lockedUntil();
        assertThat(originalLock).isNotNull();

        accountLockService.onLoginFailure(username);
        assertThat(failedCount()).isEqualTo(1);
        assertThat(lockedUntil()).isEqualTo(originalLock);
    }

    @Test
    void 同一ユーザーへの並行失敗は行ロックで更新消失を防ぎ閾値を一度だけ越える() throws Exception {
        int attempts = 16;
        var ready = new CountDownLatch(attempts);
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(attempts);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("開始バリアがタイムアウトしました");
                    accountLockService.onLoginFailure(username);
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            pool.shutdown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            start.countDown();
            pool.shutdownNow();
        }

        assertThat(failedCount()).isEqualTo(attempts - AccountLockService.MAX_FAILED);
        assertThat(lockedUntil()).isNotNull();
    }

    private int failedCount() {
        return jdbc.queryForObject("SELECT failed_count FROM sys_user WHERE id = ?", Integer.class, userId);
    }

    private LocalDateTime lockedUntil() {
        return jdbc.queryForObject("SELECT locked_until FROM sys_user WHERE id = ?", LocalDateTime.class, userId);
    }
}
