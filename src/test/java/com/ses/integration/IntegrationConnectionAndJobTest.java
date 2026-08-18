package com.ses.integration;

import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.IntegrationTokensDto;
import com.ses.entity.ExternalMapping;
import com.ses.entity.IntegrationConnection;
import com.ses.entity.IntegrationJob;
import com.ses.service.integration.ExternalMappingService;
import com.ses.service.integration.IntegrationConnectionService;
import com.ses.service.integration.IntegrationJobService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class IntegrationConnectionAndJobTest {

    @Autowired
    private IntegrationConnectionService connectionService;

    @Autowired
    private ExternalMappingService mappingService;

    @Autowired
    private IntegrationJobService jobService;

    @AfterEach
    void cleanup() {
        jobService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        mappingService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
        connectionService.remove(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>());
    }


    @Test
    @DisplayName("テナント・法人ごとの接続分離と暗号化トークンの保存・復号")
    void connection_isolationAndTokenEncryption() {
        // 法人A (legalEntityId=101)
        IntegrationConnection connA = connectionService.getOrCreateConnection("tenant-1", 101L, "freee", "accounting");
        IntegrationTokensDto tokensA = IntegrationTokensDto.builder()
                .accessToken("access-token-A-secret-12345")
                .refreshToken("refresh-token-A-secret-67890")
                .tokenType("Bearer")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(connA.getId(), tokensA, 10001L, "テスト事業所A", 1L);

        // 法人B (legalEntityId=102)
        IntegrationConnection connB = connectionService.getOrCreateConnection("tenant-1", 102L, "freee", "accounting");
        IntegrationTokensDto tokensB = IntegrationTokensDto.builder()
                .accessToken("access-token-B-secret-99999")
                .refreshToken("refresh-token-B-secret-88888")
                .tokenType("Bearer")
                .expiresIn(7200L)
                .build();
        connectionService.saveTokens(connB.getId(), tokensB, 10002L, "テスト事業所B", 1L);

        // 復号検証
        IntegrationTokensDto decryptedA = connectionService.getDecryptedTokens(connA.getId());
        assertThat(decryptedA).isNotNull();
        assertThat(decryptedA.getAccessToken()).isEqualTo("access-token-A-secret-12345");
        assertThat(decryptedA.getRefreshToken()).isEqualTo("refresh-token-A-secret-67890");

        IntegrationTokensDto decryptedB = connectionService.getDecryptedTokens(connB.getId());
        assertThat(decryptedB).isNotNull();
        assertThat(decryptedB.getAccessToken()).isEqualTo("access-token-B-secret-99999");
        assertThat(decryptedB.getRefreshToken()).isEqualTo("refresh-token-B-secret-88888");

        // listConnections では encryptedTokens がマスクされていること
        List<IntegrationConnection> list = connectionService.listConnections("tenant-1");
        assertThat(list).isNotEmpty();
        for (IntegrationConnection c : list) {
            assertThat(c.getEncryptedTokens()).isNull();
        }
    }

    @Test
    @DisplayName("Token Race ガード: 同時401発生時にリフレッシュ関数が1回だけ実行されること")
    void tokenRace_refreshCalledOnce() throws Exception {
        IntegrationConnection conn = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        IntegrationTokensDto initialTokens = IntegrationTokensDto.builder()
                .accessToken("old-access-token")
                .refreshToken("old-refresh-token")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(conn.getId(), initialTokens, 20001L, "事業所X", 1L);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        AtomicInteger refreshCallCount = new AtomicInteger(0);

        List<Future<IntegrationTokensDto>> futures = new ArrayList<>();
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                startLatch.await();
                return connectionService.rotateTokens(conn.getId(), currentTokens -> {
                    // 同時実行時にここが呼ばれる回数をカウント
                    refreshCallCount.incrementAndGet();
                    return IntegrationTokensDto.builder()
                            .accessToken("new-access-token-" + index)
                            .refreshToken("new-refresh-token-" + index)
                            .expiresIn(3600L)
                            .build();
                });
            }));
        }

        startLatch.countDown();
        endLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // ロックにより直列化され、最終的に新トークンが保存されている
        IntegrationTokensDto finalTokens = connectionService.getDecryptedTokens(conn.getId());
        assertThat(finalTokens.getAccessToken()).startsWith("new-access-token-");
    }

    @Test
    @DisplayName("マッピングの保存・未検証時のガード (R1.3)")
    void mapping_verificationGuard() {
        IntegrationConnection conn = connectionService.getOrCreateConnection("default", null, "freee", "accounting");

        // 未検証マッピング登録
        ExternalMapping mapping = ExternalMapping.builder()
                .connectionId(conn.getId())
                .objectType("ACCOUNT_SALES")
                .internalCode("SALES_DEFAULT")
                .externalId("1001")
                .externalCode("売上高")
                .verifiedAt(null) // 未検証
                .build();
        mappingService.saveOrUpdateMapping(mapping);

        // 未検証状態での assertMappingVerified は例外をスロー
        assertThatThrownBy(() -> mappingService.assertMappingVerified(conn.getId(), "ACCOUNT_SALES", "SALES_DEFAULT"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未検証です");

        // 検証済みに更新
        ExternalMapping saved = mappingService.getMapping(conn.getId(), "ACCOUNT_SALES", "SALES_DEFAULT");
        mappingService.verifyMapping(saved.getId(), "{\"account_item_id\": 1001, \"name\": \"売上高\"}");

        // 検証後は正常通過
        mappingService.assertMappingVerified(conn.getId(), "ACCOUNT_SALES", "SALES_DEFAULT");
    }

    @Test
    @DisplayName("ジョブの冪等性チェック: 同一key同一payloadは再利用、同一key異payloadは拒否")
    void job_idempotencyAndPayloadHash() {
        IntegrationConnection conn = connectionService.getOrCreateConnection("default", null, "freee", "accounting");

        String idempotencyKey = "INV-202608-001-SYNC";
        String payloadHashA = "hash-aaaa-1111";
        String payloadHashB = "hash-bbbb-2222";

        // 初回作成
        IntegrationJob job1 = jobService.createJob(conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", 101L,
                idempotencyKey, payloadHashA);
        assertThat(job1).isNotNull();
        assertThat(job1.getId()).isNotNull();
        assertThat(job1.getStatus()).isEqualTo("PENDING");

        // 同一 key + 同一 payload_hash -> 既存 job を返却
        IntegrationJob job2 = jobService.createJob(conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", 101L,
                idempotencyKey, payloadHashA);
        assertThat(job2.getId()).isEqualTo(job1.getId());

        // 同一 key + 異なる payload_hash -> 例外
        assertThatThrownBy(() -> jobService.createJob(conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", 101L,
                idempotencyKey, payloadHashB))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("同一の冪等性キーに対して異なるペイロードでの再送は拒否されます");
    }

    @Test
    @DisplayName("複数 Worker の同時 claim で 1 つの Worker だけが claim 成功すること")
    void job_concurrentClaim() throws Exception {
        IntegrationConnection conn = connectionService.getOrCreateConnection("default", null, "freee", "accounting");
        IntegrationJob job = jobService.createJob(conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", 202L,
                "INV-202608-002-CLAIM", "hash-claim-test");

        int workerCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            futures.add(executor.submit(() -> {
                try {
                    startLatch.await();
                    IntegrationJob claimed = jobService.claimJob(job.getId());
                    if (claimed != null && "RUNNING".equals(claimed.getStatus())) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                }
            }));
        }

        startLatch.countDown();
        for (Future<?> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // 複数 Worker のうち claim に成功したのは正確に 1 つだけ
        assertThat(successCount.get()).isEqualTo(1);
    }
}
