package com.ses.crm;

import com.ses.common.exception.BusinessException;
import com.ses.dto.customer.CustomerContactSaveRequest;
import com.ses.dto.salesactivity.SalesActivityCreateRequest;
import com.ses.entity.CustomerContact;
import com.ses.entity.Opportunity;
import com.ses.entity.SalesActivity;
import com.ses.mapper.CustomerContactMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.service.CustomerContactService;
import com.ses.service.CustomerService;
import com.ses.service.SalesActivityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T050定向テスト: 期間/CAS、退職者除外、活動関連付け、宛先母集団を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "admin", roles = "管理者")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CustomerContactServiceIntegrationTest {
    @Autowired private CustomerContactService contactService;
    @Autowired private CustomerContactMapper contactMapper;
    @Autowired private CustomerService customerService;
    @Autowired private OpportunityMapper opportunityMapper;
    @Autowired private SalesActivityService activityService;

    @Test
    void primaryPeriodIsInclusiveAndOverlappingClosedIntervalsAreRejected() {
        com.ses.entity.Customer customer = new com.ses.entity.Customer();
        customer.setCompanyName("T050期間顧客");
        customerService.save(customer);

        CustomerContactSaveRequest first = request("主担当A", "a@example.com", LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31), 1, "有効", null);
        contactService.create(customer.getId(), first);

        CustomerContactSaveRequest overlap = request("主担当B", "b@example.com", LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 6, 30), 1, "有効", null);
        assertThrows(BusinessException.class, () -> contactService.create(customer.getId(), overlap));

        CustomerContactSaveRequest after = request("主担当C", "c@example.com", LocalDate.of(2026, 4, 1),
                null, 1, "有効", null);
        contactService.create(customer.getId(), after);
        assertEquals(1, contactService.recipientCandidates(customer.getId(), LocalDate.of(2026, 3, 31)).size());
        assertEquals("c@example.com", contactService.resolveRecipientEmail(customer.getId(),
                contactMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CustomerContact>()
                        .eq(CustomerContact::getName, "主担当C")).getId(), LocalDate.of(2026, 4, 1)));
    }

    @Test
    void retiredContactRemainsHistoryButIsExcludedFromRecipients() {
        com.ses.entity.Customer customer = new com.ses.entity.Customer();
        customer.setCompanyName("T050退職顧客");
        customerService.save(customer);
        CustomerContactSaveRequest request = request("退職予定", "retired@example.com", LocalDate.of(2025, 1, 1),
                null, 1, "有効", null);
        var created = contactService.create(customer.getId(), request);

        contactService.retire(customer.getId(), created.getId(), LocalDate.of(2026, 2, 28), created.getVersion());

        assertTrue(contactService.recipientCandidates(customer.getId(), LocalDate.of(2026, 3, 1)).isEmpty());
        assertEquals(1, contactService.list(customer.getId(), LocalDate.of(2026, 3, 1)).size());
    }

    @Test
    void activityCanBeRelatedToContactAndOpportunityOfSameCustomer() {
        com.ses.entity.Customer customer = new com.ses.entity.Customer();
        customer.setCompanyName("T050接点顧客");
        customerService.save(customer);

        CustomerContactSaveRequest contactRequest = request("接点担当", "contact@example.com", LocalDate.now(),
                null, 0, "有効", null);
        var contact = contactService.create(customer.getId(), contactRequest);
        Opportunity opportunity = new Opportunity();
        opportunity.setCustomerId(customer.getId());
        opportunity.setTitle("T050商機");
        opportunity.setStage("見込");
        opportunityMapper.insert(opportunity);

        SalesActivityCreateRequest activityRequest = new SalesActivityCreateRequest();
        activityRequest.setActivityType("商談");
        activityRequest.setActivityDate(LocalDate.now());
        activityRequest.setTitle("初回商談");
        activityRequest.setContactId(contact.getId());
        activityRequest.setOpportunityId(opportunity.getId());
        SalesActivity activity = activityService.create(customer.getId(), activityRequest);

        SalesActivity stored = activityService.getById(activity.getId());
        assertEquals(contact.getId(), stored.getContactId());
        assertEquals(opportunity.getId(), stored.getOpportunityId());
    }

    private CustomerContactSaveRequest request(String name, String email, LocalDate from, LocalDate to,
                                                int primary, String status, Integer version) {
        CustomerContactSaveRequest request = new CustomerContactSaveRequest();
        request.setName(name);
        request.setEmail(email);
        request.setValidFrom(from);
        request.setValidTo(to);
        request.setPrimaryFlag(primary);
        request.setStatus(status);
        request.setVersion(version);
        return request;
    }
}
