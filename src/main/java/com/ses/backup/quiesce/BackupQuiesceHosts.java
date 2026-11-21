package com.ses.backup.quiesce;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 接続元の host の解釈（design.md §5.4。D-P3-1）。
 *
 * <ul>
 *   <li>{@code information_schema.PROCESSLIST.HOST} は TCP の接続なら末尾に {@code :<port>} が付く。末尾の {@code :<数字>} だけを
 *       外す（{@code fe80::1:54321} → {@code fe80::1}、{@code [::1]:54321} → {@code ::1}、{@code 10.0.0.5:3306} → {@code 10.0.0.5}）。
 *       port が無い値（{@code localhost} 等）はそのまま。</li>
 *   <li>{@code USER()} の host 部には port が付かないため、何も外さずそのまま使う（{@code fe80::1} を {@code fe80:} と誤って切らない）。</li>
 * </ul>
 */
public final class BackupQuiesceHosts {

    /** {@code observed_app_hosts} の列の長さ。超えたら照合に必ず失敗する値を書く（host を落として照合を通さないため）。 */
    static final int OBSERVED_HOSTS_MAX_LENGTH = 1024;

    private static final Pattern BRACKETED = Pattern.compile("^\\[([^\\]]*)\\](?::\\d+)?$");
    private static final Pattern TRAILING_PORT = Pattern.compile("^(.+):(\\d+)$");

    private BackupQuiesceHosts() {
    }

    /** {@code PROCESSLIST.HOST} から port を外した host。 */
    public static String fromProcesslist(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        Matcher bracketed = BRACKETED.matcher(value);
        if (bracketed.matches()) {
            return bracketed.group(1);
        }
        Matcher port = TRAILING_PORT.matcher(value);
        return port.matches() ? port.group(1) : value;
    }

    /** {@code USER()}（{@code user@host}）の host 部。port は付かないためそのまま返す。{@code @} が無ければ空。 */
    public static String fromUserFunction(String user) {
        if (user == null) {
            return "";
        }
        int at = user.lastIndexOf('@');
        return at < 0 ? "" : user.substring(at + 1).trim();
    }

    /**
     * 観測した接続元を {@code host=件数} にし、host の昇順に {@code ,} でつなぐ。列の長さを超える場合は、どの instance の
     * 接続元とも一致しない {@code OVERFLOW=<host の数>} を返す（provider の照合は失敗する。安全側）。
     */
    public static String aggregate(Collection<String> processlistHosts) {
        Map<String, Integer> counts = new TreeMap<>();
        for (String raw : processlistHosts) {
            if (raw != null) {
                counts.merge(fromProcesslist(raw), 1, Integer::sum);
            }
        }
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (!joined.isEmpty()) {
                joined.append(',');
            }
            joined.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return joined.length() <= OBSERVED_HOSTS_MAX_LENGTH ? joined.toString() : "OVERFLOW=" + counts.size();
    }
}
