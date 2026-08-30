package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiCursorCodec;
import com.ses.config.integrationhub.ExternalApiEffectiveScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import com.ses.dto.integrationhub.ExternalApiContractStatus;
import com.ses.dto.integrationhub.ExternalApiCountResponse;
import com.ses.dto.integrationhub.ExternalApiEngineerAvailability;
import com.ses.dto.integrationhub.ExternalApiInvoiceStatus;
import com.ses.dto.integrationhub.ExternalApiListResponse;
import com.ses.dto.integrationhub.ExternalApiProject;
import com.ses.dto.integrationhub.ExternalApiReadRow;
import com.ses.mapper.ExternalApiReadMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/** A1 read service。唯一の入力はF2が作成したimmutable effective scopeである。 */
@Service
@RequiredArgsConstructor
public class ExternalApiReadService {
    private static final int MAX_LIMIT = 100;
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");

    private final ExternalApiReadMapper mapper;
    private final ExternalApiPublicIdCodec publicIdCodec;
    private final ExternalApiCursorCodec cursorCodec;
    private final Clock clock;

    public ExternalApiListResponse<ExternalApiEngineerAvailability> listEngineerAvailability(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, int limit, String cursor) {
        List<Long> engineerIds = requiredIds(scope, "engineerIds");
        return page(principal, scope, "/external-api/v1/engineer-availability", limit, cursor,
                (afterId, fetchLimit) -> mapper.selectEngineers(engineerIds, afterId, fetchLimit),
                row -> toEngineer(principal, row));
    }

    public ExternalApiEngineerAvailability getEngineerAvailability(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, String publicId) {
        Long id = resolveId(principal, "engineer-availability", publicId, requiredIds(scope, "engineerIds"));
        if (id == null) return null;
        return mapper.selectEngineers(List.of(id), null, 1).stream()
                .findFirst().map(row -> toEngineer(principal, row)).orElse(null);
    }

    public ExternalApiListResponse<ExternalApiProject> listProjects(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, int limit, String cursor) {
        List<Long> projectIds = requiredIds(scope, "projectIds");
        List<Long> customerIds = optionalIds(scope, "customerIds");
        return page(principal, scope, "/external-api/v1/projects", limit, cursor,
                (afterId, fetchLimit) -> mapper.selectProjects(projectIds, customerIds, afterId, fetchLimit),
                row -> toProject(principal, row));
    }

    public ExternalApiProject getProject(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, String publicId) {
        Long id = resolveId(principal, "project", publicId, requiredIds(scope, "projectIds"));
        if (id == null) return null;
        List<Long> customerIds = optionalIds(scope, "customerIds");
        return mapper.selectProjects(List.of(id), customerIds, null, 1).stream()
                .findFirst().map(row -> toProject(principal, row)).orElse(null);
    }

    public ExternalApiCountResponse countProjects(ExternalApiPrincipal principal, ExternalApiEffectiveScope scope) {
        List<Long> projectIds = requiredIds(scope, "projectIds");
        return new ExternalApiCountResponse(mapper.countProjects(projectIds, optionalIds(scope, "customerIds")),
                clock.instant());
    }

    public ExternalApiListResponse<ExternalApiContractStatus> listContractStatuses(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, int limit, String cursor) {
        List<Long> contractIds = requiredIds(scope, "contractIds");
        List<Long> projectIds = optionalIds(scope, "projectIds");
        return page(principal, scope, "/external-api/v1/contract-statuses", limit, cursor,
                (afterId, fetchLimit) -> mapper.selectContracts(contractIds, projectIds, afterId, fetchLimit),
                row -> toContract(principal, row));
    }

    public ExternalApiContractStatus getContractStatus(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, String publicId) {
        Long id = resolveId(principal, "contract-status", publicId, requiredIds(scope, "contractIds"));
        if (id == null) return null;
        return mapper.selectContracts(List.of(id), optionalIds(scope, "projectIds"), null, 1).stream()
                .findFirst().map(row -> toContract(principal, row)).orElse(null);
    }

    public ExternalApiCountResponse countContractStatuses(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope) {
        List<Long> contractIds = requiredIds(scope, "contractIds");
        return new ExternalApiCountResponse(mapper.countContracts(contractIds, optionalIds(scope, "projectIds")),
                clock.instant());
    }

    public ExternalApiListResponse<ExternalApiInvoiceStatus> listInvoiceStatuses(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, int limit, String cursor) {
        List<Long> invoiceIds = requiredIds(scope, "invoiceIds");
        List<Long> contractIds = optionalIds(scope, "contractIds");
        return page(principal, scope, "/external-api/v1/invoice-statuses", limit, cursor,
                (afterId, fetchLimit) -> mapper.selectInvoices(invoiceIds, contractIds, afterId, fetchLimit),
                row -> toInvoice(principal, row));
    }

    public ExternalApiInvoiceStatus getInvoiceStatus(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, String publicId) {
        Long id = resolveId(principal, "invoice-status", publicId, requiredIds(scope, "invoiceIds"));
        if (id == null) return null;
        return mapper.selectInvoices(List.of(id), optionalIds(scope, "contractIds"), null, 1).stream()
                .findFirst().map(row -> toInvoice(principal, row)).orElse(null);
    }

    public ExternalApiCountResponse countInvoiceStatuses(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope) {
        List<Long> invoiceIds = requiredIds(scope, "invoiceIds");
        return new ExternalApiCountResponse(mapper.countInvoices(invoiceIds, optionalIds(scope, "contractIds")),
                clock.instant());
    }

