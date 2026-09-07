package com.ses.service.ai.copilot.scope;

import com.ses.common.exception.BusinessException;
import com.ses.common.util.SecurityUtils;
import com.ses.service.ai.copilot.catalog.SemanticCatalogEntry;
import com.ses.service.security.DataScopeService;
import com.ses.service.security.OrganizationScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Set;

/**
 * role / DataScope / 組織scopeを正本serviceと同じ母集団へ収束させる。
 */
@Component
@RequiredArgsConstructor
public class CopilotScopeResolver {

    public static final String POLICY_VERSION = "nf08-scope-1";

    private final DataScopeService dataScopeService;
    private final OrganizationScopeService organizationScopeService;

    public CopilotScopeContext resolve(SemanticCatalogEntry entry) {
        String role = SecurityUtils.currentRole();
        if (role == null || !entry.allowedRoles().contains(role)) {
            throw BusinessException.of(403, "SCOPE_DENIED");
        }
        if ("HR".equals(role) || "要員".equals(role)) {
            throw BusinessException.of(403, "SCOPE_DENIED");
        }

        String scopeType;
        boolean emptyPopulation = false;
        int populationMarker;

        if (organizationScopeService.hasFullAccess() && !dataScopeService.isScoped()) {
            scopeType = "COMPANY_WIDE";
            populationMarker = -1;
        } else if (dataScopeService.isSalesDataScoped()) {
            scopeType = "SALES_DATA_SCOPED";
            populationMarker = sizeOf(
                    dataScopeService.allowedContractIds(),
                    dataScopeService.allowedEngineerIds(),
                    dataScopeService.allowedCustomerIds());
            emptyPopulation = populationMarker == 0;
        } else if (!organizationScopeService.hasFullAccess()) {
            scopeType = "ORGANIZATION_SCOPED";
            LocalDate asOf = LocalDate.now();
            populationMarker = sizeOf(
                    organizationScopeService.allowedOrganizationIds(asOf),
                    organizationScopeService.allowedDirectUserIds(asOf));
            emptyPopulation = populationMarker == 0;
        } else if (dataScopeService.isScoped()) {
            scopeType = "DATA_SCOPED";
            populationMarker = sizeOf(dataScopeService.allowedContractIds(), dataScopeService.allowedEngineerIds());
            emptyPopulation = populationMarker == 0;
        } else {
            scopeType = "COMPANY_WIDE";
            populationMarker = -1;
        }

        String scopeHash = sha256(scopeType + "|" + POLICY_VERSION + "|" + populationMarker);
        return new CopilotScopeContext(scopeType, POLICY_VERSION, scopeHash, emptyPopulation);
    }

    private static int sizeOf(Set<?> first, Set<?> second) {
        return (first == null ? 0 : first.size()) + (second == null ? 0 : second.size());
    }

    private static int sizeOf(Set<?> first, Set<?> second, Set<?> third) {
        return sizeOf(first, second) + (third == null ? 0 : third.size());
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            return "0".repeat(64);
        }
    }
}
