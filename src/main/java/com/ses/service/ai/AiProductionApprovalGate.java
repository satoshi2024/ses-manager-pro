package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 実providerの最終承認境界。
 * productionApproval一個のbooleanへ責任を縮約せず、各gateの状態と不足項目を監査可能に保持する。
 */
@Component
@RequiredArgsConstructor
public class AiProductionApprovalGate {
    private final AiConfig aiConfig;

    public AiProductionGateStatus status() {
        AiConfig.ProductionGates g = aiConfig.getProductionGates();
        if (g == null) return new AiProductionGateStatus(false, List.of("productionGates"));
        List<String> missing = new ArrayList<>();
        if (!g.isOwnerApproved()) missing.add("owner");
        if (!g.isApprovedCatalog()) missing.add("approvedCatalog");
        if (!g.isAllowedRoles()) missing.add("allowedRoles");
        if (!g.isProviderContract()) missing.add("providerContract");
        if (!g.isNf07Approved()) missing.add("NF07");
        if (!g.isDg08Approved()) missing.add("DG08");
        if (!g.isExistingAiProductionGate()) missing.add("existingAiProductionGate");
        if (!g.isRetentionApproved()) missing.add("retention");
        if (!g.isCostLimitApproved()) missing.add("costLimit");
        if (!g.isHumanEscalation()) missing.add("humanEscalation");
        return new AiProductionGateStatus(missing.isEmpty(), List.copyOf(missing));
    }

    /** local mock/rule以外は、外部送信と全承認項目を同時に満たさない限り拒否する。 */
    public void assertProviderAllowed(String provider) {
        if (isLocalProvider(provider)) {
            if (aiConfig.isExternalSendEnabled()) {
                throw new BusinessException(503, "AI external-send is disabled by policy");
            }
            return;
        }
        if (provider == null || provider.isBlank()) {
            throw new BusinessException(503, "AI provider is not configured");
        }
        if (!aiConfig.isExternalSendEnabled()) {
            throw new BusinessException(503, "AI external-send is disabled by policy");
        }
        AiProductionGateStatus decision = status();
        if (!decision.approved()) {
            throw new BusinessException(503, "AI provider approval is incomplete: "
                    + String.join(",", decision.missingGates()));
        }
    }

    public boolean isLocalProvider(String provider) {
        return "mock".equalsIgnoreCase(provider) || "rule".equalsIgnoreCase(provider);
    }

    public record AiProductionGateStatus(boolean approved, List<String> missingGates) {
        public AiProductionGateStatus {
            missingGates = missingGates == null ? List.of() : List.copyOf(missingGates);
        }
    }
}
