package com.ses.importcenter.metadata;

import com.ses.importcenter.foundation.LookupStatus;
import com.ses.importcenter.foundation.ReadOnlyImportResolver;
import com.ses.importcenter.foundation.ReferenceLookup;
import com.ses.importcenter.foundation.ReferenceTarget;
import com.ses.importcenter.foundation.ReferenceType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** MySQL試験専用のSYNTH-*候補resolver。本番natural keyやtenant分離の証明には使用しない。 */
final class SyntheticJdbcImportResolver implements ReadOnlyImportResolver {
    private final JdbcTemplate jdbc;
    private final String tenantId;
    private final String legalEntityId;
    private final List<String> statements = new CopyOnWriteArrayList<>();

    SyntheticJdbcImportResolver(JdbcTemplate jdbc, String tenantId, String legalEntityId) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.tenantId = Objects.requireNonNull(tenantId);
        this.legalEntityId = Objects.requireNonNull(legalEntityId);
    }

    @Override
    public ReferenceLookup resolve(ReferenceType type, String tenant, String legalEntity, String key) {
        if (!tenantId.equals(tenant) || !legalEntityId.equals(legalEntity)) {
            return new ReferenceLookup(LookupStatus.OUT_OF_SCOPE, List.of());
        }
        if (type == null || key == null || !key.startsWith("SYNTH-")) {
            return new ReferenceLookup(LookupStatus.NOT_FOUND, List.of());
        }
        String sql = query(type);
        if (sql == null) return new ReferenceLookup(LookupStatus.NOT_FOUND, List.of());
        statements.add(sql);
        List<Candidate> rows = jdbc.query(sql, (rs, n) -> new Candidate(
                rs.getLong("id"), rs.getObject("legal_entity_id") == null ? null : rs.getString("legal_entity_id"),
                rs.getString("external_key"), rs.getInt("deleted_flag") == 0,
                rs.getBoolean("active_flag"), rs.getString("version_value"),
                rs.getString("relation_key")), key);
        if (rows.size() > 1) return new ReferenceLookup(LookupStatus.AMBIGUOUS, List.of());
        if (rows.isEmpty()) return ReferenceLookup.notFound();
        Candidate row = rows.get(0);
        if (row.legalEntityId == null || !legalEntityId.equals(row.legalEntityId)) {
            return new ReferenceLookup(LookupStatus.OUT_OF_SCOPE, List.of());
        }
        if (!row.active) {
            return new ReferenceLookup(row.notDeleted ? LookupStatus.OUT_OF_SCOPE : LookupStatus.LOGICALLY_DELETED,
                    List.of());
        }
        Map<String, String> relations = switch (type) {
            case PROJECT -> Map.of("customerExternalKey", nullToEmpty(row.relationKey));
            case POSITION -> Map.of("projectExternalKey", nullToEmpty(row.relationKey));
            default -> Map.of();
        };
        ReferenceTarget target = new ReferenceTarget(Long.toString(row.id), tenantId, legalEntityId,
                row.externalKey, row.version, true, relations);
        return new ReferenceLookup(LookupStatus.UNIQUE_ACTIVE, List.of(target));
    }

    List<String> statements() { return List.copyOf(statements); }

    private static String query(ReferenceType type) {
        return switch (type) {
            case CUSTOMER -> "SELECT id, legal_entity_id, company_name AS external_key, deleted_flag, "
                    + "(deleted_flag=0) AS active_flag, CAST(NULL AS CHAR) AS version_value, "
                    + "CAST(NULL AS CHAR) AS relation_key FROM m_customer WHERE BINARY(company_name)=BINARY(?)";
            case ENGINEER -> "SELECT id, legal_entity_id, full_name AS external_key, deleted_flag, "
                    + "(deleted_flag=0) AS active_flag, CAST(NULL AS CHAR) AS version_value, "
                    + "CAST(NULL AS CHAR) AS relation_key FROM t_engineer WHERE BINARY(full_name)=BINARY(?)";
            case PROJECT -> "SELECT p.id, p.legal_entity_id, p.project_name AS external_key, p.deleted_flag, "
                    + "(p.deleted_flag=0) AS active_flag, CAST(NULL AS CHAR) AS version_value, "
                    + "c.company_name AS relation_key FROM t_project p JOIN m_customer c ON c.id=p.customer_id "
                    + "WHERE BINARY(p.project_name)=BINARY(?)";
            case POSITION -> "SELECT pp.id, p.legal_entity_id, pp.role_name AS external_key, pp.deleted_flag, "
                    + "(pp.deleted_flag=0 AND p.deleted_flag=0) AS active_flag, CAST(pp.version AS CHAR) AS version_value, "
                    + "p.project_name AS relation_key FROM t_project_position pp JOIN t_project p ON p.id=pp.project_id "
                    + "WHERE BINARY(pp.role_name)=BINARY(?)";
            case ORGANIZATION -> "SELECT id, legal_entity_id, code AS external_key, deleted_flag, "
                    + "(deleted_flag=0 AND status='有効' AND valid_from<=CURRENT_DATE "
                    + "AND (valid_to IS NULL OR valid_to>=CURRENT_DATE)) AS active_flag, CAST(version AS CHAR) AS version_value, "
                    + "CAST(NULL AS CHAR) AS relation_key FROM m_organization_unit "
                    + "WHERE tenant_id IS NULL AND BINARY(code)=BINARY(?)";
            case COST_CENTER -> "SELECT id, legal_entity_id, code AS external_key, deleted_flag, "
                    + "(deleted_flag=0 AND status='有効' AND valid_from<=CURRENT_DATE "
                    + "AND (valid_to IS NULL OR valid_to>=CURRENT_DATE)) AS active_flag, CAST(version AS CHAR) AS version_value, "
                    + "CAST(NULL AS CHAR) AS relation_key FROM m_cost_center WHERE BINARY(code)=BINARY(?)";
        };
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private record Candidate(long id, String legalEntityId, String externalKey, boolean notDeleted,
                             boolean active, String version, String relationKey) { }
}
