package com.ses.service.search.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Quotation;
import com.ses.mapper.QuotationMapper;
import com.ses.service.search.GlobalSearchProvider;
import com.ses.service.security.DataScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class QuotationSearchProvider implements GlobalSearchProvider {

    @Autowired
    private QuotationMapper quotationMapper;

    @Autowired
    private DataScopeService dataScopeService;

    @Override
    public String getType() {
        return "QUOTATION";
    }

    @Override
    public String getRequiredActionKey() {
        return "quotation.view";
    }

    @Override
    public List<GlobalSearchResultDTO> search(String query, int maxResults) {
        Set<Long> allowedCustomerIds = dataScopeService.allowedCustomerIds();
        Set<Long> allowedEngineerIds = dataScopeService.allowedEngineerIds();

        if (dataScopeService.isScoped()) {
            boolean hasCustomers = allowedCustomerIds != null && !allowedCustomerIds.isEmpty();
            boolean hasEngineers = allowedEngineerIds != null && !allowedEngineerIds.isEmpty();
            if (!hasCustomers && !hasEngineers) {
                return List.of();
            }
        }

        LambdaQueryWrapper<Quotation> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(Quotation::getQuotationNo, query)
                .or().like(Quotation::getTitle, query));

        if (dataScopeService.isScoped()) {
            wrapper.and(w -> {
                if (allowedCustomerIds != null && !allowedCustomerIds.isEmpty()) {
                    w.in(Quotation::getCustomerId, allowedCustomerIds);
                }
                if (allowedEngineerIds != null && !allowedEngineerIds.isEmpty()) {
                    w.or().in(Quotation::getEngineerId, allowedEngineerIds);
                }
            });
        }

        wrapper.orderByDesc(Quotation::getUpdatedAt);

        Page<Quotation> page = quotationMapper.selectPage(new Page<>(1, maxResults), wrapper);
        return page.getRecords().stream().map(q -> GlobalSearchResultDTO.builder()
                .type(getType())
                .id(q.getId())
                .title(q.getQuotationNo() != null ? q.getQuotationNo() : q.getTitle())
                .subtitle(q.getTitle())
                .status(q.getStatus())
                .url("/quotation/list")
                .updatedAt(q.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }
}
