package com.ses.crm;

import com.ses.dto.crm.CrmKpiDto;
import com.ses.entity.Lead;
import com.ses.entity.Opportunity;
import com.ses.entity.SalesActivity;
import com.ses.mapper.LeadMapper;
import com.ses.mapper.OpportunityMapper;
import com.ses.mapper.SalesActivityMapper;
import com.ses.service.CrmKpiService;
import com.ses.service.CustomerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** T052定向テスト: 集計口径、source追跡、forecast排他、全社合計を確認する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@WithMockUser(username = "admin", roles = "管理者")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CrmKpiServiceIntegrationTest {
    @Autowired private CrmKpiService crmKpiService;
    @Autowired private CustomerService customerService;
    @Autowired private LeadMapper leadMapper;
    @Autowired private OpportunityMapper opportunityMapper;
    @Autowired private SalesActivityMapper activityMapper;

    @Test
    void kpiUsesStageAmountOwnerConversionSourceRoiAndSeparateForecastSeries() {
        com.ses.entity.Customer customer = new com.ses.entity.Customer();
        customer.setCompanyName("T052 KPI顧客");
        customerService.save(customer);

        Lead converted = Lead.builder().companyName("T052 KPI顧客").source("展示会").sourceCost(new BigDecimal("500000"))
                .ownerUserId(1L)
                .status("転換済").version(1).build();
        leadMapper.insert(converted);

        Opportunity open = opportunity(null, customer.getId(), "公開商機", "見込", new BigDecimal("1000000"), 20, 1L);
        opportunityMapper.insert(open);
        Opportunity won = opportunity(null, customer.getId(), "受注商機", "受注", new BigDecimal("1000000"), 100, 1L);
        opportunityMapper.insert(won);
        // 同じsourceのleadから受注商機を辿り、ROIを計算できるよう生成後IDを反映する。
        Lead convertedUpdate = new Lead();
        convertedUpdate.setId(converted.getId());
        convertedUpdate.setConvertedOpportunityId(won.getId());
        leadMapper.updateById(convertedUpdate);

        Opportunity lost = opportunity(null, customer.getId(), "失注商機", "失注", new BigDecimal("500000"), 0, 1L);
        lost.setLostReason("予算");
        opportunityMapper.insert(lost);

        SalesActivity activity = new SalesActivity();
        activity.setCustomerId(customer.getId());
        activity.setOpportunityId(open.getId());
        activity.setActivityType("商談");
        activity.setActivityDate(LocalDate.now().minusDays(2));
        activity.setTitle("T052活動");
        activityMapper.insert(activity);

        CrmKpiDto dto = crmKpiService.summarize(null, null);
        Map<String, CrmKpiDto.StageKpi> stages = dto.getStages().stream().collect(Collectors.toMap(CrmKpiDto.StageKpi::getStage, Function.identity()));
        assertEquals(new BigDecimal("1000000"), stages.get("見込").getAmount());
        assertEquals(new BigDecimal("200000"), stages.get("見込").getWeightedAmount());
        assertEquals(1, stages.get("失注").getCount());
        assertEquals(2, stages.get("見込").getAverageNoActivityDays());
        assertEquals(1, dto.getForecast().getOpportunityCount(), "受注/失注/変換済みは商機forecastから除外");
        assertEquals(new BigDecimal("200000"), dto.getForecast().getOpportunityAmount());
        assertEquals(1, dto.getOwners().stream().filter(o -> Long.valueOf(1L).equals(o.getOwnerUserId())).findFirst().orElseThrow().getLeadCount());
        assertEquals(new BigDecimal("100.0"), dto.getOwners().stream().filter(o -> Long.valueOf(1L).equals(o.getOwnerUserId())).findFirst().orElseThrow().getLeadConversionRate());
        assertEquals(new BigDecimal("100.0"), dto.getSources().get(0).getRoiRate(), "転換先商機のsource ROI");
    }

    private Opportunity opportunity(Long id, Long customerId, String title, String stage,
                                    BigDecimal expectedAmount, int probability, Long ownerId) {
        Opportunity opportunity = new Opportunity();
        opportunity.setId(id);
        opportunity.setCustomerId(customerId);
        opportunity.setTitle(title);
        opportunity.setStage(stage);
        opportunity.setExpectedAmount(expectedAmount);
        opportunity.setProbability(probability);
        opportunity.setOwnerUserId(ownerId);
        opportunity.setVersion(1);
        return opportunity;
    }
}
