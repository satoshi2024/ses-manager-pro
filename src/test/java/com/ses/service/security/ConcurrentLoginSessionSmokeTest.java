package com.ses.service.security;

import com.ses.config.LoginUser;
import com.ses.entity.SysUser;
import com.ses.entity.UserSession;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** R3-001: 永続session登録のMySQL lock回帰を実DBで検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ConcurrentLoginSessionSmokeTest {

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("ses")
            .withPassword("ses");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", MYSQL::getDriverClassName);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("app.security.session.max-concurrent-sessions", () -> "5");
    }

    @Autowired
    private PersistentSessionService persistentSessionService;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private UserSessionMapper userSessionMapper;

    @Test
    void 異なる25ユーザーを同時登録できる() throws Exception {
        List<SysUser> users = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            users.add(insertUser("r3_parallel_" + i));
        }

        runConcurrent(users);

        LocalDateTime now = LocalDateTime.now();
        for (SysUser user : users) {
            assertEquals(1, userSessionMapper.selectActiveByUser("default", user.getId(), now).size(),
                    "各ユーザーにactive sessionが1件だけ存在すること");
        }
    }

    @Test
    void 同一ユーザーの6同時登録でもactiveは5件になる() throws Exception {
        SysUser user = insertUser("r3_same_user");

        runConcurrent(java.util.Collections.nCopies(6, user));

        assertEquals(5, userSessionMapper.selectActiveByUser(
                "default", user.getId(), LocalDateTime.now()).size());
    }

    private SysUser insertUser(String username) {
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword("test-password");
        user.setRealName("同時ログイン検証");
        user.setRole("営業");
        user.setStatus(1);
        sysUserMapper.insert(user);
        return user;
    }

    private void runConcurrent(List<SysUser> users) throws Exception {
        int threads = users.size();
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < users.size(); i++) {
            SysUser user = users.get(i);
            int workerId = i;
            futures.add(executor.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS), "開始barrierを待機できること");
                try {
                    LoginUser principal = new LoginUser(user, List.of());
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(principal, null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    MockHttpServletRequest request = new MockHttpServletRequest();
                    request.setRemoteAddr("127.0.0." + ((workerId % 200) + 1));
                    persistentSessionService.register(request, authentication);
                } finally {
                    SecurityContextHolder.clearContext();
                }
                return null;
            }));
        }
        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS), "全workerがbarrierへ到達すること");
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
