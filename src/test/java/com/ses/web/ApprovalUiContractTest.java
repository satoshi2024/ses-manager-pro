package com.ses.web;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** A1のdesktop/390px共通契約。実ブラウザ幅はDemoで確認し、markup回帰を自動化する。 */
class ApprovalUiContractTest {
    @Test
    void inboxAndRequestPages_useResponsiveTableAndPageScript() throws Exception {
        for (String page : new String[]{"templates/approval/inbox.html", "templates/approval/requests.html"}) {
            String html = read(page);
            assertThat(html).contains("table-responsive", "approvalTable", "approval.js", "container-fluid");
        }
    }

    @Test
    void detailPage_containsMaskedDiffHistoryAndTargetNavigation() throws Exception {
        String html = read("templates/approval/detail.html");
        assertThat(html).contains("approvalDiffBody", "approvalHistory", "approvalTargetLink",
                "approvalExportLink", "approvalComment", "approval.js");
    }

    @Test
    void targetBusinessPages_useApprovalRequestPathsAndPendingCopy() throws Exception {
        String quotation = read("static/js/modules/quotation.js");
        assertThat(quotation).contains("/api/quotations/${id}/status", "createDraft: !!createDraft",
                "approval.requestSubmitted", "提出を申請", "受注を申請");

        String contract = read("static/js/modules/contract.js");
        String priceRevision = read("static/js/modules/contract-price-revision.js");
        assertThat(contract).contains("/api/contracts/' + id + '/status", "approval.requestSubmitted");
        assertThat(priceRevision).contains("/api/contracts/${contractId}/price-revisions",
                "approval.requestSubmitted");

        String invoice = read("static/js/modules/invoice.js");
        assertThat(invoice).contains("/api/invoices/${id}/status", "/api/invoices/${id}/void",
                "/api/invoices/bp-payments/${id}", "送付を申請", "取消を申請", "支払確定を申請",
                "approval.requestSubmitted");

        String closing = read("static/js/modules/monthly-closing.js");
        String closingPage = read("templates/monthly-closing/list.html");
        assertThat(closing).contains("/api/monthly-closing/confirm", "/api/monthly-closing/reopen",
                "approval.requestSubmitted");
        assertThat(closingPage).contains("締めを申請", "再開を申請");
    }

    private String read(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
