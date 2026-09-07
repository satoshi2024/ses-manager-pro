package com.ses.service.ai.copilot.catalog;

import java.util.List;
import java.util.Set;

/**
 * 人手レビュー済みsemantic catalogの1エントリ。実行時はこの定義だけが許可される。
 */
public record SemanticCatalogEntry(
        String queryId,
        String catalogVersion,
        String resultSchemaVersion,
        Set<String> allowedRoles,
        boolean enabled,
        int resultLimit,
        List<String> citationKeys
) {
}
