package com.ses.service.integrationhub;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 公開ステータスマッパーの全網羅テスト。
 * 全現行内部状態のパラメータ化テスト、未知値・null・空白・超長状態のUNKNOWNフェイルクローズ、
 * 出力が大文字ASCIIコードであり日本語業務ステータスを含まないことを検証する。
 */
class ExternalApiStatusMapperTest {

    private static final Pattern ASCII_CODE_PATTERN = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern JAPANESE_PATTERN = Pattern.compile("[\\u3000-\\u303f\\u3040-\\u309f\\u30a0-\\u30ff\\uff00-\\uff9f\\u4e00-\\u9faf]");

    // ========================================================================
    // Project Status Tests
    // ========================================================================

    @ParameterizedTest(name = "案件現行内部状態マッピング: [{0}] -> [{1}]")
    @CsvSource({
            "募集中, OPEN",
            "選考中, SELECTING",
            "充足, FILLED",
            "クローズ, CLOSED",
            "OPEN, OPEN",
            "SELECTING, SELECTING",
            "FILLED, FILLED",
            "CLOSED, CLOSED"
    })
    @DisplayName("案件の全現行内部状態およびASCIIコードが正しく大文字ASCIIへ変換されること")
    void projectStatus_mapsCurrentInternalStatusesToAsciiCode(String internal, String expected) {
        String result = ExternalApiProjectStatusMapper.toExternalStatus(internal);
        assertEquals(expected, result);
        assertValidAsciiCode(result);
    }

