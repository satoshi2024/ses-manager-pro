package com.ses.service.search.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Invoice;
import com.ses.mapper.InvoiceMapper;
import com.ses.service.search.GlobalSearchProvider;
import com.ses.service.security.DataScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class InvoiceSearchProvider implements GlobalSearchProvider {

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private DataScopeService dataScopeService;

    @Override
    public String getType() {
        return "INVOICE";
    }

    @Override
    public List<GlobalSearchResultDTO> search(String query, int maxResults) {
        if (dataScopeService.isScoped()) {
            Set<Long> allowedCustomerIds = dataScopeService.allowedCustomerIds();
            if (allowedCustomerIds == null || allowedCustomerIds.isEmpty()) {
                return List.of();
            }
        }

        LambdaQueryWrapper<Invoice> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(Invoice::getInvoiceNo, query);

        if (dataScopeService.isScoped()) {
            wrapper.in(Invoice::getCustomerId, dataScopeService.allowedCustomerIds());
        }

        wrapper.orderByDesc(Invoice::getUpdatedAt);

        Page<Invoice> page = invoiceMapper.selectPage(new Page<>(1, maxResults), wrapper);
        return page.getRecords().stream().map(inv -> GlobalSearchResultDTO.builder()
                .type(getType())
                .id(inv.getId())
                .title(inv.getInvoiceNo())
                .subtitle(inv.getBillingMonth()) // 金額 (total/subtotal) は subtitle へ出さない (design §2)
                .status(inv.getStatus())
                .url("/invoice/list?id=" + inv.getId())
                .updatedAt(inv.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }
}
