package com.ses.dto.integrationhub;

import java.time.Instant;

/** 公開APIのscope適用済みcount。 */
public record ExternalApiCountResponse(long count, Instant asOf) {
}
