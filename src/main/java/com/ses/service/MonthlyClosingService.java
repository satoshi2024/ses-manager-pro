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
}
