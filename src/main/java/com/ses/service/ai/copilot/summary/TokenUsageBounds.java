package com.ses.service.ai.copilot.summary;

/** preflightで独立に根拠を持つprompt/candidate母集合の最大token数。 */
record TokenUsageBounds(Long promptMax, Long candidateMax, Long cachedMax, Long thinkingMax) {
    TokenUsageBounds(Long promptMax, Long candidateMax) {
        this(promptMax, candidateMax, null, null);
    }
}
