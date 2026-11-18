package com.ses.importcenter.foundation;

/** parserで固定適用する上限。外部設定による無制限化は提供しない。 */
public record ImportLimits(long maxSourceBytes, int maxRows, int maxColumns, int maxFieldChars) {
    public static final ImportLimits DEFAULT = new ImportLimits(64L * 1024 * 1024, 100_000, 64, 65_536);

    public ImportLimits {
        if (maxSourceBytes < 1 || maxRows < 1 || maxColumns < 1 || maxFieldChars < 1) {
            throw new IllegalArgumentException("parser上限は正数で指定してください");
        }
    }
}
