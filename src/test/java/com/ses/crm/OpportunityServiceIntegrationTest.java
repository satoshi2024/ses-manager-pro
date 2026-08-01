package com.ses.crm;

import com.ses.dto.crm.OpportunityConversionDto;
import com.ses.entity.Opportunity;
import com.ses.mapper.OpportunityMapper;
import com.ses.service.OpportunityService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** T049のH2実DB回帰。受注変換の永続化とforecast排他を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OpportunityServiceIntegrationTest {

    @Autowired
    private OpportunityService opportunityService;

    @Autowired
    private OpportunityMapper opportunityMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void wonConversion_isPersistedAndSecondExecutionDoesNotDuplicate() {
        long customerId = newCustomer("T049変換顧客");
        Opportunity opportunity = newOpportunity(customerId, "交渉", "2026-09");
        opportunityMapper.insert(opportunity);

        Opportunity changed = opportunityService.changeStage(opportunity.getId(), "受注", null, 1);
        OpportunityConversionDto first = new OpportunityConversionDto(
                changed.getId(), changed.getConvertedProjectId(), changed.getConvertedQuotationId());
        OpportunityConversionDto second = opportunityService.convertToProjectAndQuotation(opportunity.getId());

        assertNotNull(first.getProjectId());
        assertNotNull(first.getQuotationId());
        assertEquals(first, second);
        assertEquals(1, count("t_project", "source_opportunity_id", opportunity.getId()));
        assertEquals(1, count("t_quotation", "source_opportunity_id", opportunity.getId()));
        assertEquals("受注", opportunityMapper.selectById(opportunity.getId()).getStage());
    }

    @Test
    void forecastExcludesWonConvertedOpportunityButKeepsOpenOne() {
        long customerId = newCustomer("T049予測顧客");
        Opportunity open = newOpportunity(customerId, "交渉", "2026-10");
        opportunityMapper.insert(open);
        Opportunity won = newOpportunity(customerId, "交渉", "2026-11");
        opportunityMapper.insert(won);
        opportunityService.changeStage(won.getId(), "受注", null, 1);

        List<Opportunity> candidates = opportunityService.listForecastCandidates();

        assertTrue(candidates.stream().anyMatch(o -> o.getId().equals(open.getId())));
        assertTrue(candidates.stream().noneMatch(o -> o.getId().equals(won.getId())));
    }

    private Opportunity newOpportunity(long customerId, String stage, String startMonth) {
        Opportunity opportunity = new Opportunity();
        opportunity.setCustomerId(customerId);
        opportunity.setTitle("T049商機");
        opportunity.setStage(stage);
        opportunity.setExpectedStartMonth(startMonth);
        opportunity.setDurationMonths(3);
        opportunity.setRequiredCount(1);
        opportunity.setUnitPrice(new BigDecimal("700000"));
        opportunity.setProbability(60);
        opportunity.setVersion(1);
        return opportunity;
    }

    private long newCustomer(String name) {
        jdbcTemplate.update("INSERT INTO m_customer (company_name, trust_level, deleted_flag) VALUES (?, 'B', 0)", name);
        return jdbcTemplate.queryForObject("SELECT id FROM m_customer WHERE company_name = ?", Long.class, name);
    }

    private int count(String table, String column, Long value) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?",
                Integer.class, value);
    }
}
