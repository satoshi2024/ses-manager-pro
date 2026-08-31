package com.ses.service;

import com.ses.entity.AssetEvent;
import com.ses.common.audit.ActorAttribution;

import java.util.List;

/**
 * 資産不変イベント台帳サービス
 */
public interface AssetEventService {

    /**
     * イベントを追記記録する
     */
    AssetEvent recordEvent(Long assetId,
                           String eventType,
                           Long actorUserId,
                           String assigneeType,
                           Long assigneeId,
                           String fromStatus,
                           String toStatus,
                           Long evidenceDocId,
                           String eventSummary,
                           String detailsJson);

    /** 外部参照の状態確認を、明示的な主体・チャネル付きで追記する。 */
    AssetEvent recordExternalAccountConfirmation(Long referenceId,
                                                 String eventType,
                                                 String beforeState,
                                                 String afterState,
                                                 ActorAttribution attribution,
                                                 String eventSummary,
                                                 String detailsJson);

    /**
     * 資産ごとのイベント履歴を取得する（時系列降順）
     */
    List<AssetEvent> getEventsByAssetId(Long assetId);
}
