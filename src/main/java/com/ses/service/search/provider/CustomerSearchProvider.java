package com.ses.service.search.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Customer;
import com.ses.mapper.CustomerMapper;
import com.ses.service.search.GlobalSearchProvider;
import com.ses.service.security.DataScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CustomerSearchProvider implements GlobalSearchProvider {

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private DataScopeService dataScopeService;

    @Override
    public String getType() {
        return "CUSTOMER";
    }

    @Override
    public String getRequiredActionKey() {
        return "customer.view";
    }

    @Override
    public List<GlobalSearchResultDTO> search(String query, int maxResults) {
        if (dataScopeService.isScoped()) {
            Set<Long> allowedIds = dataScopeService.allowedCustomerIds();
            if (allowedIds == null || allowedIds.isEmpty()) {
                return List.of();
            }
        }

        LambdaQueryWrapper<Customer> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(Customer::getCompanyName, query)
                .or().like(Customer::getCompanyNameKana, query)
                .or().like(Customer::getContactPerson, query));

        if (dataScopeService.isScoped()) {
            wrapper.in(Customer::getId, dataScopeService.allowedCustomerIds());
        }

        wrapper.orderByDesc(Customer::getUpdatedAt);

        Page<Customer> page = customerMapper.selectPage(new Page<>(1, maxResults), wrapper);
        return page.getRecords().stream().map(c -> GlobalSearchResultDTO.builder()
                .type(getType())
                .id(c.getId())
                .title(c.getCompanyName())
                .subtitle(c.getContactPerson())
                .status(c.getCommercialFlow())
                .url("/customer/detail?id=" + c.getId())
                .updatedAt(c.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }
}
