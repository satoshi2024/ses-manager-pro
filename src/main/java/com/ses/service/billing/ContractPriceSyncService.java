package com.ses.service.billing;

import com.ses.entity.Contract;
import com.ses.entity.ContractPriceHistory;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ContractPriceHistoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactionTemplate;

    @Scheduled(cron = "0 0 0 * * ?")
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
        int processCount = 0;
        int updateCount = 0;

        for (Map.Entry<Long, List<ContractPriceHistory>> entry : historyByContract.entrySet()) {
            Long contractId = entry.getKey();
            transactionTemplate.executeWithoutResult(status -> {
                // 行ロックで通常更新と直列化し、ロック取得後に最新状態で履歴を再解決する（R3R-29）。
                Contract contract = contractMapper.selectByIdForUpdate(contractId);
                if (contract == null) return;
    
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
                        // 単価列だけを部分UPDATEし、他項目を旧値で上書きしない（R3R-29）。
                        contractMapper.updatePriceOnly(contractId, resolvedSelling, resolvedCost);
                        updateCount++;
                    }
                }
            });
            processCount++;
        }
        
        log.info("契約単価同期完了。処理{}件（単価変更あり: {}件）", processCount, updateCount);
    }
}