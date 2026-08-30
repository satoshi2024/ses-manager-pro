package com.ses.service;

import com.ses.BaseIntegrationTest;
import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;
import com.ses.mapper.AssetAssignmentMapper;
import com.ses.mapper.AssetMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Asset Assignment Concurrency Test (並行貸与期間重複排他テスト)")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AssetAssignmentConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private AssetService assetService;

    @Autowired
    private AssetAssignmentService assetAssignmentService;

    @Autowired
    private AssetMapper assetMapper;

    @Autowired
    private AssetAssignmentMapper assetAssignmentMapper;

    @Test
    @DisplayName("Concurrent assignment requests for the same asset: exactly 1 succeeds, others rejected")
    void testConcurrentAssignmentOnSameAsset() throws Exception {
        // 1. 保管中の資産を1件作成
        Asset asset = Asset.builder()
                .assetTag("AST-CONCURRENCY-001")
                .assetName("ThinkPad X1 Carbon")
                .category("PC")
                .status("IN_STOCK")
                .build();
        assetService.createAsset(asset, 1L);
        Long assetId = asset.getId();

        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<String> failureReasons = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final long engineerId = 1000L + i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // 全スレッド同時に開始
                    assetAssignmentService.createAssignment(
                            assetId,
                            "ENGINEER",
                            engineerId,
                            LocalDate.now(),
                            LocalDate.now().plusMonths(6),
                            null,
                            "並行貸与テスト",
                            1L
                    );
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                    failureReasons.add(e.getMessage());
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // 一斉スタート
        boolean finished = finishLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(finished).isTrue();
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failureCount.get()).isEqualTo(threadCount - 1);

        // 資産ステータスが ASSIGNED になっていること
        Asset currentAsset = assetMapper.selectById(assetId);
        assertThat(currentAsset.getStatus()).isEqualTo("ASSIGNED");

        // 貸与レコードが1件だけ作成されていること
        List<AssetAssignment> assignments = assetAssignmentService.getAssignmentHistoryByAssetId(assetId);
        assertThat(assignments).hasSize(1);
        assertThat(assignments.get(0).getStatus()).isEqualTo("ACTIVE");

        // クリーンアップ
        assetAssignmentMapper.deleteById(assignments.get(0).getId());
        assetMapper.deleteById(assetId);
    }
}
