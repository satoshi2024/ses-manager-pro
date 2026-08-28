package com.ses.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.Asset;

import java.util.List;

/**
 * 資産台帳管理サービス
 */
public interface AssetService extends IService<Asset> {

    /**
     * 資産を新規登録する（イベント記録付き）
     */
    Asset createAsset(Asset asset, Long actorUserId);

    /**
     * 資産情報を更新する（CAS楽観ロック保護）
     */
    Asset updateAsset(Asset asset, Long actorUserId);

    /**
     * 資産ステータスを変更する（CAS楽観ロック保護）
     */
    Asset changeStatus(Long assetId, String toStatus, String reason, Long actorUserId, Long evidenceDocId);

    /**
     * 資産を廃棄済みにする
     */
    Asset disposeAsset(Long assetId, String reason, Long actorUserId, Long evidenceDocId);

    /**
     * 資産の紛失を報告する（LOSTへ遷移、インシデント記録）
     */
    Asset reportLost(Long assetId, String incidentDetails, Long actorUserId, Long evidenceDocId);

    /**
     * ページネーション検索
     */
    IPage<Asset> searchAssets(int page, int size, String keyword, String category, String status, Long ownerCompanyId);

    /**
     * 資産タグで1件取得
     */
    Asset getByAssetTag(String assetTag);
}
