package com.ses.controller.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.result.ApiResult;
import com.ses.dto.customer.CustomerContactDto;
import com.ses.entity.Opportunity;
import com.ses.entity.SalesActivity;
import com.ses.mapper.OpportunityMapper;
import com.ses.service.CustomerContactService;
import com.ses.service.SalesActivityService;
import com.ses.service.security.DataScopeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** 顧客・担当者・商機・活動を同じ顧客スコープで返すタイムラインAPI。 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerTimelineApiController {
    private final CustomerContactService customerContactService;
    private final SalesActivityService salesActivityService;
    private final OpportunityMapper opportunityMapper;
    private final DataScopeService dataScopeService;

    @GetMapping("/{customerId}/timeline")
    public ApiResult<?> timeline(@PathVariable Long customerId) {
        dataScopeService.assertAllowedCustomer(customerId);
        List<CustomerContactDto> contacts = customerContactService.list(customerId, LocalDate.now());
        List<SalesActivity> activities = salesActivityService.list(new LambdaQueryWrapper<SalesActivity>()
                .eq(SalesActivity::getCustomerId, customerId)
                .orderByDesc(SalesActivity::getActivityDate)
                .last("LIMIT 100"));
        List<Opportunity> opportunities = opportunityMapper.selectList(new LambdaQueryWrapper<Opportunity>()
                .eq(Opportunity::getCustomerId, customerId)
                .orderByDesc(Opportunity::getId)
                .last("LIMIT 100"));
        return ApiResult.success(Map.of(
                "contacts", contacts,
                "activities", activities,
                "opportunities", opportunities));
    }
}
