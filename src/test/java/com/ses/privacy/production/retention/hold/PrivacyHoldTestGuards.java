package com.ses.privacy.production.retention.hold;

import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 破壊的経路の test 用の {@link PrivacyHoldPathGuard}（A1H-2）。本物の guard を、tenant ごとの判定を差し替えられる gate の
 * mock と本人導出の mock（{@link HoldSubjectResolver}）の上に組む。既定は全 tenant が NO_HOLDS（hold 機構を使っていない）で、
 * 既存の経路の test はこれを配線するだけで従来どおりに動く。mock は lenient（使わない test で不要 stub の失敗にしない）。
 * gate そのものの判定（照会1回・Journal と projection の一致）は {@code PrivacyHoldGateTest} と {@code PrivacyHoldPathGuardTest} が扱う。
 */
public final class PrivacyHoldTestGuards {

    private PrivacyHoldTestGuards() {
    }

    /** hold 機構を使っていない tenant の判定。 */
    public static PrivacyHoldGate.Decision noHolds() {
        return new PrivacyHoldGate.Decision(PrivacyHoldGate.State.NO_HOLDS, null, null, null, null);
    }

    /** gate が判定できない（Journal 不達など）。 */
    public static PrivacyHoldGate.Decision unavailable() {
        return new PrivacyHoldGate.Decision(PrivacyHoldGate.State.UNAVAILABLE, PrivacyHoldGate.REASON_JOURNAL_UNAVAILABLE,
                null, null, null);
    }

    /** Journal と一致した hold 集合。 */
    public static PrivacyHoldGate.Decision verified(Set<String> heldSubjects, Set<String> heldElements,
                                                    Map<String, SubjectAttribution> catalog) {
        return new PrivacyHoldGate.Decision(PrivacyHoldGate.State.VERIFIED, null, heldSubjects, heldElements, catalog);
    }

    /** 全 tenant が NO_HOLDS の guard。 */
    public static PrivacyHoldPathGuard noHoldsGuard() {
        return stub().guard;
    }

    public static Stub stub() {
        return new Stub();
    }

    /** 差し替えられる gate・resolver と、それを使う本物の guard・provider。 */
    public static final class Stub {
        public final PrivacyHoldGate gate = mock(PrivacyHoldGate.class, withSettings().strictness(Strictness.LENIENT));
        public final HoldSubjectResolver resolver = mock(HoldSubjectResolver.class,
                withSettings().strictness(Strictness.LENIENT));
        public final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        public final PrivacyHoldPathGuard guard;
        public final PrivacyHoldFileReferenceProvider fileReferenceProvider;
        /** gate の照会回数（evaluate・evaluateLocked・evaluateEngagedTenants の合計）。 */
        public final AtomicInteger gateCalls = new AtomicInteger();
        private final Map<String, PrivacyHoldGate.Decision> byTenant = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        private Stub() {
            when(gate.evaluate(any())).thenAnswer(invocation -> decisionOf(invocation.getArgument(0)));
            when(gate.evaluateLocked(any())).thenAnswer(invocation -> decisionOf(invocation.getArgument(0)));
            when(gate.evaluateEngagedTenants()).thenAnswer(invocation -> {
                gateCalls.incrementAndGet();
                List<PrivacyHoldGate.TenantDecision> engaged = new ArrayList<>();
                byTenant.forEach((tenant, decision) -> {
                    if (decision.engaged()) {
                        engaged.add(new PrivacyHoldGate.TenantDecision(tenant, decision));
                    }
                });
                return List.copyOf(engaged);
            });
            ObjectProvider<MeterRegistry> meterProvider = mock(ObjectProvider.class,
                    withSettings().strictness(Strictness.LENIENT));
            when(meterProvider.getIfAvailable()).thenReturn(meters);
            this.guard = new PrivacyHoldPathGuard(gate, resolver, meterProvider);
            this.fileReferenceProvider = new PrivacyHoldFileReferenceProvider(gate, resolver, meterProvider);
        }

        /** metric の値（名前と tag 1組。無ければ 0）。 */
        public double counter(String name, String tag, String value) {
            var counter = meters.find(name).tag(tag, value).counter();
            return counter == null ? 0 : counter.count();
        }

        /** tenant の判定を差し替える。 */
        public Stub tenant(String tenantId, PrivacyHoldGate.Decision decision) {
            byTenant.put(tenantId, decision);
            return this;
        }

        /** hold で消さなかった件数の metric の値（経路・判定ごと）。 */
        public double skipped(PrivacyHoldPath path, PrivacyHoldGate.Verdict verdict) {
            var counter = meters.find(PrivacyHoldPathGuard.SKIPPED_METRIC)
                    .tag("path", path.name()).tag("verdict", verdict.name()).counter();
            return counter == null ? 0 : counter.count();
        }

        private PrivacyHoldGate.Decision decisionOf(String tenantId) {
            gateCalls.incrementAndGet();
            return byTenant.getOrDefault(tenantId, noHolds());
        }
    }
}
