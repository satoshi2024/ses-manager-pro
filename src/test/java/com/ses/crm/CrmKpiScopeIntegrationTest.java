package com.ses.crm;

import com.ses.dto.crm.CrmKpiDto;
import com.ses.entity.Lead;
import com.ses.entity.Opportunity;
import com.ses.mapper.LeadMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.service.CrmKpiService;
import com.ses.service.CustomerService;
import com.ses.service.security.DataScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/** T052 L3: 営業Aは許可customerかつ本人/未割当の商機だけを集計する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "1", roles = "営業")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CrmKpiScopeIntegrationTest {
    @Autowired private CrmKpiService crmKpiService;
    @Autowired private CustomerService customerService;
    @Autowired private OpportunityMapper opportunityMapper;
    @Autowired private LeadMapper leadMapper;
    @MockBean private DataScopeService dataScopeService;

    @BeforeEach
    void scope() {
        when(dataScopeService.isScoped()).thenReturn(true);
        when(dataScopeService.isSalesDataScoped()).thenReturn(true);
        when(dataScopeService.allowedCustomerIds()).thenReturn(Set.of(10L));
        when(dataScopeService.allowedProposalIds()).thenReturn(Set.of());
        when(dataScopeService.allowedEngineerIds()).thenReturn(Set.of());
        when(dataScopeService.allowedContractIds()).thenReturn(Set.of());
        when(dataScopeService.allowedProjectIds()).thenReturn(Set.of());
        when(dataScopeService.allowedOrganizationIds()).thenReturn(Set.of());
        doNothing().when(dataScopeService).assertAllowedCustomer(anyLong());
    }

    @Test
    void salesScopeExcludesAnotherOwnerAndAnotherCustomer() {
        com.ses.entity.Customer allowedCustomer = new com.ses.entity.Customer();
        allowedCustomer.setId(10L);
        allowedCustomer.setCompanyName("scope A");
        customerService.save(allowedCustomer);
        com.ses.entity.Customer otherCustomer = new com.ses.entity.Customer();
        otherCustomer.setId(11L);
        otherCustomer.setCompanyName("scope B");
        customerService.save(otherCustomer);

        Opportunity own = opportunity(10L, "本人商機", 1L);
        opportunityMapper.insert(own);
        Opportunity otherOwner = opportunity(10L, "他担当商機", 2L);
        opportunityMapper.insert(otherOwner);
        Opportunity otherCustomerOpp = opportunity(11L, "他顧客商機", 1L);
        opportunityMapper.insert(otherCustomerOpp);
        Lead ownLead = Lead.builder().companyName("A").ownerUserId(1L).status("未対応").version(1).build();
        leadMapper.insert(ownLead);
        Lead otherLead = Lead.builder().companyName("B").ownerUserId(2L).status("未対応").version(1).build();
        leadMapper.insert(otherLead);

        CrmKpiDto dto = crmKpiService.summarize(null, null);
        assertEquals(1, dto.getForecast().getOpportunityCount());
        assertEquals(1, dto.getOwners().stream().mapToLong(CrmKpiDto.OwnerKpi::getLeadCount).sum());
    }

    private Opportunity opportunity(Long customerId, String title, Long ownerId) {
        Opportunity o = new Opportunity();
        o.setCustomerId(customerId); o.setTitle(title); o.setStage("見込");
        o.setExpectedAmount(new BigDecimal("100")); o.setProbability(20); o.setOwnerUserId(ownerId); o.setVersion(1);
        return o;
    }
}
