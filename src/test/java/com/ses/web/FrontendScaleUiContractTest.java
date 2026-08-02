package com.ses.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("200名規模フロントエンド契約")
class FrontendScaleUiContractTest {

    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    @Test
    @DisplayName("契約一覧はpage stateと10/20/50件選択を持つ")
    void contractList_hasServerSidePaginationStateAndSizes() throws IOException {
        String js = read("static/js/modules/contract.js");
        String html = read("templates/contract/list.html");

        assertTrue(js.contains("let contractCurrentPage = 1;"));
        assertTrue(js.contains("let contractPageSize = 20;"));
        assertTrue(js.contains("let contractLastPage = 1;"));
        assertTrue(js.contains("current: requestedPage"));
        assertTrue(js.contains("size: contractPageSize"));
        assertTrue(html.contains("id=\"contract-pagination\""));
        assertTrue(html.contains("id=\"contract-page-position\""));
        assertTrue(html.contains("<option value=\"10\">10</option>"));
        assertTrue(html.contains("<option value=\"20\" selected>20</option>"));
        assertTrue(html.contains("<option value=\"50\">50</option>"));
        assertFalse(html.contains("<option value=\"1000\""));
    }

    @Test
    @DisplayName("filterとページサイズ変更は1ページ目へ戻る")
    void contractFilters_resetCurrentPage() throws IOException {
        String js = read("static/js/modules/contract.js").replace("\r\n", "\n");
        assertTrue(js.contains("function applyContractFilters() {\n    loadContracts(1);\n}"));
        assertTrue(js.contains("contractPageSize = Number($(this).val()) || 20;\n        loadContracts(1);"));
    }

    @Test
    @DisplayName("CRUD後はfilterを維持し最終空pageだけ補正する")
    void contractCrud_preservesFilterAndCorrectsOnlyEmptyLastPage() throws IOException {
        String js = read("static/js/modules/contract.js");
        assertTrue(js.contains("records.length === 0 && total > 0 && requestedPage > totalPages"));
        assertTrue(js.contains("loadContracts(totalPages);"));
        assertTrue(occurrences(js, "loadContracts(contractCurrentPage);") >= 3,
                "保存・状態変更・削除後は現在ページを再取得すること");
        assertTrue(js.contains("SES.i18n.t('common.page.info', [total, start, end])"));
    }

    private String read(String relative) throws IOException {
        return Files.readString(RESOURCE_ROOT.resolve(relative));
    }

    private int occurrences(String text, String value) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(value, offset)) >= 0) {
            count++;
            offset += value.length();
        }
        return count;
    }
}
