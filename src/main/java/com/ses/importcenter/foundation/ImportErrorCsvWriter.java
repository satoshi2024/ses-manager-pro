package com.ses.importcenter.foundation;

import com.ses.common.util.CsvUtils;
import java.io.IOException;
import java.io.Writer;

/** row番号/hash/findingのみをformula injection-safe CSVへ書く。source PIIを出力しない。 */
public final class ImportErrorCsvWriter {
    private ImportErrorCsvWriter() { }
    public static void writeHeader(Writer writer) throws IOException {
        append(writer, "sourceRowNumber", "sourceRowHash", "code", "message");
    }
    public static void write(Writer writer, ImportFinding finding) throws IOException {
        append(writer, Long.toString(finding.sourceRowNumber()), finding.sourceRowHash(), finding.code(), finding.message());
    }
    private static void append(Writer writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write(CsvUtils.escape(sanitize(values[i])));
        }
        writer.write("\r\n");
    }
    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) return value;
        int index = 0;
        while (index < value.length() && (Character.isWhitespace(value.charAt(index)) || Character.isISOControl(value.charAt(index)))) index++;
        if (index == value.length()) return value;
        char first = value.charAt(index);
        if (first == '=' || first == '+' || first == '@' || first == '-' || first == '\t' || first == '\r') {
            if (first == '-' && isNumeric(value)) return value;
            return "'" + value;
        }
        return value;
    }
    private static boolean isNumeric(String value) {
        try { new java.math.BigDecimal(value); return true; }
        catch (NumberFormatException ignored) { return false; }
    }
}