    private <T> ExternalApiListResponse<T> page(ExternalApiPrincipal principal, ExternalApiEffectiveScope scope,
                                                 String route, int limit, String cursor,
                                                 RowFetcher fetcher, Function<ExternalApiReadRow, T> converter) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside the approved bound");
        }
        String digest = scopeDigest(scope);
        Instant now = clock.instant();
        ExternalApiCursorCodec.State cursorState;
        Long afterId = null;
        Instant asOf = now;
        long expiresAt = cursorCodec.expiryFrom(now);
        if (cursor != null && !cursor.isBlank()) {
            cursorState = cursorCodec.decode(cursor, principal.clientId(), principal.tenantId(),
                    principal.legalEntityId(), route, digest, now);
            afterId = cursorState.lastInternalId();
            asOf = Instant.ofEpochSecond(cursorState.asOfEpochSecond());
            expiresAt = cursorState.expiresAtEpochSecond();
        }
        List<ExternalApiReadRow> rows = fetcher.fetch(afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<T> items = rows.stream().map(converter).toList();
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            Long lastId = rows.get(rows.size() - 1).getId();
            if (lastId == null || lastId < 1) {
                throw new IllegalStateException("external read row identifier is missing");
            }
            nextCursor = cursorCodec.encode(new ExternalApiCursorCodec.State(
                    principal.clientId(), principal.tenantId(), principal.legalEntityId(), route,
                    digest, asOf.getEpochSecond(), lastId, expiresAt));
        }
        return new ExternalApiListResponse<>(items, nextCursor, hasMore, asOf);
    }

    private ExternalApiEngineerAvailability toEngineer(ExternalApiPrincipal principal, ExternalApiReadRow row) {
        return new ExternalApiEngineerAvailability(
                publicIdCodec.encode(principal, "engineer-availability", row.getId()),
                availabilityStatus(row.getStatus()), row.getAvailableDate(), null, null);
    }

    private ExternalApiProject toProject(ExternalApiPrincipal principal, ExternalApiReadRow row) {
        return new ExternalApiProject(publicIdCodec.encode(principal, "project", row.getId()),
                boundedStatus(row.getStatus()), row.getStartDate(), row.getEndDate(),
                row.getCustomerId() == null ? null : publicIdCodec.encode(principal, "customer", row.getCustomerId()));
    }

    private ExternalApiContractStatus toContract(ExternalApiPrincipal principal, ExternalApiReadRow row) {
        return new ExternalApiContractStatus(publicIdCodec.encode(principal, "contract-status", row.getId()),
                row.getProjectId() == null ? null : publicIdCodec.encode(principal, "project", row.getProjectId()),
                boundedStatus(row.getStatus()), row.getStartDate(), row.getEndDate(),
                blankToNull(row.getRenewalStatus()));
    }

    private ExternalApiInvoiceStatus toInvoice(ExternalApiPrincipal principal, ExternalApiReadRow row) {
        boolean settled = row.getPaidDate() != null || "入金済".equals(row.getStatus());
        Instant paidAt = row.getPaidDate() == null ? null
                : row.getPaidDate().atStartOfDay(SERVER_ZONE).toInstant();
        return new ExternalApiInvoiceStatus(publicIdCodec.encode(principal, "invoice-status", row.getId()),
                row.getContractId() == null ? null : publicIdCodec.encode(principal, "contract-status", row.getContractId()),
                boundedStatus(row.getStatus()), row.getIssueDate(), row.getDueDate(), paidAt,
                settled ? "SETTLED" : "OUTSTANDING");
    }

    private String availabilityStatus(String status) {
        if ("Bench".equals(status) || "提案中".equals(status)) return "AVAILABLE";
        if ("稼動中".equals(status) || "退場予定".equals(status)) return "UNAVAILABLE";
        return "UNKNOWN";
    }

    private String boundedStatus(String status) {
        if (status == null || status.isBlank()) return "UNKNOWN";
        return status.length() <= 64 ? status : "UNKNOWN";
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Long resolveId(ExternalApiPrincipal principal, String resourceType, String publicId,
                          List<Long> allowedIds) {
        Long match = null;
        for (Long candidate : allowedIds) {
            if (publicIdCodec.matches(principal, resourceType, candidate, publicId)) {
                if (match != null) throw new IllegalStateException("public id maps to multiple rows");
                match = candidate;
            }
        }
        return match;
    }

    private List<Long> requiredIds(ExternalApiEffectiveScope scope, String dimension) {
        if (scope == null) throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
        Set<String> values = scope.allowedValues().get(dimension);
        if (values == null || values.isEmpty()) {
            throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
        }
        return parseIds(values);
    }

    private List<Long> optionalIds(ExternalApiEffectiveScope scope, String dimension) {
        if (scope == null) throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
        Set<String> values = scope.allowedValues().get(dimension);
        return values == null ? null : parseIds(values);
    }

    private List<Long> parseIds(Set<String> values) {
        List<Long> ids = new ArrayList<>();
        for (String value : values) {
            try {
                long id = Long.parseLong(value);
                if (id < 1) throw new NumberFormatException();
                ids.add(id);
            } catch (NumberFormatException e) {
                throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
            }
        }
        ids.sort(Comparator.naturalOrder());
        return ids;
    }

    private String scopeDigest(ExternalApiEffectiveScope scope) {
        if (scope == null) throw new IllegalArgumentException("effective scope is missing");
        Map<String, Set<String>> sorted = new TreeMap<>(scope.allowedValues());
        StringBuilder canonical = new StringBuilder();
        sorted.forEach((key, values) -> canonical.append(key).append('=')
                .append(values.stream().sorted().reduce((left, right) -> left + "," + right).orElse(""))
                .append(';'));
        return IntegrationHubDigest.sha256Hex(canonical.toString());
    }

    @FunctionalInterface
    private interface RowFetcher {
        List<ExternalApiReadRow> fetch(Long afterId, int limit);
    }
}
