package com.ses.service.skillsheet;

import com.ses.dto.bpavailability.ParsedBpAvailabilityDto;

/**
 * 要員空き状況メール解析サービス
 */
public interface BpAvailabilityParseService {
    ParsedBpAvailabilityDto parse(String extractedText);
}
