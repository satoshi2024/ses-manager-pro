package com.ses.service;

import com.ses.dto.compliance.ProcurementComplianceFinding;
import com.ses.entity.Contract;

import java.time.LocalDate;
import java.util.List;

public interface BpComplianceService {

    /**
     * 発注・契約とBP条件に基づき、発注コンプライアンスの不備・リスク Findings を都度導出する。
     */
    List<ProcurementComplianceFinding> evaluateContractCompliance(Long bpCompanyId, Contract contract, LocalDate receiptDate);
}
