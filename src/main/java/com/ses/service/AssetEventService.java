package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.AssetEvent;

import java.util.List;

/**
 * 資産不変イベント台帳サービス
 */
public interface AssetEventService extends IService<AssetEvent> {

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

    /**
     * 資産ごとのイベント履歴を取得する（時系列降順）
     */
    List<AssetEvent> getEventsByAssetId(Long assetId);
}
