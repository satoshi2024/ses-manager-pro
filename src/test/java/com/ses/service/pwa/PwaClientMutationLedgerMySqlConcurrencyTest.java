package com.ses.service.pwa;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ses.common.exception.PwaConflictException;
import com.ses.entity.PwaClientMutation;
import com.ses.mapper.PwaClientMutationMapper;
import com.ses.test.MySQLContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 実MySQLで同一clientRequestIdの二writerを競合させ、DB UNIQUEとclaim transactionを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Tag("mysql")
@Testcontainers(disabledWithoutDocker = true)
class PwaClientMutationLedgerMySqlConcurrencyTest {

    private static final String REQUEST_ID = "pwa-mysql-race-1";
    private static final String SCOPE = "opaque-scope-mysql";

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ses_manager_pwa_ledger")
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
    private PwaClientMutationLedgerService ledger;
    @Autowired
    private PwaClientMutationMapper mapper;
    @Autowired
    private PwaCanonicalizer canonicalizer;
    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PwaUserContextService userContextService;

    @AfterEach
    void cleanup() {
        mapper.delete(new QueryWrapper<PwaClientMutation>()
                .eq("client_request_id", REQUEST_ID));
    }

    @Test
    void 同一ID同一hashの二writerは一件だけclaimし敗者は409になる() throws Exception {
        ObjectNode payload = objectMapper.createObjectNode().put("contractId", 10);
        PwaMutationCommand command = new PwaMutationCommand("timesheet", "2026-08", 0, payload);
        String hash = command.payloadHash(canonicalizer);
        PwaUserContextService.CurrentContext context = new PwaUserContextService.CurrentContext(
                700001L, 900001L, SCOPE, Instant.now().minusSeconds(60));
        when(userContextService.assertCurrent(SCOPE)).thenReturn(context);
        when(userContextService.hashScope(SCOPE)).thenReturn("b".repeat(64));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<PwaClientMutationLedgerService.Claim> claims = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        long createdAt = Instant.now().toEpochMilli();
        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await(10, TimeUnit.SECONDS);
                    claims.add(ledger.claim(command, REQUEST_ID, hash, createdAt, SCOPE));
                } catch (Throwable e) {
                    failures.add(e);
                }
            });
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();
        executor.shutdown();
        assertThat(executor.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(failures).hasSize(1)
                .first().isInstanceOf(PwaConflictException.class);
        assertThat(claims).hasSize(1);
        assertThat(mapper.selectByUserAndClientRequest(700001L, REQUEST_ID)).isNotNull();
    }
}
