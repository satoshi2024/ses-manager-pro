package com.ses.service.security;

import java.util.Locale;
import java.util.Map;

/** URIとHTTP methodをaction keyへ正規化する。業務APIは未登録のまま通過させない。 */
public final class ActionPermissionResolver {

    /** 後段filter・break-glassが同じ解決結果を使うためのrequest属性。 */
    public static final String REQUEST_ACTION_ATTRIBUTE =
            ActionPermissionResolver.class.getName() + ".ACTION";

    private static final Map<String, String> RESOURCE_NAMES = Map.ofEntries(
            Map.entry("ai", "ai"),
            Map.entry("analytics", "analytics"),
            Map.entry("autocomplete", "autocomplete"),
            Map.entry("audit-logs", "audit"),
            Map.entry("bp-availabilities", "bp-availability"),
            Map.entry("bp-companies", "bp-company"),
            Map.entry("bp-availability-ingestions", "bp-availability-ingestion"),
            Map.entry("candidates", "candidate"),
            Map.entry("cashflow", "cashflow"),
            Map.entry("compliance", "compliance"),
            Map.entry("contract-documents", "contract-document"),
            Map.entry("contracts", "contract"),
            Map.entry("customers", "customer"),
            Map.entry("dashboard", "dashboard"),
            Map.entry("documents", "document"),
            Map.entry("email-templates", "email"),
            Map.entry("engineers", "engineer"),
            Map.entry("files", "file"),
            Map.entry("identity-providers", "identity-provider"),
            Map.entry("invoices", "invoice"),
            Map.entry("management-accounting", "management-accounting"),
            Map.entry("monthly-closing", "monthly-closing"),
            Map.entry("my", "my"),
            Map.entry("notifications", "notifications"),
            Map.entry("organizations", "organization"),
            Map.entry("payroll", "payroll"),
            Map.entry("permission-groups", "permission"),
            Map.entry("profile", "profile"),
            Map.entry("project-ingestions", "project-ingestion"),
            Map.entry("projects", "project"),
            Map.entry("proposals", "proposal"),
            Map.entry("quotations", "quotation"),
            Map.entry("reconciliation", "reconciliation"),
            Map.entry("resume-ingestions", "resume-ingestion"),
            Map.entry("role-menus", "permission"),
            Map.entry("sales-performance", "sales-performance"),
            Map.entry("skill-tags", "skill-tag"),
            Map.entry("skillsheet-templates", "skillsheet-template"),
            Map.entry("system-configs", "system-config"),
            Map.entry("users", "user"),
            Map.entry("work-records", "work-record"),
            Map.entry("search", "search"),
            Map.entry("tasks", "task"),
            Map.entry("saved-views", "saved-view"),
            Map.entry("batch-operations", "batch-operation")
    );

    private ActionPermissionResolver() {
    }

