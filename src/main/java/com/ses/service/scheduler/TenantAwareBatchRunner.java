package com.ses.service.scheduler;

import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.accounting.AccountingTenantInventoryProperties;
import com.ses.service.accounting.AccountingTimezoneResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
 * inventoryに明示されたtenantごとにbatchを実行する共通runner。
 * HTTP sessionや親threadのThreadLocalを参照せず、各tenantをrunWithTenantで囲む。
 */
@Component
@RequiredArgsConstructor
public class TenantAwareBatchRunner {

    private final AccountingTenantInventoryProperties tenantInventory;
    private final AccountingTimezoneResolver timezoneResolver;

    /** tenantごとの処理結果を合計する。inventory空はrequireNormalizedIds()で停止する。 */
    public int runAndSum(ToIntFunction<String> task) {
        int total = 0;
        for (String tenantId : tenantInventory.requireNormalizedIds()) {
            ZoneId zone = timezoneResolver.resolve(tenantId);
            total += AccountingTenantContextHolder.runWithTenant(tenantId, zone,
                    () -> task.applyAsInt(tenantId));
        }
        return total;
    }

    /** tenantごとに処理する。例外は握り潰さず呼出元へ伝播する。 */
    public void run(Consumer<String> task) {
        for (String tenantId : tenantInventory.requireNormalizedIds()) {
            ZoneId zone = timezoneResolver.resolve(tenantId);
            AccountingTenantContextHolder.runWithTenant(tenantId, zone,
                    () -> task.accept(tenantId));
        }
    }
}
