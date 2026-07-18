package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.ses.entity.Contract;
import com.ses.entity.ContractPriceHistory;
import com.ses.mapper.ContractPriceHistoryMapper;
import com.ses.service.billing.ContractPriceResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ContractPriceResolverImpl implements ContractPriceResolver {

    private final ContractPriceHistoryMapper priceHistoryMapper;

    @Override
    public ResolvedPrice resolve(Contract contract, YearMonth month) {
        List<ContractPriceHistory> histories = priceHistoryMapper.selectList(
                new QueryWrapper<ContractPriceHistory>().eq("contract_id", contract.getId()));
        return ContractPriceResolver.resolveFrom(contract, month, histories);
    }

    @Override
    public Map<Long, ResolvedPrice> resolveBatch(List<Contract> contracts, YearMonth month) {
        Map<Long, ResolvedPrice> result = new LinkedHashMap<>();
        if (contracts == null || contracts.isEmpty()) {
            return result;
        }
        List<Long> ids = contracts.stream().map(Contract::getId).filter(java.util.Objects::nonNull).toList();
        Map<Long, List<ContractPriceHistory>> byContract = ids.isEmpty() ? Collections.emptyMap()
                : priceHistoryMapper.selectList(new QueryWrapper<ContractPriceHistory>().in("contract_id", ids))
                        .stream().collect(Collectors.groupingBy(ContractPriceHistory::getContractId));
        for (Contract c : contracts) {
            result.put(c.getId(), ContractPriceResolver.resolveFrom(c, month,
                    byContract.getOrDefault(c.getId(), Collections.emptyList())));
        }
        return result;
    }
}
