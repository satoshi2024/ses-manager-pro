package com.ses.service.compliance;

import com.ses.entity.ComplianceMappingReviewRequirementGroup;
import com.ses.entity.ComplianceMappingReviewRequirementType;
import com.ses.entity.ComplianceMappingSource;
import com.ses.entity.ComplianceMappingVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * G2 gate canonicalizer（decision delta §6.2・field-mapping §8）。
 * mapping_hash = canonical payloadのSHA-256（64 lowercase hex）。
 * canonical payloadの構成（§6.2）:
 *  - mapping code/version/effective period（m_compliance_mapping_version）
 *  - 96 stable row IDごとのsource ID/field semantics/canonical resolution（mapping-manifest.csv・specの96行manifestのmirror）
 *  - source code/URL/version/confirmed_on/effective period（m_compliance_mapping_source）
 * 含めない: review policy、status、actor、UI表示順。
 * 再計算はDBから取得したrowで行い、保存hashと比較する（client supplied hashは信頼しない）。
 */
@Component
public class ComplianceMappingCanonicalizer {

    /** 96 stable row manifest（field-mapping.md §3.5のmirror。rowId,sourceId,fieldSemantics,resolution）。 */
    static final List<ManifestRow> MANIFEST = loadManifest();

    public record ManifestRow(String rowId, String sourceId, String fieldSemantics, String resolution) {
    }

    /** §6.2のcanonical payloadからmapping_hash（SHA-256・64 hex）を計算する。 */
    public String computeMappingHash(ComplianceMappingVersion version, List<ComplianceMappingSource> sources) {
        StringBuilder payload = new StringBuilder();
        // 1. mapping version block（status/actor/UI表示順は含めない）
        payload.append("mapping_code=").append(nvl(version.getMappingCode())).append('\n');
        payload.append("mapping_version=").append(nvl(version.getMappingVersion())).append('\n');
        payload.append("effective_from=").append(nvl(version.getEffectiveFrom())).append('\n');
        payload.append("effective_to=").append(nvl(version.getEffectiveTo())).append('\n');
        // 2. 96 stable row block（rowId昇順）
        List<ManifestRow> sortedRows = new ArrayList<>(MANIFEST);
        sortedRows.sort(Comparator.comparing(ManifestRow::rowId));
        for (ManifestRow row : sortedRows) {
            payload.append("row=").append(row.rowId()).append('|').append(row.sourceId()).append('|')
                    .append(row.fieldSemantics()).append('|').append(row.resolution()).append('\n');
        }
        // 3. source block（sourceCode昇順）
        List<ComplianceMappingSource> sortedSources = new ArrayList<>(sources);
        sortedSources.sort(Comparator.comparing(ComplianceMappingSource::getSourceCode,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (ComplianceMappingSource source : sortedSources) {
            payload.append("source=").append(nvl(source.getSourceCode())).append('|')
                    .append(nvl(source.getSourceUrl())).append('|')
                    .append(nvl(source.getSourceVersion())).append('|')
                    .append(nvl(source.getConfirmedOn())).append('|')
                    .append(nvl(source.getEffectiveFrom())).append('|')
                    .append(nvl(source.getEffectiveTo())).append('\n');
        }
        return sha256(payload.toString());
    }

    /** §6.3のcanonical payloadからreview_policy_hash（SHA-256・64 hex）を計算する。 */
    public String computeReviewPolicyHash(List<ComplianceMappingReviewRequirementGroup> groups,
                                          List<ComplianceMappingReviewRequirementType> types) {
        StringBuilder payload = new StringBuilder();
        java.util.Map<Long, String> groupCodeMap = new java.util.HashMap<>();
        List<ComplianceMappingReviewRequirementGroup> sortedGroups = new ArrayList<>(groups);
        sortedGroups.sort(Comparator.comparing(ComplianceMappingReviewRequirementGroup::getRequirementGroupCode,
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (ComplianceMappingReviewRequirementGroup group : sortedGroups) {
            if (group.getId() != null) {
                groupCodeMap.put(group.getId(), group.getRequirementGroupCode());
            }
            payload.append("group=").append(nvl(group.getRequirementGroupCode())).append('|')
                    .append(nvl(group.getMinimumDistinctReviewers())).append('\n');
        }
        List<ComplianceMappingReviewRequirementType> sortedTypes = new ArrayList<>(types);
        sortedTypes.sort(Comparator.comparing((ComplianceMappingReviewRequirementType t) -> groupCodeMap.getOrDefault(t.getRequirementGroupId(), ""),
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ComplianceMappingReviewRequirementType::getReviewerTypeCodeSnapshot,
                        Comparator.nullsLast(Comparator.naturalOrder())));
        for (ComplianceMappingReviewRequirementType type : sortedTypes) {
            String groupCode = groupCodeMap.getOrDefault(type.getRequirementGroupId(), "∅");
            payload.append("type=").append(nvl(groupCode)).append('|')
                    .append(nvl(type.getReviewerTypeCodeSnapshot())).append('|')
                    .append(nvl(type.getReviewerTypeNameSnapshot())).append('|')
                    .append(nvl(type.getCredentialLabelSnapshot())).append('|')
                    .append(nvl(type.getCredentialRequiredSnapshot())).append('\n');
        }
        return sha256(payload.toString());
    }

    public static List<ManifestRow> loadManifest() {
        try {
            String csv = new String(new ClassPathResource("compliance/mapping-manifest.csv")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<ManifestRow> rows = new ArrayList<>();
            for (String line : csv.split("\r?\n")) {
                if (line.isBlank() || line.startsWith("row_id,")) {
                    continue;
                }
                String[] fields = parseCsv(line);
                if (fields.length >= 4) {
                    rows.add(new ManifestRow(fields[0], fields[1], fields[2], fields[3]));
                }
            }
            if (rows.isEmpty()) {
                throw new IllegalStateException("mapping manifestが空です");
            }
            return List.copyOf(rows);
        } catch (Exception e) {
            throw new IllegalStateException("mapping manifestの読込に失敗しました", e);
        }
    }

    private static String[] parseCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else if (ch == '"') {
                    inQuotes = false;
                } else {
                    current.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    private static String nvl(Object value) {
        return value == null ? "∅" : String.valueOf(value);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("hash計算に失敗しました", e);
        }
    }
}
