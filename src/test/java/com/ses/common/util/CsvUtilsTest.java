package com.ses.common.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CSVユーティリティの単体テスト（P8 Task4）。
 * エスケープとパースの往復、引用フィールド内のカンマ・改行・エスケープ済みクォート、
 * BOM除去を検証する。
 */
class CsvUtilsTest {

    @Test
    void escape_特殊文字を含む場合のみ引用符で囲む() {
        assertEquals("abc", CsvUtils.escape("abc"));
        assertEquals("\"a,b\"", CsvUtils.escape("a,b"));
        assertEquals("\"a\"\"b\"", CsvUtils.escape("a\"b"));
        assertEquals("\"a\nb\"", CsvUtils.escape("a\nb"));
        assertEquals("", CsvUtils.escape(null));
    }

    @Test
    void appendLine_フィールドをCRLF区切りで出力する() {
        StringBuilder sb = new StringBuilder();
        CsvUtils.appendLine(sb, "山田", "太郎", "");
        assertEquals("山田,太郎,\r\n", sb.toString());
    }

    @Test
    void parse_引用フィールド内のカンマと改行とエスケープクォートを復元する() throws IOException {
        String csv = "氏名,備考\r\n"
                + "山田,\"東京, 大阪\"\r\n"
                + "田中,\"1行目\n2行目\"\r\n"
                + "佐藤,\"引用\"\"符\"\"あり\"\r\n";
        List<List<String>> rows = parse(csv);

        assertEquals(4, rows.size());
        assertEquals(List.of("氏名", "備考"), rows.get(0));
        assertEquals("東京, 大阪", rows.get(1).get(1));
        assertEquals("1行目\n2行目", rows.get(2).get(1));
        assertEquals("引用\"符\"あり", rows.get(3).get(1));
    }

    @Test
    void parse_BOMを除去し末尾改行なしでも最終行を確定する() throws IOException {
        String csv = CsvUtils.UTF8_BOM + "a,b\r\nc,d";
        List<List<String>> rows = parse(csv);
        assertEquals(2, rows.size());
        assertEquals(List.of("a", "b"), rows.get(0));
        assertEquals(List.of("c", "d"), rows.get(1));
    }

    @Test
    void 往復_appendLineで書いたものをparseで復元できる() throws IOException {
        StringBuilder sb = new StringBuilder();
        CsvUtils.appendLine(sb, "a,b", "c\"d", "e\nf");
        List<List<String>> rows = parse(sb.toString());
        assertEquals(1, rows.size());
        assertEquals(List.of("a,b", "c\"d", "e\nf"), rows.get(0));
    }

    private List<List<String>> parse(String content) throws IOException {
        try (InputStream in = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {
            return CsvUtils.parse(in);
        }
    }
}
