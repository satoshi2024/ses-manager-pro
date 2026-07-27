package com.ses.service;

import com.ses.dto.accounting.ManagementAccountingSummaryDto;

/** 既存金額計算口径を使った組織別管理会計サービス。 */
public interface ManagementAccountingService {

    ManagementAccountingSummaryDto summary(String month);

    ManagementAccountingSummaryDto summary(String month, Long legalEntityId, Long organizationId,
                                           Long costCenterId, Long customerId, Long projectId, Long salesUserId);
}
