package com.ses.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.AssetInventoryItem;
import com.ses.entity.AssetInventoryRun;

import java.time.LocalDate;
import java.util.List;

/**
 * 資産棚卸し・差異照合サービス
 */
public interface AssetInventoryService extends IService<AssetInventoryRun> {

    /**
     * 棚卸し計画を開始する（理論在庫から明細を自動生成）
     */
    AssetInventoryRun startInventoryRun(String inventoryCode,
                                        String title,
                                        LocalDate targetDate,
                                        Long actorUserId);

    /**
     * 実地棚卸し結果を記録する（1件）
     */
    AssetInventoryItem recordItemCheck(Long itemId,
                                       String observedStatus,
                                       String observedLocation,
                                       String discrepancyType,
                                       String discrepancyReason,
                                       String resolutionAction,
                                       Long actorUserId);

    /**
     * 棚卸しを完了確定する（差異件数集計・スナップショット固定）
     */
    AssetInventoryRun completeInventoryRun(Long runId, Long actorUserId);

    /**
     * 棚卸し明細一覧を取得する
     */
    List<AssetInventoryItem> getItemsByRunId(Long runId);

    /**
     * 棚卸し実施一覧検索（ページネーション）
     */
    IPage<AssetInventoryRun> searchRuns(int page, int size, String status);
}
