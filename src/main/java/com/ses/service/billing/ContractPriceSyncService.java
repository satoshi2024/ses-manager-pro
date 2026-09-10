package com.ses.service.billing;

import com.ses.entity.Contract;
import com.ses.entity.ContractPriceHistory;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.ContractPriceHistoryMapper;
import com.ses.service.scheduler.TenantAwareBatchRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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
    private final TenantAwareBatchRunner tenantAwareBatchRunner;

    @Scheduled(cron = "0 0 0 * * ?")
    @SchedulerLock(name = "contractPriceSyncDaily", lockAtLeastFor = "PT1M", lockAtMostFor = "PT30M")
    public void syncCurrentPrices() {
        log.info("Starting contract price sync...");
        int[] processCount = {0};
        int[] updateCount = {0};
        tenantAwareBatchRunner.run(tenantId -> syncTenant(tenantId, processCount, updateCount));

    log.info("契約単価同期完了。処理{}件（単価変更あり: {}件）", processCount[0], updateCount[0]);
    }

    private void syncTenant(String tenantId, int[] processCount, int[] updateCount) {
        List<Long> contractIds = priceHistoryMapper.selectContractIdsForTenant(tenantId);
        YearMonth currentMonth = YearMonth.now();
        for (Long contractId : contractIds) {
            transactionTemplate.executeWithoutResult(status -> {
                // 行ロックで通常更新と改定を直列化し、tenant ownershipを再検証する。
                Contract contract = contractMapper.selectByIdForUpdateForTenant(contractId, tenantId);
                if (contract == null) return;
                List<ContractPriceHistory> histories = priceHistoryMapper.selectByContractIdForTenant(contractId, tenantId);
                ContractPriceResolver.ResolvedPrice resolved = ContractPriceResolver.resolveFrom(
                        contract, currentMonth, histories);
                if (!resolved.isFromHistory()) return;
                BigDecimal resolvedSelling = resolved.getSellingPrice();
                BigDecimal resolvedCost = resolved.getCostPrice();
                boolean sellingDiff = contract.getSellingPrice() == null
                        || contract.getSellingPrice().compareTo(resolvedSelling) != 0;
                boolean costDiff = contract.getCostPrice() == null
                        || contract.getCostPrice().compareTo(resolvedCost) != 0;
                if (sellingDiff || costDiff) {
                    log.info("Contract {} price changed: selling ({} -> {}), cost ({} -> {})",
                            contractId, contract.getSellingPrice(), resolvedSelling,
                            contract.getCostPrice(), resolvedCost);
                    if (contractMapper.updatePriceOnlyForTenant(contractId, tenantId,
                            contract.getVersion() == null ? 0 : contract.getVersion(),
                            resolvedSelling, resolvedCost) != 1) {
                        throw new IllegalStateException("契約単価同期のCASに失敗しました");
                    }
                    updateCount[0]++;
                }
                processCount[0]++;
            });
        }
    }
}
