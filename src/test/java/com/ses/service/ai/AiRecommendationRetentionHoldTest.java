package com.ses.service.ai;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ses.entity.AiRecommendationRun;
import com.ses.mapper.AiRecommendationRunMapper;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.privacy.production.retention.hold.PrivacyHoldGate;
import com.ses.privacy.production.retention.hold.PrivacyHoldPath;
import com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.ai.impl.AiRecommendationRetentionServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * NF07 A1H-2 D6: AI 推薦の redacted summary の purge が、scheduler ではなく service の回の初めの1回の gate の照会で保全 hold を
 * 尊重すること（production-bridge-phased-plan.md §3.4.1 D6）。scheduler の Bean は作らず service を直接呼ぶ（Bean の有無に
 * 依らない）。除外付きの SQL は {@code AiRecommendationRetentionTenantIsolationH2Test} が H2 で実行する。
 */
class AiRecommendationRetentionHoldTest {

    private static final String TENANT = "tenant-a";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 16, 3, 0);
    private static final LocalDateTime CUTOFF = NOW.minusDays(730);

    private final AiRecommendationRunMapper runMapper = mock(AiRecommendationRunMapper.class);

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AiRecommendationRun.class);
    }

    @AfterEach
    void clearTenant() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void D6_hold機構を使っていないtenantは照会1回だけで従来の1文のpurgeを使い本人導出を呼ばない() {
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub();
        when(runMapper.purgeExpiredSummaries(TENANT, CUTOFF, NOW, 100)).thenReturn(3);

        assertEquals(3, purge(hold));

        verify(runMapper).purgeExpiredSummaries(TENANT, CUTOFF, NOW, 100);
        verify(runMapper, never()).update(any(), any());
        verify(runMapper, never()).selectObjs(any());
        assertEquals(1, hold.gateCalls.get());
        verifyNoInteractions(hold.resolver);
    }

    @Test
    void D6_DATA_ELEMENT_holdならその回を消さず件数を数える() {
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub().tenant(TENANT,
                PrivacyHoldTestGuards.verified(Set.of(), Set.of("AI_REDACTED_SUMMARY"), catalog()));
        when(runMapper.selectObjs(any())).thenReturn(List.of(1L, 2L));

        assertEquals(0, purge(hold));

        verify(runMapper, never()).purgeExpiredSummaries(any(), any(), any(), anyInt());
        verify(runMapper, never()).update(any(), any());
        assertEquals(2.0, hold.skipped(PrivacyHoldPath.D6, PrivacyHoldGate.Verdict.HELD));
    }

    @Test
    void D6_gateがUNAVAILABLEならその回を消さない() {
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub().tenant(TENANT, PrivacyHoldTestGuards.unavailable());
        when(runMapper.selectObjs(any())).thenReturn(List.of(1L));

        assertEquals(0, purge(hold));

        verify(runMapper, never()).purgeExpiredSummaries(any(), any(), any(), anyInt());
        verify(runMapper, never()).update(any(), any());
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D6, PrivacyHoldGate.Verdict.UNAVAILABLE));
    }

    @Test
    void D6_SUBJECT_holdに当たるrunが無ければ従来の1文のpurgeを使う() {
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub().tenant(TENANT,
                PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7"), Set.of(), catalog()));
        when(hold.resolver.aiRecommendationRunSubjects(eq(TENANT), any())).thenReturn(Map.of());
        when(runMapper.purgeExpiredSummaries(TENANT, CUTOFF, NOW, 100)).thenReturn(1);

        assertEquals(1, purge(hold));

        verify(runMapper).purgeExpiredSummaries(TENANT, CUTOFF, NOW, 100);
    }

    @Test
    void D6_SUBJECT_holdの本人のrunは除外して消す() {
        PrivacyHoldTestGuards.Stub hold = PrivacyHoldTestGuards.stub().tenant(TENANT,
                PrivacyHoldTestGuards.verified(Set.of("ENGINEER:7"), Set.of(), catalog()));
        when(hold.resolver.aiRecommendationRunSubjects(eq(TENANT), any())).thenReturn(Map.of(9L, Set.of("ENGINEER:7")));
        // 1回目: held かつ期限切れの run の件数、2回目: held を除いた候補
        when(runMapper.selectObjs(any())).thenReturn(List.of(9L)).thenReturn(List.of(10L, 11L));
        when(runMapper.update(any(), any())).thenReturn(2);

        assertEquals(2, purge(hold));

        verify(runMapper, never()).purgeExpiredSummaries(any(), any(), any(), anyInt());
        verify(runMapper).update(any(), any());
        assertEquals(1.0, hold.skipped(PrivacyHoldPath.D6, PrivacyHoldGate.Verdict.HELD));
    }

    private int purge(PrivacyHoldTestGuards.Stub hold) {
        AiRecommendationRetentionServiceImpl service = new AiRecommendationRetentionServiceImpl(runMapper, 730,
                Clock.systemUTC(), hold.guard);
        return AccountingTenantContextHolder.runWithTenant(TENANT, () -> service.purgeExpiredRedactedSummaries(NOW, 100));
    }

    private static Map<String, SubjectAttribution> catalog() {
        return Map.of("AI_REDACTED_SUMMARY", SubjectAttribution.ATTRIBUTABLE);
    }
}
