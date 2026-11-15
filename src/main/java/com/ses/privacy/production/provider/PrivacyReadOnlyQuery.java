package com.ses.privacy.production.provider;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * NF07 F2 provider の唯一の DB 接続口（SELECT 専用）。
 *
 * <p>SELECT 句は {@link ProviderTable} の allow-list だけから組み立て、呼出し側は WHERE 句と bind 値だけを渡す。
 * WHERE 句に文の区切り・注釈・書込みや lock の語が含まれれば実行前に拒否する（provider の記述誤りを早期に止める。
 * 値は常に bind 変数で渡す）。本 class は {@code query} 以外の操作を公開しない。
 * DB 側の最小権限（SELECT だけの GRANT）は W-12（DBA）の範囲である。
 */
@Component
public class PrivacyReadOnlyQuery {

    /** WHERE 句で拒否する語（大小文字を区別しない。語の途中は照合しない: {@code updated_at} は通す）。 */
    private static final Pattern FORBIDDEN_IN_WHERE = Pattern.compile(
            "(?i)(;|--|/\\*|#|\\b(insert|update|delete|replace|merge|upsert|drop|alter|create|truncate|rename|grant|"
                    + "revoke|call|exec|execute|handler|load|lock|unlock|into|outfile|dumpfile|sleep|benchmark|share|"
                    + "nowait|skip)\\b)");

    private final NamedParameterJdbcOperations jdbc;

    @Autowired
    public PrivacyReadOnlyQuery(JdbcTemplate jdbcTemplate) {
        this(new NamedParameterJdbcTemplate(Objects.requireNonNull(jdbcTemplate, "JdbcTemplateが必要です")));
    }

    PrivacyReadOnlyQuery(NamedParameterJdbcOperations jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "NamedParameterJdbcOperationsが必要です");
    }

    /**
     * allow-list の列だけを SELECT する。
     *
     * @param table       表と列の allow-list
     * @param whereClause WHERE 句（bind 変数 {@code :name} を使う。値を埋め込まない）
     * @param params      bind 値
     * @return 列名→値（allow-list の順序）。CLOB は文字列、日時は {@code java.time} に変換する
     */
    public List<Map<String, Object>> select(ProviderTable table, String whereClause, Map<String, ?> params) {
        String sql = selectSql(table, whereClause);
        Map<String, Object> bind = new LinkedHashMap<>(Objects.requireNonNull(params, "bind値が必要です"));
        return jdbc.query(sql, bind, (rs, rowNum) -> readRow(rs, table.columns()));
    }

    /** 実行する SELECT 文（検査用に package-private で公開する）。 */
    static String selectSql(ProviderTable table, String whereClause) {
        Objects.requireNonNull(table, "表の指定が必要です");
        if (whereClause == null || whereClause.isBlank()) {
            throw new IllegalArgumentException("WHERE 句が必要です（全件の読取りはしない）: " + table.table());
        }
        if (FORBIDDEN_IN_WHERE.matcher(whereClause).find()) {
            throw new IllegalArgumentException("WHERE 句に使えない語が含まれています: " + table.table());
        }
        return "SELECT " + String.join(", ", table.columns()) + " FROM " + table.table() + " WHERE " + whereClause;
    }

    private static Map<String, Object> readRow(ResultSet rs, List<String> columns) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            row.put(columns.get(i), normalize(rs.getObject(i + 1)));
        }
        return row;
    }

    private static Object normalize(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            long length = clob.length();
            return length == 0 ? "" : clob.getSubString(1, (int) Math.min(length, Integer.MAX_VALUE));
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof byte[]) {
            // allow-list に binary 列は置かない。置かれた場合も値は返さない（fail-closed）
            return null;
        }
        return value;
    }
}
