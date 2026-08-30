package com.ses.service;

import com.ses.entity.AssetLostIncident;

import java.time.LocalDateTime;
import java.util.List;

/** 紛失資産インシデントの台帳・対応状態サービス。 */
public interface AssetLostIncidentService {

    /** LOST遷移と同一transactionで初回インシデントを作成し、緊急通知をoutboxへ登録する。 */
    AssetLostIncident createInitial(Long assetId, String incidentDetails, Long reporterUserId,
                                    Long evidenceDocumentId);

    /** 資産に紐づく最新インシデントを取得する。 */
    AssetLostIncident getByAssetId(Long assetId);

    /** 初動・外部対応・警察届・保険申請・関連DocumentLinkを更新する。 */
    AssetLostIncident update(Long assetId, String incidentDetails, String remoteWipeStatus,
                             LocalDateTime remoteWipeRequestedAt, LocalDateTime remoteWipeExecutedAt,
                             LocalDateTime remoteWipeConfirmedAt, String policeReportNumber,
                             String insuranceClaimStatus, LocalDateTime insuranceClaimedAt,
                             List<Long> documentIds, Long actorUserId);
}
