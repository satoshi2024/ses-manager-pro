package com.ses.service;

import com.ses.entity.Asset;
import com.ses.entity.AssetAssignment;

import java.util.List;

/**
 * 資産・貸与期限監視およびアラート通知サービス
 */
public interface AssetAlertService {

    /**
     * 返却予定日を過ぎた未返却貸与を検知し通知を発行する
     */
    int checkOverdueAssignments();

    /**
     * 30日以内にリース満了を迎える資産を検知し通知を発行する
     */
    int checkExpiringLeases();

    /**
     * 紛失インシデント発生時の緊急通知を発行する
     */
    void notifyLostAssetIncident(Asset asset, String incidentDetails, Long reporterUserId);

    /**
     * 現在超過している未返却貸与一覧を取得する
     */
    List<AssetAssignment> getOverdueAssignments();
}
