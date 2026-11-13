package com.ses.service.ai;

import com.ses.config.AiConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF08: 外部送信承認設定は全項目が揃い現行設定と一致する場合だけ完全とみなす。 */
class AiExternalSendApprovalTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    @Test
    void 既定のAiConfigは承認不完全で値を持たない() {
        AiExternalSendApproval.Evaluation evaluation = AiExternalSendApproval.evaluate(new AiConfig(), TODAY);

        assertFalse(evaluation.complete());
        assertNull(evaluation.snapshot());
        assertTrue(evaluation.violations().contains("decisionId.missing"));
        assertTrue(evaluation.violations().contains("costLimit.perRequestYen"));
    }

    @Test
    void 完全な承認設定はsnapshotへ正規化して返す() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setDestinationHost("GenerativeLanguage.GoogleApis.com");
        config.getExternalSendApproval().setPurposes(new ArrayList<>(List.of(" management_copilot ")));

        AiExternalSendApproval.Evaluation evaluation = AiExternalSendApproval.evaluate(config, TODAY);

        assertTrue(evaluation.complete(), evaluation.violations().toString());
        AiExternalSendApproval.Snapshot snapshot = evaluation.snapshot();
        assertEquals(AiExternalSendApprovalFixtures.DECISION_ID, snapshot.decisionId());
        assertEquals("gemini", snapshot.provider());
        assertEquals(AiExternalSendApprovalFixtures.MODEL, snapshot.model());
        assertEquals(Set.of(AiGatewayRequest.USE_COPILOT), snapshot.purposes());
        assertEquals(AiExternalSendApprovalFixtures.HOST, snapshot.destinationHost());
        assertEquals(50, snapshot.costLimit().perRequestYen());
        assertTrue(snapshot.permitsPurpose(AiGatewayRequest.USE_COPILOT));
        assertFalse(snapshot.permitsPurpose(AiGatewayRequest.USE_CHAT));
        assertFalse(snapshot.permitsPurpose(null));
    }

    static Stream<Arguments> 欠落や不一致の各項目() {
        return Stream.of(
                Arguments.of("decisionId.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDecisionId(" ")),
                Arguments.of("decisionId.placeholder", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDecisionId("NF08-TBD-0001")),
                Arguments.of("decisionId.placeholder", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDecisionId("PENDING-SIGNATURE")),
                Arguments.of("decisionId.format", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDecisionId("<OWNER>")),
                Arguments.of("dg08DecisionId.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDg08DecisionId(null)),
                Arguments.of("nf07PolicyId.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setNf07PolicyId("")),
                Arguments.of("dpaReference.placeholder", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDpaReference("DPA-UNSIGNED-01")),
                Arguments.of("decisionIds.notDistinct", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDg08DecisionId(AiExternalSendApprovalFixtures.DECISION_ID.toLowerCase())),
                Arguments.of("signedOn.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setSignedOn(null)),
                Arguments.of("signedOn.format", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setSignedOn("2026/11/01")),
                Arguments.of("signedOn.future", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setSignedOn(TODAY.plusDays(1).toString())),
                Arguments.of("expiresOn.expired", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setExpiresOn(TODAY.toString())),
                Arguments.of("expiresOn.tooLong", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setExpiresOn(TODAY.plusDays(400).toString())),
                Arguments.of("provider.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setProvider(null)),
                Arguments.of("provider.unsupported", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setProvider("mock")),
                Arguments.of("provider.mismatch", (Consumer<AiConfig>) c -> c.setProvider("rule")),
                Arguments.of("model.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setModel(null)),
                Arguments.of("model.format", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setModel("gemini/../x")),
                Arguments.of("model.mismatch", (Consumer<AiConfig>) c -> c.setModel("gemini-other-model")),
                Arguments.of("model.mismatch", (Consumer<AiConfig>) c -> c.setModel(null)),
                Arguments.of("purposes.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setPurposes(new ArrayList<>(List.of(" ")))),
                Arguments.of("purposes.unsupported", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setPurposes(new ArrayList<>(List.of(AiGatewayRequest.USE_COPILOT, AiGatewayRequest.USE_CHAT)))),
                Arguments.of("purposes.unsupported", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setPurposes(new ArrayList<>(List.of(AiProductionApprovalGate.PURPOSE_LEGACY_ENDPOINT)))),
                Arguments.of("destinationHost.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDestinationHost(null)),
                Arguments.of("destinationHost.format", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDestinationHost("https://generativelanguage.googleapis.com")),
                Arguments.of("destinationHost.notAllowlisted", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setDestinationHost("generativelanguage.googleapis.com.evil.test")),
                Arguments.of("apiUrl.notCanonical", (Consumer<AiConfig>) c -> c.setApiUrl("https://generativelanguage.googleapis.com/v1beta/models/x:generateContent")),
                Arguments.of("apiUrl.notCanonical", (Consumer<AiConfig>) c -> c.setApiUrl("http://generativelanguage.googleapis.com")),
                Arguments.of("destinationHost.mismatch", (Consumer<AiConfig>) c -> c.setApiUrl("https://other-provider.example.com")),
                Arguments.of("maxTokens.unbounded", (Consumer<AiConfig>) c -> c.setMaxTokens(0)),
                Arguments.of("costLimit.missing", (Consumer<AiConfig>) c -> c.getExternalSendApproval().setCostLimit(null)),
                Arguments.of("costLimit.perRequestYen", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setPerRequestYen(0)),
                Arguments.of("costLimit.dailyYen", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setDailyYen(null)),
                Arguments.of("costLimit.monthlyYen", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setMonthlyYen(-1)),
                Arguments.of("costLimit.dailyBelowPerRequest", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setDailyYen(49)),
                Arguments.of("costLimit.monthlyBelowDaily", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setMonthlyYen(99)),
                Arguments.of("costLimit.inputRate", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setInputYenPerThousandTokens(BigDecimal.ZERO)),
                Arguments.of("costLimit.outputRate", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setOutputYenPerThousandTokens(null)),
                Arguments.of("costLimit.maxRequestBytes", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setMaxRequestBytes(64 * 1024 + 1)),
                Arguments.of("costLimit.maxThinkingTokens", (Consumer<AiConfig>) c -> c.getExternalSendApproval().getCostLimit().setMaxThinkingTokens(-1)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("欠落や不一致の各項目")
    void 一項目でも欠落や不一致があれば承認しない(String expectedViolation, Consumer<AiConfig> breakApproval) {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        breakApproval.accept(config);

        AiExternalSendApproval.Evaluation evaluation = AiExternalSendApproval.evaluate(config, TODAY);

        assertFalse(evaluation.complete());
        assertNull(evaluation.snapshot());
        assertTrue(evaluation.violations().contains(expectedViolation),
                "expected " + expectedViolation + " in " + evaluation.violations());
    }

    @Test
    void 違反コードは設定値そのものを含まない() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setDecisionId("SECRET-LOOKING-VALUE-TBD");
        config.setModel("customer-name-model");

        AiExternalSendApproval.Evaluation evaluation = AiExternalSendApproval.evaluate(config, TODAY);

        assertFalse(evaluation.complete());
        String joined = String.join(",", evaluation.violations());
        assertFalse(joined.contains("SECRET-LOOKING-VALUE"));
        assertFalse(joined.contains("customer-name-model"));
    }

    @Test
    void 失効日の前日までは有効で当日から拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setExpiresOn(TODAY.plusDays(1).toString());

        assertTrue(AiExternalSendApproval.evaluate(config, TODAY).complete());
        assertFalse(AiExternalSendApproval.evaluate(config, TODAY.plusDays(1)).complete());
    }

    @Test
    void 宛先hostはapiUrl未設定ならprovider既定hostと照合する() {
        assertEquals(AiExternalSendApprovalFixtures.HOST, AiExternalSendApproval.endpointHost(null, "gemini"));
        assertEquals(AiExternalSendApprovalFixtures.HOST,
                AiExternalSendApproval.endpointHost("https://generativelanguage.googleapis.com/", "gemini"));
        assertNull(AiExternalSendApproval.endpointHost("https://user@generativelanguage.googleapis.com", "gemini"));
        assertNull(AiExternalSendApproval.endpointHost("https://generativelanguage.googleapis.com:8443", "gemini"));
        assertNull(AiExternalSendApproval.endpointHost(null, "unknown"));
    }
}
