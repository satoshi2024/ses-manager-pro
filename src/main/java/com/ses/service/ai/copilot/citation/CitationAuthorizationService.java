package com.ses.service.ai.copilot.citation;

import com.ses.dto.ai.ResolvedCitationDto;
import com.ses.service.ai.copilot.CopilotExecutionContext;
import com.ses.service.ai.copilot.parameter.CopilotQueryParameters;
import com.ses.service.ai.copilot.scope.CopilotScopeContext;

import java.util.List;

/** catalog citation keyを現行sessionで再認可し、安全なrouteのみ返す。 */
public interface CitationAuthorizationService {

    ResolvedCitationDto authorize(String citationKey);

    List<ResolvedCitationDto> authorizeAll(List<String> citationKeys);

    ResolvedCitationDto authorize(String citationKey, CopilotExecutionContext context,
                                  CopilotQueryParameters typedParameters,
                                  CopilotScopeContext resolvedScope, String scopeHash);

    List<ResolvedCitationDto> authorizeAll(List<String> citationKeys, CopilotExecutionContext context,
                                           CopilotQueryParameters typedParameters,
                                           CopilotScopeContext resolvedScope, String scopeHash);
}
