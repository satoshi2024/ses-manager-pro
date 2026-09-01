package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.AiGatewayRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CopilotSummaryValidatorTest {

    @Test
    void 正常なsummaryとclaimKeysは通過する() {
        assertDoesNotThrow(() -> {
            CopilotSummaryValidator.validateSummaryText("登録された指標キーを確認しました。");
            CopilotSummaryValidator.validateClaimKeys(
                    List.of("forecast.utilization.2026-09"),
                    List.of("forecast.utilization.2026-09"));
        });
    }

    @Test
    void HTMLは拒否する() {
        assertThrows(CopilotSummaryValidationException.class,
                () -> CopilotSummaryValidator.validateSummaryText("<b>稼働率</b>"));
    }

    @Test
    void 数値再計算は拒否する() {
        assertThrows(CopilotSummaryValidationException.class,
                () -> CopilotSummaryValidator.validateSummaryText("稼働率は75%です"));
    }

    @Test
    void カナリアは拒否する() {
        assertThrows(CopilotSummaryValidationException.class,
                () -> CopilotSummaryValidator.validateSummaryText("leak " + AiGatewayRequest.CANARY));
    }

    @Test
    void 未知のclaimKeyは拒否する() {
        assertThrows(CopilotSummaryValidationException.class,
                () -> CopilotSummaryValidator.validateClaimKeys(
                        List.of("kpi.utilization"),
                        List.of("forecast.utilization.2026-09")));
    }
}
