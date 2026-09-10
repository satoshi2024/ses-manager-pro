package com.ses.service.ai;

import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** NF08: production approvalは独立gateの一つ欠落でも実providerを許可しない。 */
class AiProductionApprovalGateTest {

    static Stream<org.junit.jupiter.params.provider.Arguments> everyIndependentGateIsRequired() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of("owner", (Consumer<AiConfig.ProductionGates>) g -> g.setOwnerApproved(false)),
                org.junit.jupiter.params.provider.Arguments.of("approvedCatalog", (Consumer<AiConfig.ProductionGates>) g -> g.setApprovedCatalog(false)),
                org.junit.jupiter.params.provider.Arguments.of("allowedRoles", (Consumer<AiConfig.ProductionGates>) g -> g.setAllowedRoles(false)),
                org.junit.jupiter.params.provider.Arguments.of("providerContract", (Consumer<AiConfig.ProductionGates>) g -> g.setProviderContract(false)),
                org.junit.jupiter.params.provider.Arguments.of("NF07", (Consumer<AiConfig.ProductionGates>) g -> g.setNf07Approved(false)),
                org.junit.jupiter.params.provider.Arguments.of("DG08", (Consumer<AiConfig.ProductionGates>) g -> g.setDg08Approved(false)),
                org.junit.jupiter.params.provider.Arguments.of("existingAiProductionGate", (Consumer<AiConfig.ProductionGates>) g -> g.setExistingAiProductionGate(false)),
                org.junit.jupiter.params.provider.Arguments.of("retention", (Consumer<AiConfig.ProductionGates>) g -> g.setRetentionApproved(false)),
                org.junit.jupiter.params.provider.Arguments.of("costLimit", (Consumer<AiConfig.ProductionGates>) g -> g.setCostLimitApproved(false)),
                org.junit.jupiter.params.provider.Arguments.of("humanEscalation", (Consumer<AiConfig.ProductionGates>) g -> g.setHumanEscalation(false)));
    }

    @ParameterizedTest(name = "missing {0} gate is fail-closed")
    @MethodSource("everyIndependentGateIsRequired")
    void 独立gateごとに欠落すれば実providerを拒否する(String expectedMissing,
                                             Consumer<AiConfig.ProductionGates> removeGate) {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setExternalSendEnabled(true);
        enableAll(config.getProductionGates());
        removeGate.accept(config.getProductionGates());

        AiProductionApprovalGate gate = new AiProductionApprovalGate(config);

        assertThrows(BusinessException.class, () -> gate.assertProviderAllowed("gemini"));
        assertEquals(false, gate.status().approved());
        assertEquals(true, gate.status().missingGates().contains(expectedMissing));
    }

    @Test
    void 各gateが一つでも欠ければ拒否する() {
        AiConfig config = new AiConfig();
        config.setExternalSendEnabled(true);
        config.setProvider("gemini");
        AiConfig.ProductionGates gates = config.getProductionGates();
        gates.setOwnerApproved(true);
        gates.setApprovedCatalog(true);
        gates.setAllowedRoles(true);
        gates.setProviderContract(true);
        gates.setNf07Approved(true);
        gates.setDg08Approved(true);
        gates.setExistingAiProductionGate(true);
        gates.setRetentionApproved(true);
        gates.setCostLimitApproved(true);
        gates.setHumanEscalation(true);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config);

        assertEquals(true, gate.status().approved());
        gates.setHumanEscalation(false);
        assertThrows(BusinessException.class, () -> gate.assertProviderAllowed("gemini"));
        assertEquals(false, gate.status().approved());
        assertEquals("humanEscalation", gate.status().missingGates().get(0));
    }

    @Test
    void externalSend無効なら全gateが揃っても実providerを拒否する() {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setExternalSendEnabled(false);
        assertThrows(BusinessException.class,
                () -> new AiProductionApprovalGate(config).assertProviderAllowed("gemini"));
    }

    @Test
    void mockとruleだけが外部送信なしで許可される() {
        AiConfig config = new AiConfig();
        config.setExternalSendEnabled(false);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config);
        gate.assertProviderAllowed("mock");
        gate.assertProviderAllowed("rule");
    }

    private static void enableAll(AiConfig.ProductionGates gates) {
        gates.setOwnerApproved(true);
        gates.setApprovedCatalog(true);
        gates.setAllowedRoles(true);
        gates.setProviderContract(true);
        gates.setNf07Approved(true);
        gates.setDg08Approved(true);
        gates.setExistingAiProductionGate(true);
        gates.setRetentionApproved(true);
        gates.setCostLimitApproved(true);
        gates.setHumanEscalation(true);
    }
}
