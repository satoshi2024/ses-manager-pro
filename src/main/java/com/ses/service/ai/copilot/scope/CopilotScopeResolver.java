package com.ses.service.ai.copilot.scope;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Set;

/**
 * role / DataScope / 組織scopeを正本serviceと同じ母集団へ収束させる。
 */
@Component
@RequiredArgsConstructor
public class CopilotScopeResolver {

    public static final String POLICY_VERSION = "nf08-scope-2";

    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;

    public CopilotScopeContext resolve(SemanticCatalogEntry entry, CopilotExecutionContext context) {
        String role = SecurityUtils.currentRole();
        if (role == null || !entry.allowedRoles().contains(role)) {
            throw BusinessException.of(403, "SCOPE_DENIED");
        }
        if ("HR".equals(role) || "要員".equals(role)) {
            throw BusinessException.of(403, "SCOPE_DENIED");
        }

        LocalDate asOf = context.asOf();
        String scopeType;
        CopilotScopePopulation population;

        if (organizationScopeService.hasFullAccess() && !dataScopeService.isScoped()) {
            scopeType = "COMPANY_WIDE";
            population = emptyPopulation();
        } else if (dataScopeService.isSalesDataScoped()) {
            scopeType = "SALES_DATA_SCOPED";
            population = new CopilotScopePopulation(
                    safeSet(dataScopeService.allowedCustomerIds()),
                    safeSet(dataScopeService.allowedContractIds()),
                    safeSet(dataScopeService.allowedEngineerIds()),
                    Set.of(),
                    Set.of());
        } else if (!organizationScopeService.hasFullAccess()) {
            scopeType = "ORGANIZATION_SCOPED";
            population = new CopilotScopePopulation(
                    safeSet(organizationScopeService.allowedCustomerIds(asOf)),
                    safeSet(organizationScopeService.allowedContractIds(asOf)),
                    safeSet(organizationScopeService.allowedEngineerIds(asOf)),
                    safeSet(organizationScopeService.allowedOrganizationIds(asOf)),
                    safeSet(organizationScopeService.allowedDirectUserIds(asOf)));
        } else if (dataScopeService.isScoped()) {
            scopeType = "DATA_SCOPED";
            population = new CopilotScopePopulation(
                    safeSet(dataScopeService.allowedCustomerIds()),
                    safeSet(dataScopeService.allowedContractIds()),
                    safeSet(dataScopeService.allowedEngineerIds()),
                    Set.of(),
                    Set.of());
        } else {
            scopeType = "COMPANY_WIDE";
            population = emptyPopulation();
        }

        boolean emptyPopulation = isEmptyPopulation(scopeType, population);
        if (emptyPopulation) {
            throw BusinessException.of(403, "SCOPE_DENIED");
        }

        String scopeHash = CopilotScopeHash.hash(
                context.tenantId(),
                context.legalEntityId(),
                scopeType,
                POLICY_VERSION,
                asOf,
                population.customerIds(),
                population.contractIds(),
                population.engineerIds(),
                population.organizationIds(),
                population.directUserIds());

        return new CopilotScopeContext(scopeType, POLICY_VERSION, scopeHash, false);
    }

    private static CopilotScopePopulation emptyPopulation() {
        return new CopilotScopePopulation(Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    private static Set<Long> safeSet(Set<Long> ids) {
        return ids == null ? Set.of() : ids;
    }

    private static boolean isEmptyPopulation(String scopeType, CopilotScopePopulation population) {
        return switch (scopeType) {
            case "COMPANY_WIDE" -> false;
            case "SALES_DATA_SCOPED" -> population.customerIds().isEmpty()
                    && population.contractIds().isEmpty()
                    && population.engineerIds().isEmpty();
            case "ORGANIZATION_SCOPED" -> population.organizationIds().isEmpty()
                    && population.directUserIds().isEmpty();
            case "DATA_SCOPED" -> population.contractIds().isEmpty()
                    && population.engineerIds().isEmpty();
            default -> true;
        };
    }
}
