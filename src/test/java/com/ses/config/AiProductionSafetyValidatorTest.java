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

    @Test
    void 未知providerはprodだけ起動拒否しdevとtestの互換性を維持する() {
        for (String profile : java.util.List.of("prod", "dev", "test")) {
            AiConfig config = new AiConfig();
            config.setProvider("unknown");
            new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                    .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                    .withBean(AiConfig.class, () -> config)
                    .withBean(Clock.class, () -> FIXED)
                    .withUserConfiguration(AiProductionSafetyValidator.class, AiProductionApprovalGate.class)
                    .run(context -> {
                        if ("prod".equals(profile)) {
                            org.assertj.core.api.Assertions.assertThat(context).hasFailed();
                            org.assertj.core.api.Assertions.assertThat(context.getStartupFailure())
                                    .hasRootCauseMessage("本番のAI providerが未登録です: AI_PROVIDER_NOT_REGISTERED");
                        } else {
                            org.assertj.core.api.Assertions.assertThat(context).hasNotFailed();
                        }
                    });
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "unknown", "gemni"})
    void 未登録providerは外部送信OFFでも本番起動時に拒否する(String provider) {
        AiConfig config = new AiConfig();
        config.setProvider(provider);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> validator(config).validate());
        assertTrue(failure.getMessage().contains("AI_PROVIDER_NOT_REGISTERED"));
        assertFalse(failure.getMessage().contains("gemni"));
    }

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
        AiConfig config = new AiConfig();
        // application.ymlの既定providerを明示し、未設定値のprod拒否と区別する。
        config.setProvider("mock");
        assertDoesNotThrow(() -> new AiProductionSafetyValidator(config).validate());
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
    void B2c2完了前は署名済み承認設定が完全でもexternalSendの本番起動を拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains(AiProductionSafetyValidator.GATE_PERMIT_DISPATCH_NOT_WIRED));
        assertFalse(ex.getMessage().contains(AiProductionSafetyValidator.GATE_COST_LEDGER_NOT_PERSISTENT),
                "既定のledgerはpersistent");
        assertFalse(ex.getMessage().contains(AiExternalSendApprovalFixtures.DECISION_ID), "不足コードだけを示す");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {AiConfig.COST_LEDGER_PERSISTENT, AiConfig.COST_LEDGER_MEMORY})
    void B2c2完了前はledger設定にかかわらずexternalSendの本番起動を拒否する(String ledger) {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.setExternalSendCostLedger(ledger);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains(AiProductionSafetyValidator.GATE_PERMIT_DISPATCH_NOT_WIRED));
    }

    @Test
    void B2c2完了前の拒否は承認設定の不足コードも併記する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().getCostLimit().setMonthlyYen(null);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains(AiProductionSafetyValidator.GATE_PERMIT_DISPATCH_NOT_WIRED));
        assertTrue(ex.getMessage().contains("approval.costLimit.monthlyYen"));
    }

    @Test
    void permitOnly送信の接続状態は設定ではなくコード定数で固定されている() throws Exception {
        java.lang.reflect.Field wired = AiProductionSafetyValidator.class.getDeclaredField("PERMIT_ONLY_DISPATCH_WIRED");
        int modifiers = wired.getModifiers();
        assertTrue(java.lang.reflect.Modifier.isStatic(modifiers) && java.lang.reflect.Modifier.isFinal(modifiers));
        wired.setAccessible(true);
        assertFalse(wired.getBoolean(null), "B2c-2の完了と独立Reviewまでfalseを維持する");
        for (java.lang.reflect.Field field : AiConfig.class.getDeclaredFields()) {
            String name = field.getName().toLowerCase(java.util.Locale.ROOT);
            assertFalse(name.contains("permit") || name.contains("wired"),
                    "接続状態を設定値で上書きできるpropertyを持たない: " + field.getName());
        }
    }

    @Test
    void prod_profileのcontextは署名済み承認設定でもexternalSendを起動拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(context -> context.getEnvironment().setActiveProfiles("prod"))
                .withBean(AiConfig.class, () -> config)
                .withBean(Clock.class, () -> FIXED)
                .withUserConfiguration(AiProductionSafetyValidator.class, AiProductionApprovalGate.class)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).hasFailed();
                    org.assertj.core.api.Assertions.assertThat(context.getStartupFailure())
                            .rootCause().hasMessageContaining(AiProductionSafetyValidator.GATE_PERMIT_DISPATCH_NOT_WIRED);
                });
    }

    @Test
    void B2c2完了後は署名済み承認設定とpersistentLedgerで起動できる() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);

        assertDoesNotThrow(() -> wiredValidator(config).validate());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.NullSource
    @org.junit.jupiter.params.provider.ValueSource(strings = {AiConfig.COST_LEDGER_MEMORY, "", " persistent",
            "persistent ", "PERSISTENT", "db"})
    void B2c2完了後もpersistent以外のledgerはexternalSendの本番起動を拒否する(String ledger) {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.setExternalSendCostLedger(ledger);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> wiredValidator(config).validate());
        assertTrue(ex.getMessage().contains(AiProductionSafetyValidator.GATE_COST_LEDGER_NOT_PERSISTENT));
        assertFalse(ex.getMessage().contains(AiProductionSafetyValidator.GATE_PERMIT_DISPATCH_NOT_WIRED));
    }

    @Test
    void 外部送信が無効ならmemoryLedger設定でも起動を妨げない() {
        AiConfig config = new AiConfig();
        config.setProvider("mock");
        config.setExternalSendCostLedger(AiConfig.COST_LEDGER_MEMORY);

        assertDoesNotThrow(() -> validator(config).validate());
        assertDoesNotThrow(() -> wiredValidator(config).validate());
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

    /** B2c-2完了後（permit-only送信の接続後）の判定。B2c-1の間は external-send=true 自体が起動拒否になる。 */
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
            assertDoesNotThrow(() -> new AiProductionSafetyValidator(config, gate, true).validate());
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

    /** B2c-2完了後の判定（B2c-1の間は external-send=true 自体が起動拒否になる）。 */
    @Test
    void 署名日前の承認も起動を止めない() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setSignedOn(TODAY.plusDays(2).toString());
        AiExternalSendApprovalFixtures.resign(config);

        assertDoesNotThrow(() -> wiredValidator(config).validate());
    }

    @Test
    void B2c2完了前は失効のみの承認でもexternalSendの本番起動を拒否する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        config.getExternalSendApproval().setExpiresOn(TODAY.toString());
        AiExternalSendApprovalFixtures.resign(config);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> validator(config).validate());
        assertTrue(ex.getMessage().contains(AiProductionSafetyValidator.GATE_PERMIT_DISPATCH_NOT_WIRED));
        assertTrue(ex.getMessage().contains("approval.expiresOn.expired"));
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

    /** B2c-2完了後（permit-only送信とpersistent ledgerの接続後）を模擬する。 */
    private static AiProductionSafetyValidator wiredValidator(AiConfig config) {
        return new AiProductionSafetyValidator(config, new AiProductionApprovalGate(config, FIXED), true);
    }
}
