package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.bp.BpPaymentTreeDto;
import com.ses.entity.BpPayment;
import com.ses.mapper.BpPaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BpPaymentServiceImplTest {

    @Mock
    private BpPaymentMapper bpPaymentMapper;

    @Mock
    private com.ses.mapper.WorkRecordMapper workRecordMapper;

    @Mock
    private com.ses.service.MonthlyClosingService monthlyClosingService;

    @Mock
    private com.ses.mapper.BpCompanyMapper bpCompanyMapper;

    @Mock
    private com.ses.service.WorkRecordService workRecordService;

    @InjectMocks
    private BpPaymentServiceImpl bpPaymentService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bpPaymentService, "workRecordService", workRecordService);
    }

    @Test
    void testAddLayer_Success() {
        BpPayment payment = new BpPayment();
        payment.setWorkRecordId(1L);
        payment.setLayerOrder(1);
        payment.setBpCompanyId(10L);

        com.ses.entity.BpCompany company = new com.ses.entity.BpCompany();
        company.setId(10L);
        company.setLegalName("テストBP");
        when(bpCompanyMapper.selectById(10L)).thenReturn(company);
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);
        when(bpPaymentMapper.insert(payment)).thenReturn(1);

        BpPayment result = bpPaymentService.addLayer(payment);
        assertNotNull(result);
        assertEquals("テストBP", result.getBpCompanyNameSnapshot());
        verify(bpPaymentMapper, times(1)).insert(payment);
    }

    @Test
    void testAddLayer_DuplicateLayerRejected() {
        BpPayment payment = new BpPayment();
        payment.setWorkRecordId(1L);
        payment.setLayerOrder(1);
        payment.setBpCompanyId(10L);

        com.ses.entity.BpCompany company = new com.ses.entity.BpCompany();
        company.setId(10L);
        company.setLegalName("テストBP");
        when(bpCompanyMapper.selectById(10L)).thenReturn(company);
        when(bpPaymentMapper.selectCount(any())).thenReturn(1L);

        Exception exception = assertThrows(BusinessException.class, () -> {
            bpPaymentService.addLayer(payment);
        });
        assertEquals("error.bpPayment.duplicateLayer", exception.getMessage());
    }

    @Test
    void testAddLayer_ParentMismatchRejected() {
        BpPayment payment = new BpPayment();
        payment.setWorkRecordId(1L);
        payment.setLayerOrder(2);
        payment.setParentPaymentId(100L);
        payment.setBpCompanyId(10L);

        com.ses.entity.BpCompany company = new com.ses.entity.BpCompany();
        company.setId(10L);
        company.setLegalName("テストBP");
        when(bpCompanyMapper.selectById(10L)).thenReturn(company);
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);

        BpPayment parent = new BpPayment();
        parent.setId(100L);
        parent.setWorkRecordId(2L); // Different work record

        when(bpPaymentMapper.selectById(100L)).thenReturn(parent);

        Exception exception = assertThrows(BusinessException.class, () -> {
            bpPaymentService.addLayer(payment);
        });
        assertEquals("error.bpPayment.parentInvalid", exception.getMessage());
    }

    @Test
    void testGetTreeByWorkRecordId_MarginCalculation() {
        BpPayment p1 = new BpPayment();
        p1.setId(1L);
        p1.setWorkRecordId(1L);
        p1.setLayerOrder(1);
        p1.setAmount(new BigDecimal("1000000"));

        BpPayment p2 = new BpPayment();
        p2.setId(2L);
        p2.setWorkRecordId(1L);
        p2.setLayerOrder(2);
        p2.setAmount(new BigDecimal("800000"));
        p2.setParentPaymentId(1L);

        BpPayment p3 = new BpPayment();
        p3.setId(3L);
        p3.setWorkRecordId(1L);
        p3.setLayerOrder(3);
        p3.setAmount(new BigDecimal("700000"));
        p3.setParentPaymentId(2L);

        when(bpPaymentMapper.selectByWorkRecordIdOrderByLayer(1L)).thenReturn(Arrays.asList(p1, p2, p3));

        List<BpPaymentTreeDto> tree = bpPaymentService.getTreeByWorkRecordId(1L);

        assertEquals(1, tree.size());
        BpPaymentTreeDto root = tree.get(0);
        assertEquals(1L, root.getId());
        assertEquals(new BigDecimal("200000"), root.getMargin()); // 1000000 - 800000

        assertEquals(1, root.getChildren().size());
        BpPaymentTreeDto child = root.getChildren().get(0);
        assertEquals(2L, child.getId());
        assertEquals(new BigDecimal("100000"), child.getMargin()); // 800000 - 700000

        assertEquals(1, child.getChildren().size());
        BpPaymentTreeDto grandchild = child.getChildren().get(0);
        assertEquals(3L, grandchild.getId());
        assertEquals(new BigDecimal("700000"), grandchild.getMargin()); // 700000 - 0
    }

    @Test
    void testGetTreeByWorkRecordId_BackwardCompatibility() {
        // 既存の単層データ
        BpPayment p1 = new BpPayment();
        p1.setId(1L);
        p1.setWorkRecordId(1L);
        p1.setLayerOrder(1);
        p1.setAmount(new BigDecimal("500000"));

        when(bpPaymentMapper.selectByWorkRecordIdOrderByLayer(1L)).thenReturn(Collections.singletonList(p1));

        List<BpPaymentTreeDto> tree = bpPaymentService.getTreeByWorkRecordId(1L);
        assertEquals(1, tree.size());
        assertEquals(new BigDecimal("500000"), tree.get(0).getMargin());
        assertTrue(tree.get(0).getChildren().isEmpty());
    }

    @Test
    void updateLayer_支払済の金額変更は拒否する() {
        BpPayment existing = new BpPayment();
        existing.setId(1L);
        existing.setStatus("支払済");
        existing.setAmount(new BigDecimal("500000"));
        when(bpPaymentMapper.selectById(1L)).thenReturn(existing);

        BpPayment request = new BpPayment();
        request.setAmount(new BigDecimal("400000"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> bpPaymentService.updateLayer(1L, request));

        assertEquals("error.bpPayment.paidAmountEdit", ex.getMessage());
        verify(bpPaymentMapper, never()).update(any(), any());
    }

    @Test
    void updateLayer_状態変更は専用API以外では拒否する() {
        BpPayment existing = new BpPayment();
        existing.setId(1L);
        existing.setStatus("未払");
        when(bpPaymentMapper.selectById(1L)).thenReturn(existing);

        BpPayment request = new BpPayment();
        request.setStatus("支払済");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> bpPaymentService.updateLayer(1L, request));

        assertEquals("error.bpPayment.statusDedicatedApi", ex.getMessage());
    }

    @Test
    void deleteLayer_支払済は拒否する() {
        BpPayment existing = new BpPayment();
        existing.setId(1L);
        existing.setStatus("支払済");
        when(bpPaymentMapper.selectById(1L)).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> bpPaymentService.deleteLayer(1L));

        assertEquals("error.bpPayment.paidDelete", ex.getMessage());
        verify(bpPaymentMapper, never()).deleteById(1L);
    }

    @Test
    void BPの読取更新削除はいずれも同じ勤怠scope守衛を通る() {
        BpPayment existing = new BpPayment();
        existing.setId(1L);
        existing.setWorkRecordId(7L);
        existing.setStatus("未払");
        existing.setAmount(new BigDecimal("500000"));
        com.ses.entity.WorkRecord workRecord = new com.ses.entity.WorkRecord();
        workRecord.setId(7L);
        workRecord.setWorkMonth("2026-07");

        when(bpPaymentMapper.selectById(1L)).thenReturn(existing);
        when(workRecordMapper.selectById(7L)).thenReturn(workRecord);
        when(bpPaymentMapper.selectByWorkRecordIdOrderByLayer(7L)).thenReturn(Collections.emptyList());
        when(bpPaymentMapper.selectCount(any())).thenReturn(0L);
        when(bpPaymentMapper.update(isNull(), any())).thenReturn(1);

        bpPaymentService.getTreeByWorkRecordId(7L);
        bpPaymentService.updateLayer(1L, new BpPayment());
        bpPaymentService.deleteLayer(1L);

        verify(workRecordService, times(3)).assertAllowed(7L);
    }
}
