package com.ses.privacy.production.provider;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * provider が読む1表と、読む列の allow-list（P3-8）。
 *
 * <p>SELECT 句は {@link PrivacyReadOnlyQuery} がこの列だけから組み立てるため、{@code SELECT *} や allow-list 外の列は
 * 返らない。{@code id} は行の識別に必ず含める。{@code storedNameColumns} は保存名（実体 file の名前）を持つ列で、
 * allow-list の部分集合でなければならない。WHERE 句の絞り込み（tenant・主キー・本人への link 列）は返す列ではない。
 *
 * @param table             表名
 * @param columns           返す列（順序どおりに SELECT する）
 * @param storedNameColumns 保存名を持つ列
 */
public record ProviderTable(String table, List<String> columns, List<String> storedNameColumns) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    public ProviderTable {
        Objects.requireNonNull(table, "表名が必要です");
        columns = List.copyOf(Objects.requireNonNull(columns, "列のallow-listが必要です"));
        storedNameColumns = List.copyOf(Objects.requireNonNull(storedNameColumns, "保存名の列一覧が必要です"));
        if (!IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("表名が不正です: " + table);
        }
        if (columns.isEmpty() || !columns.contains("id")) {
            throw new IllegalArgumentException("列のallow-listは id を含む必要があります: " + table);
        }
        Set<String> seen = new HashSet<>();
        for (String column : columns) {
            if (column == null || !IDENTIFIER.matcher(column).matches()) {
                throw new IllegalArgumentException("列名が不正です（* や式は使えません）: " + table + "." + column);
            }
            if (!seen.add(column)) {
                throw new IllegalArgumentException("列が重複しています: " + table + "." + column);
            }
        }
        if (!columns.containsAll(storedNameColumns)) {
            throw new IllegalArgumentException("保存名の列は allow-list に含める必要があります: " + table);
        }
    }

    /** 論理削除列を返すか（論理削除済みの行も返すため、行ごとに状態を示す）。 */
    public boolean hasDeletedFlag() {
        return columns.contains("deleted_flag");
    }
}
