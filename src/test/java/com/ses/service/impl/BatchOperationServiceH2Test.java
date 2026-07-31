package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.batch.BatchOperationResultDTO;
import com.ses.entity.Engineer;
import com.ses.mapper.EngineerMapper;
import com.ses.service.BatchOperationService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class BatchOperationServiceH2Test {

    @Autowired
    private BatchOperationService batchOperationService;

    @Autowired
    private EngineerMapper engineerMapper;

    @MockBean
    private DataScopeService dataScopeService;

    @Test
    void testBatchLimitExceededThrowsException() {
        List<Long> ids = new ArrayList<>();
        for (long i = 1; i <= 101; i++) {
            ids.add(i);
        }

        assertThrows(BusinessException.class, () ->
                batchOperationService.batchUpdateEngineerStatus(ids, "稼動中", 1L));
    }

    @Test
    void testPartialSuccessAndFailureIsolation() {
        // 1. 正常な要員データ作成 (status: 'Bench')
        Engineer eng1 = new Engineer();
        eng1.setFullName("要員一");
        eng1.setEmploymentType("正社員");
        eng1.setStatus("Bench");
        engineerMapper.insert(eng1);

        Engineer eng2 = new Engineer();
        eng2.setFullName("要員二");
        eng2.setEmploymentType("正社員");
        eng2.setStatus("Bench");
        engineerMapper.insert(eng2);

        Long validId1 = eng1.getId();
        Long validId2 = eng2.getId();
        Long invalidId = 99999L; // 存在しないID

        List<Long> batchIds = List.of(validId1, invalidId, validId2);

        // Scope制限なし
        given(dataScopeService.isScoped()).willReturn(false);

        // 一括更新実行
        BatchOperationResultDTO result = batchOperationService.batchUpdateEngineerStatus(batchIds, "稼動中", 1L);

        // 検証: 全体 3件、成功 2件、失敗 1件
        assertEquals(3, result.getTotalCount());
        assertEquals(2, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(1, result.getErrors().size());
        assertEquals(invalidId, result.getErrors().get(0).getId());

        // 成功した eng1, eng2 が実際に DB 上で「稼動中」に更新されていること
        Engineer updated1 = engineerMapper.selectById(validId1);
        Engineer updated2 = engineerMapper.selectById(validId2);
        assertEquals("稼動中", updated1.getStatus());
        assertEquals("稼動中", updated2.getStatus());
    }

    @Test
    void testBatchOperationDataScopeIsolation() {
        Engineer eng1 = new Engineer();
        eng1.setFullName("営業A担当要員");
        eng1.setEmploymentType("正社員");
        eng1.setStatus("Bench");
        engineerMapper.insert(eng1);

        Engineer eng2 = new Engineer();
        eng2.setFullName("営業B担当要員");
        eng2.setEmploymentType("正社員");
        eng2.setStatus("Bench");
        engineerMapper.insert(eng2);

        // 営業AのScope設定: eng1 のみ許可
        given(dataScopeService.isScoped()).willReturn(true);
        given(dataScopeService.allowedEngineerIds()).willReturn(Set.of(eng1.getId()));

        BatchOperationResultDTO result = batchOperationService.batchUpdateEngineerStatus(
                List.of(eng1.getId(), eng2.getId()), "稼動中", 100L);

        // 許可された eng1 は成功、非許可の eng2 は 403 エラーで記録
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(eng2.getId(), result.getErrors().get(0).getId());
    }
}
