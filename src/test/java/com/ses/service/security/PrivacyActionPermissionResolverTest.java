package com.ses.service.security;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF07 W-5（production-bridge-phased-plan.md §6.1、Plan Review 注意事項1）の resolver 分岐を固定する。
 *
 * <p>privacy 分岐は query 除去の直後・画面判定と export/download 判定より前にある。後ろへ動くと
 * {@code /privacy/**} が null（素通し）になり、{@code /api/privacy/dsar/1/export} 等が営業・要員の持つ
 * {@code export.execute} / {@code file.download} に解決される。
 */
class PrivacyActionPermissionResolverTest {

    @ParameterizedTest
    @CsvSource({
            "GET,/api/privacy/policies,privacy.policy.view",
            "HEAD,/api/privacy/policies/evaluate,privacy.policy.view",
            "GET,/api/privacy/policies/1,privacy.policy.view",
            "POST,/api/privacy/policies,privacy.policy.manage",
            "PUT,/api/privacy/policies/1,privacy.policy.manage",
            "PATCH,/api/privacy/policies/1,privacy.policy.manage",
            "DELETE,/api/privacy/policies/1,privacy.policy.manage",
            "GET,/api/privacy/catalog,privacy.catalog.view",
            "POST,/api/privacy/catalog,privacy.catalog.manage",
            "GET,/api/privacy/holds,privacy.hold.view",
            "POST,/api/privacy/holds,privacy.hold.manage",
            "PUT,/api/privacy/holds/7,privacy.hold.manage",
            "DELETE,/api/privacy/holds/7,privacy.hold.manage",
            "POST,/api/privacy/holds/7/release,privacy.hold.release",
            "GET,/api/privacy/dsar,privacy.dsar.view",
            "POST,/api/privacy/dsar,privacy.dsar.manage",
            "PATCH,/api/privacy/dsar/3,privacy.dsar.manage",
            "POST,/api/privacy/dsar/3/approve,privacy.dsar.approve",
            "GET,/api/privacy/unknown-sub,privacy.view",
            "POST,/api/privacy/unknown-sub,privacy.create",
            "PUT,/api/privacy/unknown-sub/1,privacy.update",
            "DELETE,/api/privacy/unknown-sub/1,privacy.delete",
            "GET,/api/privacy,privacy.view"
    })
    void privacyのAPIは全6methodでnullでないprivacy_actionへ解決される(String method, String uri, String expected) {
        assertEquals(expected, ActionPermissionResolver.resolve(method, uri));
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/privacy/dsar/1/package/download",
            "POST,/api/privacy/dsar/1/export",
            "GET,/api/privacy/dsar/1/export",
            "DELETE,/api/privacy/dsar/1/export",
            "GET,/api/privacy/dsar/1/package/x.pdf",
            "GET,/api/privacy/dsar/1/package/x.csv",
            "GET,/api/privacy/dsar/1/package/x.xlsx",
            "PUT,/api/privacy/dsar/1/package",
            "GET,/api/privacy/dsar/1/download",
            "GET,/api/privacy/dsar/1/report.pdf",
            "GET,/api/privacy/dsar/export",
            "GET,/api/privacy/dsar/1/export-zip"
    })
    void DSARのpackageとexportはexportとdownloadの判定より先にprivacy_dsar_exportへ解決される(String method, String uri) {
        String action = ActionPermissionResolver.resolve(method, uri);
        assertEquals("privacy.dsar.export", action);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/api/privacy/holds/export",
            "GET,/api/privacy/policies/report.csv",
            "GET,/api/privacy/unknown/download",
            "POST,/api/privacy/catalog/export-x"
    })
    void privacy配下のexportやdownload風の経路も汎用actionへ落ちずprivacy_actionに留まる(String method, String uri) {
        String action = ActionPermissionResolver.resolve(method, uri);
        assertNotNull(action);
        assertTrue(action.startsWith("privacy."), action);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/privacy,privacy.view",
            "GET,/privacy/holds,privacy.view",
            "HEAD,/privacy/dsar/1,privacy.view",
            "POST,/privacy/holds,privacy.create",
            "PUT,/privacy/holds/1,privacy.update",
            "PATCH,/privacy/holds/1,privacy.update",
            "DELETE,/privacy/holds/1,privacy.delete",
            "GET,/privacy/dsar/1/export,privacy.view",
            "GET,/privacy/dsar/1/package/download,privacy.view"
    })
    void privacyの画面は画面判定より先に全6methodでprivacy_actionへ解決される(String method, String uri, String expected) {
        assertEquals(expected, ActionPermissionResolver.resolve(method, uri));
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPTIONS", "TRACE", "CONNECT", "PROPFIND"})
    void privacyの6method以外はnullを返しAPIはfilterが画面はSecurityConfigが拒否する(String method) {
        assertNull(ActionPermissionResolver.resolve(method, "/api/privacy/holds"));
        assertNull(ActionPermissionResolver.resolve(method, "/api/privacy/dsar/1/export"));
        assertNull(ActionPermissionResolver.resolve(method, "/api/privacy/policies"));
        assertNull(ActionPermissionResolver.resolve(method, "/privacy/holds"));
    }

    @Test
    void queryを除いた上でprivacyとして解決し小文字methodも同じ結果になる() {
        assertEquals("privacy.policy.view",
                ActionPermissionResolver.resolve("get", "/api/privacy/policies/evaluate?moduleCode=X&dataElementId=Y"));
        assertEquals("privacy.dsar.export",
                ActionPermissionResolver.resolve("GET", "/api/privacy/dsar/1/package/download?name=x.pdf"));
        assertEquals("privacy.view", ActionPermissionResolver.resolve("GET", "/privacy/holds?tab=1"));
    }

    @Test
    void 区切りの異なるprivacy風のrootはprivacyとして扱わない() {
        assertNull(ActionPermissionResolver.resolve("GET", "/api/privacyx"));
        assertNull(ActionPermissionResolver.resolve("GET", "/api/privacy-admin"));
        assertNull(ActionPermissionResolver.resolve("GET", "/privacyx"));
        assertFalse(ActionPermissionResolver.isPrivacyPath("/api/privacyx"));
        assertFalse(ActionPermissionResolver.isPrivacyPath("/privacy-x/1"));
        assertTrue(ActionPermissionResolver.isPrivacyPath("/privacy"));
        assertTrue(ActionPermissionResolver.isPrivacyPath("/api/privacy/dsar/1"));
    }

    @Test
    void privacyは既知resourceでありlegacyとbreak_glassは別の分岐で拒否する前提を固定する() {
        // RESOURCE_NAMES に privacy を登録した結果 isKnownAction は true になる。
        // AuthorizationServiceImpl の legacy fallback と BreakGlassServiceImpl はこれを privacy. 接頭辞で拒否する。
        assertTrue(ActionPermissionResolver.isKnownAction("privacy.view"));
        assertTrue(ActionPermissionResolver.isKnownAction("privacy.dsar.export"));
        assertFalse(ActionPermissionResolver.isKnownAction("privacyx.view"));
    }
}
