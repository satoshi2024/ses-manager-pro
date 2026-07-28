package com.ses.service.impl;

import com.ses.entity.SystemConfig;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.Ordered;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 二つのcommit後callbackが逆順に実行されても最新設定を再読込することを検証する。 */
@SpringBootTest
@ActiveProfiles("test")
class SystemConfigCommitOrderingTest {

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private SystemConfigMapper systemConfigMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private boolean await(CountDownLatch latch) {
        try {
            return latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("テストスレッドが中断された", e);
        }
    }

    @Test
    void 二つのcommit後callbackが逆順でも最新DB値をcacheへ再読込する() throws Exception {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> systemConfigService.put("company_name", "Old", "会社名"));
        assertEquals("Old", systemConfigMapper.selectById("company_name").getConfigValue());
        assertEquals("Old", systemConfigService.getString("company_name", ""));

        CountDownLatch transactionAAfterCommitPaused = new CountDownLatch(1);
        CountDownLatch releaseTransactionA = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var transactionA = executor.submit(() -> template.executeWithoutResult(status -> {
                // AのDB commit後、service callbackより先に停止する。Bはこの間にcommitできる。
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public int getOrder() {
                        return Ordered.HIGHEST_PRECEDENCE;
                    }

                    @Override
                    public void afterCommit() {
                        transactionAAfterCommitPaused.countDown();
                        assertTrue(await(releaseTransactionA));
                    }
                });
                systemConfigService.put("company_name", "A", "会社名");
            }));
            assertTrue(await(transactionAAfterCommitPaused));

            var transactionB = executor.submit(() ->
                    template.executeWithoutResult(status -> systemConfigService.put("company_name", "B", "会社名")));
            transactionB.get(15, TimeUnit.SECONDS);
            assertEquals("B", systemConfigMapper.selectById("company_name").getConfigValue(),
                    "Bのcommit後はDBがBである");

            releaseTransactionA.countDown();
            transactionA.get(15, TimeUnit.SECONDS);
        } finally {
            releaseTransactionA.countDown();
            executor.shutdownNow();
        }

        assertEquals("B", systemConfigMapper.selectById("company_name").getConfigValue());
        assertEquals("B", systemConfigService.getString("company_name", ""),
                "逆順callback後もcache missから最新DB値を読む");
    }
}
