package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.batch.BatchApplyRequestDTO;
import com.ses.dto.batch.BatchOperationResultDTO;
import com.ses.dto.batch.BatchPreviewResultDTO;
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
        // 200件まではOK、201件で拒否 (R4.1 / R5)
        List<Long> ids200 = new ArrayList<>();
        for (long i = 1; i <= 200; i++) {
            ids200.add(i);
        }
        given(dataScopeService.isScoped()).willReturn(false);

        BatchPreviewResultDTO previewResult = batchOperationService.previewEngineerStatusUpdate(ids200, "稼動中", 1L);
        assertNotNull(previewResult);
        assertEquals(200, previewResult.getTotalCount());

        List<Long> ids201 = new ArrayList<>();
        for (long i = 1; i <= 201; i++) {
            ids201.add(i);
        }

        assertThrows(BusinessException.class, () ->
                batchOperationService.previewEngineerStatusUpdate(ids201, "稼動中", 1L));
    }

    @Test
    void testPreviewAndApplyWithTokenVerification() {
        Engineer eng1 = new Engineer();
        eng1.setFullName("要員一");
        eng1.setEmploymentType("正社員");
        eng1.setStatus("Bench");
        engineerMapper.insert(eng1);

        List<Long> ids = List.of(eng1.getId());
        given(dataScopeService.isScoped()).willReturn(false);

        // 1. Preview 呼び出し
        BatchPreviewResultDTO preview = batchOperationService.previewEngineerStatusUpdate(ids, "稼動中", 1L);
        assertNotNull(preview.getPreviewToken());
        assertEquals(1, preview.getValidCount());

        // 2. 改ざんされた Token で Apply 実行 -> 拒否
        BatchApplyRequestDTO invalidReq = new BatchApplyRequestDTO();
        invalidReq.setIds(ids);
        invalidReq.setStatus("稼動中");
        invalidReq.setPreviewToken(preview.getPreviewToken() + "_tampered");

        assertThrows(BusinessException.class, () ->
                batchOperationService.applyEngineerStatusUpdate(invalidReq, 1L));

        // 3. 正しい Token で Apply 実行 -> 成功
        BatchApplyRequestDTO validReq = new BatchApplyRequestDTO();
        validReq.setIds(ids);
        validReq.setStatus("稼動中");
        validReq.setPreviewToken(preview.getPreviewToken());

        BatchOperationResultDTO result = batchOperationService.applyEngineerStatusUpdate(validReq, 1L);
        assertEquals(1, result.getSuccessCount());
    }

    @Test
    void testPartialSuccessAndFailureIsolation() {
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
        given(dataScopeService.isScoped()).willReturn(false);

        BatchPreviewResultDTO preview = batchOperationService.previewEngineerStatusUpdate(batchIds, "稼動中", 1L);
        BatchApplyRequestDTO applyReq = new BatchApplyRequestDTO();
        applyReq.setIds(batchIds);
        applyReq.setStatus("稼動中");
        applyReq.setPreviewToken(preview.getPreviewToken());

        BatchOperationResultDTO result = batchOperationService.applyEngineerStatusUpdate(applyReq, 1L);

        assertEquals(3, result.getTotalCount());
        assertEquals(2, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(1, result.getErrors().size());
        assertEquals(invalidId, result.getErrors().get(0).getId());

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

        given(dataScopeService.isScoped()).willReturn(true);
        given(dataScopeService.allowedEngineerIds()).willReturn(Set.of(eng1.getId()));

        List<Long> ids = List.of(eng1.getId(), eng2.getId());
        BatchPreviewResultDTO preview = batchOperationService.previewEngineerStatusUpdate(ids, "稼動中", 100L);
        BatchApplyRequestDTO applyReq = new BatchApplyRequestDTO();
        applyReq.setIds(ids);
        applyReq.setStatus("稼動中");
        applyReq.setPreviewToken(preview.getPreviewToken());

        BatchOperationResultDTO result = batchOperationService.applyEngineerStatusUpdate(applyReq, 100L);

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(eng2.getId(), result.getErrors().get(0).getId());
    }
}
