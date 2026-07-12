package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.invoice.UnbilledWorkRecordDto;
import com.ses.entity.Invoice;
import com.ses.entity.InvoiceItem;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.InvoiceItemMapper;
import com.ses.mapper.InvoiceMapper;
import com.ses.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class InvoiceServiceImplTest {

    @Mock
    private InvoiceMapper invoiceMapper;

    @Mock
    private InvoiceItemMapper invoiceItemMapper;

    @Mock
    private CustomerMapper customerMapper;

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private InvoiceServiceImpl invoiceService;

    @BeforeEach
    void setUp() {
        // ServiceImpl の baseMapper フィールドを手動で注入
        ReflectionTestUtils.setField(invoiceService, "baseMapper", invoiceMapper);
    }

    @Test
    void testGenerate_Success() {
        Long customerId = 1L;
        String billingMonth = "2026-07";

        UnbilledWorkRecordDto dto = new UnbilledWorkRecordDto();
        dto.setWorkRecordId(10L);
        dto.setBillingAmount(new BigDecimal("100000"));
        dto.setEngineerName("山田太郎");
        dto.setProjectName("開発案件");

        when(invoiceMapper.selectUnbilledWorkRecords(customerId, billingMonth))
                .thenReturn(Collections.singletonList(dto));

        when(invoiceMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(invoiceMapper.insert(any(Invoice.class))).thenReturn(1);
        when(invoiceItemMapper.insert(any(InvoiceItem.class))).thenReturn(1);
        when(systemConfigService.getDecimal(any(), any())).thenReturn(new BigDecimal("0.10"));

        Invoice invoice = invoiceService.generate(customerId, billingMonth);

        assertNotNull(invoice);
        assertEquals("INV-202607-0001", invoice.getInvoiceNo());
        assertEquals(new BigDecimal("100000"), invoice.getSubtotal());
        assertEquals(new BigDecimal("10000"), invoice.getTax());
        assertEquals(new BigDecimal("110000"), invoice.getTotal());

        verify(invoiceMapper, times(1)).insert(any(Invoice.class));
        verify(invoiceItemMapper, times(1)).insert(any(InvoiceItem.class));
    }

    @Test
    void testGenerate_NoUnbilledRecords() {
        Long customerId = 1L;
        String billingMonth = "2026-07";

        when(invoiceMapper.selectUnbilledWorkRecords(customerId, billingMonth))
                .thenReturn(Collections.emptyList());

        assertThrows(BusinessException.class, () -> invoiceService.generate(customerId, billingMonth));
    }

    @Test
    void testGenerateInvoiceNo() {
        String billingMonth = "2026-07";
        Invoice existing = new Invoice();
        existing.setInvoiceNo("INV-202607-0005");

        when(invoiceMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        String nextNo = invoiceService.generateInvoiceNo(billingMonth);
        assertEquals("INV-202607-0006", nextNo);
    }
}
