package com.ses.service;

import com.ses.dto.bpcompany.BpRiskSummaryDto;

public interface BpRiskDashboardService {

    BpRiskSummaryDto getRiskSummary();

    int generateRiskNotifications();
}
