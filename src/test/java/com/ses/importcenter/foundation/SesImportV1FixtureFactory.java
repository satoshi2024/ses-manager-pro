package com.ses.importcenter.foundation;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/** テスト専用の非実在値fixture generator。 */
final class SesImportV1FixtureFactory {
    private SesImportV1FixtureFactory() { }
    static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    static byte[] utf8Bom(String text) {
        byte[] body = utf8(text);
        byte[] result = new byte[body.length + 3];
        result[0] = (byte) 0xef; result[1] = (byte) 0xbb; result[2] = (byte) 0xbf;
        System.arraycopy(body, 0, result, 3, body.length);
        return result;
    }
    static byte[] shiftJis(String text) { return text.getBytes(Charset.forName("Shift_JIS")); }
    static String customerCsv(String rows) { return "externalKey,companyName,remarks\r\n" + rows; }
    static String allPositive(ImportEntity entity) {
        return switch (entity) {
            case CUSTOMER -> "externalKey,companyName\nC-SYN-1,架空合同会社\n";
            case ENGINEER -> "externalKey,fullName,employmentType\nE-SYN-1,架空太郎,正社員\n";
            case PROJECT -> "externalKey,customerExternalKey,projectName\nP-SYN-1,C-SYN-1,架空案件\n";
            case PROPOSAL -> "externalKey,engineerExternalKey,projectExternalKey\nPR-SYN-1,E-SYN-1,P-SYN-1\n";
            case CONTRACT -> "externalKey,customerExternalKey,engineerExternalKey,projectExternalKey,startDate,sellingPrice,costPrice\nCT-SYN-1,C-SYN-1,E-SYN-1,P-SYN-1,2026-01-01,100,80\n";
        };
    }
}
