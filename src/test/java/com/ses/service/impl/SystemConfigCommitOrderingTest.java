package com.ses.service.impl;

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

    @Test
    void cache失効callback実行中の読み取りはデフォルト値へフォールバックしない() throws Exception {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.executeWithoutResult(status -> systemConfigService.put("company_name", "Before", "会社名"));
        assertEquals("Before", systemConfigService.getString("company_name", "DEFAULT"));

        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var transaction = executor.submit(() -> template.executeWithoutResult(status -> {
                systemConfigService.put("company_name", "After", "会社名");
                // service callbackの後に実行され、callback連鎖を一時停止する。
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        callbackEntered.countDown();
                        assertTrue(await(releaseCallback));
                    }
                });
            }));

            assertTrue(await(callbackEntered));
            var reader = executor.submit(() -> systemConfigService.getString("company_name", "DEFAULT"));
            assertEquals("After", reader.get(10, TimeUnit.SECONDS),
                    "cache失効の中間状態を外部へ公開せずDBの最新値を返す");

            releaseCallback.countDown();
            transaction.get(15, TimeUnit.SECONDS);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
        }
    }
}
