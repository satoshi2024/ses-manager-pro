package com.ses.crm;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** T051 L1: CRM画面のモバイル横スクロールと失敗時ロールバック契約を確認する。 */
class CrmUiRegressionTest {
    @Test
    void leadAndOpportunityScreensContainResponsiveAndRollbackContracts() throws Exception {
        String leads = read("templates/crm/leads.html");
        String opportunities = read("templates/crm/opportunities.html");
        String js = read("static/js/modules/crm-opportunities.js");
        String leadJs = read("static/js/modules/crm-leads.js");
        String kpi = read("templates/crm/kpi.html");
        String kpiJs = read("static/js/modules/crm-kpi.js");
        assertTrue(leads.contains("viewport") || read("templates/layout/base.html").contains("viewport"));
        assertTrue(opportunities.contains("overflow-x:auto"));
        assertTrue(js.contains("rollbackOpportunityCard") && js.contains("dataTransfer"));
        assertTrue(leadJs.contains("自動統合は行いません"));
        assertTrue(kpi.contains("kpi-opportunity-forecast") && kpi.contains("kpi-stage-body"));
        assertTrue(kpiJs.contains("/api/crm/opportunities/kpi") && kpiJs.contains("proposalAmount"));
        assertTrue(leads.contains("lead-owner") && opportunities.contains("opportunity-owner"));
        assertTrue(read("templates/customer/detail.html").contains("act-contact") && read("templates/customer/detail.html").contains("contact-roles"));
        assertTrue(kpi.contains("crm.kpi.owner") && kpiJs.contains("/crm/opportunities?stage="));
    }

    private String read(String path) throws Exception {
        return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
    }
}
