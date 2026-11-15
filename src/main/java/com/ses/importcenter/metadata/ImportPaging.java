package com.ses.importcenter.metadata;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.common.util.PageUtils;
import com.ses.importcenter.foundation.ImportLimits;

/**
 * 取込センターの手書きLIMIT/OFFSET用のpage正規化（W3-12。W3-05 review P3-3）。
 *
 * <p>{@link PageUtils#safePage} はsizeだけを丸め、page番号に上限を持たない。巨大なpage番号では
 * {@code (page-1)*size} が桁あふれして負のOFFSETになり、SQLが失敗して500になる（previewでは監査を書いた後）。
 * 取込は1 jobあたり最大 {@value #MAX_PAGE} 行なので、page番号もこの値へ丸める（範囲外のpageは空で返る）。
 */
final class ImportPaging {
    /** page番号の上限。1 jobの行数上限（{@link ImportLimits#DEFAULT}）と同じ。 */
    static final long MAX_PAGE = 100_000;

    static {
        if (MAX_PAGE != ImportLimits.DEFAULT.maxRows()) {
            throw new IllegalStateException("取込のpage番号上限は行数上限と同じ値にしてください");
        }
    }

    private ImportPaging() {
    }

    static Page<Object> safePage(long page, long size, long defaultSize, long maxSize) {
        return PageUtils.safePage(Math.min(page, MAX_PAGE), size, defaultSize, maxSize);
    }

    static long offset(Page<Object> normalized) {
        return (normalized.getCurrent() - 1) * normalized.getSize();
    }
}
