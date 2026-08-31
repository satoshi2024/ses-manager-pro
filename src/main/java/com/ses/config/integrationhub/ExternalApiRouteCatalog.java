package com.ses.config.integrationhub;

import java.util.Set;

/** 承認済みGET-only routeの有限catalog。raw pathや任意のcontroller mappingを認可根拠にしない。 */
public final class ExternalApiRouteCatalog {
    public static final String INBOUND_WEBHOOK_SCOPE = "integration.webhook.receive";
    public static final String INBOUND_WEBHOOK_PERMISSION = "integration.webhook.receive";
    public static final String ENGINEER_AVAILABILITY_SCOPE = "integration.availability.read";
    public static final String ENGINEER_AVAILABILITY_PERMISSION = "integration.engineer-availability.read";
    public static final String PROJECT_SCOPE = "integration.project.read";
    public static final String PROJECT_PERMISSION = "integration.project.read";
    public static final String CONTRACT_STATUS_SCOPE = "integration.contract-status.read";
    public static final String CONTRACT_STATUS_PERMISSION = "integration.contract-status.read";
    public static final String INVOICE_STATUS_SCOPE = "integration.invoice-status.read";
    public static final String INVOICE_STATUS_PERMISSION = "integration.invoice-status.read";

    /** 認可filterとquotaが共有する、raw pathを含まないcanonical route templateの正本。 */
    public static final Set<String> QUOTA_ROUTE_TEMPLATES = Set.of(
            "/external-api/v1/engineer-availability",
            "/external-api/v1/engineer-availability/{publicEngineerId}",
            "/external-api/v1/projects",
            "/external-api/v1/projects/{publicProjectId}",
            "/external-api/v1/projects/count",
            "/external-api/v1/contract-statuses",
            "/external-api/v1/contract-statuses/{publicContractId}",
            "/external-api/v1/contract-statuses/count",
            "/external-api/v1/invoice-statuses",
            "/external-api/v1/invoice-statuses/{publicInvoiceId}",
            "/external-api/v1/invoice-statuses/count",
            "/external-api/v1/webhooks/{provider}");

    /** Spring matcher用のpath（detailのwildcardはcatalog側で1 segmentに再検証する）。 */
    public static final String[] SECURITY_MATCHERS = {
            "/external-api/v1",
            "/external-api/v1/engineer-availability",
            "/external-api/v1/engineer-availability/*",
            "/external-api/v1/projects",
            "/external-api/v1/projects/*",
            "/external-api/v1/projects/count",
            "/external-api/v1/contract-statuses",
            "/external-api/v1/contract-statuses/*",
            "/external-api/v1/contract-statuses/count",
            "/external-api/v1/invoice-statuses",
            "/external-api/v1/invoice-statuses/*",
            "/external-api/v1/invoice-statuses/count"
    };

    /** provider受信はGET-only read contractとは分離した、承認済みinbound route。 */
    public static final String[] INBOUND_SECURITY_MATCHERS = {
            "/external-api/v1/webhooks/*"
    };

    private ExternalApiRouteCatalog() {
    }

    public static boolean isExternalApiPath(String path) {
        return "/external-api/v1".equals(path)
                || path != null && path.startsWith("/external-api/v1/");
    }

    public enum ResourceType {
        ENGINEER_AVAILABILITY("engineerIds"),
        PROJECT("projectIds"),
        CONTRACT_STATUS("contractIds"),
        INVOICE_STATUS("invoiceIds"),
        INBOUND_WEBHOOK("tenantIds");

        private final String dataScopeDimension;

        ResourceType(String dataScopeDimension) {
            this.dataScopeDimension = dataScopeDimension;
        }

        public String dataScopeDimension() {
            return dataScopeDimension;
        }
    }

    public record Route(String template, String scopeCode, String operationCode, ResourceType resourceType) {
    }

    public static Route resolve(String method, String path) {
        if (path == null || path.contains("//")) {
            return null;
        }
        if ("POST".equals(method) && oneSegmentDetail(path, "/external-api/v1/webhooks/")) {
            return new Route("/external-api/v1/webhooks/{provider}", INBOUND_WEBHOOK_SCOPE,
                    INBOUND_WEBHOOK_PERMISSION, ResourceType.INBOUND_WEBHOOK);
        }
        if (!"GET".equals(method)) {
            return null;
        }
        if ("/external-api/v1/engineer-availability".equals(path)) {
            return new Route(path, ENGINEER_AVAILABILITY_SCOPE, ENGINEER_AVAILABILITY_PERMISSION,
                    ResourceType.ENGINEER_AVAILABILITY);
        }
        if (oneSegmentDetail(path, "/external-api/v1/engineer-availability/")) {
            return new Route("/external-api/v1/engineer-availability/{publicEngineerId}",
                    ENGINEER_AVAILABILITY_SCOPE, ENGINEER_AVAILABILITY_PERMISSION,
                    ResourceType.ENGINEER_AVAILABILITY);
        }
        if ("/external-api/v1/projects".equals(path)) {
            return new Route(path, PROJECT_SCOPE, PROJECT_PERMISSION, ResourceType.PROJECT);
        }
        if ("/external-api/v1/projects/count".equals(path)) {
            return new Route(path, PROJECT_SCOPE, PROJECT_PERMISSION, ResourceType.PROJECT);
        }
        if (oneSegmentDetail(path, "/external-api/v1/projects/")) {
            return new Route("/external-api/v1/projects/{publicProjectId}", PROJECT_SCOPE, PROJECT_PERMISSION,
                    ResourceType.PROJECT);
        }
        if ("/external-api/v1/contract-statuses".equals(path)) {
            return new Route(path, CONTRACT_STATUS_SCOPE, CONTRACT_STATUS_PERMISSION, ResourceType.CONTRACT_STATUS);
        }
        if ("/external-api/v1/contract-statuses/count".equals(path)) {
            return new Route(path, CONTRACT_STATUS_SCOPE, CONTRACT_STATUS_PERMISSION, ResourceType.CONTRACT_STATUS);
        }
        if (oneSegmentDetail(path, "/external-api/v1/contract-statuses/")) {
            return new Route("/external-api/v1/contract-statuses/{publicContractId}",
                    CONTRACT_STATUS_SCOPE, CONTRACT_STATUS_PERMISSION, ResourceType.CONTRACT_STATUS);
        }
        if ("/external-api/v1/invoice-statuses".equals(path)) {
            return new Route(path, INVOICE_STATUS_SCOPE, INVOICE_STATUS_PERMISSION, ResourceType.INVOICE_STATUS);
        }
        if ("/external-api/v1/invoice-statuses/count".equals(path)) {
            return new Route(path, INVOICE_STATUS_SCOPE, INVOICE_STATUS_PERMISSION, ResourceType.INVOICE_STATUS);
        }
        if (oneSegmentDetail(path, "/external-api/v1/invoice-statuses/")) {
            return new Route("/external-api/v1/invoice-statuses/{publicInvoiceId}",
                    INVOICE_STATUS_SCOPE, INVOICE_STATUS_PERMISSION, ResourceType.INVOICE_STATUS);
        }
        return null;
    }

    private static boolean oneSegmentDetail(String path, String prefix) {
        if (!path.startsWith(prefix)) {
            return false;
        }
        String id = path.substring(prefix.length());
        return id.matches("[A-Za-z0-9._~-]{1,128}") && !"count".equals(id);
    }
}
