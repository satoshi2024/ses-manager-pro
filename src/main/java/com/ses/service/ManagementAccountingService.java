package com.ses.service;

import com.ses.dto.accounting.ManagementAccountingSummaryDto;
import com.ses.service.ai.copilot.CopilotExecutionContext;

/** 既存金額計算口径を使った組織別管理会計サービス。 */
public interface ManagementAccountingService {

    ManagementAccountingSummaryDto summary(String month);

    ManagementAccountingSummaryDto summary(String month, Long legalEntityId, Long organizationId,
                                           Long costCenterId, Long customerId, Long projectId, Long salesUserId);

    default ManagementAccountingSummaryDto summary(String month, CopilotExecutionContext context) {
        throw com.ses.common.exception.BusinessException.of(403, "EXECUTION_CONTEXT_REQUIRED");
    }

}
