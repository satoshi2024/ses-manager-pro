package com.ses.service;

import com.ses.dto.asset.OffboardingClearanceResultDto;

/**
 * NF-01 退社ワークフロー連携 資産・アカウントオフボーディングサービス
 */
public interface AssetOffboardingService {

    /**
     * 要員の退社前クリアランスチェックを実施する
     * 未返却端末、未失効アカウント、未解放ライセンスの有無を検証
     */
    OffboardingClearanceResultDto checkOffboardingClearance(Long engineerId,
                                                             Long lifecycleCaseId,
                                                             Long lifecycleTaskId);

    /**
     * 退社に伴う外部アカウント失効要求およびライセンス一括解放を実行する
     */
    void triggerOffboardingRevocations(Long engineerId, Long actorUserId);

    /**
     * 退社前未返却の例外免除を登録する
     */
    void approveOffboardingWaiver(Long engineerId,
                                  Long lifecycleCaseId,
                                  Long lifecycleTaskId,
                                  String reason,
                                  Long approvalRequestId,
                                  Long actorUserId);
}
