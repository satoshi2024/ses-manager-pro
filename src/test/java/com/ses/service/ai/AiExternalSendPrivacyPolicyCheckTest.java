package com.ses.service.ai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ses.common.exception.BusinessException;
import com.ses.config.AiConfig;
import com.ses.privacy.foundation.PolicyEnforcementDecision;
import com.ses.privacy.foundation.PolicyEnforcementPort;
import com.ses.privacy.foundation.PolicyEnforcementReason;
import com.ses.privacy.foundation.PolicyEnforcementRequest;
import com.ses.privacy.foundation.PolicyFailureReason;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF07-NF08-LINK の NF08 側の判定（{@link AiExternalSendPrivacyPolicyCheck}）と共有gateの委譲。
 * NF07 の内部判定 port は test-only の stub（実 provider・実 secret・DB なし）。
 */
class AiExternalSendPrivacyPolicyCheckTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 13);
    private static final String TENANT = "tenant-a";

    private final List<PolicyEnforcementRequest> requests = new ArrayList<>();
    private SimpleMeterRegistry meters;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        logger = (Logger) LoggerFactory.getLogger(AiExternalSendPrivacyPolicyCheck.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void NF08の定数のkeyと承認設定のpolicyIdと実行tenantでNF07へ問い合わせる() {
        AiExternalSendPrivacyPolicyCheck check = check(answering(PolicyEnforcementDecision.permit()));

        assertTrue(check.permits(TENANT, approval(), AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));

        // 承認の署名日（fixture は TODAY の前日 2026-11-12）の翌日0時 JST を上限として渡す（W3-03 P3-2）
        assertEquals(List.of(new PolicyEnforcementRequest(TENANT, "NF08_AI_SUMMARY", "AI_PROMPT_LOG",
                "EXTERNAL_DISPATCH", "gemini-adapter", AiExternalSendApprovalFixtures.NF07_POLICY_ID,
                Instant.parse("2026-11-12T15:00:00Z"))), requests);
        assertTrue(appender.list.isEmpty(), "許可はlogを出さない");
        assertTrue(meters.getMeters().isEmpty(), "許可はmetricを出さない");
    }

    @ParameterizedTest
    @EnumSource(value = PolicyEnforcementReason.class, names = "PERMITTED", mode = EnumSource.Mode.EXCLUDE)
    void NF07の拒否は全て送信を止め固定コードだけをlogとmetricへ出す(PolicyEnforcementReason reason) {
        AiExternalSendPrivacyPolicyCheck check = check(answering(
                PolicyEnforcementDecision.deny(reason, PolicyFailureReason.REVOCATION_STATUS_UNKNOWN)));

        assertEquals(reason, check.evaluate(TENANT, approval(), AiExternalSendPrivacyPolicyCheck.Stage.ADAPTER));
        assertFalse(check.permits(TENANT, approval(), AiExternalSendPrivacyPolicyCheck.Stage.ADAPTER));

        assertEquals(2.0, meters.counter(AiExternalSendPrivacyPolicyCheck.DENIED_METRIC,
                "stage", "ADAPTER", "reason", reason.name()).count());
        String logs = joinedLogs();
        assertTrue(logs.contains(AiExternalSendPrivacyPolicyCheck.LOG_CODE_DENIED), logs);
        assertTrue(logs.contains("reason=" + reason.name()), logs);
        assertTrue(logs.contains("detail=REVOCATION_STATUS_UNKNOWN"), logs);
        for (String secret : List.of(TENANT, AiExternalSendApprovalFixtures.NF07_POLICY_ID,
                AiExternalSendApprovalFixtures.DECISION_ID)) {
            assertFalse(logs.contains(secret), "log・metricにtenant・policy ID・決定IDを出さない");
            meters.getMeters().forEach(meter -> meter.getId().getTags()
                    .forEach(tag -> assertFalse(tag.getValue().contains(secret))));
        }
        assertTrue(appender.list.stream().allMatch(event -> event.getLevel() == Level.WARN));
    }

    @Test
    void portが無い_例外_nullを返すときは送信しない() {
        assertEquals(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE, AiExternalSendPrivacyPolicyCheck.unavailable()
                .evaluate(TENANT, approval(), AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        assertEquals(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE, new AiExternalSendPrivacyPolicyCheck(() -> {
            throw new IllegalStateException("bean unavailable");
        }, () -> meters).evaluate(TENANT, approval(), AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        assertEquals(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE, check(request -> {
            throw new IllegalStateException("contract violation");
        }).evaluate(TENANT, approval(), AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        assertEquals(PolicyEnforcementReason.ENFORCEMENT_UNAVAILABLE, check(request -> null)
                .evaluate(TENANT, approval(), AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        // metric の登録先の障害は判定を変えない
        assertFalse(new AiExternalSendPrivacyPolicyCheck(() -> answering(
                PolicyEnforcementDecision.deny(PolicyEnforcementReason.POLICY_DRIFT, PolicyFailureReason.NONE)),
                () -> {
                    throw new IllegalStateException("registry unavailable");
                }).permits(TENANT, approval(), AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
    }

    @Test
    void tenant_承認_providerの対応が無ければNF07へ問い合わせずに拒否する() {
        AtomicInteger calls = new AtomicInteger();
        AiExternalSendPrivacyPolicyCheck check = check(request -> {
            calls.incrementAndGet();
            return PolicyEnforcementDecision.permit();
        });
        AiExternalSendApproval.Snapshot approval = approval();
        AiExternalSendApproval.Snapshot otherProvider = new AiExternalSendApproval.Snapshot(approval.decisionId(),
                approval.dg08DecisionId(), approval.nf07PolicyId(), approval.dpaReference(), approval.signedOn(),
                approval.expiresOn(), "other-provider", approval.model(), approval.purposes(),
                approval.dataCategories(), approval.destinationHost(), approval.costLimit(), approval.approvalDigest());

        for (String tenant : java.util.Arrays.asList(null, "", " ")) {
            assertEquals(PolicyEnforcementReason.REQUEST_INVALID,
                    check.evaluate(tenant, approval, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        }
        assertEquals(PolicyEnforcementReason.REQUEST_INVALID,
                check.evaluate(TENANT, null, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        assertEquals(PolicyEnforcementReason.REQUEST_INVALID,
                check.evaluate(TENANT, otherProvider, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        AiExternalSendApproval.Snapshot noSignedOn = new AiExternalSendApproval.Snapshot(approval.decisionId(),
                approval.dg08DecisionId(), approval.nf07PolicyId(), approval.dpaReference(), null,
                approval.expiresOn(), approval.provider(), approval.model(), approval.purposes(),
                approval.dataCategories(), approval.destinationHost(), approval.costLimit(), approval.approvalDigest());
        assertEquals(PolicyEnforcementReason.REQUEST_INVALID,
                check.evaluate(TENANT, noSignedOn, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        assertEquals(0, calls.get());
    }

    @Test
    void 共有gateは手動構築ではNF07の判定を常に拒否し指定したcheckへ委譲する() {
        AiConfig config = AiExternalSendApprovalFixtures.approvedConfig(TODAY);
        Clock clock = Clock.fixed(Instant.parse("2026-11-13T01:00:00Z"), ZoneOffset.UTC);
        AiExternalSendApproval.Snapshot approval = approval();

        for (AiProductionApprovalGate manual : List.of(new AiProductionApprovalGate(config),
                new AiProductionApprovalGate(config, clock), new AiProductionApprovalGate(config, clock,
                        (AiExternalSendPrivacyPolicyCheck) null))) {
            assertFalse(manual.externalSendPrivacyPolicyPermits(TENANT, approval,
                    AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
            BusinessException denied = assertThrows(BusinessException.class, () -> manual
                    .assertExternalSendPrivacyPolicy(TENANT, approval, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
            assertEquals(503, denied.getCode());
            assertEquals(AiProductionApprovalGate.REASON_PRIVACY_POLICY_NOT_ACTIVE, denied.getMessage());
        }

        AiProductionApprovalGate linked = new AiProductionApprovalGate(config, clock,
                check(answering(PolicyEnforcementDecision.permit())));
        assertTrue(linked.externalSendPrivacyPolicyPermits(TENANT, approval,
                AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY));
        linked.assertExternalSendPrivacyPolicy(TENANT, approval, AiExternalSendPrivacyPolicyCheck.Stage.GATEWAY);
        // NF07 の判定は起動時の承認判定に含めない（policy の状態は送信のたびに評価する）
        assertTrue(linked.startupExternalSendStatus().approved());
        assertTrue(new AiProductionApprovalGate(config, clock).startupExternalSendStatus().approved());
    }

    private AiExternalSendPrivacyPolicyCheck check(PolicyEnforcementPort port) {
        return new AiExternalSendPrivacyPolicyCheck(() -> port, () -> meters);
    }

    private PolicyEnforcementPort answering(PolicyEnforcementDecision decision) {
        return request -> {
            requests.add(request);
            return decision;
        };
    }

    private static AiExternalSendApproval.Snapshot approval() {
        return AiExternalSendApproval.evaluate(AiExternalSendApprovalFixtures.approvedConfig(TODAY), TODAY).snapshot();
    }

    private String joinedLogs() {
        return String.join("\n", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
}
