package com.ses.service.expense.impl;

import com.ses.entity.ExpenseAccountingJob;
import com.ses.entity.ExpenseRequest;
import com.ses.service.expense.ExpenseAccountingSender;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 開発・test用のmock会計連携sender。常に成功を返す。
 * payload_hash→correlation_idをインメモリで記録し、同一hashの再送要求は
 * 外部呼出しを行わず既存のcorrelation_idで成功扱いにする（冪等。design §6.3）。
 * provider選択はm_system_configのexpense.accounting.providerを正とし、mockのときだけ使われる
 * （prodではfreee等の実senderへ置き換わる。S15 accounting-payment-integration）。
 */
@Component
@Profile("!prod")
public class MockExpenseAccountingSender implements ExpenseAccountingSender {

    public static final String PROVIDER_NAME = "mock";

    private final ConcurrentHashMap<String, String> sentByPayloadHash = new ConcurrentHashMap<>();

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public SendResult send(ExpenseRequest expense, ExpenseAccountingJob job) {
        String hash = job == null ? null : job.getPayloadHash();
        if (hash == null || hash.isBlank()) {
            return new SendResult(false, null, "PAYLOAD_HASH_MISSING");
        }
        String existing = sentByPayloadHash.get(hash);
        if (existing != null) {
            // 同一payloadは既に送信済み。再送しない（scheduler二重起動・クラッシュ後リトライの冪等）。
            return new SendResult(true, existing, null);
        }
        String correlationId = "EXP-MOCK-" + UUID.randomUUID();
        sentByPayloadHash.put(hash, correlationId);
        return new SendResult(true, correlationId, null);
    }

    /** テスト観測用: 送信済みpayload_hash→correlation_idのスナップショット。 */
    public Map<String, String> sentPayloadHashes() {
        return Map.copyOf(sentByPayloadHash);
    }
}