    public static String resolve(String method, String uri) {
        if (uri == null || method == null) {
            return null;
        }
        method = method.toUpperCase(Locale.ROOT);
        int queryIndex = uri.indexOf('?');
        if (queryIndex >= 0) {
            uri = uri.substring(0, queryIndex);
        }
        if ("/".equals(uri)) {
            return "dashboard.view";
        }
        if (matchesPrefix(uri, "/api/security/break-glass/incidents")) {
            return "break-glass.manage";
        }
        if ("POST".equals(method) && uri.matches("/api/security/mfa/\\d+/reset")) {
            return "mfa.reset";
        }
        // MFA/sessionはログイン完了に必要な認証基盤APIで、業務permission groupの対象外。
        if (matchesPrefix(uri, "/api/security")) {
            return null;
        }
        if (!uri.startsWith("/api/")) {
            return null;
        }
        String remainder = uri.substring("/api/".length());
        int slash = remainder.indexOf('/');
        String root = slash >= 0 ? remainder.substring(0, slash) : remainder;
        if (root.isBlank()) {
            return null;
        }
        String resource = RESOURCE_NAMES.get(root);
        if (resource == null) {
            return null;
        }
        if (isExportPath(uri)) {
            return "export.execute";
        }
        if (isDownloadPath(uri)) {
            return "file.download";
        }
        if (matchesPrefix(uri, "/api/permission-groups")) {
            return "permission.manage";
        }
        if (matchesPrefix(uri, "/api/role-menus")) {
            return "permission.manage";
        }
        if (matchesPrefix(uri, "/api/audit-logs")) {
            return "audit.security.view";
        }
        if (matchesPrefix(uri, "/api/users")) {
            return action("user", method);
        }
        if (matchesPrefix(uri, "/api/files") && uri.endsWith("/rescan") && "POST".equals(method)) {
            return "file.scan.retry";
        }
        if (matchesPrefix(uri, "/api/files")) {
            return "GET".equals(method) ? "file.download" : "file.upload";
        }
        if (matchesPrefix(uri, "/api/engineers")) {
            return action("engineer", method);
        }
        if (matchesPrefix(uri, "/api/customers")) {
            return action("customer", method);
        }
        if (matchesPrefix(uri, "/api/projects")) {
            return action("project", method);
        }
        if (matchesPrefix(uri, "/api/proposals")) {
            return action("proposal", method);
        }
        if (matchesPrefix(uri, "/api/contracts")) {
            return action("contract", method);
        }
        if (matchesPrefix(uri, "/api/invoices")) {
            return action("invoice", method);
        }
        if (matchesPrefix(uri, "/api/documents")) {
            return action("document", method);
        }
        if (matchesPrefix(uri, "/api/payroll")) {
            return "payroll.view";
        }
        if (matchesPrefix(uri, "/api/work-records")
                && (uri.endsWith("/approve") || uri.endsWith("/reject"))) {
            return "work-record.approve";
        }
        if (matchesPrefix(uri, "/api/search")) {
            return "search.view";
        }
        if (matchesPrefix(uri, "/api/tasks")) {
            return action("task", method);
        }
        if (matchesPrefix(uri, "/api/saved-views")) {
            return action("saved-view", method);
        }
        if (matchesPrefix(uri, "/api/batch-operations")) {
            return action("batch-operation", method);
        }
        return action(resource, method);
    }

    /** inventoryに登録済みのactionだけをlegacy/default group互換対象にする。 */
    public static boolean isKnownAction(String actionKey) {
        if (actionKey == null || actionKey.isBlank()) {
            return false;
        }
        if (actionKey.equals("export.execute") || actionKey.equals("file.download")
                || actionKey.equals("file.upload") || actionKey.equals("file.scan.retry")
                || actionKey.equals("permission.manage") || actionKey.equals("audit.security.view")
                || actionKey.equals("mfa.reset")) {
            return true;
        }
        int separator = actionKey.indexOf('.');
        String resource = separator > 0 ? actionKey.substring(0, separator) : actionKey;
        return RESOURCE_NAMES.containsValue(resource);
    }

    /** 業務scope判定前にも利用できる、本人向け認証継続endpointのexact allow-list。 */
    public static boolean isAuthenticationInfrastructure(String method, String uri) {
        if (method == null || uri == null) {
            return false;
        }
        method = method.toUpperCase(Locale.ROOT);
        if (("POST".equals(method) && uri.equals("/logout"))
                || ("GET".equals(method)
                && (uri.equals("/mfa/setup") || uri.equals("/mfa/challenge")))) {
            return true;
        }
        if ("GET".equals(method)) {
            return uri.equals("/api/security/mfa/status") || uri.equals("/api/security/sessions");
        }
        if ("POST".equals(method)) {
            return uri.equals("/api/security/mfa/setup") || uri.equals("/api/security/mfa/enable")
                    || uri.equals("/api/security/mfa/verify")
                    || uri.equals("/api/security/sessions/revoke-others")
                    || uri.equals("/api/security/sessions/revoke-all");
        }
        return "DELETE".equals(method) && uri.matches("/api/security/sessions/\\d+");
    }

    private static boolean matchesPrefix(String uri, String prefix) {
        return uri.equals(prefix) || uri.startsWith(prefix + "/");
    }

    private static boolean isExportPath(String uri) {
        return uri.endsWith("/export")
                || uri.contains("/export-") || uri.endsWith("-export")
                || uri.contains("-export/");
    }

    private static boolean isDownloadPath(String uri) {
        return uri.endsWith("/download") || uri.contains("/download/")
                || uri.endsWith(".pdf") || uri.endsWith(".xlsx") || uri.endsWith(".csv");
    }

    private static String action(String resource, String method) {
        return switch (method) {
            case "GET", "HEAD" -> resource + ".view";
            case "POST" -> resource + ".create";
            case "PUT", "PATCH" -> resource + ".update";
            case "DELETE" -> resource + ".delete";
            default -> null;
        };
    }
}
