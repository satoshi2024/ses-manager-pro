package com.ses.service.ai.copilot.summary;

import com.ses.service.ai.AiGatewayRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        for (String summary : List.of(
                "稼働率は7%です",
                "売上は1,250円です",
                "利益は-3.5です",
                "伸び率は1.2e3%です",
                "進捗は٣件です",
                "評価はⅣです",
                "候補は⑦件です",
                "売上は¥5です",
                "予算は＄10です",
                "稼働率は７５％です",
                "稼働率は75‰です",
                "差分は一件です",
                "売上は七です",
                "利益は壱萬円です")) {
            CopilotSummaryValidationException exception = assertThrows(
                    CopilotSummaryValidationException.class,
                    () -> CopilotSummaryValidator.validateSummaryText(summary),
                    summary);
            assertEquals("NUMERIC_RECALC_REJECTED", exception.reasonCode(), summary);
        }
    }

    @Test
    void 数値や通貨記号を含まない自然な説明文は通過する() {
        for (String summary : List.of(
                "全体の傾向を確認できました。必要に応じて詳細画面をご覧ください。",
                "一般的な傾向です。",
                "一方で改善しています。",
                "案件参画が進んでいます。")) {
            assertDoesNotThrow(() -> CopilotSummaryValidator.validateSummaryText(summary), summary);
        }
    }

    @Test
    void 数字を含む正当なclaimKeyは本文の数値検査対象外() {
        assertDoesNotThrow(() -> CopilotSummaryValidator.validateClaimKeys(
                List.of("forecast.utilization.2026-09"),
                List.of("forecast.utilization.2026-09")));
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
