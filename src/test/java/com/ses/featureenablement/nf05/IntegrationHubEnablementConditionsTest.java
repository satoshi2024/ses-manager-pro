package com.ses.featureenablement.nf05;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.config.feature.Feature;
import com.ses.config.integrationhub.IntegrationHubEnablementDecision;
import com.ses.config.integrationhub.IntegrationHubExternalApiProperties;
import com.ses.config.integrationhub.Nf05EnablementFixtures;
import com.ses.entity.AuditLog;
import com.ses.entity.SystemConfig;
import com.ses.featureenablement.condition.FeatureConditionCheck;
import com.ses.featureenablement.condition.FeatureEvaluationContext;
import com.ses.featureenablement.config.FeatureEnablementProperties;
import com.ses.featureenablement.state.FeatureEnablementSnapshot;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.SystemConfigMapper;
import com.ses.service.security.LegalEntityReadinessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * FE-D05（feature-enablement-console design §9.4・§9.6、review rb-fe-d P2-3）: NF05 の固有の評価器の分岐。barrier の行を読めなければ
 * 評価できない類（{@code state=STATE_IO_ERROR}）、OFF の監査の照会は {@code reference_type='NF05'} を付ける（review P3-N8）、lock の
 * 中は最後に読んだ truststore、承認の値は DB の源で署名・property の源で配備の設定、法人の未解決（共用の結果）、inbound は barrier を
 * 読まない。
 */
class IntegrationHubEnablementConditionsTest {

    private static final Instant NOW = Instant.parse("2026-12-10T01:00:00Z");

    @TempDir
    Path directory;

    private SystemConfigMapper configs;
    private AuditLogMapper audits;
    private LegalEntityReadinessService readiness;
    private FeatureEnablementProperties settings;
    private IntegrationHubExternalApiProperties properties;
    private MockEnvironment environment;
    private Map<String, FeatureEnablementSnapshot.ConfirmedSignOff> signOffs;

    @BeforeEach
    void setUp() {
        configs = mock(SystemConfigMapper.class);
        audits = mock(AuditLogMapper.class);
        readiness = mock(LegalEntityReadinessService.class);
        when(readiness.isReady()).thenReturn(true);
        settings = new FeatureEnablementProperties();
        settings.setSource(FeatureEnablementProperties.Source.DATABASE);
        Path truststore = Nf05EnablementFixtures.truststore(directory);
        properties = Nf05EnablementFixtures.properties(truststore);
        environment = Nf05EnablementFixtures.environment(truststore);
        signOffs = Nf05EnablementFixtures.signOffs(properties,
                IntegrationHubEnablementDecision.readTruststore(truststore.toString()));
        when(configs.selectById(IntegrationHubEnablementDecision.BARRIER_CONFIG_KEY)).thenReturn(new SystemConfig(
                IntegrationHubEnablementDecision.BARRIER_CONFIG_KEY,
                Nf05EnablementFixtures.barrier("OFF", Nf05EnablementFixtures.BOUND_TENANT), "synthetic"));
        when(audits.selectCount(any())).thenReturn(1L);
    }

    @Test
    void barrierの行を読めなければ評価できない類で不成立にしない() {
        when(configs.selectById(anyString())).thenThrow(new IllegalStateException("synthetic DB failure"));
        List<FeatureConditionCheck> checks = conditions().evaluate(Feature.NF05_OUTBOUND,
                context(FeatureEvaluationContext.Purpose.PERIODIC, true));
        assertThat(codes(checks)).contains("state=STATE_IO_ERROR:UNVERIFIABLE")
                .noneMatch(code -> code.startsWith("barrier.tenantFingerprint") && code.endsWith("UNSATISFIED"));
    }

    @Test
    void OFFの監査の照会はNF05の参照の種類を付け開く遷移の目的だけで照会する() {
        IntegrationHubEnablementConditions conditions = conditions();
        conditions.evaluate(Feature.NF05_OUTBOUND, context(FeatureEvaluationContext.Purpose.PERIODIC, true));
        verify(audits, never()).selectCount(any());

        List<FeatureConditionCheck> checks = conditions.evaluate(Feature.NF05_OUTBOUND,
                context(FeatureEvaluationContext.Purpose.APPROVAL, true));
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<Wrapper<AuditLog>> query = ArgumentCaptor.forClass((Class) Wrapper.class);
        verify(audits).selectCount(query.capture());
        QueryWrapper<AuditLog> wrapper = (QueryWrapper<AuditLog>) query.getValue();
        assertThat(wrapper.getSqlSegment()).contains("reference_type").contains("reference_id").contains("application_code");
        assertThat(wrapper.getParamNameValuePairs().values()).contains("NF05", 0L, "NF05_OUTBOUND_OFF");
        assertThat(codes(checks)).contains("barrier.offRecorded:SATISFIED");

        when(audits.selectCount(any())).thenThrow(new IllegalStateException("synthetic DB failure"));
        assertThat(codes(conditions.evaluate(Feature.NF05_OUTBOUND, context(FeatureEvaluationContext.Purpose.APPROVAL,
                true)))).contains("barrier.offRecorded:UNVERIFIABLE");
    }

