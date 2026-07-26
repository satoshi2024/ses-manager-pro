package com.ses.service;

import com.ses.dto.accounting.ManagementAccountingSummaryDto;

/** 既存金額計算口径を使った組織別管理会計サービス。 */
public interface ManagementAccountingService {

    ManagementAccountingSummaryDto summary(String month);
}
