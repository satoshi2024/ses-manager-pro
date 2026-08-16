package com.ses.service.portal;

/**
 * R1.5: 内部の顧客担当者/BP担当者の退職・無効化を検知し、portal accessを失効させる。
 * 担当者emailと一致するportal user（同種組織のみ）を停止し、全sessionを失効させる。
 */
public interface PortalContactInvalidationService {

    /**
     * 失効条件を満たす担当者とemail一致するportal userを停止する。
     * 条件: 顧客担当者（status=退職 / valid_to到来 / 論理削除）、BP担当者（論理削除）。
     * 停止したuser数を返す。
     */
    int invalidateByContacts();
}
