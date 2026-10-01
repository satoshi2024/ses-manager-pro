package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.batch.BatchApplyRequestDTO;
import com.ses.dto.batch.BatchOperationResultDTO;
import com.ses.dto.batch.BatchPreviewResultDTO;
import com.ses.entity.Engineer;
import com.ses.mapper.EngineerMapper;
import com.ses.service.BatchOperationService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.security.DataScopeService;
import com.ses.test.EnableDefaultTenantTestContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
@EnableDefaultTenantTestContext
public class BatchOperationServiceH2Test {

    @Autowired
    private BatchOperationService batchOperationService;

    @Autowired
    private EngineerMapper engineerMapper;

    @MockBean
    private DataScopeService dataScopeService;

    @BeforeEach
    void setUp() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void tearDown() {
        AccountingTenantContextHolder.clear();
    }

    private Engineer createEngineer(String fullName, String status) {
        Engineer eng = new Engineer();
        eng.setTenantId("default");
        eng.setLegalEntityId(1L);
        eng.setFullName(fullName);
        eng.setEmploymentType("正社員");
        eng.setStatus(status);
        engineerMapper.insert(eng);
        return eng;
    }

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
        Engineer eng1 = createEngineer("要員一", "Bench");

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
    void testApplyRejectsTokenFromDifferentUser() {
        Engineer eng1 = createEngineer("要員一", "Bench");

        List<Long> ids = List.of(eng1.getId());
        given(dataScopeService.isScoped()).willReturn(false);

        BatchPreviewResultDTO preview = batchOperationService.previewEngineerStatusUpdate(ids, "稼動中", 1L);

        BatchApplyRequestDTO otherUserReq = new BatchApplyRequestDTO();
        otherUserReq.setIds(ids);
        otherUserReq.setStatus("稼動中");
        otherUserReq.setPreviewToken(preview.getPreviewToken());

        assertThrows(BusinessException.class, () ->
                batchOperationService.applyEngineerStatusUpdate(otherUserReq, 2L));
    }

    @Test
    void testPartialSuccessAndFailureIsolation() {
        Engineer eng1 = createEngineer("要員一", "Bench");
        Engineer eng2 = createEngineer("要員二", "Bench");

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
        Engineer eng1 = createEngineer("営業A担当要員", "Bench");
        Engineer eng2 = createEngineer("営業B担当要員", "Bench");

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

    @Test
    void testProdWithTestProfile_failsFastWhenTokenSecretMissing() {
        org.springframework.mock.env.MockEnvironment env = new org.springframework.mock.env.MockEnvironment();
        env.setActiveProfiles("prod", "test");
        BatchOperationServiceImpl service = new BatchOperationServiceImpl(null, null, null, env);
        assertThrows(IllegalStateException.class, service::validateTokenSecretOnStartup);

        org.springframework.mock.env.MockEnvironment env2 = new org.springframework.mock.env.MockEnvironment();
        env2.setActiveProfiles("test", "prod");
        BatchOperationServiceImpl service2 = new BatchOperationServiceImpl(null, null, null, env2);
        assertThrows(IllegalStateException.class, service2::validateTokenSecretOnStartup);
    }
}
