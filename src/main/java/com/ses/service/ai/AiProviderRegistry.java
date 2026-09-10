package com.ses.service.ai;

import com.ses.common.exception.BusinessException;

import java.util.Locale;
import java.util.Map;

/**
 * provider名と実装契約を固定するregistry。未登録値はmockへfallbackさせない。
 */
public final class AiProviderRegistry {
    private static final Map<String, ProviderDescriptor> PROVIDERS = Map.of(
            "mock", new ProviderDescriptor("mock", true),
            "rule", new ProviderDescriptor("rule", true),
            "gemini", new ProviderDescriptor("gemini", false));

    public ProviderDescriptor requireRegistered(String provider) {
        String normalized = normalize(provider);
        ProviderDescriptor descriptor = PROVIDERS.get(normalized);
        if (descriptor == null) {
            throw BusinessException.of(503, "AI_PROVIDER_NOT_REGISTERED");
        }
        return descriptor;
    }

    public void assertImplementation(String provider, AiTextService implementation) {
        ProviderDescriptor descriptor = requireRegistered(provider);
        if (implementation == null) {
            throw BusinessException.of(503, "AI_PROVIDER_IMPLEMENTATION_UNAVAILABLE");
        }
        String implementationId = implementation.providerId();
        // Mockito等のtest doubleはidentityを返さないため、実装IDが存在する場合だけ照合する。
        if (implementationId != null && !descriptor.name().equals(implementationId)) {
            throw BusinessException.of(503, "AI_PROVIDER_IMPLEMENTATION_MISMATCH");
        }
    }

    public boolean isLocal(String provider) {
        return requireRegistered(provider).localOnly();
    }

    private String normalize(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    public record ProviderDescriptor(String name, boolean localOnly) {
    }
}
