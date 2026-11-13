package com.ses.service.accounting;

import com.ses.common.exception.BusinessException;
import com.ses.dto.accounting.canonical.PriorDealScope;
import com.ses.entity.IntegrationJob;
import com.ses.mapper.IntegrationJobMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * freee送信ジョブの世代キー（{@link AccountingSendKeyResolver}）の境界。
 * 世代は「閉じた送信ジョブ数」で数え、取消ジョブの成否・処理中のジョブ・旧方式の採番の飛びで
 * 二重送信にならないこと、既存取引の確認要否が履歴に対して単調であることを固定する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountingSendKeyResolverTest {

    private static final String SYNC = "SALES_INVOICE_SYNC";
    private static final String CANCEL = "SALES_INVOICE_CANCEL";
    private static final AtomicLong TARGET_SEQ = new AtomicLong(9_100_000L);

    @Autowired
    private AccountingSendKeyResolver resolver;

    @Autowired
    private IntegrationJobMapper jobMapper;

    private long target;

    @BeforeEach
    void setUp() {
        target = TARGET_SEQ.incrementAndGet();
    }

    private String base() {
        return "SALES_INVOICE:" + target + ":INV-RESOLVER";
    }

    private void syncJob(String key, String status, int attempts, String externalId, String hash) {
        insert(SYNC, key, status, attempts, externalId, hash, "{\"invoiceId\":" + target + "}");
    }

    private void cancelJob(String dealId, String status) {
        insert(CANCEL, "SALES_CANCEL:" + target + ":" + dealId, status, 1, null, "hash-cancel-" + dealId,
                "{\"invoiceId\":" + target + ",\"externalDealId\":\"" + dealId + "\",\"reason\":\"REASON_OTHER\"}");
    }

    /**
     * MyBatis経由で登録する（JdbcTemplateで直接INSERTすると、同じトランザクション内のMyBatisのローカルキャッシュが
     * 消えず、2回目の照会が登録前の結果を返す）。
     */
    private void insert(String jobType, String key, String status, int attempts, String externalId, String hash,
                        String snapshot) {
        jobMapper.insert(IntegrationJob.builder()
                .connectionId(999999L)
                .jobType(jobType)
                .targetType("INVOICE")
                .targetId(target)
                .tenantId("default")
                .legalEntityId(1L)
                .idempotencyKey(key)
                .payloadHash(hash)
                .payloadSnapshot(snapshot)
                .status(status)
                .attemptCount(attempts)
                .maxAttempts(5)
                .externalId(externalId)
                .version(0)
                .build());
    }

    private String resolve(String payloadHash) {
        return resolver.resolve(base(), "INVOICE", target, SYNC, CANCEL, payloadHash);
    }

    private boolean requiresCheck() {
        return resolver.requiresExistingDealCheck("INVOICE", target, SYNC);
    }

    @Test
    @DisplayName("初回の送信は世代0（従来のキー）で、既存取引の確認は不要")
    void firstSend_usesTheOriginalKey() {
        assertThat(resolve("hash-1")).isEqualTo(base());
        assertThat(requiresCheck()).isFalse();
    }

    @Test
    @DisplayName("最新の送信成功の取引が取消されていなければ送信済みとして拒否し、取消が成功した後は次の世代")
    void succeededDeal_mustBeCancelledBeforeTheNextGeneration() {
        syncJob(base(), "SUCCEEDED", 1, "D1", "hash-1");
        assertThatThrownBy(() -> resolve("hash-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.accounting.alreadySynced");

        cancelJob("D1", "SUCCEEDED");
        assertThat(resolve("hash-2")).isEqualTo(base() + ":g1");
        assertThat(requiresCheck()).as("取引の取消が成功した世代の次は確認不要").isFalse();
    }

    @Test
    @DisplayName("取引の取消ジョブが失敗していれば再送信しない（取引がfreeeに残っている）")
    void failedCancelJob_blocksTheResend() {
        syncJob(base(), "SUCCEEDED", 1, "D1", "hash-1");
        cancelJob("D1", "FAILED");

        assertThatThrownBy(() -> resolve("hash-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.accounting.cancelJobFailed");
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "RUNNING", "RETRYABLE"})
    @DisplayName("取引の取消ジョブ（補償取消を含む）が処理中なら再送信しない")
    void unfinishedCancelJob_blocksTheResend(String cancelStatus) {
        syncJob(base(), "CANCELLED", 1, null, "hash-1"); // 通信中に取消された送信
        insert(CANCEL, "COMPENSATE_CANCEL:" + target + ":D1", cancelStatus, 0, null, "hash-comp",
                "{\"invoiceId\":" + target + ",\"externalDealId\":\"D1\",\"reason\":\"IN_FLIGHT_CANCEL_COMPENSATION\"}");

        assertThatThrownBy(() -> resolve("hash-2"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.accounting.cancelJobInProgress");
    }

    @Test
    @DisplayName("送信ジョブがFAILEDなら、送信ボタンからは作り直さない")
    void failedSendJob_blocksTheResend() {
        syncJob(base(), "FAILED", 1, null, "hash-1");

        assertThatThrownBy(() -> resolve("hash-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.accounting.previousJobFailed");
    }

    @Test
    @DisplayName("旧方式の採番で世代が飛んでいても、閉じたジョブのキーは使わず次の空き世代を返す")
    void legacyGenerationGap_isSkipped() {
        syncJob(base(), "CANCELLED", 0, null, "hash-1");
        syncJob(base() + ":g2", "CANCELLED", 0, null, "hash-2"); // 旧方式は取消ジョブも数えたため g1 が飛んでいる

        assertThat(resolve("hash-3")).isEqualTo(base() + ":g3");
    }

    @Test
    @DisplayName("採番の飛びの先に処理中のジョブがあれば、それを今の世代として扱い2件目を作らない")
    void openJobBeyondAGap_isTheCurrentGeneration() {
        syncJob(base(), "CANCELLED", 0, null, "hash-1");
        syncJob(base() + ":g2", "PENDING", 0, null, "hash-2");

        assertThat(resolve("hash-2")).as("同じ内容は既存ジョブへ統合").isEqualTo(base() + ":g2");
        assertThatThrownBy(() -> resolve("hash-other"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("error.accounting.jobInProgress");
    }

    @Test
    @DisplayName("既存取引の確認要否は履歴に対して単調: 送信後に取消されたジョブが1件でもあれば以後ずっと確認する")
    void existingDealCheck_isMonotonicOverTheHistory() {
        syncJob(base(), "CANCELLED", 0, null, "hash-1"); // 送信前の取消
        assertThat(requiresCheck()).isFalse();

        syncJob(base() + ":g1", "CANCELLED", 2, null, "hash-2"); // 送信後（結果不明）の取消
        assertThat(requiresCheck()).isTrue();

        syncJob(base() + ":g2", "SUCCEEDED", 1, "D3", "hash-3");
        cancelJob("D3", "SUCCEEDED");
        assertThat(requiresCheck()).as("後の世代が正常に送信・取消されても確認を外さない").isTrue();
        assertThat(resolve("hash-4")).isEqualTo(base() + ":g3");
    }

    @Test
    @DisplayName("取消された世代の走査範囲: スナップショットの取引先・発生日（経費は経費日付）を読み、読めなければ範囲不明として残す")
    void priorDealScopes_comeFromTheCancelledSnapshots() {
        insert(SYNC, base(), "CANCELLED", 1, null, "hash-1",
                "{\"invoiceId\":" + target + ",\"partnerExternalId\":\"3002\",\"issueDate\":\"2026-07-01\"}");
        insert(SYNC, base() + ":g1", "CANCELLED", 0, null, "hash-2",
                "{\"invoiceId\":" + target + ",\"partnerExternalId\":\"3009\",\"issueDate\":\"2026-07-09\"}"); // 送信前の取消は対象外
        insert(SYNC, base() + ":g2", "CANCELLED", 2, null, "hash-3",
                "{\"expenseId\":" + target + ",\"expenseDate\":\"2026-08-21\"}");
        insert(SYNC, base() + ":g3", "CANCELLED", 1, null, "hash-4", "not-json");

        assertThat(resolver.priorDealScopes("INVOICE", target, SYNC)).containsExactly(
                new PriorDealScope("3002", java.time.LocalDate.of(2026, 7, 1)),
                new PriorDealScope(null, java.time.LocalDate.of(2026, 8, 21)),
                new PriorDealScope(null, null));
        assertThat(requiresCheck()).isTrue();
    }
}