    @ParameterizedTest(name = "案件未知・空値テスト: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n", "UNKNOWN_STATUS", "無効なステータス", "保留", "進行中"})
    @DisplayName("案件ステータスの未知値・null・空白はUNKNOWNへフェイルクローズすること")
    void projectStatus_unknownAndBlankFailClosedToUnknown(String invalid) {
        String result = ExternalApiProjectStatusMapper.toExternalStatus(invalid);
        assertEquals(ExternalApiProjectStatusMapper.UNKNOWN, result);
        assertValidAsciiCode(result);
    }

    @Test
    @DisplayName("案件ステータスの64文字超過入力はUNKNOWNへフェイルクローズすること")
    void projectStatus_overlongStatusFailsClosedToUnknown() {
        String overlong = "A".repeat(65);
        String result = ExternalApiProjectStatusMapper.toExternalStatus(overlong);
        assertEquals(ExternalApiProjectStatusMapper.UNKNOWN, result);
    }

    // ========================================================================
    // Contract Status Tests
    // ========================================================================

    @ParameterizedTest(name = "契約現行内部状態マッピング: [{0}] -> [{1}]")
    @CsvSource({
            "準備中, DRAFT",
            "稼動中, ACTIVE",
            "終了, COMPLETED",
            "解約, CANCELLED",
            "DRAFT, DRAFT",
            "PREPARING, DRAFT",
            "ACTIVE, ACTIVE",
            "COMPLETED, COMPLETED",
            "ENDED, COMPLETED",
            "CANCELLED, CANCELLED",
            "TERMINATED, CANCELLED"
    })
    @DisplayName("契約の全現行内部状態およびASCIIコードが正しく大文字ASCIIへ変換されること")
    void contractStatus_mapsCurrentInternalStatusesToAsciiCode(String internal, String expected) {
        String result = ExternalApiContractStatusMapper.toExternalStatus(internal);
        assertEquals(expected, result);
        assertValidAsciiCode(result);
    }

    @ParameterizedTest(name = "契約未知・空値テスト: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n", "UNKNOWN_CONTRACT", "無効な契約状態", "締結済", "更新中"})
    @DisplayName("契約ステータスの未知値・null・空白はUNKNOWNへフェイルクローズすること")
    void contractStatus_unknownAndBlankFailClosedToUnknown(String invalid) {
        String result = ExternalApiContractStatusMapper.toExternalStatus(invalid);
        assertEquals(ExternalApiContractStatusMapper.UNKNOWN, result);
        assertValidAsciiCode(result);
    }

    @Test
    @DisplayName("契約ステータスの64文字超過入力はUNKNOWNへフェイルクローズすること")
    void contractStatus_overlongStatusFailsClosedToUnknown() {
        String overlong = "B".repeat(65);
        String result = ExternalApiContractStatusMapper.toExternalStatus(overlong);
        assertEquals(ExternalApiContractStatusMapper.UNKNOWN, result);
    }

    // ========================================================================
    // Invoice Status Tests
    // ========================================================================

    @ParameterizedTest(name = "請求現行内部状態マッピング: [{0}] -> [{1}]")
    @CsvSource({
            "未送付, UNSENT",
            "送付済, SENT",
            "一部入金, PARTIALLY_PAID",
            "入金済, PAID",
            "UNSENT, UNSENT",
            "DRAFT, UNSENT",
            "SENT, SENT",
            "PARTIALLY_PAID, PARTIALLY_PAID",
            "PARTIAL, PARTIALLY_PAID",
            "PAID, PAID"
    })
    @DisplayName("請求の全現行内部状態およびASCIIコードが正しく大文字ASCIIへ変換されること")
    void invoiceStatus_mapsCurrentInternalStatusesToAsciiCode(String internal, String expected) {
        String result = ExternalApiInvoiceStatusMapper.toExternalStatus(internal);
        assertEquals(expected, result);
        assertValidAsciiCode(result);
    }

    @ParameterizedTest(name = "請求未知・空値テスト: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n", "UNKNOWN_INVOICE", "無効な請求状態", "作成済", "督促中"})
    @DisplayName("請求ステータスの未知値・null・空白はUNKNOWNへフェイルクローズすること")
    void invoiceStatus_unknownAndBlankFailClosedToUnknown(String invalid) {
        String result = ExternalApiInvoiceStatusMapper.toExternalStatus(invalid);
        assertEquals(ExternalApiInvoiceStatusMapper.UNKNOWN, result);
        assertValidAsciiCode(result);
    }

    @Test
    @DisplayName("請求ステータスの64文字超過入力はUNKNOWNへフェイルクローズすること")
    void invoiceStatus_overlongStatusFailsClosedToUnknown() {
        String overlong = "C".repeat(65);
        String result = ExternalApiInvoiceStatusMapper.toExternalStatus(overlong);
        assertEquals(ExternalApiInvoiceStatusMapper.UNKNOWN, result);
    }

    // ========================================================================
    // renewalStatus Tests
    // ========================================================================

    @ParameterizedTest(name = "更新判断マッピング: [{0}] -> [{1}]")
    @CsvSource({
            "CONTINUE, CONTINUE",
            "継続, CONTINUE",
            "継続確定, CONTINUE",
            "RENEW, CONTINUE",
            "END, END",
            "終了, END",
            "更新不要, END",
            "終了予定, END",
            "END_SCHEDULED, END"
    })
    @DisplayName("契約更新判断の現行値が正しく大文字ASCIIコードへ変換されること")
    void renewalStatus_mapsInternalStatusesToAsciiCode(String internal, String expected) {
        String result = ExternalApiRenewalStatusMapper.toExternalStatus(internal);
        assertEquals(expected, result);
        assertValidAsciiCode(result);
    }

    @ParameterizedTest(name = "更新判断未設定テスト: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n"})
    @DisplayName("契約更新判断のnull・空文字・空白はnullを返すこと")
    void renewalStatus_nullAndBlankReturnNull(String blank) {
        assertNull(ExternalApiRenewalStatusMapper.toExternalStatus(blank));
    }

    @ParameterizedTest(name = "更新判断未知値テスト: [{0}]")
    @ValueSource(strings = {"UNKNOWN_DECISION", "未定", "保留中"})
    @DisplayName("契約更新判断の未知値はUNKNOWNへフェイルクローズすること")
    void renewalStatus_unknownFailsClosedToUnknown(String unknown) {
        String result = ExternalApiRenewalStatusMapper.toExternalStatus(unknown);
        assertEquals(ExternalApiRenewalStatusMapper.UNKNOWN, result);
        assertValidAsciiCode(result);
    }

    @Test
    @DisplayName("契約更新判断の64文字超過入力はUNKNOWNへフェイルクローズすること")
    void renewalStatus_overlongStatusFailsClosedToUnknown() {
        String overlong = "D".repeat(65);
        String result = ExternalApiRenewalStatusMapper.toExternalStatus(overlong);
        assertEquals(ExternalApiRenewalStatusMapper.UNKNOWN, result);
    }

    // ========================================================================
    // settlementStatus Tests
    // ========================================================================

    @Test
    @DisplayName("入金日が存在する場合は請求ステータスに関わらずSETTLEDとなること")
    void settlementStatus_settledWhenPaidDatePresent() {
        LocalDate paidDate = LocalDate.of(2026, 9, 1);
        assertEquals("SETTLED", ExternalApiSettlementStatusMapper.toExternalStatus("未送付", paidDate));
        assertEquals("SETTLED", ExternalApiSettlementStatusMapper.toExternalStatus("一部入金", paidDate));
        assertEquals("SETTLED", ExternalApiSettlementStatusMapper.toExternalStatus("入金済", paidDate));
        assertEquals("SETTLED", ExternalApiSettlementStatusMapper.toExternalStatus(null, paidDate));
    }

    @ParameterizedTest(name = "入金日なし決済状態マッピング: [{0}] -> [{1}]")
    @CsvSource({
            "入金済, SETTLED",
            "PAID, SETTLED",
            "SETTLED, SETTLED",
            "一部入金, PARTIALLY_SETTLED",
            "PARTIALLY_PAID, PARTIALLY_SETTLED",
            "PARTIAL, PARTIALLY_SETTLED",
            "PARTIALLY_SETTLED, PARTIALLY_SETTLED",
            "未送付, OUTSTANDING",
            "送付済, OUTSTANDING",
            "UNSENT, OUTSTANDING",
            "SENT, OUTSTANDING",
            "OUTSTANDING, OUTSTANDING"
    })
    @DisplayName("入金日がない場合の請求ステータスに基づく決済状態導出テスト")
    void settlementStatus_derivesFromInvoiceStatusWhenNoPaidDate(String invoiceStatus, String expected) {
        String result = ExternalApiSettlementStatusMapper.toExternalStatus(invoiceStatus, null);
        assertEquals(expected, result);
        assertValidAsciiCode(result);
    }

    @ParameterizedTest(name = "決済状態未知・空値テスト: [{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t", "\n", "UNKNOWN_STATUS", "無効なステータス"})
    @DisplayName("入金日なしで請求ステータスが未知・空の場合はUNKNOWNへフェイルクローズすること")
    void settlementStatus_unknownAndBlankFailClosedToUnknown(String invalid) {
        String result = ExternalApiSettlementStatusMapper.toExternalStatus(invalid, null);
        assertEquals(ExternalApiSettlementStatusMapper.UNKNOWN, result);
        assertValidAsciiCode(result);
    }

    @Test
    @DisplayName("決済状態の64文字超過入力はUNKNOWNへフェイルクローズすること")
    void settlementStatus_overlongStatusFailsClosedToUnknown() {
        String overlong = "E".repeat(65);
        String result = ExternalApiSettlementStatusMapper.toExternalStatus(overlong, null);
        assertEquals(ExternalApiSettlementStatusMapper.UNKNOWN, result);
    }

    // ========================================================================
    // Facade Tests
    // ========================================================================

    @Test
    @DisplayName("統合ファサード ExternalApiStatusMapper が正しく各マッパーへ委譲すること")
    void statusMapperFacadeDelegatesCorrectly() {
        assertEquals("OPEN", ExternalApiStatusMapper.toProjectStatus("募集中"));
        assertEquals("ACTIVE", ExternalApiStatusMapper.toContractStatus("稼動中"));
        assertEquals("UNSENT", ExternalApiStatusMapper.toInvoiceStatus("未送付"));
        assertEquals("CONTINUE", ExternalApiStatusMapper.toRenewalStatus("継続"));
        assertEquals("SETTLED", ExternalApiStatusMapper.toSettlementStatus("入金済", null));
        assertEquals("OUTSTANDING", ExternalApiStatusMapper.toSettlementStatus("送付済"));
    }

    private static void assertValidAsciiCode(String code) {
        assertTrue(ASCII_CODE_PATTERN.matcher(code).matches(),
                "コードは大文字ASCIIパターン [A-Z][A-Z0-9_]{0,63} に合致すること: " + code);
        assertFalse(JAPANESE_PATTERN.matcher(code).find(),
                "外部公開コードに日本語文字が含まれていないこと: " + code);
    }
}
