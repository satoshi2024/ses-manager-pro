package com.ses.service.ai.copilot.citation;

import com.ses.dto.ai.ResolvedCitationDto;

import java.util.List;

/** catalog citation keyを現行sessionで再認可し、安全なrouteのみ返す。 */
public interface CitationAuthorizationService {

    ResolvedCitationDto authorize(String citationKey);

    List<ResolvedCitationDto> authorizeAll(List<String> citationKeys);
}
