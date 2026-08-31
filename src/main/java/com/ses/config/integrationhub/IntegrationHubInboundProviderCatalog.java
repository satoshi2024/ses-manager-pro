package com.ses.config.integrationhub;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** DG-05で承認されたinbound providerの明示catalog。未登録providerはdefault deny。 */
@Component
public final class IntegrationHubInboundProviderCatalog {
    private final Set<String> approvedProviders;

    @Autowired
    public IntegrationHubInboundProviderCatalog(IntegrationHubExternalApiProperties properties) {
        this(properties.getProvider() == null ? Set.of()
                : new LinkedHashSet<>(properties.getProvider().getApprovedInboundProviders()));
    }

    public IntegrationHubInboundProviderCatalog(Set<String> approvedProviders) {
        this.approvedProviders = approvedProviders == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(approvedProviders));
    }

    public boolean isApproved(String provider) {
        return provider != null && approvedProviders.contains(provider);
    }

    public Set<String> approvedProviders() {
        return approvedProviders;
    }
}
