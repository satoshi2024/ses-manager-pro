package com.ses.service.integrationhub;

import com.ses.config.integrationhub.ExternalApiCursorCodec;
import com.ses.config.integrationhub.ExternalApiEffectiveScope;
import com.ses.config.integrationhub.ExternalApiPrincipal;
import com.ses.config.integrationhub.ExternalApiPublicIdCodec;
import com.ses.config.integrationhub.ExternalApiSecurityException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.dto.integrationhub.ExternalApiContractStatus;
import com.ses.dto.integrationhub.ExternalApiCountResponse;
import com.ses.dto.integrationhub.ExternalApiEngineerAvailability;
import com.ses.dto.integrationhub.ExternalApiInvoiceStatus;
import com.ses.dto.integrationhub.ExternalApiListResponse;
import com.ses.dto.integrationhub.ExternalApiProject;
import com.ses.dto.integrationhub.ExternalApiReadRow;
import com.ses.dto.integrationhub.ExternalApiSnapshotItem;
import com.ses.mapper.ExternalApiReadMapper;
import com.ses.mapper.ExternalApiReadSnapshotMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/** A1 read service。唯一の入力はF2が作成したimmutable effective scopeである。 */
@Service
@RequiredArgsConstructor
public class ExternalApiReadService {
    private static final int MAX_LIMIT = 100;
    private static final int MAX_SNAPSHOT_ITEMS = 512;
    private static final ZoneId SERVER_ZONE = ZoneId.of("Asia/Tokyo");

    private final ExternalApiReadMapper mapper;
    private final ExternalApiReadSnapshotMapper snapshotMapper;
    private final ExternalApiPublicIdCodec publicIdCodec;
    private final ExternalApiCursorCodec cursorCodec;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public ExternalApiListResponse<ExternalApiEngineerAvailability> listEngineerAvailability(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, int limit, String cursor) {
        List<Long> engineerIds = requiredIds(scope, "engineerIds");
        return page(principal, scope, "/external-api/v1/engineer-availability", limit, cursor,
                (afterId, fetchLimit) -> mapper.selectEngineers(engineerIds, afterId, fetchLimit),
                row -> toEngineer(principal, row), ExternalApiEngineerAvailability.class);
    }

    public ExternalApiEngineerAvailability getEngineerAvailability(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, String publicId) {
        Long id = resolveId(principal, "engineer-availability", publicId, requiredIds(scope, "engineerIds"));
        if (id == null) return null;
        return mapper.selectEngineers(List.of(id), null, 1).stream()
                .findFirst().map(row -> toEngineer(principal, row)).orElse(null);
    }

