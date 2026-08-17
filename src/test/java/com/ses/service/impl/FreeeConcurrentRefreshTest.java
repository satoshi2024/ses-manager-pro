package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.service.FreeeIntegrationService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.ses.test.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HFP-01-REV-004: 並行refreshの自動test（実MySQL + 実HTTP）。
 *
 * <p>2 threadが同時に{@code refresh()}（REQUIRES_NEW + SELECT FOR UPDATE）を実行しても、
 * 同一refresh tokenの外部使用（token endpoint POST）は1回だけであり、
 * 新refresh tokenがDBへrotation保存されることを検証する（R12-2 / AC04）。</p>
 */
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("REV-004: 並行refreshの外部使用は1回（実MySQL）")
class FreeeConcurrentRefreshTest {

    private static final AtomicInteger TOKEN_POSTS = new AtomicInteger();

    private static final HttpServer OAUTH_SERVER;

    static {
        try {
            OAUTH_SERVER = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            OAUTH_SERVER.createContext("/public_api/token", exchange -> {
                TOKEN_POSTS.incrementAndGet();
                byte[] body = ("{\"access_token\":\"fixture-access-token-2\","
                        + "\"refresh_token\":\"fixture-refresh-token-2\",\"expires_in\":3600}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var os = exchange.getResponseBody()) {
                    os.write(body);
                }
            });
            OAUTH_SERVER.start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_db")
            .withUsername("root")
            .withPassword("ses")
            .withStartupTimeout(Duration.ofMinutes(10))
            .withStartupAttempts(3);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
        registry.add("freee.oauth-base-url",
                () -> "http://127.0.0.1:" + OAUTH_SERVER.getAddress().getPort() + "/public_api");
        registry.add("freee.client-id", () -> "fixture-client-id");
        registry.add("freee.client-secret", () -> "fixture-client-secret");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private FreeeIntegrationService service;

    @Test
    @DisplayName("並行refreshの外部POSTは1回でrotationが保存される")
    void 並行refreshの外部POSTは1回でrotation保存される() throws Exception {
        jdbcTemplate.update("DELETE FROM t_freee_connection");
        Object target = org.springframework.test.util.AopTestUtils.getTargetObject(service);
        Method encrypt = target.getClass().getDeclaredMethod("encrypt", String.class);
        encrypt.setAccessible(true);
        String access = (String) encrypt.invoke(target, "fixture-access-token-1");
        String refresh = (String) encrypt.invoke(target, "fixture-refresh-token-1");
        jdbcTemplate.update("INSERT INTO t_freee_connection "
                + "(company_id, company_name, access_token_encrypted, refresh_token_encrypted, "
                + "token_expires_at, connection_status) "
                + "VALUES (123, 'テスト事業所', ?, ?, DATE_SUB(CURRENT_TIMESTAMP, INTERVAL 1 MINUTE), 'CONNECTED')",
                access, refresh);

        TOKEN_POSTS.set(0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        Runnable task = () -> {
            ready.countDown();
            try {
                go.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                service.refresh();
            } catch (BusinessException ignored) {
                // 状態遷移はDBで検証する
            }
        };
        Thread t1 = new Thread(task, "refresh-1");
        Thread t2 = new Thread(task, "refresh-2");
        t1.start();
        t2.start();
        assertTrue(ready.await(10, TimeUnit.SECONDS), "2 threadが開始待ちに到達すること");
        go.countDown();
        t1.join(30_000);
        t2.join(30_000);
        assertTrue(!t1.isAlive() && !t2.isAlive(), "2 threadが30秒以内に終了すること");

        assertEquals(1, TOKEN_POSTS.get(), "同一refresh tokenの外部使用は1回だけ（R03-3/AC04）");

        String storedRefresh = jdbcTemplate.queryForObject(
                "SELECT refresh_token_encrypted FROM t_freee_connection", String.class);
        Method decrypt = target.getClass().getDeclaredMethod("decrypt", String.class);
        decrypt.setAccessible(true);
        assertEquals("fixture-refresh-token-2", decrypt.invoke(target, storedRefresh),
                "新refresh tokenへrotation保存されていること");
        String status = jdbcTemplate.queryForObject(
                "SELECT connection_status FROM t_freee_connection", String.class);
        assertEquals("CONNECTED", status, "refresh成功後はCONNECTEDのまま");
    }
}
