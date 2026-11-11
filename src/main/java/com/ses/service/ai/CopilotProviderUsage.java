package com.ses.service.ai;

/** Providerが返した型付きtoken usage。API keyや生応答本文は含めない。 */
public record CopilotProviderUsage(int promptTokenCount, int candidateTokenCount, int totalTokenCount,
                                   Integer thoughtsTokenCount, Integer cachedContentTokenCount) { }
