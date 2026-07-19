package com.ses.service;

import com.ses.dto.closing.MonthlyClosingSummaryDto;

/**
 * 月次締めチェックリストサービス。
 */
public interface MonthlyClosingService {

    /** 対象月の5項目の件数と明細を集計する。 */
    MonthlyClosingSummaryDto summary(String month);

    /** 締めを記録する。(a)-(d)=0 を再検証し、管理者/マネージャーのみ許可。 */
    void confirmClosing(String month, Long userId, String role);

    /** 締めを解除する。管理者/マネージャーのみ許可。 */
    void reopenClosing(String month, Long userId, String role);

    /** 対象月が締め済みか。 */
    boolean isClosed(String month);

    /**
     * 対象月が更新可能（未締め）であることを保証する共通ガード。
     * 締め設定行を FOR UPDATE でロックしたうえで判定するため、confirm と保護対象更新
     * （工数保存・請求取消等）を直列化する。呼び出し側の @Transactional 内で使用すること。
     * 締め済みなら例外を送出し、締めJSON破損時は fail-closed（更新拒否）とする。
     */
    void assertOpenForUpdate(String month);
}
