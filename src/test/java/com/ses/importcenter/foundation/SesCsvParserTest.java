package com.ses.importcenter.foundation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SesCsvParserTest {
    private final SesCsvParser parser = new SesCsvParser();

    @Test
    void parsesUtf8BomQuotedCommaNewlineEscapedQuoteAndCrlfAndHashesOriginalBytes() throws Exception {
        byte[] source = SesImportV1FixtureFactory.utf8Bom(SesImportV1FixtureFactory.customerCsv(
                "C-SYN-1,\"架空,会社\",\"一行目\r\n二行目 \"\"引用\"\"\"\r\n\r\n"));
        List<SourceRow> rows = new ArrayList<>();
        var summary = parser.parse(new ByteArrayInputStream(source), "synthetic.csv", ImportEntity.CUSTOMER,
                ImportEncoding.UTF8, rows::add);
        assertEquals(2, summary.physicalRows());
        assertEquals(1, summary.sourceRows());
        assertEquals(1, summary.emptySkipped());
        assertTrue(summary.utf8Bom());
        assertEquals("架空,会社", rows.get(0).values().get("companyName"));
        assertEquals("一行目\r\n二行目 \"引用\"", rows.get(0).values().get("remarks"));
        assertEquals(1, rows.get(0).sourceRowNumber());
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)), summary.sourceSha256());
    }

    @Test
    void skipsBlankAndAllEmptyMulticolumnRecordsWithoutChangingQuotedNewlineOrTrailingNewlineSemantics() throws Exception {
        String csv = "externalKey,companyName,remarks\r\n"
                + "\r\n"
                + ",,\r\n"
                + "C-SYN-1,架空会社,\"一行目\r\n二行目\"\r\n";
        List<SourceRow> rows = new ArrayList<>();
        var summary = parser.parse(new ByteArrayInputStream(SesImportV1FixtureFactory.utf8(csv)), "synthetic.csv",
                ImportEntity.CUSTOMER, ImportEncoding.UTF8, rows::add);
        assertEquals(3, summary.physicalRows());
        assertEquals(1, summary.sourceRows());
        assertEquals(2, summary.emptySkipped());
        assertEquals(1, rows.size());
        assertEquals(3, rows.get(0).sourceRowNumber());
        assertEquals("一行目\r\n二行目", rows.get(0).values().get("remarks"));

        List<SourceRow> trailingNewlineRows = new ArrayList<>();
        var trailing = parser.parse(new ByteArrayInputStream(SesImportV1FixtureFactory.utf8(
                        "externalKey,companyName,remarks\nC-SYN-2,架空会社,備考\n")), "trailing.csv",
                ImportEntity.CUSTOMER, ImportEncoding.UTF8, trailingNewlineRows::add);
        assertEquals(1, trailing.physicalRows());
        assertEquals(0, trailing.emptySkipped());
        assertEquals(1, trailingNewlineRows.size());
    }

    @Test
    void decodesShiftJisOnlyWhenExplicitlySelected() throws Exception {
        byte[] source = SesImportV1FixtureFactory.shiftJis(SesImportV1FixtureFactory.customerCsv("C-SYN-2,架空顧客,備考\r\n"));
        List<SourceRow> rows = new ArrayList<>();
        var shiftJisSummary = parser.parse(new ByteArrayInputStream(source), "synthetic.csv", ImportEntity.CUSTOMER, ImportEncoding.SHIFT_JIS, rows::add);
        assertEquals("架空顧客", rows.get(0).values().get("companyName"));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parser.parse(new ByteArrayInputStream(source),
                "synthetic.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, ignored -> { }));
        List<SourceRow> utf8Rows = new ArrayList<>();
        var utf8Summary = parser.parse(new ByteArrayInputStream(SesImportV1FixtureFactory.utf8(
                        SesImportV1FixtureFactory.customerCsv("C-SYN-2,架空顧客,備考\r\n"))),
                "synthetic.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, utf8Rows::add);
        assertEquals(rows.get(0).sourceRowHash(), utf8Rows.get(0).sourceRowHash());
        assertNotEquals(shiftJisSummary.sourceSha256(), utf8Summary.sourceSha256());
        byte[] truncatedShiftJis = java.util.Arrays.copyOf(source, source.length + 1);
        truncatedShiftJis[truncatedShiftJis.length - 1] = (byte) 0x81;
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parser.parse(new ByteArrayInputStream(truncatedShiftJis),
                "synthetic.csv", ImportEntity.CUSTOMER, ImportEncoding.SHIFT_JIS, ignored -> { }));
    }

    @Test
    void rejectsXlsxWrongBomDuplicateUnknownHeaderAndMalformedQuotes() {
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parse("x.xlsx", "", ImportEncoding.UTF8));
        byte[] bom = SesImportV1FixtureFactory.utf8Bom("externalKey,companyName\nC,架空\n");
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parser.parse(new ByteArrayInputStream(bom),
                "x.csv", ImportEntity.CUSTOMER, ImportEncoding.SHIFT_JIS, ignored -> { }));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parse("x.csv", "externalKey,companyName,companyName\n", ImportEncoding.UTF8));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parse("x.csv", "externalKey,companyName,unknown\n", ImportEncoding.UTF8));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parse("x.csv", "externalKey,companyName\nC,\"broken\n", ImportEncoding.UTF8));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parse("x.csv", "externalKey,companyName\nC,\"closed\"x\n", ImportEncoding.UTF8));
    }

    @Test
    void explicitMappingAcceptsAliasesWhileTheDefaultF2EntryPointStillRejectsThem() throws Exception {
        byte[] source = "顧客ID,会社名\nC-SYN-1,架空会社\n".getBytes(StandardCharsets.UTF_8);
        Map<String, String> mapping = Map.of("顧客ID", "externalKey", "会社名", "companyName");

        assertThrows(SesCsvParser.CsvFormatException.class, () -> parser.parse(new ByteArrayInputStream(source),
                "mapped.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, ignored -> { }));
        List<SourceRow> rows = new ArrayList<>();
        var summary = parser.parseMapped(new ByteArrayInputStream(source), "mapped.csv", ImportEntity.CUSTOMER,
                ImportEncoding.UTF8, mapping, rows::add);

        assertEquals("C-SYN-1", rows.get(0).values().get("externalKey"));
        assertEquals("架空会社", rows.get(0).values().get("companyName"));
        assertEquals(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source)), summary.sourceSha256());
    }

    @Test
    void rejectsMalformedUtf8AndConfiguredLimits() {
        byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28};
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parser.parse(new ByteArrayInputStream(invalidUtf8),
                "x.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, ignored -> { }));
        SesCsvParser small = new SesCsvParser(new ImportLimits(100, 1, 3, 32));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> small.parse(new ByteArrayInputStream(
                SesImportV1FixtureFactory.utf8("externalKey,companyName\nC,架空\nD,別架空\n")),
                "x.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, ignored -> { }));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> small.parse(new ByteArrayInputStream(
                SesImportV1FixtureFactory.utf8("externalKey,companyName\nC," + "x".repeat(33) + "\n")),
                "x.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, ignored -> { }));
        SesCsvParser byteLimited = new SesCsvParser(new ImportLimits(24, 10, 4, 32));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> byteLimited.parse(new ByteArrayInputStream(
                SesImportV1FixtureFactory.utf8("externalKey,companyName\nC," + "x".repeat(20) + "\n")),
                "x.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, ignored -> { }));
        SesCsvParser columnLimited = new SesCsvParser(new ImportLimits(100, 10, 1, 32));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> columnLimited.parse(new ByteArrayInputStream(
                SesImportV1FixtureFactory.utf8("externalKey,companyName\n")),
                "x.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, ignored -> { }));
        assertThrows(SesCsvParser.CsvFormatException.class, () -> parser.parse(new ByteArrayInputStream(new byte[]{'P', 'K', 3, 4}),
                "archive.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, ignored -> { }));
    }

    @Test
    void parsesTenThousandRowsWithoutRetainingThemInTheParser() throws Exception {
        StringBuilder csv = new StringBuilder("externalKey,companyName\n");
        for (int i = 0; i < 10_000; i++) csv.append("C-SYN-").append(i).append(",架空会社").append(i).append('\n');
        long[] count = {0};
        var result = parser.parse(new ByteArrayInputStream(SesImportV1FixtureFactory.utf8(csv.toString())),
                "generated.csv", ImportEntity.CUSTOMER, ImportEncoding.UTF8, row -> count[0]++);
        assertEquals(10_000, count[0]);
        assertEquals(10_000, result.sourceRows());
    }

    private SesCsvParser.ParseSummary parse(String name, String text, ImportEncoding encoding) throws Exception {
        return parser.parse(new ByteArrayInputStream(text.getBytes(encoding.charset())), name,
                ImportEntity.CUSTOMER, encoding, ignored -> { });
    }
}
