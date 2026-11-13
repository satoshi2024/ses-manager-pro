package com.ses.service.accounting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.IntegrationJob;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * freee送信ジョブの冪等性キーを「世代」付きで決め、既存ジョブの状態に応じて理由付きで拒否する。
 *
 * <p>以前はキーが対象レコードごとに固定だったため、freee側の取引を取消した後（金額訂正など）や、
 * 送信前のジョブを取消した後に再送信できなかった（既存ジョブがそのまま返るか、内容が変わると409）。
 * 世代 = 取消済み（CANCELLED）の送信ジョブ数 + 成功した取引取消ジョブ数 とし、世代0は従来のキーのまま
 * （送信済みの既存データを二重送信しない）。</p>
 *
 * <ul>
 *   <li>同じ世代のジョブが SUCCEEDED: freeeへ送信済み。取消してから再送信するよう案内する</li>
 *   <li>同じ世代のジョブが FAILED: 連携ジョブタブで原因を確認して再試行するよう案内する
 *       （FAILEDには金額不一致など取引作成済みのものも含むため、送信ボタンからは作り直さない）</li>
 *   <li>処理中（PENDING/RUNNING/RETRYABLE）で内容が変わった: 完了を待つよう案内する</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class AccountingSendKeyResolver {

    private final IntegrationJobService jobService;

    /**
     * @param baseKey       世代0のキー（従来の形式）
     * @param syncJobType   送信ジョブ種別（例: SALES_INVOICE_SYNC）
     * @param cancelJobType freee取引の取消ジョブ種別。取消の無い種別は null
     * @param payloadHash   今回の送信内容のハッシュ
     */
    public String resolve(String baseKey, String targetType, Long targetId, String syncJobType,
                          String cancelJobType, String payloadHash) {
        long generation = count(targetType, targetId, syncJobType, "CANCELLED")
                + (cancelJobType == null ? 0 : count(targetType, targetId, cancelJobType, "SUCCEEDED"));
        String key = generation == 0 ? baseKey : baseKey + ":g" + generation;
        IntegrationJob existing = jobService.getByIdempotencyKey(key);
        if (existing == null) {
            return key;
        }
        if ("SUCCEEDED".equals(existing.getStatus())) {
            throw BusinessException.of(409, cancelJobType == null
                    ? "error.accounting.alreadySyncedNoCancel" : "error.accounting.alreadySynced");
        }
        if ("FAILED".equals(existing.getStatus())) {
            throw BusinessException.of(409, "error.accounting.previousJobFailed");
        }
        if (payloadHash != null && existing.getPayloadHash() != null && !payloadHash.equals(existing.getPayloadHash())) {
            throw BusinessException.of(409, "error.accounting.jobInProgress");
        }
        return key; // 処理中で内容も同じ: 既存ジョブへ統合される（createJobの冪等性）
    }

    private long count(String targetType, Long targetId, String jobType, String status) {
        return jobService.count(new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getTargetType, targetType)
                .eq(IntegrationJob::getTargetId, targetId)
                .eq(IntegrationJob::getJobType, jobType)
                .eq(IntegrationJob::getStatus, status));
    }
}
