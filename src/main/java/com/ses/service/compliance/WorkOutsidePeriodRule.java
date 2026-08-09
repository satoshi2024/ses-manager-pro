package com.ses.service.compliance;

import com.ses.dto.compliance.ComplianceFinding;
import com.ses.service.SystemConfigService;
import com.ses.entity.Contract;
import com.ses.entity.WorkRecordDaily;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * F2 rule: 契約期間外の稼動記録（期間外工数）を検出する（R3.1/R5）。
 * 雇用勤怠（t_employee_attendance）ではなく客先工数（t_work_record_daily）を対象とし、
 * 雇用勤怠と客先工数の差異表示（R2.3）はB1の帳票側で扱う。
 */
@Component
public class WorkOutsidePeriodRule extends AbstractComplianceRule {

    public static final String CODE = "RISK_WORK_OUTSIDE_PERIOD";

    public WorkOutsidePeriodRule(SystemConfigService systemConfigService, MessageSource messageSource) {
        super(systemConfigService, messageSource);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public boolean appliesTo(Contract contract) {
        return contract.getStartDate() != null;
    }

    @Override
    protected List<ComplianceFinding> evaluateEnabled(Contract contract, ComplianceRuleContext context) {
        LocalDate start = contract.getStartDate();
        LocalDate end = contract.getEndDate();
        List<ComplianceFinding> findings = new ArrayList<>();
        for (WorkRecordDaily daily : context.workRecordDailies()) {
            LocalDate workDate = daily.getWorkDate();
            if (workDate == null) {
                continue;
            }
            boolean outside = workDate.isBefore(start) || (end != null && workDate.isAfter(end));
            if (outside) {
                findings.add(finding(context, CODE, contract.getId(),
                        "WR:" + daily.getId(), null, workDate));
            }
        }
        return findings;
    }
}
