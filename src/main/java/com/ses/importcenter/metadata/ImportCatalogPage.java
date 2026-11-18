package com.ses.importcenter.metadata;

import java.util.List;

/** source・mapping一覧の1page。{@code size}は{@code PageUtils.safePage}で上限を適用した値。 */
public record ImportCatalogPage<T>(long page, long size, long total, List<T> items) {
    public ImportCatalogPage {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
