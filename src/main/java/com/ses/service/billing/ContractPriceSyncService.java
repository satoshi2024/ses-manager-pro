package com.ses.service.billing;

import com.ses.entity.Contract;
import com.ses.entity.ContractPriceHistory;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ContractPriceHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 契約の現在単価を履歴から同期するサービス（C53対応）
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContractPriceSyncService {

    private final ContractMapper contractMapper;
    private final ContractPriceHistoryMapper priceHistoryMapper;

    @Scheduled(cron = "0 0 0 * * ?")
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(rollbackFor = Exception.class)
    public void syncCurrentPrices() {
        log.info("Starting contract price sync...");
        
        List<ContractPriceHistory> allHistories = priceHistoryMapper.selectList(null);
        if (allHistories.isEmpty()) {
            log.info("No price histories found. Sync completed.");
            return;
        }

        Map<Long, List<ContractPriceHistory>> historyByContract = allHistories.stream()
                .collect(Collectors.groupingBy(ContractPriceHistory::getContractId));

        YearMonth currentMonth = YearMonth.now();
        int updateCount = 0;

        for (Map.Entry<Long, List<ContractPriceHistory>> entry : historyByContract.entrySet()) {
            Long contractId = entry.getKey();
            Contract contract = contractMapper.selectById(contractId);
            if (contract == null) continue;

            ContractPriceResolver.ResolvedPrice resolved = ContractPriceResolver.resolveFrom(
                    contract, currentMonth, entry.getValue());

            if (resolved.isFromHistory()) {
                BigDecimal resolvedSelling = resolved.getSellingPrice();
                BigDecimal resolvedCost = resolved.getCostPrice();

                boolean sellingDiff = contract.getSellingPrice() == null || contract.getSellingPrice().compareTo(resolvedSelling) != 0;
                boolean costDiff = contract.getCostPrice() == null || contract.getCostPrice().compareTo(resolvedCost) != 0;

                if (sellingDiff || costDiff) {
                    log.info("Contract {} price changed: selling ({} -> {}), cost ({} -> {})",
                            contractId, contract.getSellingPrice(), resolvedSelling,
                            contract.getCostPrice(), resolvedCost);
                    contract.setSellingPrice(resolvedSelling);
                    contract.setCostPrice(resolvedCost);
                    contractMapper.updateById(contract);
                    updateCount++;
                }
            }
        }
        
        log.info("Contract price sync completed. Updated {} contracts.", updateCount);
    }
}