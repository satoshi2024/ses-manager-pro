package com.ses.service.impl;

import com.ses.entity.Candidate;
import com.ses.entity.Engineer;
import com.ses.mapper.CandidateActivityMapper;
import com.ses.mapper.CandidateMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Candidate 編集時期待値自動同期テスト (R3-019)")
class CandidateEditExpectationSyncTest {

    @InjectMocks
    private CandidateServiceImpl candidateService;

    @Mock
    private CandidateMapper candidateMapper;
    @Mock
    private CandidateActivityMapper candidateActivityMapper;
    @Mock
    private EngineerMapper engineerMapper;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
        ReflectionTestUtils.setField(candidateService, "baseMapper", candidateMapper);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    @DisplayName("候補者のdesiredRate更新時、紐づくEngineerのexpectedUnitPriceが自動同期される")
    void updateCandidateSyncsEngineerExpectations() {
        Candidate existing = Candidate.builder()
                .name("山田 太郎")
                .desiredRate(new BigDecimal("700000"))
                .convertedEngineerId(50L)
                .build();
        existing.setId(10L);
        existing.setTenantId("default");
        existing.setVersion(0);

        Candidate update = Candidate.builder()
                .name("山田 太郎")
                .desiredRate(new BigDecimal("750000"))
                .build();
        update.setId(10L);
        update.setTenantId("default");
        update.setVersion(0);

        Engineer eng = new Engineer();
        eng.setId(50L);
        eng.setFullName("山田 太郎");
        eng.setExpectedUnitPrice(new BigDecimal("700000"));
        eng.setTenantId("default");
        eng.setVersion(0);

        when(candidateMapper.selectByIdForUpdateForTenant(10L, "default")).thenReturn(existing);
        doReturn(1).when(candidateMapper).updateByIdForTenant(any(), org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.eq(0));
        when(engineerMapper.selectByIdForTenant(50L, "default")).thenReturn(eng);
        doReturn(1).when(engineerMapper).updateByIdForTenant(any(), org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.eq(0));

        candidateService.updateById(update);

        verify(engineerMapper).updateByIdForTenant((Engineer) argThat(e ->
                ((Engineer) e).getId().equals(50L) && new BigDecimal("750000").equals(((Engineer) e).getExpectedUnitPrice())
        ), org.mockito.ArgumentMatchers.eq("default"), org.mockito.ArgumentMatchers.eq(0));
    }
}
