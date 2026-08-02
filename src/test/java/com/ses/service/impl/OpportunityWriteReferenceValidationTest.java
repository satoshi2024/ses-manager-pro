package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.crm.OpportunitySaveRequest;
import com.ses.entity.Customer;
import com.ses.entity.Opportunity;
import com.ses.entity.Project;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.QuotationMapper;
import com.ses.service.QuotationService;
import com.ses.service.security.CrmScopeService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 商機write pathの顧客存在・scope検証を固定する。 */
@ExtendWith(MockitoExtension.class)
class OpportunityWriteReferenceValidationTest {

    @Mock
    private OpportunityMapper opportunityMapper;
    @Mock
    private ProjectMapper projectMapper;
    @Mock
    private QuotationMapper quotationMapper;
    @Mock
    private QuotationService quotationService;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private CustomerMapper customerMapper;
    @Mock
    private CrmScopeService crmScopeService;

    private OpportunityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OpportunityServiceImpl(
                projectMapper, quotationMapper, quotationService, dataScopeService, customerMapper);
        ReflectionTestUtils.setField(service, "baseMapper", opportunityMapper);
        ReflectionTestUtils.setField(service, "crmScopeService", crmScopeService);
        org.mockito.Mockito.lenient()
                .when(crmScopeService.isOwnerAllowed(anyLong(), any(LocalDate.class))).thenReturn(true);
    }

    @Test
    void adminCreate_存在しない顧客は404でinsertしない() {
        OpportunitySaveRequest request = request(9999L, 1);
        when(customerMapper.selectById(9999L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createBasic(request));

        assertEquals(404, error.getCode());
        assertEquals("error.crm.customerNotFound", error.getMessageKey());
        verify(opportunityMapper, never()).insert(any(Opportunity.class));
    }

    @Test
    void salesCreate_scope外顧客は非漏えい404でinsertしない() {
        OpportunitySaveRequest request = request(200L, 1);
        when(customerMapper.selectById(200L)).thenReturn(customer(200L));
        doThrow(BusinessException.of(404, "error.crm.customerNotFound"))
                .when(crmScopeService).assertAllowedCustomer(anyLong(), any(LocalDate.class));

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createBasic(request));

        assertEquals(404, error.getCode());
        assertEquals("error.crm.customerNotFound", error.getMessageKey());
        verify(opportunityMapper, never()).insert(any(Opportunity.class));
    }

    @Test
    void create_論理削除済み顧客は不存在404でinsertしない() {
        OpportunitySaveRequest request = request(300L, 1);
        // MyBatis-PlusのselectByIdは論理削除済み行を返さない。
        when(customerMapper.selectById(300L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.createBasic(request));

        assertEquals(404, error.getCode());
        verify(opportunityMapper, never()).insert(any(Opportunity.class));
    }

    @Test
    void create_正常顧客は存在とscopeを確認してからinsertする() {
        OpportunitySaveRequest request = request(100L, 1);
        Customer customer = customer(100L);
        Opportunity stored = opportunity(1L, 100L, "見込", 1);
        when(customerMapper.selectById(100L)).thenReturn(customer);
        doAnswer(invocation -> {
            Opportunity value = invocation.getArgument(0);
            value.setId(1L);
            return 1;
        }).when(opportunityMapper).insert(any(Opportunity.class));
        when(opportunityMapper.selectById(1L)).thenReturn(stored);

        Opportunity result = service.createBasic(request);

        assertSame(stored, result);
        InOrder order = inOrder(customerMapper, crmScopeService, opportunityMapper);
        order.verify(customerMapper).selectById(100L);
        order.verify(crmScopeService).assertAllowedCustomer(anyLong(), any(LocalDate.class));
        order.verify(opportunityMapper).insert(any(Opportunity.class));
    }

    @Test
    void update_バージョン競合を顧客検証より先に返す() {
        Opportunity current = opportunity(10L, 100L, "見込", 2);
        when(opportunityMapper.selectByIdForUpdate(10L)).thenReturn(current);
        OpportunitySaveRequest request = request(9999L, 1);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateBasic(10L, request));

        assertEquals(409, error.getCode());
        assertEquals("error.opportunity.versionConflict", error.getMessageKey());
        verify(customerMapper, never()).selectById(anyLong());
        verify(opportunityMapper, never()).update(any(), any());
    }

    @Test
    void update_一致versionでも不存在顧客なら404でupdateしない() {
        Opportunity current = opportunity(10L, 100L, "見込", 2);
        when(opportunityMapper.selectByIdForUpdate(10L)).thenReturn(current);
        when(customerMapper.selectById(9999L)).thenReturn(null);
        OpportunitySaveRequest request = request(9999L, 2);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.updateBasic(10L, request));

        assertEquals(404, error.getCode());
        verify(opportunityMapper, never()).update(any(), any());
    }

    @Test
    void convert_顧客が削除済みならproject作成前に404を返す() {
        Opportunity current = opportunity(20L, 300L, "受注", 3);
        when(opportunityMapper.selectByIdForUpdate(20L)).thenReturn(current);
        when(customerMapper.selectById(300L)).thenReturn(null);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.convertToProjectAndQuotation(20L));

        assertEquals(404, error.getCode());
        verify(projectMapper, never()).selectBySourceOpportunityIdIncludingDeleted(anyLong());
        verify(projectMapper, never()).insert(any(Project.class));
    }

    private OpportunitySaveRequest request(Long customerId, int version) {
        OpportunitySaveRequest request = new OpportunitySaveRequest();
        request.setCustomerId(customerId);
        request.setTitle("参照検証商機");
        request.setRequiredCount(1);
        request.setUnitPrice(new BigDecimal("700000"));
        request.setProbability(20);
        request.setOwnerUserId(10L);
        request.setVersion(version);
        return request;
    }

    private Customer customer(Long id) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setCompanyName("検証顧客");
        return customer;
    }

    private Opportunity opportunity(Long id, Long customerId, String stage, int version) {
        Opportunity opportunity = new Opportunity();
        opportunity.setId(id);
        opportunity.setCustomerId(customerId);
        opportunity.setTitle("参照検証商機");
        opportunity.setStage(stage);
        opportunity.setVersion(version);
        return opportunity;
    }
}
