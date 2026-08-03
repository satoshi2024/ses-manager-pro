package com.ses.service;

import com.ses.dto.crm.CrmKpiDto;

/** CRM商機・lead KPIの集計サービス。 */
public interface CrmKpiService {
    CrmKpiDto summarize(Long ownerUserId, String stage);
}
