package com.ses.service.ai;

/** bounded Copilot adapterの応答。通常AI経路の戻り値とは分離する。 */
public record CopilotProviderResponse(String text, CopilotProviderUsage usage) { }
