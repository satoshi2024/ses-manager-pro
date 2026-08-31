package com.ses.controller.externalapi;

import com.ses.config.integrationhub.ExternalApiEffectiveScope;
import com.ses.config.integrationhub.ExternalApiErrorWriter;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import com.ses.dto.integrationhub.ExternalApiContractStatus;
import com.ses.dto.integrationhub.ExternalApiCountResponse;
import com.ses.dto.integrationhub.ExternalApiEngineerAvailability;
import com.ses.dto.integrationhub.ExternalApiInvoiceStatus;
import com.ses.dto.integrationhub.ExternalApiListResponse;
import com.ses.dto.integrationhub.ExternalApiProject;
import com.ses.service.integrationhub.ExternalApiReadService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/** A1承認済みGET-only external API。controllerはexternal DTOだけを返す。 */
@RestController
@RequestMapping("/external-api/v1")
@RequiredArgsConstructor
public class ExternalApiReadController {
    private static final Set<String> PAGE_QUERY = Set.of("limit", "cursor");
    private static final Set<String> NO_QUERY = Set.of();

    private final ExternalApiReadService readService;

    @GetMapping("/engineer-availability")
    public ExternalApiListResponse<ExternalApiEngineerAvailability> listEngineerAvailability(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        validateQuery(request, PAGE_QUERY);
        return readService.listEngineerAvailability(principal(request), scope(request), parseLimit(limit), cursor);
    }

    @GetMapping("/engineer-availability/{publicEngineerId}")
    public ExternalApiEngineerAvailability getEngineerAvailability(
            @PathVariable String publicEngineerId, HttpServletRequest request) {
        validateQuery(request, NO_QUERY);
        ExternalApiEngineerAvailability result = readService.getEngineerAvailability(
                principal(request), scope(request), publicEngineerId);
        if (result == null) throw ExternalApiSecurityException.notFound("RESOURCE_NOT_FOUND");
        return result;
    }

    @GetMapping("/projects")
    public ExternalApiListResponse<ExternalApiProject> listProjects(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        validateQuery(request, PAGE_QUERY);
        return readService.listProjects(principal(request), scope(request), parseLimit(limit), cursor);
    }

    @GetMapping("/projects/{publicProjectId}")
    public ExternalApiProject getProject(@PathVariable String publicProjectId, HttpServletRequest request) {
        validateQuery(request, NO_QUERY);
        ExternalApiProject result = readService.getProject(principal(request), scope(request), publicProjectId);
        if (result == null) throw ExternalApiSecurityException.notFound("RESOURCE_NOT_FOUND");
        return result;
    }

    @GetMapping("/projects/count")
    public ExternalApiCountResponse countProjects(HttpServletRequest request) {
        validateQuery(request, NO_QUERY);
        return readService.countProjects(principal(request), scope(request));
    }

    @GetMapping("/contract-statuses")
    public ExternalApiListResponse<ExternalApiContractStatus> listContractStatuses(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        validateQuery(request, PAGE_QUERY);
        return readService.listContractStatuses(principal(request), scope(request), parseLimit(limit), cursor);
    }

    @GetMapping("/contract-statuses/{publicContractId}")
    public ExternalApiContractStatus getContractStatus(
            @PathVariable String publicContractId, HttpServletRequest request) {
        validateQuery(request, NO_QUERY);
        ExternalApiContractStatus result = readService.getContractStatus(
                principal(request), scope(request), publicContractId);
        if (result == null) throw ExternalApiSecurityException.notFound("RESOURCE_NOT_FOUND");
        return result;
    }

    @GetMapping("/contract-statuses/count")
    public ExternalApiCountResponse countContractStatuses(HttpServletRequest request) {
        validateQuery(request, NO_QUERY);
        return readService.countContractStatuses(principal(request), scope(request));
    }

    @GetMapping("/invoice-statuses")
    public ExternalApiListResponse<ExternalApiInvoiceStatus> listInvoiceStatuses(
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String cursor,
            HttpServletRequest request) {
        validateQuery(request, PAGE_QUERY);
        return readService.listInvoiceStatuses(principal(request), scope(request), parseLimit(limit), cursor);
    }

    @GetMapping("/invoice-statuses/{publicInvoiceId}")
    public ExternalApiInvoiceStatus getInvoiceStatus(
            @PathVariable String publicInvoiceId, HttpServletRequest request) {
        validateQuery(request, NO_QUERY);
        ExternalApiInvoiceStatus result = readService.getInvoiceStatus(
                principal(request), scope(request), publicInvoiceId);
        if (result == null) throw ExternalApiSecurityException.notFound("RESOURCE_NOT_FOUND");
        return result;
    }

    @GetMapping("/invoice-statuses/count")
    public ExternalApiCountResponse countInvoiceStatuses(HttpServletRequest request) {
        validateQuery(request, NO_QUERY);
        return readService.countInvoiceStatuses(principal(request), scope(request));
    }

    private ExternalApiPrincipal principal(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiErrorWriter.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof ExternalApiPrincipal principal)) {
            throw ExternalApiSecurityException.authentication("EXTERNAL_PRINCIPAL_MISSING");
        }
        return principal;
    }

    private ExternalApiEffectiveScope scope(HttpServletRequest request) {
        Object value = request.getAttribute(ExternalApiEffectiveScope.class.getName());
        if (!(value instanceof ExternalApiEffectiveScope scope)) {
            throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
        }
        return scope;
    }

    private int parseLimit(String value) {
        if (value == null) return 50;
        if (!value.matches("[1-9][0-9]?") && !"100".equals(value)) {
            throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        }
        int parsed = Integer.parseInt(value);
        if (parsed < 1 || parsed > 100) throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
        return parsed;
    }

    private void validateQuery(HttpServletRequest request, Set<String> allowed) {
        for (Map.Entry<String, String[]> entry : request.getParameterMap().entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue() == null || entry.getValue().length != 1
                    || entry.getValue()[0] == null || entry.getValue()[0].isBlank()) {
                throw ExternalApiSecurityException.invalid("REQUEST_INVALID");
            }
        }
    }
}
