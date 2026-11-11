package com.ses.service.ai.impl;

/** transport内部の実HTTP応答だけを受けるsealed入力境界。attempt bindingや費用権限は表さない。 */
sealed interface TransportBoundResponse
        permits CopilotBoundedHttpTransport.IssuedTransportBoundResponse {

    CopilotGeminiResponseParser.BoundParsedResponse parse(
            CopilotGeminiResponseParser parser, long deadlineNanos, String expectedQueryId);
}
