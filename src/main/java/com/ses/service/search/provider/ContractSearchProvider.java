package com.ses.service.search.provider;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.search.GlobalSearchResultDTO;
import com.ses.entity.Contract;
import com.ses.mapper.ContractMapper;
import com.ses.service.search.GlobalSearchProvider;
import com.ses.service.security.DataScopeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ContractSearchProvider implements GlobalSearchProvider {

    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private DataScopeService dataScopeService;

    @Override
    public String getType() {
        return "CONTRACT";
    }

    @Override
    public List<GlobalSearchResultDTO> search(String query, int maxResults) {
        if (dataScopeService.isScoped()) {
            Set<Long> allowedIds = dataScopeService.allowedContractIds();
            if (allowedIds == null || allowedIds.isEmpty()) {
                return List.of();
            }
        }

        LambdaQueryWrapper<Contract> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(Contract::getContractNo, query);

        if (dataScopeService.isScoped()) {
            wrapper.in(Contract::getId, dataScopeService.allowedContractIds());
        }

        wrapper.orderByDesc(Contract::getUpdatedAt);

        Page<Contract> page = contractMapper.selectPage(new Page<>(1, maxResults), wrapper);
        return page.getRecords().stream().map(c -> GlobalSearchResultDTO.builder()
                .type(getType())
                .id(c.getId())
                .title(c.getContractNo())
                .subtitle(c.getContractType()) // 単価・原価は subtitle へ出さない (design §2)
                .status(c.getStatus())
                .url("/contract/list?id=" + c.getId())
                .updatedAt(c.getUpdatedAt())
                .build()).collect(Collectors.toList());
    }
}
