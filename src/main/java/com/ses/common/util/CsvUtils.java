package com.ses.common.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV入出力ユーティリティ（RFC4180 最小実装）。
 * カンマ・ダブルクォート・改行を含むフィールドをエスケープ/復元する。
 */
public final class CsvUtils {

    /** Excelで文字化けしないためのUTF-8 BOM */
    public static final String UTF8_BOM = "﻿";

    private CsvUtils() {
    }

    /**
     * 1行分のフィールドをエスケープしてCSV行（末尾CRLF付き）としてappendする。
     * エクスポート専用の経路のため、Excelでの数式実行（CSVインジェクション）対策として
     * 危険な先頭文字を持つフィールドはシングルクォートを前置して無害化する。
     */
    public static void appendLine(StringBuilder sb, String... fields) {
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(sanitizeForSpreadsheet(fields[i])));
        }
        sb.append("\r\n");
    }

    /**
     * Excel等の表計算ソフトが数式として解釈しうる先頭文字（= + - @ タブ CR）を持つ場合、
     * シングルクォートを前置して文字列として扱わせる（CWE-1236対策）。
     */
    static String sanitizeForSpreadsheet(String field) {
        if (field == null || field.isEmpty()) {
            return field;
        }
        char c = field.charAt(0);
        if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
            return "'" + field;
        }
        return field;
    }

    /**
     * フィールドをRFC4180に従いエスケープする。
     * カンマ・改行・ダブルクォートを含む場合はダブルクォートで囲み、内部の " は "" にする。
     */
    public static String escape(String field) {
        if (field == null) {
            return "";
        }
        boolean needsQuote = field.contains(",") || field.contains("\"")
                || field.contains("\n") || field.contains("\r");
        if (!needsQuote) {
            return field;
        }
        return "\"" + field.replace("\"", "\"\"") + "\"";
    }

    /**
     * CSVをパースして行ごとのフィールド配列リストを返す。
     * 先頭のUTF-8 BOMは除去する。引用フィールド内のカンマ・改行・エスケープ済みクォートに対応。
     */
    public static List<List<String>> parse(InputStream in) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder all = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) {
                all.append(buf, 0, n);
            }
            String content = all.toString();
            if (content.startsWith(UTF8_BOM)) {
                content = content.substring(1);
            }
            parseContent(content, rows);
        }
        return rows;
    }

    private static void parseContent(String content, List<List<String>> rows) {
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        int len = content.length();
        boolean fieldStarted = false;

        while (i < len) {
            char c = content.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < len && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    inQuotes = false;
                    i++;
                } else {
                    field.append(c);
                    i++;
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                    fieldStarted = true;
                    i++;
                } else if (c == ',') {
                    current.add(field.toString());
                    field.setLength(0);
                    fieldStarted = true;
                    i++;
                } else if (c == '\r') {
                    // CRLF or CR を行終端として扱う
                    current.add(field.toString());
                    field.setLength(0);
                    rows.add(current);
                    current = new ArrayList<>();
                    fieldStarted = false;
                    i++;
                    if (i < len && content.charAt(i) == '\n') {
                        i++;
                    }
                } else if (c == '\n') {
                    current.add(field.toString());
                    field.setLength(0);
                    rows.add(current);
                    current = new ArrayList<>();
                    fieldStarted = false;
                    i++;
                } else {
                    field.append(c);
                    fieldStarted = true;
                    i++;
                }
            }
        }
        // 最終フィールド/行の確定（末尾に改行が無い場合）
        if (fieldStarted || field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
    }
}