    @Transactional
    public ExternalApiListResponse<ExternalApiProject> listProjects(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, int limit, String cursor) {
        List<Long> projectIds = requiredIds(scope, "projectIds");
        List<Long> customerIds = optionalIds(scope, "customerIds");
        return page(principal, scope, "/external-api/v1/projects", limit, cursor,
                (afterId, fetchLimit) -> mapper.selectProjects(projectIds, customerIds, afterId, fetchLimit),
                row -> toProject(principal, row), ExternalApiProject.class);
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

    @Transactional
    public ExternalApiListResponse<ExternalApiContractStatus> listContractStatuses(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, int limit, String cursor) {
        List<Long> contractIds = requiredIds(scope, "contractIds");
        List<Long> projectIds = optionalIds(scope, "projectIds");
        return page(principal, scope, "/external-api/v1/contract-statuses", limit, cursor,
                (afterId, fetchLimit) -> mapper.selectContracts(contractIds, projectIds, afterId, fetchLimit),
                row -> toContract(principal, row), ExternalApiContractStatus.class);
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

    @Transactional
    public ExternalApiListResponse<ExternalApiInvoiceStatus> listInvoiceStatuses(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, int limit, String cursor) {
        List<Long> invoiceIds = requiredIds(scope, "invoiceIds");
        List<Long> contractIds = optionalIds(scope, "contractIds");
        List<Long> customerIds = requiredIds(scope, "customerIds");
        return page(principal, scope, "/external-api/v1/invoice-statuses", limit, cursor,
                (afterId, fetchLimit) -> mapper.selectInvoices(invoiceIds, contractIds, customerIds, afterId, fetchLimit),
                row -> toInvoice(principal, row), ExternalApiInvoiceStatus.class);
    }

    public ExternalApiInvoiceStatus getInvoiceStatus(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope, String publicId) {
        Long id = resolveId(principal, "invoice-status", publicId, requiredIds(scope, "invoiceIds"));
        if (id == null) return null;
        return mapper.selectInvoices(List.of(id), optionalIds(scope, "contractIds"), requiredIds(scope, "customerIds"), null, 1).stream()
                .findFirst().map(row -> toInvoice(principal, row)).orElse(null);
    }

    public ExternalApiCountResponse countInvoiceStatuses(
            ExternalApiPrincipal principal, ExternalApiEffectiveScope scope) {
        List<Long> invoiceIds = requiredIds(scope, "invoiceIds");
        return new ExternalApiCountResponse(mapper.countInvoices(invoiceIds, optionalIds(scope, "contractIds"),
                        requiredIds(scope, "customerIds")),
                clock.instant());
    }

    private <T> ExternalApiListResponse<T> page(ExternalApiPrincipal principal, ExternalApiEffectiveScope scope,
                                                 String route, int limit, String cursor,
                                                 RowFetcher fetcher, Function<ExternalApiReadRow, T> converter,
                                                 Class<T> dtoType) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit is outside the approved bound");
        }
        String digest = scopeDigest(scope);
        Instant now = clock.instant();
        if (cursor != null && !cursor.isBlank()) {
            return pageFromSnapshot(principal, route, digest, limit, cursor, dtoType, now);
        }
        snapshotMapper.deleteExpiredItems(now);
        snapshotMapper.deleteExpiredSnapshots(now);
        Instant asOf = now;
        long expiresAt = cursorCodec.expiryFrom(now);
        List<ExternalApiReadRow> rows = fetcher.fetch(null, MAX_SNAPSHOT_ITEMS + 1);
        if (rows.size() > MAX_SNAPSHOT_ITEMS) {
            throw ExternalApiSecurityException.forbidden("FORBIDDEN_SCOPE");
        }
        List<T> allItems = rows.stream().map(converter).toList();
        boolean hasMore = allItems.size() > limit;
        String snapshotId = null;
        if (hasMore) {
            snapshotId = UUID.randomUUID().toString();
            snapshotMapper.insertSnapshot(snapshotId, principal.clientId(), principal.tenantId(),
                    principal.legalEntityId(), route, digest, asOf, Instant.ofEpochSecond(expiresAt));
            for (int index = 0; index < rows.size(); index++) {
                ExternalApiReadRow row = rows.get(index);
                if (row == null || row.getId() == null || row.getId() < 1) {
                    throw new IllegalStateException("external read row identifier is missing");
                }
                snapshotMapper.insertItem(snapshotId, row.getId(), serialize(allItems.get(index)));
            }
        }
        List<T> items = allItems.subList(0, Math.min(limit, allItems.size()));
        String nextCursor = null;
        if (hasMore && !items.isEmpty()) {
            Long lastId = rows.get(items.size() - 1).getId();
            nextCursor = cursorCodec.encode(new ExternalApiCursorCodec.State(
                    principal.clientId(), principal.tenantId(), principal.legalEntityId(), route,
                    digest, snapshotId, asOf.getEpochSecond(), lastId, expiresAt));
        }
        return new ExternalApiListResponse<>(items, nextCursor, hasMore, asOf);
    }

    private <T> ExternalApiListResponse<T> pageFromSnapshot(ExternalApiPrincipal principal, String route,
                                                              String digest, int limit, String cursor,
                                                              Class<T> dtoType, Instant now) {
        ExternalApiCursorCodec.State cursorState = cursorCodec.decode(cursor, principal.clientId(),
                principal.tenantId(), principal.legalEntityId(), route, digest, now);
        if (cursorState.snapshotId() == null || cursorState.snapshotId().isBlank()) {
            throw ExternalApiSecurityException.invalid("CURSOR_INVALID");
        }
        List<ExternalApiSnapshotItem> rows = snapshotMapper.selectItemsAfter(
                cursorState.snapshotId(), cursorState.lastInternalId(), limit + 1);
        if (rows.isEmpty()) {
            throw ExternalApiSecurityException.invalid("CURSOR_INVALID");
        }
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = new ArrayList<>(rows.subList(0, limit));
        }
        List<T> items = rows.stream().map(row -> deserialize(row.payloadJson(), dtoType)).toList();
        String nextCursor = null;
        if (hasMore && !rows.isEmpty()) {
            Long lastId = rows.get(rows.size() - 1).resourceId();
            if (lastId == null || lastId < 1) {
                throw new IllegalStateException("external read row identifier is missing");
            }
            nextCursor = cursorCodec.encode(new ExternalApiCursorCodec.State(
                    principal.clientId(), principal.tenantId(), principal.legalEntityId(), route,
                    digest, cursorState.snapshotId(), cursorState.asOfEpochSecond(), lastId,
                    cursorState.expiresAtEpochSecond()));
        }
        return new ExternalApiListResponse<>(items, nextCursor, hasMore,
                Instant.ofEpochSecond(cursorState.asOfEpochSecond()));
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("safe external snapshot serialization failed", e);
        }
    }

    private <T> T deserialize(String payload, Class<T> dtoType) {
        try {
            return objectMapper.readValue(payload, dtoType);
        } catch (Exception e) {
            throw ExternalApiSecurityException.invalid("CURSOR_INVALID");
        }
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
                row.getContractId() == null || !Long.valueOf(1L).equals(row.getContractCount()) ? null
                        : publicIdCodec.encode(principal, "contract-status", row.getContractId()),
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
