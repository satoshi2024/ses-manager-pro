package com.ses.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注文画面の法人bindingとアクセシブルネームを静的契約として固定する。 */
class SalesOrderUiContractTest {

    @Test
    void legalEntity候補は専用autocompleteを使い組織unitIdを誤送信しない() throws Exception {
        String js = resource("static/js/modules/sales-order.js");
        assertTrue(js.contains("/api/autocomplete/legal-entities"));
        assertTrue(js.indexOf("/api/autocomplete/legal-entities")
                        != js.lastIndexOf("/api/autocomplete/legal-entities"),
                "見積プリセットと通常作成/編集の双方で法人候補をロードすること");
        assertFalse(js.contains("/api/organization-units/options"));
        assertTrue(js.contains("documents/${d.sourceDocumentId}/download"));
        assertTrue(js.contains("documents/${d.acknowledgementDocumentId}/download"));
        assertTrue(js.contains("/api/sales-orders/${id}/contract-drafts"),
                "契約化ボタンはSalesOrderApiControllerの確定routeを呼ぶこと");
        assertFalse(js.contains("/api/sales-orders/${id}/contracts"));
    }

    @Test
    void detailModalと動的明細とpaginationにaccessibleNameがある() throws Exception {
        String html = resource("templates/sales-order/list.html");
        String js = resource("static/js/modules/sales-order.js");
        assertTrue(html.contains("aria-labelledby=\"salesOrderDetailModalTitle\""));
        assertTrue(html.contains("id=\"salesOrderDetailModalTitle\""));
        assertTrue(html.contains("class=\"btn-close\" data-bs-dismiss=\"modal\" aria-label=\"閉じる\""));
        assertTrue(js.contains("aria-label=\"${label('salesOrder.modal.line.engineer')}\""));
        assertTrue(js.contains("common.page.prev"));
        assertTrue(js.contains("common.page.next"));
        assertTrue(js.contains("aria-hidden=\"true\""));
    }

    @Test
    void 検収書downloadは副作用のない確定GETrouteを使う() throws Exception {
        String js = resource("static/js/modules/acceptance.js");
        assertTrue(js.contains("/document/download"));
        assertFalse(js.contains("href=\"/api/acceptances/${r.id}/document\""));
    }

    private String resource(String path) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException(path + " が見つかりません");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
