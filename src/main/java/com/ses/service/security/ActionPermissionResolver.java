package com.ses.service.security;

/** URIとHTTP methodをaction keyへ正規化する。未知URIは認可境界を推測せずnullを返す。 */
public final class ActionPermissionResolver {

    private ActionPermissionResolver() {
    }

    public static String resolve(String method, String uri) {
        if (uri == null || method == null) {
            return null;
        }
        if (isExportPath(uri)) {
            return "export.execute";
        }
        if (matchesPrefix(uri, "/api/permission-groups")) {
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
        return null;
    }

    private static boolean matchesPrefix(String uri, String prefix) {
        return uri.equals(prefix) || uri.startsWith(prefix + "/");
    }

    private static boolean isExportPath(String uri) {
        return matchesPrefix(uri, "/api/export") || uri.endsWith("/export")
                || uri.contains("/export-") || uri.endsWith("-export")
                || uri.contains("-export/");
    }

    private static String action(String resource, String method) {
        return switch (method) {
            case "GET" -> resource + ".view";
            case "POST" -> resource + ".create";
            case "PUT", "PATCH" -> resource + ".update";
            case "DELETE" -> resource + ".delete";
            default -> null;
        };
    }
}
