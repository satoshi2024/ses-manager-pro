package com.ses.dto.integrationhub;

import java.time.Instant;
import java.util.List;

/** 公開APIの共通cursor response。内部Page/DB offsetを公開しない。 */
public record ExternalApiListResponse<T>(
        List<T> items,
        String nextCursor,
        boolean hasMore,
        Instant asOf) {
}
