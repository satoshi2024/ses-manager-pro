package com.ses.service.accounting;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** 本番系profileで定期処理のtenant inventoryを起動時に検証する。 */
@Component
@Profile("!test")
public class AccountingTenantInventoryStartupValidator {

    public AccountingTenantInventoryStartupValidator(AccountingTenantInventoryProperties properties) {
        properties.requireNormalizedIds();
    }
}
