package com.ses.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.service.ai.AiExternalSendApprovalFixtures;
import com.ses.service.ai.AiProductionApprovalGate;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** NF-08 本番profileのAI安全起動条件を確認する。 */
class AiProductionSafetyValidatorTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneId.of("UTC"));
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);

    @Test
    void 未承認のmanagementCopilotは本番起動を拒否する() {
        AiConfig config = new AiConfig();
        config.setManagementCopilotEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> new AiProductionSafetyValidator(config).validate());
    }

    @Test
    void externalSendは本番起動を拒否する() {
        AiConfig config = new AiConfig();
        config.setExternalSendEnabled(true);

        assertThrows(IllegalStateException.class,
                () -> new AiProductionSafetyValidator(config).validate());
    }

    @Test
    void 既定設定は外部送信もcopilotも無効のまま起動できる() {
        assertDoesNotThrow(() -> new AiProductionSafetyValidator(new AiConfig()).validate());
    }

    @Test
    void チェックリスト全trueでも承認設定がなければexternalSendは起動失敗する() {
        AiConfig config = new AiConfig();
        config.setProvider("gemini");
        config.setExternalSendEnabled(true);
        AiExternalSendApprovalFixtures.enableChecklist(config.getProductionGates());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("approval.decisionId.missing"));
    }

    @Test
    void 署名済み承認設定が完全ならexternalSendで起動できる() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);

        assertDoesNotThrow(() -> validator(config).validate());
    }

    @Test
    void 承認設定の一項目欠落は起動時にfailFastする() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().getCostLimit().setMonthlyYen(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("approval.costLimit.monthlyYen"));
        assertFalse(ex.getMessage().contains(AiExternalSendApprovalFixtures.DPA_REFERENCE),
                "起動失敗メッセージは不足コードだけを示す");
    }

    @Test
    void 承認設定とチェックリストの不一致は起動時にfailFastする() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getProductionGates().setDg08Approved(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("checklist.DG08"));
    }

    @Test
    void 承認設定がlocalProviderを指す外部送信は起動失敗する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.setProvider("mock");

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("provider.local"));
    }

    @Test
    void 外部送信が無効なら承認設定の準備だけでは起動を妨げない() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.setExternalSendEnabled(false);
        config.getExternalSendApproval().setDecisionId(null);

        assertDoesNotThrow(() -> validator(config).validate());
    }

    @Test
    void 承認の失効は起動を止めず外部送信だけを停止してWARNを出す() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setExpiresOn(TODAY.toString());
        AiExternalSendApprovalFixtures.resign(config);
        AiProductionApprovalGate gate = new AiProductionApprovalGate(config, FIXED);
        Logger logger = (Logger) LoggerFactory.getLogger(AiProductionSafetyValidator.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> new AiProductionSafetyValidator(config, gate).validate());
        } finally {
            logger.detachAppender(appender);
        }

        assertFalse(gate.externalSendStatus().approved(), "外部送信は停止したまま");
        assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.WARN
                && event.getFormattedMessage().contains(
                        AiProductionSafetyValidator.LOG_CODE_STARTED_WITH_EXTERNAL_SEND_STOPPED)
                && event.getFormattedMessage().contains("approval.expiresOn.expired")));
        assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage()
                .contains(AiExternalSendApprovalFixtures.DECISION_ID)), "WARNは不足コードだけを示す");
    }

    @Test
    void 署名日前の承認も起動を止めない() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setSignedOn(TODAY.plusDays(2).toString());
        AiExternalSendApprovalFixtures.resign(config);

        assertDoesNotThrow(() -> validator(config).validate());
    }

    @Test
    void 失効と構造的な不足が重なれば起動時にfailFastする() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setExpiresOn(TODAY.toString());
        AiExternalSendApprovalFixtures.resign(config);
        config.getProductionGates().setHumanEscalation(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("checklist.humanEscalation"));
        assertTrue(ex.getMessage().contains("approval.expiresOn.expired"), "診断のため時間要因の不足も併記する");
    }

    @Test
    void 承認digestが決定書と一致しなければ起動時にfailFastする() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        // 同じ決定IDのまま日次上限だけを書き換えた（決定書のdigestを取り直していない）設定。
        config.getExternalSendApproval().getCostLimit().setDailyYen(900);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("approval.approvalDigest.mismatch"));
        assertFalse(ex.getMessage().contains(AiExternalSendApprovalFixtures.DECISION_ID));
    }

    @Test
    void 失効日を書き換えた設定はdigest不一致として起動時にfailFastする() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setExpiresOn(TODAY.toString());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains("approval.approvalDigest.mismatch"));
    }

    private static AiProductionSafetyValidator validator(AiConfig config) {
        return new AiProductionSafetyValidator(config, new AiProductionApprovalGate(config, FIXED));
    }
}
