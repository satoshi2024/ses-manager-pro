package com.ses.service.approval;

import com.ses.entity.Acceptance;
import com.ses.entity.BpPayment;
import com.ses.entity.Contract;
import com.ses.entity.CostCenter;
import com.ses.entity.Engineer;
import com.ses.entity.Invoice;
import com.ses.entity.Quotation;
import com.ses.entity.SalesOrder;
import com.ses.entity.WorkRecord;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.CostCenterMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.mapper.WorkRecordMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Wave1 承認adapter向けの組織ID導出。
 * ORGANIZATION_MANAGER ルートが空承認者にならないよう、申請時点の所属組織を解決する。
 */
@Component
@RequiredArgsConstructor
public class ApprovalOrganizationResolver {

    private final UserOrganizationMapper userOrganizationMapper;
    private final EngineerMapper engineerMapper;
    private final ContractMapper contractMapper;
    private final CostCenterMapper costCenterMapper;
    private final WorkRecordMapper workRecordMapper;

    public Long forQuotation(Quotation quotation) {
        if (quotation == null) {
            return null;
        }
        Long fromCreator = primaryOrg(quotation.getCreatedBy(), LocalDate.now());
        if (fromCreator != null) {
            return fromCreator;
        }
        return engineerOrg(quotation.getEngineerId());
    }

    public Long forSalesOrder(SalesOrder order) {
        if (order == null) {
            return null;
        }
        return primaryOrg(order.getCreatedBy(), LocalDate.now());
    }

    public Long forInvoice(Invoice invoice) {
        if (invoice == null) {
            return null;
        }
        Long fromCc = costCenterOrg(invoice.getCostCenterId());
        if (fromCc != null) {
            return fromCc;
        }
        return primaryOrg(invoice.getCreatedBy(), LocalDate.now());
    }

    public Long forContract(Contract contract) {
        if (contract == null) {
            return null;
        }
        Long fromSales = primaryOrg(contract.getSalesUserId(), LocalDate.now());
        if (fromSales != null) {
            return fromSales;
        }
        Long fromEngineer = engineerOrg(contract.getEngineerId());
        if (fromEngineer != null) {
            return fromEngineer;
        }
        return primaryOrg(contract.getCreatedBy(), LocalDate.now());
    }

    public Long forAcceptance(Acceptance acceptance) {
        if (acceptance == null) {
            return null;
        }
        if (acceptance.getContractId() != null) {
            Contract contract = contractMapper.selectById(acceptance.getContractId());
            Long fromContract = forContract(contract);
            if (fromContract != null) {
                return fromContract;
            }
        }
        return primaryOrg(acceptance.getCreatedBy(), LocalDate.now());
    }

    public Long forBpPayment(BpPayment payment) {
        if (payment == null) {
            return null;
        }
        Long fromCc = costCenterOrg(payment.getCostCenterId());
        if (fromCc != null) {
            return fromCc;
        }
        if (payment.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectById(payment.getWorkRecordId());
            if (wr != null && wr.getContractId() != null) {
                Contract contract = contractMapper.selectById(wr.getContractId());
                LocalDate asOf = LocalDate.now();
                if (wr.getWorkMonth() != null && !wr.getWorkMonth().isBlank()) {
                    try {
                        asOf = YearMonth.parse(wr.getWorkMonth()).atEndOfMonth();
                    } catch (Exception ignored) {
                        // keep today
                    }
                }
                if (contract != null && contract.getSalesUserId() != null) {
                    return primaryOrg(contract.getSalesUserId(), asOf);
                }
            }
        }
        return null;
    }

    private Long costCenterOrg(Long costCenterId) {
        if (costCenterId == null) {
            return null;
        }
        CostCenter cc = costCenterMapper.selectById(costCenterId);
        return cc == null ? null : cc.getOrganizationId();
    }

    private Long primaryOrg(Long userId, LocalDate asOf) {
        if (userId == null) {
            return null;
        }
        return userOrganizationMapper.selectPrimaryOrganizationAt(userId, asOf);
    }

    private Long engineerOrg(Long engineerId) {
        if (engineerId == null) {
            return null;
        }
        Engineer engineer = engineerMapper.selectById(engineerId);
        return engineer == null ? null : engineer.getOrganizationId();
    }
}
