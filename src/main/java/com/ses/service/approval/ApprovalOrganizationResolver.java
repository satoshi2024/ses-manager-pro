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
import com.ses.service.accounting.AccountingTenantContextHolder;
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
        String tenantId = requireTenant();
        if (quotation == null) {
            return null;
        }
        Long fromCreator = primaryOrg(quotation.getCreatedBy(), LocalDate.now(), tenantId);
        if (fromCreator != null) {
            return fromCreator;
        }
        return engineerOrg(quotation.getEngineerId(), tenantId);
    }

    public Long forSalesOrder(SalesOrder order) {
        String tenantId = requireTenant();
        if (order == null) {
            return null;
        }
        return primaryOrg(order.getCreatedBy(), LocalDate.now(), tenantId);
    }

    public Long forInvoice(Invoice invoice) {
        String tenantId = requireTenant();
        if (invoice == null) {
            return null;
        }
        Long fromCc = costCenterOrg(invoice.getCostCenterId(), tenantId);
        if (fromCc != null) {
            return fromCc;
        }
        return primaryOrg(invoice.getCreatedBy(), LocalDate.now(), tenantId);
    }

    public Long forContract(Contract contract) {
        String tenantId = requireTenant();
        if (contract == null) {
            return null;
        }
        Long fromSales = primaryOrg(contract.getSalesUserId(), LocalDate.now(), tenantId);
        if (fromSales != null) {
            return fromSales;
        }
        Long fromEngineer = engineerOrg(contract.getEngineerId(), tenantId);
        if (fromEngineer != null) {
            return fromEngineer;
        }
        return primaryOrg(contract.getCreatedBy(), LocalDate.now(), tenantId);
    }

    public Long forAcceptance(Acceptance acceptance) {
        String tenantId = requireTenant();
        if (acceptance == null) {
            return null;
        }
        if (acceptance.getContractId() != null) {
            Contract contract = contractMapper.selectByIdForTenant(acceptance.getContractId(), tenantId);
            Long fromContract = forContract(contract);
            if (fromContract != null) {
                return fromContract;
            }
        }
        return primaryOrg(acceptance.getCreatedBy(), LocalDate.now(), tenantId);
    }

    public Long forBpPayment(BpPayment payment) {
        String tenantId = requireTenant();
        if (payment == null) {
            return null;
        }
        Long fromCc = costCenterOrg(payment.getCostCenterId(), tenantId);
        if (fromCc != null) {
            return fromCc;
        }
        if (payment.getWorkRecordId() != null) {
            WorkRecord wr = workRecordMapper.selectByIdForTenant(payment.getWorkRecordId(), tenantId);
            if (wr != null && wr.getContractId() != null) {
                Contract contract = contractMapper.selectByIdForTenant(wr.getContractId(), tenantId);
                LocalDate asOf = LocalDate.now();
                if (wr.getWorkMonth() != null && !wr.getWorkMonth().isBlank()) {
                    try {
                        asOf = YearMonth.parse(wr.getWorkMonth()).atEndOfMonth();
                    } catch (Exception ignored) {
                        // keep today
                    }
                }
                if (contract != null && contract.getSalesUserId() != null) {
                    return primaryOrg(contract.getSalesUserId(), asOf, tenantId);
                }
            }
        }
        return null;
    }

    private Long costCenterOrg(Long costCenterId, String tenantId) {
        if (costCenterId == null) {
            return null;
        }
        CostCenter cc = costCenterMapper.selectByIdForTenant(costCenterId, tenantId);
        return cc == null ? null : cc.getOrganizationId();
    }

    private Long primaryOrg(Long userId, LocalDate asOf, String tenantId) {
        if (userId == null) {
            return null;
        }
        return userOrganizationMapper.selectPrimaryOrganizationAtForTenant(tenantId, userId, asOf);
    }

    private Long engineerOrg(Long engineerId, String tenantId) {
        if (engineerId == null) {
            return null;
        }
        Engineer engineer = engineerMapper.selectByIdForTenant(engineerId, tenantId);
        return engineer == null ? null : engineer.getOrganizationId();
    }

    private String requireTenant() {
        return AccountingTenantContextHolder.requireTenantContext();
    }
}
