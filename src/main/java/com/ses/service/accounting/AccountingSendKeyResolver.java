package com.ses.service.accounting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.IntegrationJob;
import com.ses.service.integration.IntegrationJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * freee送信ジョブの冪等性キーを「世代」付きで決め、既存ジョブの状態に応じて理由付きで拒否する。
 *
 * <p>以前はキーが対象レコードごとに固定だったため、freee側の取引を取消した後（金額訂正など）や、
 * 送信前のジョブを取消した後に再送信できなかった（既存ジョブがそのまま返るか、内容が変わると409）。
 * 世代 = 閉じた送信ジョブの数（取消済みのジョブ + freee取引の取消が成功した送信成功ジョブ）とし、
 * 世代0は従来のキーのまま（送信済みの既存データを二重送信しない）。</p>
 *
 * <p>世代を「取消ジョブの成功数」で数えると、通信中の取消（送信ジョブはCANCELLED、作成された取引は補償取消ジョブが削除）で
 * 同じ取引を2回数えてしまい、再送信で作った取引の上にもう1件作れてしまう。そのため、取消ジョブは
 * 「どの送信ジョブの取引を消したか」で送信ジョブ側に対応付け、送信ジョブ単位で数える。</p>
 *
 * <ul>
 *   <li>最新の送信成功ジョブの取引が取消済みでない: freeeへ送信済み。取消してから再送信するよう案内する</li>
 *   <li>取引の取消ジョブが処理中・失敗: 取消が終わるまで再送信しない（補償取消の前に作ると重複が残り得る）</li>
 *   <li>同じ世代のジョブが FAILED: 連携ジョブタブで原因を確認して再試行するよう案内する
 *       （FAILEDには金額不一致など取引作成済みのものも含むため、送信ボタンからは作り直さない）</li>
 *   <li>処理中（PENDING/RUNNING/RETRYABLE）で内容が変わった: 完了を待つよう案内する</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountingSendKeyResolver {

    /** 世代の走査上限（旧方式で採番された世代の飛びを吸収するため。通常は数件）。 */
    private static final int MAX_GENERATION_SCAN = 1000;

    private final IntegrationJobService jobService;
    private final ObjectMapper objectMapper;

    /**
     * @param baseKey       世代0のキー（従来の形式）
     * @param syncJobType   送信ジョブ種別（例: SALES_INVOICE_SYNC）
     * @param cancelJobType freee取引の取消ジョブ種別。取消の無い種別は null
     * @param payloadHash   今回の送信内容のハッシュ
     */
    public String resolve(String baseKey, String targetType, Long targetId, String syncJobType,
                          String cancelJobType, String payloadHash) {
        Set<String> cancelledDealIds = cancelledDealIds(targetType, targetId, cancelJobType);
        List<IntegrationJob> syncJobs = list(targetType, targetId, syncJobType);

        // 不変条件: 最新の送信成功ジョブが作った取引が取消済みでなければ、freeeへ送信済み
        IntegrationJob latestSucceeded = null;
        for (IntegrationJob job : syncJobs) {
            if ("SUCCEEDED".equals(job.getStatus()) && hasText(job.getExternalId())) {
                latestSucceeded = job;
            }
        }
        if (latestSucceeded != null && !cancelledDealIds.contains(latestSucceeded.getExternalId())) {
            throw alreadySynced(cancelJobType);
        }

        long generation = syncJobs.stream().filter(job -> isClosed(job, cancelledDealIds)).count();
        for (int scanned = 0; scanned < MAX_GENERATION_SCAN; scanned++, generation++) {
            String key = generation == 0 ? baseKey : baseKey + ":g" + generation;
            IntegrationJob existing = jobService.getByIdempotencyKey(key);
            if (existing == null) {
                return key;
            }
            if (isClosed(existing, cancelledDealIds)) {
                continue; // 旧方式の採番で世代が飛んでいる: 閉じたジョブのキーは使わない
            }
            if ("SUCCEEDED".equals(existing.getStatus())) {
                throw alreadySynced(cancelJobType);
            }
            if ("FAILED".equals(existing.getStatus())) {
                throw BusinessException.of(409, "error.accounting.previousJobFailed");
            }
            if (payloadHash != null && existing.getPayloadHash() != null && !payloadHash.equals(existing.getPayloadHash())) {
                throw BusinessException.of(409, "error.accounting.jobInProgress");
            }
            return key; // 処理中で内容も同じ: 既存ジョブへ統合される（createJobの冪等性）
        }
        throw new IllegalStateException("送信ジョブの世代を決められません: targetType=" + targetType + " targetId=" + targetId);
    }

    /**
     * 再送信するジョブが、作成前に同じ管理番号の取引がfreeeに無いことを確認すべきか。
     *
     * <p>外部へ送信した後（試行回数 &gt; 0）に取消された送信ジョブは、freeeに取引が作成されたか確定できない
     * （タイムアウト後の再試行待ちを取消した場合や、通信中の取消で応答が失敗だった場合）。
     * その後の世代を確認なしに作ると、freeeに同じ取引が2件できる。</p>
     */
    public boolean requiresExistingDealCheck(String targetType, Long targetId, String syncJobType) {
        return list(targetType, targetId, syncJobType).stream()
                .anyMatch(job -> "CANCELLED".equals(job.getStatus())
                        && job.getAttemptCount() != null && job.getAttemptCount() > 0);
    }

    /** 閉じた送信ジョブ: 取消済み、または作成した取引の取消が成功した送信成功ジョブ。このキーは二度と使わない。 */
    private static boolean isClosed(IntegrationJob job, Set<String> cancelledDealIds) {
        if ("CANCELLED".equals(job.getStatus())) {
            return true;
        }
        return "SUCCEEDED".equals(job.getStatus()) && hasText(job.getExternalId())
                && cancelledDealIds.contains(job.getExternalId());
    }

    /**
     * 取消が成功したfreee取引ID。取消ジョブが処理中・失敗なら再送信を拒否する
     * （手動の取消・通信中取消の補償取消のどちらも、完了してから次の取引を作る）。
     */
    private Set<String> cancelledDealIds(String targetType, Long targetId, String cancelJobType) {
        Set<String> dealIds = new HashSet<>();
        if (cancelJobType == null) {
            return dealIds;
        }
        for (IntegrationJob cancel : list(targetType, targetId, cancelJobType)) {
            String status = cancel.getStatus() == null ? "" : cancel.getStatus();
            switch (status) {
                case "SUCCEEDED" -> {
                    String dealId = cancelledDealId(cancel);
                    if (dealId != null) {
                        dealIds.add(dealId);
                    }
                }
                case "FAILED" -> throw BusinessException.of(409, "error.accounting.cancelJobFailed");
                case "CANCELLED" -> {
                    // 取消ジョブ自体は取消できないが、念のため何もしない（取引は残っている扱い）
                }
                default -> throw BusinessException.of(409, "error.accounting.cancelJobInProgress");
            }
        }
        return dealIds;
    }

    /** 取消ジョブのスナップショット（externalDealId）から対象のfreee取引IDを読む。 */
    private String cancelledDealId(IntegrationJob cancel) {
        String snapshot = cancel.getPayloadSnapshot();
        if (snapshot == null || snapshot.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(snapshot).get("externalDealId");
            return node == null || node.isNull() || node.asText().isBlank() ? null : node.asText();
        } catch (Exception e) {
            log.warn("取消ジョブのスナップショットを解釈できません: jobId={}", cancel.getId());
            return null;
        }
    }

    private static BusinessException alreadySynced(String cancelJobType) {
        return BusinessException.of(409, cancelJobType == null
                ? "error.accounting.alreadySyncedNoCancel" : "error.accounting.alreadySynced");
    }

    private List<IntegrationJob> list(String targetType, Long targetId, String jobType) {
        return jobService.list(new LambdaQueryWrapper<IntegrationJob>()
                .eq(IntegrationJob::getTargetType, targetType)
                .eq(IntegrationJob::getTargetId, targetId)
                .eq(IntegrationJob::getJobType, jobType)
                .orderByAsc(IntegrationJob::getId));
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
