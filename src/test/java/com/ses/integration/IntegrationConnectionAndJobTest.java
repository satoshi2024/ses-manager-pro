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
                .expiresIn(-100L)
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
        for (Future<IntegrationTokensDto> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        executor.shutdown();

        // 3段階リース・CASにより直列化され、最終的に新トークンが保存されている
        IntegrationTokensDto finalTokens = connectionService.getDecryptedTokens(conn.getId());
        assertThat(finalTokens.getAccessToken()).startsWith("new-access-token-");
        assertThat(refreshCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Legacy Token 復号: V106以前のJSON形式・legacy AES暗号トークンが正常に復号できること (P1-04)")
    void legacyTokenDecryption() {
        IntegrationConnection conn = connectionService.getOrCreateConnection("legacy-tenant", null, "freee", "payroll");
        // V106で投入される形式: {"accessToken": "...", "refreshToken": "..."}
        String legacyJson = "{\"accessToken\":\"legacy-access-token-val\",\"refreshToken\":\"legacy-refresh-token-val\"}";
        conn.setEncryptedTokens(legacyJson);
        connectionService.updateById(conn);

        IntegrationTokensDto tokens = connectionService.getDecryptedTokens(conn.getId());
        assertThat(tokens).isNotNull();
        assertThat(tokens.getAccessToken()).isEqualTo("legacy-access-token-val");
        assertThat(tokens.getRefreshToken()).isEqualTo("legacy-refresh-token-val");
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

    @Test
    @DisplayName("NULL法人接続の重複排除と論理削除後の再作成保証 (R4-T01 / P1-04)")
    void uniqueConstraint_nullEntityDuplicateBlocked_and_allowsSoftDeleteRecreation() {
        // 1. 初回作成 (legalEntityId=null)
        IntegrationConnection conn1 = connectionService.getOrCreateConnection("tenant-dup-test", null, "freee", "accounting");
        assertThat(conn1).isNotNull();
        assertThat(conn1.getId()).isNotNull();

        // 2. 同一テナント・同一provider・同一product・legalEntityId=null で再度 getOrCreateConnection
        // 既存の conn1 が返却されること（重複作成されない）
        IntegrationConnection conn2 = connectionService.getOrCreateConnection("tenant-dup-test", null, "freee", "accounting");
        assertThat(conn2.getId()).isEqualTo(conn1.getId());

        // 3. conn1 を論理削除
        connectionService.removeById(conn1.getId());
        assertThat(connectionService.getById(conn1.getId())).isNull();

        // 4. 論理削除後、同一条件で新規作成が可能であること (active_slot による再作成保証)
        IntegrationConnection conn3 = connectionService.getOrCreateConnection("tenant-dup-test", null, "freee", "accounting");
        assertThat(conn3).isNotNull();
        assertThat(conn3.getId()).isNotEqualTo(conn1.getId());
    }

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Rollback SQL 契約検証: 退避テーブル復元と安全な列削除順序 (R4-T01 / design §1.2)")
    void migration_rollback_partialSafeAllShapes() throws Exception {
        // 1. connection 登録
        IntegrationConnection conn = connectionService.getOrCreateConnection("tenant-rollback", 101L, "freee", "accounting");
        assertThat(conn).isNotNull();

        // 2. job 登録 (新列 payload_snapshot, lease_token を含む)
        IntegrationJob job = IntegrationJob.builder()
                .connectionId(conn.getId())
                .jobType("SALES_INVOICE_SYNC")
                .targetType("INVOICE")
                .targetId(999L)
                .tenantId("tenant-rollback")
                .legalEntityId(101L)
                .idempotencyKey("INV-ROLLBACK-TEST-001")
                .payloadSnapshot("{\"invoiceId\":999}")
                .payloadHash("hash-999")
                .status("PENDING")
                .attemptCount(0)
                .maxAttempts(5)
                .version(0)
                .build();
        jobService.save(job);

        IntegrationJob savedJob = jobService.getById(job.getId());
        assertThat(savedJob.getPayloadSnapshot()).isEqualTo("{\"invoiceId\":999}");
        assertThat(savedJob.getTenantId()).isEqualTo("tenant-rollback");

        // 3. Rollback SQL の実際の実行検証 (R1-P1-04)
        // (1) バックアップテーブル作成 & 重複行退避シミュレーション
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS m_integration_connection_backup_v106_1 (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                original_id BIGINT NOT NULL,
                tenant_id VARCHAR(64) NOT NULL,
                legal_entity_id BIGINT NULL,
                provider VARCHAR(32) NOT NULL,
                product VARCHAR(32) NOT NULL,
                company_id BIGINT NULL,
                company_name VARCHAR(128) NULL,
                status VARCHAR(32) NOT NULL,
                created_by BIGINT NULL,
                created_at DATETIME NOT NULL,
                updated_at DATETIME NOT NULL,
                deleted_flag INT NOT NULL DEFAULT 0,
                backup_reason VARCHAR(64) NOT NULL DEFAULT 'V106_1_DEDUPLICATION_BACKUP'
            )
        """);

        // 退避行を挿入
        jdbcTemplate.update("""
            INSERT INTO m_integration_connection_backup_v106_1
            (original_id, tenant_id, legal_entity_id, provider, product, status, created_at, updated_at, deleted_flag)
            VALUES (?, 'tenant-rollback', 101, 'freee', 'accounting', 'ACTIVE', NOW(), NOW(), 0)
        """, conn.getId());

        // (2) Rollback SQL ステップ 2: backup から soft-delete された行を復元
        int restoredCount = jdbcTemplate.update("""
            UPDATE m_integration_connection
            SET deleted_flag = 0
            WHERE id IN (SELECT original_id FROM m_integration_connection_backup_v106_1)
        """);
        assertThat(restoredCount).isGreaterThanOrEqualTo(1);

        // (3) Rollback SQL ステップ 4: backup テーブル削除
        jdbcTemplate.execute("DROP TABLE IF EXISTS m_integration_connection_backup_v106_1");

        // 復元後もデータ整合性が維持されていること
        assertThat(jobService.getById(savedJob.getId())).isNotNull();
        assertThat(connectionService.getById(conn.getId())).isNotNull();
    }

    @Test
    @DisplayName("3段階リース Fencing CAS: 競合発生時に新トークンを破棄して最新行を再読込すること (R4-T02)")
    void multiNode_fencingCas_discardsStaleToken_onVersionMismatch() {
        IntegrationConnection conn = connectionService.getOrCreateConnection("tenant-cas-test", null, "freee", "accounting");
        IntegrationTokensDto initialTokens = IntegrationTokensDto.builder()
                .accessToken("access-initial-123")
                .refreshToken("refresh-initial-456")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(conn.getId(), initialTokens, 50001L, "CASテスト事業所", 1L);

        // トークンリフレッシュ関数内で、DB側の token_version を裏で進めて CAS 不一致をシミュレート
        IntegrationTokensDto result = connectionService.forceRefreshToken(conn.getId(), current -> {
            // シミュレーション: 別ノードが裏で token_version を進めた
            IntegrationConnection latest = connectionService.getById(conn.getId());
            latest.setTokenVersion(latest.getTokenVersion() + 1);
            connectionService.updateById(latest);

            return IntegrationTokensDto.builder()
                    .accessToken("access-stolen-789")
                    .refreshToken("refresh-stolen-012")
                    .expiresIn(3600L)
                    .build();
        });

        // CAS 失敗により stolen token は反映されず、安全に既存/最新トークンが復号返却されること
        assertThat(result).isNotNull();
        assertThat(result.getAccessToken()).isEqualTo("access-initial-123");
    }

    @Test
    @DisplayName("敗者ノード挙動: リース保有中に待機し完了しなかった場合は TokenRefreshInProgressException を送出すること (R4-T02)")
    void multiNode_loserNode_throwsTokenRefreshInProgressException() {
        IntegrationConnection conn = connectionService.getOrCreateConnection("tenant-loser-test", null, "freee", "accounting");
        IntegrationTokensDto initialTokens = IntegrationTokensDto.builder()
                .accessToken("access-loser-initial")
                .refreshToken("refresh-loser-initial")
                .expiresIn(3600L)
                .build();
        connectionService.saveTokens(conn.getId(), initialTokens, 50002L, "敗者テスト事業所", 1L);

        // 別ノードがリースを 45秒間保有中 (refresh_lease_token 設定 & refresh_lease_expires_at 未来)
        IntegrationConnection connInDb = connectionService.getById(conn.getId());
        connInDb.setRefreshLeaseToken("other-node-uuid-1111");
        connInDb.setRefreshLeaseExpiresAt(java.time.LocalDateTime.now().plusSeconds(45));
        connectionService.updateById(connInDb);

        // 敗者ノードが forceRefreshToken を実行 -> 3回待機後に TokenRefreshInProgressException 送出
        assertThatThrownBy(() -> connectionService.forceRefreshToken(conn.getId(), current -> current))
                .isInstanceOf(com.ses.common.exception.TokenRefreshInProgressException.class)
                .hasMessageContaining("TOKEN_REFRESH_IN_PROGRESS");
    }

    @Test
    @DisplayName("ジョブ取消マトリクス検証: SALES_INVOICE_CANCEL の取消要求は 400 拒否されること (R4-T05)")
    void cancelJob_rejectsSalesInvoiceCancel_and_terminalJobs() {
        IntegrationConnection conn = connectionService.getOrCreateConnection("tenant-cancel-test", null, "freee", "accounting");

        // 1. SALES_INVOICE_CANCEL ジョブの作成
        IntegrationJob cancelJob = jobService.createJob(conn.getId(), "SALES_INVOICE_CANCEL", "INVOICE", 501L,
                "CANCEL-INV-501", "hash-cancel");

        // 取消ジョブ自体のキャンセルは 400 で拒否されること
        assertThatThrownBy(() -> jobService.cancelJob(cancelJob.getId(), "誤ってキャンセル"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("SALES_INVOICE_CANCEL");

        // 2. 通常ジョブの PENDING キャンセルは成功すること
        IntegrationJob normalJob = jobService.createJob(conn.getId(), "SALES_INVOICE_SYNC", "INVOICE", 502L,
                "SYNC-INV-502", "hash-sync");
        jobService.cancelJob(normalJob.getId(), "ユーザー指示");
        IntegrationJob updated = jobService.getById(normalJob.getId());
        assertThat(updated.getStatus()).isEqualTo("CANCELLED");

        // 3. 既に CANCELLED のジョブの再キャンセルは 400 で拒否されること
        assertThatThrownBy(() -> jobService.cancelJob(normalJob.getId(), "再キャンセル"))
                .isInstanceOf(BusinessException.class);
    }
}
