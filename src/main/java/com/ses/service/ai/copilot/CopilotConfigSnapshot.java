package com.ses.service.ai.copilot;

import com.ses.config.AiConfig;
import com.ses.service.ai.AiTextService;
import com.ses.service.ai.CopilotBoundedAiTextService;

import java.net.URI;

/** 起動時に固定するCopilot adapter設定。秘密値は保持しない。 */
public record CopilotConfigSnapshot(
        String configuredProvider,
        String adapterProvider,
        String canonicalEndpoint,
        String modelId,
        String promptVersion,
        String configHash,
        int maxOutputTokens
) {
    public CopilotConfigSnapshot {
        configuredProvider = safe(configuredProvider);
        adapterProvider = safe(adapterProvider);
        canonicalEndpoint = safe(canonicalEndpoint);
        modelId = safe(modelId);
        promptVersion = safe(promptVersion);
        configHash = safe(configHash);
        if (maxOutputTokens < 0) {
            throw new IllegalArgumentException("maxOutputTokens must not be negative");
        }
    }

    public static CopilotConfigSnapshot capture(AiConfig config, AiTextService service) {
        String provider = config == null ? "" : config.getProvider();
        String endpoint = canonicalEndpoint(config == null ? null : config.getApiUrl());
        int maxOutputTokens = config == null ? 0 : Math.max(0, config.getMaxTokens());
        if (!(service instanceof CopilotBoundedAiTextService bounded)) {
            return new CopilotConfigSnapshot(provider, service == null ? "" : service.providerId(),
                    endpoint, "", "", "", maxOutputTokens);
        }
        return new CopilotConfigSnapshot(provider, bounded.providerId(), endpoint,
                bounded.copilotModelId(), bounded.copilotPromptVersion(), bounded.copilotConfigHash(),
                maxOutputTokens);
    }

    public boolean hasBoundedIdentity() {
        return !configuredProvider.isBlank() && configuredProvider.equals(adapterProvider)
                && !modelId.isBlank() && !promptVersion.isBlank() && !configHash.isBlank();
    }

    private static String canonicalEndpoint(String configured) {
        if (configured == null || configured.isBlank()) {
            return "";
        }
        try {
            URI uri = URI.create(configured.trim());
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getScheme() == null || uri.getHost() == null
                    || !"https".equalsIgnoreCase(uri.getScheme())
                    || !"generativelanguage.googleapis.com".equalsIgnoreCase(uri.getHost())
                    || (uri.getPort() != -1 && uri.getPort() != 443)) {
                return "UNSAFE_ENDPOINT";
            }
            int port = uri.getPort();
            return uri.getScheme().toLowerCase(java.util.Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(java.util.Locale.ROOT)
                    + (port < 0 ? "" : ":" + port) + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (RuntimeException ex) {
            return "UNSAFE_ENDPOINT";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
