package com.ses.privacy.foundation;

import java.util.Map;

/** provider未登録を既定とし、missing adapterはmockせず拒否する。 */
public final class PrivacyProviderRegistry {
    private final Map<String, PrivacyProvider> providers;
    private final SyntheticEnvironmentGuard environmentGuard;

    public PrivacyProviderRegistry(Map<String, PrivacyProvider> providers, SyntheticEnvironmentGuard environmentGuard) {
        this.providers = Map.copyOf(providers);
        this.environmentGuard = java.util.Objects.requireNonNull(environmentGuard, "environmentGuardが必要です");
        this.providers.forEach((id, provider) -> {
            if (!id.equals(provider.providerId())) throw new IllegalArgumentException("provider registry keyが一致しません");
        });
    }

    public PrivacyProvider require(String providerId) {
        PrivacyProvider provider = providers.get(providerId);
        if (provider == null) throw new SecurityException("承認済みprovider adapterが登録されていません");
        environmentGuard.requireSyntheticAllowed(provider.audience());
        return provider;
    }
}
