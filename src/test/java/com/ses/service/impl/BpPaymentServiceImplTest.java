package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.bp.BpPaymentTreeDto;
import com.ses.entity.BpPayment;
import com.ses.mapper.BpPaymentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    @InjectMocks
    private BpPaymentServiceImpl bpPaymentService;

    @BeforeEach
    void setUp() {
    }

    @Test
    void testAddLayer_Success() {
        BpPayment payment = new BpPayment();
        payment.setWorkRecordId(1L);
        payment.setLayerOrder(1);

        when(bpPaymentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(bpPaymentMapper.insert(payment)).thenReturn(1);

        BpPayment result = bpPaymentService.addLayer(payment);
        assertNotNull(result);
        verify(bpPaymentMapper, times(1)).insert(payment);
    }

    @Test
    void testAddLayer_DuplicateLayerRejected() {
        BpPayment payment = new BpPayment();
        payment.setWorkRecordId(1L);
        payment.setLayerOrder(1);

        when(bpPaymentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            bpPaymentService.addLayer(payment);
        });
        assertEquals("指定された階層は既に存在します。", exception.getMessage());
    }

    @Test
    void testAddLayer_ParentMismatchRejected() {
        BpPayment payment = new BpPayment();
        payment.setWorkRecordId(1L);
        payment.setLayerOrder(2);
        payment.setParentPaymentId(100L);

        when(bpPaymentMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);

        BpPayment parent = new BpPayment();
        parent.setId(100L);
        parent.setWorkRecordId(2L); // Different work record

        when(bpPaymentMapper.selectById(100L)).thenReturn(parent);

        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            bpPaymentService.addLayer(payment);
        });
        assertEquals("親階層が正しくありません。", exception.getMessage());
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
}
