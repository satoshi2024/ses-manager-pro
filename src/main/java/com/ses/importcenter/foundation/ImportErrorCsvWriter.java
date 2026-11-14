package com.ses.importcenter.foundation;

import com.ses.common.util.CsvUtils;
import java.io.IOException;
import java.io.Writer;

/**
 * row番号/hash/findingのみをformula injection-safe CSVへ書く。source PIIを出力しない。
 *
 * <p>無害化の規則：先頭文字そのものがTAB・CRの値と、前置の空白・制御文字を飛ばした先頭が
 * {@code =}・{@code +}・{@code -}・{@code @}の値は、全体の前に{@code '}を付ける。{@code -}で始まっても値全体が
 * 十進数として解釈できるもの（通常の負数）は変更しない（R2.6）。
 */
public final class ImportErrorCsvWriter {
    private ImportErrorCsvWriter() { }
    public static void writeHeader(Writer writer) throws IOException {
        append(writer, "sourceRowNumber", "sourceRowHash", "code", "message");
    }
    public static void write(Writer writer, ImportFinding finding) throws IOException {
        append(writer, Long.toString(finding.sourceRowNumber()), finding.sourceRowHash(), finding.code(), finding.message());
    }

    /** 保存済み検証行から作るerror export（A1a）のheader。finding codeだけを出し、原文・本文は出さない。 */
    public static void writeRowCodeHeader(Writer writer) throws IOException {
        append(writer, "sourceRowNumber", "sourceRowHash", "code");
    }

    /** 保存済み検証行の1 finding code分を1行で書く。 */
    public static void writeRowCode(Writer writer, long sourceRowNumber, String sourceRowHash, String code)
            throws IOException {
        append(writer, Long.toString(sourceRowNumber), sourceRowHash, code);
    }

    private static void append(Writer writer, String... values) throws IOException {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) writer.write(',');
            writer.write(CsvUtils.escape(sanitize(values[i])));
        }
        writer.write("\r\n");
    }

    /** CSV injection対策の無害化。error export以外の取込CSV出力も同じ規則を使えるよう公開する。 */
    public static String sanitize(String value) {
        if (value == null || value.isEmpty()) return value;
        char leading = value.charAt(0);
        if (leading == '\t' || leading == '\r') return "'" + value;
        int index = 0;
        while (index < value.length() && (Character.isWhitespace(value.charAt(index)) || Character.isISOControl(value.charAt(index)))) index++;
        if (index == value.length()) return value;
        char first = value.charAt(index);
        if (first == '=' || first == '+' || first == '@' || first == '-') {
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
