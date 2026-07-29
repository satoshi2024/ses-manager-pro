package com.ses.service.security;

import java.util.Locale;
import java.util.Map;

/** URIとHTTP methodをaction keyへ正規化する。業務APIは未登録のまま通過させない。 */
public final class ActionPermissionResolver {

    private static final Map<String, String> RESOURCE_NAMES = Map.ofEntries(
            Map.entry("audit-logs", "audit"),
            Map.entry("candidates", "candidate"),
            Map.entry("contracts", "contract"),
            Map.entry("customers", "customer"),
            Map.entry("engineers", "engineer"),
            Map.entry("identity-providers", "identity-provider"),
            Map.entry("invoices", "invoice"),
            Map.entry("organizations", "organization"),
            Map.entry("projects", "project"),
            Map.entry("proposals", "proposal"),
            Map.entry("quotations", "quotation"),
            Map.entry("role-menus", "permission"),
            Map.entry("system-configs", "system-config"),
            Map.entry("users", "user"),
            Map.entry("work-records", "work-record")
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
        // MFA/sessionはログイン完了に必要な認証基盤APIで、業務permission groupの対象外。
        if (matchesPrefix(uri, "/api/security")) {
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
        if (matchesPrefix(uri, "/api/payroll")) {
            return "payroll.view";
        }
        if (matchesPrefix(uri, "/api/work-records")
                && (uri.endsWith("/approve") || uri.endsWith("/reject"))) {
            return "work-record.approve";
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
        String resource = RESOURCE_NAMES.getOrDefault(root, root);
        return action(resource, method);
    }

    private static boolean matchesPrefix(String uri, String prefix) {
        return uri.equals(prefix) || uri.startsWith(prefix + "/");
    }

    private static boolean isExportPath(String uri) {
        return matchesPrefix(uri, "/api/export") || uri.endsWith("/export")
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
