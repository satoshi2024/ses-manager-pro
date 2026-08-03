package com.ses.service.impl;

import com.ses.entity.Candidate;
import com.ses.entity.Engineer;
import com.ses.mapper.CandidateActivityMapper;
import com.ses.mapper.CandidateMapper;
import com.ses.mapper.EngineerMapper;
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
        ReflectionTestUtils.setField(candidateService, "baseMapper", candidateMapper);
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

        Candidate update = Candidate.builder()
                .name("山田 太郎")
                .desiredRate(new BigDecimal("750000"))
                .build();
        update.setId(10L);

        Engineer eng = new Engineer();
        eng.setId(50L);
        eng.setFullName("山田 太郎");
        eng.setExpectedUnitPrice(new BigDecimal("700000"));

        when(candidateMapper.selectById(10L)).thenReturn(existing);
        doReturn(1).when(candidateMapper).updateById((Candidate) any());
        when(engineerMapper.selectById(50L)).thenReturn(eng);

        candidateService.updateById(update);

        verify(engineerMapper).updateById((Engineer) argThat(e ->
                ((Engineer) e).getId().equals(50L) && new BigDecimal("750000").equals(((Engineer) e).getExpectedUnitPrice())
        ));
    }
}