    @Test
    void lockの中は最後に読んだtruststoreを使い無ければ評価できない() throws Exception {
        IntegrationHubEnablementConditions fresh = conditions();
        assertThat(codes(fresh.evaluate(Feature.NF05_OUTBOUND, context(FeatureEvaluationContext.Purpose.CHANGE, false))))
                .contains("nf05.outbound.truststore.unverifiable:UNVERIFIABLE");

        IntegrationHubEnablementConditions conditions = conditions();
        assertThat(codes(conditions.evaluate(Feature.NF05_OUTBOUND,
                context(FeatureEvaluationContext.Purpose.PERIODIC, true)))).contains("nf05.outbound.policy:SATISFIED");
        Files.delete(Path.of(properties.getExternalTransport().getTruststorePath()));
        assertThat(codes(conditions.evaluate(Feature.NF05_OUTBOUND, context(FeatureEvaluationContext.Purpose.CHANGE,
                false)))).as("lock の中は file を読まず最後の内容で照合する").contains("nf05.outbound.policy:SATISFIED");
        assertThat(codes(conditions.evaluate(Feature.NF05_OUTBOUND, context(FeatureEvaluationContext.Purpose.PERIODIC,
                true)))).as("外部への検査を許す評価は読み直す").contains("nf05.outbound.truststore.unverifiable:UNVERIFIABLE");
    }

    @Test
    void 承認の値はDBの源で署名からpropertyの源で配備の設定から作る() {
        assertThat(codes(conditions().evaluate(Feature.NF05_INBOUND,
                context(FeatureEvaluationContext.Purpose.PERIODIC, true)))).contains("nf05.inbound.policy:SATISFIED");

        settings.setSource(FeatureEnablementProperties.Source.PROPERTIES);
        assertThat(codes(conditions().evaluate(Feature.NF05_INBOUND,
                context(FeatureEvaluationContext.Purpose.PERIODIC, true))))
                .as("配備の設定に決定 ID が無い").contains("nf05.inbound.decisionId:UNSATISFIED");
        properties.getPublicApi().getApproval().setDecisionId(Nf05EnablementFixtures.INBOUND_DECISION);
        properties.getPublicApi().getApproval().setApprovalDigest(IntegrationHubEnablementDecision.approvalsFromSignOffs(
                signOffs).inbound().approvalDigest());
        assertThat(codes(conditions().evaluate(Feature.NF05_INBOUND,
                context(FeatureEvaluationContext.Purpose.PERIODIC, true)))).contains("nf05.inbound.policy:SATISFIED");
    }

    @Test
    void 法人の未解決と判定できない状態を分け結果を両方向で共用しinboundはbarrierを読まない() {
        IntegrationHubEnablementConditions conditions = conditions();
        assertThat(codes(conditions.evaluate(Feature.NF05_INBOUND, context(FeatureEvaluationContext.Purpose.PERIODIC,
                true)))).contains("legalEntity.resolved:SATISFIED").noneMatch(code -> code.startsWith("barrier."));
        verify(configs, never()).selectById(anyString());
        conditions.evaluate(Feature.NF05_OUTBOUND, context(FeatureEvaluationContext.Purpose.PERIODIC, true));
        verify(readiness, times(1)).isReady();

        when(readiness.isReady()).thenReturn(false);
        assertThat(codes(conditions().evaluate(Feature.NF05_OUTBOUND,
                context(FeatureEvaluationContext.Purpose.PERIODIC, true)))).contains("legalEntity.unresolved:UNSATISFIED");
        when(readiness.isReady()).thenThrow(new IllegalStateException("synthetic DB failure"));
        assertThat(codes(conditions().evaluate(Feature.NF05_OUTBOUND,
                context(FeatureEvaluationContext.Purpose.PERIODIC, true)))).contains("legalEntity.unverifiable:UNVERIFIABLE");
    }

    @Test
    void 法人の未解決を判定できなかった結果は持たず同じ実例の次の評価で照会し直す() {
        // review rb-fe-d P3-N3: 同じ実例・同じ時刻（共用の秒数の中）で、判定できなかった結果を共用しない
        when(readiness.isReady()).thenThrow(new IllegalStateException("synthetic DB failure")).thenReturn(true);
        IntegrationHubEnablementConditions conditions = conditions();

        assertThat(codes(conditions.evaluate(Feature.NF05_OUTBOUND, context(FeatureEvaluationContext.Purpose.PERIODIC,
                true)))).contains("legalEntity.unverifiable:UNVERIFIABLE");
        assertThat(codes(conditions.evaluate(Feature.NF05_INBOUND, context(FeatureEvaluationContext.Purpose.PERIODIC,
                true)))).contains("legalEntity.resolved:SATISFIED");
        verify(readiness, times(2)).isReady();

        conditions.evaluate(Feature.NF05_OUTBOUND, context(FeatureEvaluationContext.Purpose.PERIODIC, true));
        verify(readiness, times(2)).isReady();
    }

    private IntegrationHubEnablementConditions conditions() {
        return new IntegrationHubEnablementConditions(properties, environment, configs, audits, provider(settings),
                provider(readiness));
    }

    private FeatureEvaluationContext context(FeatureEvaluationContext.Purpose purpose, boolean remoteChecksAllowed) {
        FeatureEnablementSnapshot snapshot = new FeatureEnablementSnapshot(1L, NOW, NOW, true, Map.of(), Map.of(), signOffs,
                Map.of());
        return new FeatureEvaluationContext(Nf05EnablementFixtures.TENANT, Nf05EnablementFixtures.TODAY,
                Clock.fixed(NOW, ZoneId.of("Asia/Tokyo")), snapshot, purpose, Set.of(), remoteChecksAllowed);
    }

    private static List<String> codes(List<FeatureConditionCheck> checks) {
        return checks.stream().map(check -> check.code() + ":" + check.status()).toList();
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
