package com.ses.crm;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.dto.crm.LeadConversionDto;
import com.ses.dto.crm.LeadSaveRequest;
import com.ses.entity.Customer;
import com.ses.entity.CustomerContact;
import com.ses.entity.Lead;
import com.ses.entity.Opportunity;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.service.LeadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T051定向テスト: leadの重複候補、可視性、顧客・商機転換の冪等性を検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "admin", roles = "管理者")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LeadServiceIntegrationTest {
    @Autowired private LeadService leadService;
    @Autowired private CustomerMapper customerMapper;
    @Autowired private CustomerContactMapper contactMapper;
    @Autowired private OpportunityMapper opportunityMapper;

    @Test
    void duplicateCandidatesAreWarningsOnlyAndDoNotMerge() {
        LeadSaveRequest first = request("重複候補社", "contact@example.com");
        Lead created = leadService.create(first);
        List<Lead> candidates = leadService.duplicateCandidates("重複候補社", "contact@example.com", null, null);
        assertEquals(1, candidates.size());
        assertEquals(created.getId(), candidates.get(0).getId());
        assertEquals(1, leadService.list(null, null).size(), "候補表示だけでは自動統合しない");
    }

    @Test
    void conversionCreatesCustomerContactAndOpportunityIdempotently() {
        Lead lead = leadService.create(request("転換対象社", "sales@convert.example"));
        LeadConversionDto first = leadService.convert(lead.getId(), lead.getVersion());
        LeadConversionDto second = leadService.convert(lead.getId(), 1);

        assertEquals(first.getCustomerId(), second.getCustomerId());
        assertEquals(first.getOpportunityId(), second.getOpportunityId());
        assertEquals(1, customerMapper.selectCount(new LambdaQueryWrapper<Customer>().eq(Customer::getId, first.getCustomerId())));
        assertEquals(1, contactMapper.selectCount(new LambdaQueryWrapper<CustomerContact>().eq(CustomerContact::getCustomerId, first.getCustomerId())));
        assertEquals(1, opportunityMapper.selectCount(new LambdaQueryWrapper<Opportunity>().eq(Opportunity::getId, first.getOpportunityId())));
    }

    private LeadSaveRequest request(String company, String email) {
        LeadSaveRequest request = new LeadSaveRequest();
        request.setCompanyName(company);
        request.setContactName("担当者");
        request.setContactEmail(email);
        request.setSource("Web");
        return request;
    }
}
