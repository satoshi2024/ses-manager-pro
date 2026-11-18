package com.ses.importcenter.metadata;

import java.util.List;

/** 公開済み検証runのpreview 1page。{@code size}は{@code PageUtils.safePage}で上限を適用した値。 */
public record ImportPreviewPage(long jobId, long runId, long page, long size, long total, String disposition,
                                List<ImportPreviewRow> rows) {
    public ImportPreviewPage {
        rows = rows == null ? List.of() : List.copyOf(rows);
    }
}
