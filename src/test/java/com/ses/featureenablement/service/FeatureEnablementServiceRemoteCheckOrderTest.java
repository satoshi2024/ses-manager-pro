package com.ses.featureenablement.service;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.Feature;
import com.ses.entity.FeatureEnablementRequest;
import com.ses.featureenablement.FeatureEnablementTestSnapshots;
import com.ses.featureenablement.catalog.FeatureEnablementCatalog;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureConditionRegistry;
import com.ses.featureenablement.condition.FeatureEvaluation;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.mapper.FeatureEnablementRequestMapper;
import com.ses.mapper.FeatureEnablementStateMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * review rb-fe-c P2-6・P3-N4（design §4.2 の {@code remoteChecksAllowed}）: 申請と承認は、世代の行の lock（{@code write.begin}）の前に
 * 開放単位の外部への検査を新しくし（{@code registry.refreshRemote}）、lock の中の評価は {@code remoteChecksAllowed=false} で最後の結果を
 * 読む。段階 D の評価器（NF05 の truststore 等）も同じ順序に乗るため、順序そのものを固定する。
 */
class FeatureEnablementServiceRemoteCheckOrderTest {

    private static final Instant NOW = Instant.parse("2026-11-23T01:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 23);
    private static final LocalDateTime NOW_LOCAL = LocalDateTime.of(2026, 11, 23, 10, 0);

    private final FeatureEnablementWriteSupport write = mock(FeatureEnablementWriteSupport.class);
    private final FeatureEnablementActorPolicy actorPolicy = mock(FeatureEnablementActorPolicy.class);
    private final FeatureConditionRegistry registry = mock(FeatureConditionRegistry.class);
    private final FeatureEnablementRequestMapper requestMapper = mock(FeatureEnablementRequestMapper.class);
    private final FeatureEnablementWriteSupport.WriteContext context = mock(FeatureEnablementWriteSupport.WriteContext.class);
    private final FeatureEnablementActor admin = new FeatureEnablementActor(1L, "admin");
    private FeatureEnablementServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new FeatureEnablementServiceImpl(write, actorPolicy, registry, FeatureEnablementCatalog.standard(),
                requestMapper, mock(FeatureEnablementStateMapper.class), new FeatureEnablementProperties(),
                mock(ObjectProvider.class));
        when(actorPolicy.requireActiveAdministrator(any())).thenReturn(admin);
        when(write.begin(any())).thenReturn(context);
        when(write.clock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        FeatureEnablementSnapshot snapshot = FeatureEnablementTestSnapshots.builder(NOW).build();
        when(context.reread()).thenReturn(snapshot);
        when(context.snapshot()).thenReturn(snapshot);
        when(context.tenantId()).thenReturn("default");
        when(context.today()).thenReturn(TODAY);
        when(context.now()).thenReturn(NOW_LOCAL);
        // 条件を満たさない評価で止める（順序だけを見る）
        when(registry.evaluate(any(), any())).thenReturn(new FeatureEvaluation(Feature.NF04,
                FeatureEvaluationContext.Purpose.REQUEST, List.of(FeatureConditionCheck.unsatisfied("fixture.unsatisfied",
                FeatureConditionCheck.Category.AUTO, false)), NOW));
    }

    @Test
    void 申請はlockの前に外部への検査を新しくしlockの中は外部へ出ない評価にする() {
        BusinessException rejected = catchThrowableOfType(() -> service.requestOpen(Feature.NF04, null, admin),
                BusinessException.class);

        assertThat(rejected.getMessageKey()).isEqualTo("error.featureEnablement.conditionsUnsatisfied");
        InOrder order = inOrder(registry, write);
        order.verify(registry).refreshRemote(Feature.NF04);
        order.verify(write).begin(any());
        order.verify(registry).evaluate(eq(Feature.NF04), argThat(evaluation -> !evaluation.remoteChecksAllowed()
                && evaluation.purpose() == FeatureEvaluationContext.Purpose.REQUEST));
    }

    @Test
    void 承認はlockの前に外部への検査を新しくしlockの中は外部へ出ない評価にする() {
        FeatureEnablementRequest pending = new FeatureEnablementRequest();
        pending.setId(10L);
        pending.setTenantId("default");
        pending.setRequestType(FeatureEnablementServiceImpl.REQUEST_TYPE_OPEN);
        pending.setTargetKey(Feature.NF04.name());
        pending.setStatus("PENDING");
        pending.setVersion(0);
        pending.setRequestedBy(2L);
        pending.setExpiresAt(NOW_LOCAL.plusDays(7));
        when(requestMapper.selectById(10L)).thenReturn(pending);
        when(requestMapper.selectByIdForUpdate(10L)).thenReturn(pending);

        FeatureEnablementApprovalResult result = service.approve(10L, 0, null, admin);

        assertThat(result.outcome()).isEqualTo(FeatureEnablementApprovalResult.Outcome.CONDITIONS_UNSATISFIED);
        InOrder order = inOrder(registry, write);
        order.verify(registry).refreshRemote(Feature.NF04);
        order.verify(write).begin(any());
        order.verify(registry).evaluate(eq(Feature.NF04), argThat(evaluation -> !evaluation.remoteChecksAllowed()
                && evaluation.purpose() == FeatureEvaluationContext.Purpose.APPROVAL));
    }
}
