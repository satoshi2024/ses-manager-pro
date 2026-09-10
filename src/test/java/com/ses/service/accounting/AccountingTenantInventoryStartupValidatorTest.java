package com.ses.service.accounting;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AccountingTenantInventoryStartupValidatorTest {

    @Test
    void 空inventoryは起動時に拒否する() {
        AccountingTenantInventoryProperties properties = new AccountingTenantInventoryProperties();
        properties.setIds(Set.of(" ", "\t"));

        assertThrows(IllegalStateException.class,
                () -> new AccountingTenantInventoryStartupValidator(properties));
        assertThrows(IllegalStateException.class, properties::requireNormalizedIds);
    }

    @Test
    void 複数tenantのinventoryはtrimして保持する() {
        AccountingTenantInventoryProperties properties = new AccountingTenantInventoryProperties();
        properties.setIds(Set.of(" tenant-a ", "tenant-b"));

        assertDoesNotThrow(() -> new AccountingTenantInventoryStartupValidator(properties));
        org.junit.jupiter.api.Assertions.assertEquals(Set.of("tenant-a", "tenant-b"),
                properties.requireNormalizedIds());
    }
}
