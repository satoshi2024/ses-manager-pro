package com.ses.migration;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * F1-MAP-01（design §6.2）:
 * field-mapping.md §3.5の96 stable row ID（FM-C-01〜FM-L-30）をcanonical schema manifestへ照合し、
 * 全IDのresolution codeが§4定義と一致し、各codeの保存先column/tableがH2 replay schemaに1件ずつ存在することを検証する。
 * technical shape未解決0件が契約。
 */
class F1MapManifestTest {

    /** resolution code → 保存先（table, columns）。field-mapping §4 canonical tableの正本と同一。 */
    private static final Map<String, String[]> RESOLUTION_COLUMNS = new HashMap<>();

    static {
        RESOLUTION_COLUMNS.put("CONTRACT_PARTY_PERIOD_SNAPSHOT", new String[]{
                "t_contract_compliance_snapshot", "contract_no", "contract_date", "party_name", "party_address",
                "party_representative", "dispatch_from", "dispatch_to"});
        RESOLUTION_COLUMNS.put("WORKPLACE_ORG_SNAPSHOT", new String[]{
                "t_contract_compliance_snapshot", "workplace_name", "workplace_address", "workplace_department",
                "workplace_phone", "organization_unit", "organization_head_title"});
        RESOLUTION_COLUMNS.put("WORK_DESCRIPTION_TYPED", new String[]{
                "t_contract_compliance_snapshot", "work_description", "statutory_job_flag", "statutory_job_reference"});
        RESOLUTION_COLUMNS.put("RESPONSIBILITY_TYPED", new String[]{
                "t_contract_compliance_snapshot", "responsibility_level", "responsibility_detail",
                "command_person_name", "client_responsible_name", "dispatch_responsible_name"});
        RESOLUTION_COLUMNS.put("SAFETY_TYPED", new String[]{
                "t_contract_compliance_snapshot", "safety_responsibility_detail", "safety_rule_reference"});
        RESOLUTION_COLUMNS.put("WORK_TIME_TYPED", new String[]{
                "t_contract_compliance_snapshot", "work_start_minute", "work_end_minute", "work_span_next_day_flag",
                "break_start_minute", "break_end_minute"});
        RESOLUTION_COLUMNS.put("WORK_CALENDAR_HISTORY", new String[]{
                "t_compliance_work_calendar", "work_day_code", "holiday_calendar_code", "excluded_date"});
        RESOLUTION_COLUMNS.put("OVERTIME_AGREEMENT_SNAPSHOT", new String[]{
                "t_contract_compliance_snapshot", "agreement_reference_id", "overtime_daily_limit",
                "overtime_monthly_limit", "overtime_yearly_limit"});
        RESOLUTION_COLUMNS.put("LIMITATION_DUAL_TYPED", new String[]{
                "t_contract_compliance_snapshot", "workplace_limitation_date", "organization_limitation_date"});
        RESOLUTION_COLUMNS.put("COMPLAINT_HISTORY", new String[]{
                "t_compliance_complaint_history", "received_at", "content", "action", "resolution", "notified_at"});
        RESOLUTION_COLUMNS.put("BENEFITS_TYPED", new String[]{
                "t_contract_compliance_snapshot", "benefits_detail", "benefits_provided_flag"});
        RESOLUTION_COLUMNS.put("HEADCOUNT_TYPED", new String[]{
                "t_contract_compliance_snapshot", "dispatch_headcount"});
        RESOLUTION_COLUMNS.put("AGREEMENT_FLAG_TYPED", new String[]{
                "t_contract_compliance_snapshot", "agreement_target_flag", "treatment_scheme"});
        RESOLUTION_COLUMNS.put("WORKER_EMPLOYMENT_RESTRICTION_SNAPSHOT", new String[]{
                "t_contract_compliance_worker_snapshot", "employment_term_type", "employment_from", "employment_to",
                "indefinite_worker_flag", "age_over_60_flag", "worker_restriction_type"});
        RESOLUTION_COLUMNS.put("EMPLOYMENT_STABILITY_HISTORY", new String[]{
                "t_employment_stability_history", "request_at", "request_method", "response_at", "response_content",
                "action", "outcome"});
        RESOLUTION_COLUMNS.put("DIRECT_HIRE_DISPUTE_HISTORY", new String[]{
                "t_direct_hire_dispute_history", "measure", "fee_detail", "request_method"});
        RESOLUTION_COLUMNS.put("LIMITATION_EXEMPTION_TYPED", new String[]{
                "t_contract_compliance_snapshot", "limitation_exemption_type", "limitation_exemption_detail",
                "limitation_exemption_basis", "limitation_exemption_from", "limitation_exemption_to"});
        RESOLUTION_COLUMNS.put("PLANNED_INTRODUCTION_HISTORY", new String[]{
                "t_planned_introduction_history", "introduction_date", "outcome", "reason"});
        RESOLUTION_COLUMNS.put("PLANNED_INTRODUCTION_TERMS", new String[]{
                "t_planned_introduction_terms", "contract_period_from", "contract_period_to", "renewal_rule",
                "work_change_scope", "trial_period", "wage_detail", "insurance_detail", "smoking_measure",
                "employer_name"});
        RESOLUTION_COLUMNS.put("DISPATCH_FEE_TYPED", new String[]{
                "t_contract_compliance_snapshot", "dispatch_fee_amount", "dispatch_fee_basis", "dispatch_fee_currency"});
        RESOLUTION_COLUMNS.put("INSURANCE_TYPED", new String[]{
                "t_contract_compliance_snapshot", "social_insurance_procedure_incomplete_reason",
                "health_insurance_status", "health_insurance_missing_reason", "health_insurance_expected_date",
                "pension_insurance_status", "employment_insurance_status"});
        RESOLUTION_COLUMNS.put("NOTIFICATION_DIFFERENCE_HISTORY", new String[]{
                "t_notification_difference_history", "difference_type", "contract_snapshot_id", "notice_snapshot_id",
                "difference_detail"});
        RESOLUTION_COLUMNS.put("DOCUMENT_DELIVERY", new String[]{
                "t_document_delivery", "document_type", "template_version", "effective_from", "effective_to",
                "snapshot_hash", "recipient_contact_id", "delivered_at", "confirmed_at"});
        RESOLUTION_COLUMNS.put("WORKER_PII_SNAPSHOT", new String[]{
                "t_contract_compliance_worker_snapshot", "worker_name", "employer_name", "employer_address",
                "employer_title", "gender", "age_band"});
        RESOLUTION_COLUMNS.put("LEDGER_WORK_HISTORY", new String[]{
                "t_ledger_work_snapshot", "work_month", "work_days", "work_hours", "overtime_hours", "absence_days",
                "closed_at"});
        RESOLUTION_COLUMNS.put("TRAINING_HISTORY", new String[]{
                "t_training_history", "training_date", "minutes", "content"});
        RESOLUTION_COLUMNS.put("CAREER_HISTORY", new String[]{
                "t_career_consulting_history", "consulting_date", "content"});
        RESOLUTION_COLUMNS.put("RETENTION_METADATA", new String[]{
                "t_contract_compliance_snapshot", "retention_due_date", "legal_hold_flag"});
    }

    @Test
    void 全96stableIDがcanonicalmanifestへ1件ずつ解決しschemaに存在する() throws Exception {
        Path mapping = Path.of(".kiro", "specs", "dispatch-outsourcing-compliance-ledger", "field-mapping.md");
        assertTrue(Files.exists(mapping), "field-mapping.mdが必要です: " + mapping.toAbsolutePath());
        String content = Files.readString(mapping, StandardCharsets.UTF_8);

        Map<String, String> idToCode = parseStableManifest(content);
        assertEquals(96, idToCode.size(), "96 stable row IDが必要です");
        assertEquals(96, new HashSet<>(idToCode.keySet()).size(), "stable IDの重複は禁止");

        Set<String> section4Codes = parseSection4Codes(content);
        for (String code : new HashSet<>(idToCode.values())) {
            assertTrue(section4Codes.contains(code), "§4 canonical tableに未定義のcode: " + code);
            assertTrue(RESOLUTION_COLUMNS.containsKey(code),
                    "test manifestに未定義のcode: " + code + "（§3.5と§4を同期すること）");
        }
        for (String code : RESOLUTION_COLUMNS.keySet()) {
            assertTrue(section4Codes.contains(code), "§4に無いcodeがtest manifestにある: " + code);
        }

        try (Connection connection = DriverManager.getConnection(
                "jdbc:h2:mem:f1_map_01;MODE=MySQL;DB_CLOSE_DELAY=-1")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE t_contract (id BIGINT PRIMARY KEY)");
            }
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("sql/schema-dispatch-compliance-h2.sql"));
            try (Statement statement = connection.createStatement()) {
                for (Map.Entry<String, String[]> entry : RESOLUTION_COLUMNS.entrySet()) {
                    String table = entry.getValue()[0];
                    assertTableExists(statement, table, entry.getKey());
                    for (int i = 1; i < entry.getValue().length; i++) {
                        assertColumnExists(statement, table, entry.getValue()[i], entry.getKey());
                    }
                }
                // WORK_TIME_TYPEDの複数休憩は反復detail（t_compliance_break_detail）へ分解する（R8-P0-01）
                assertTableExists(statement, "t_compliance_break_detail", "WORK_TIME_TYPED");
                for (String column : new String[]{"break_no", "start_offset_minute", "end_offset_minute",
                        "event_id", "event_type", "supersedes_event_id", "correction_reason"}) {
                    assertColumnExists(statement, "t_compliance_break_detail", column, "WORK_TIME_TYPED");
                }
            }
        }
        // 96 IDすべてが未解決0件（全IDが§4定義codeへ割り当て済み）
        for (Map.Entry<String, String> entry : idToCode.entrySet()) {
            assertTrue(RESOLUTION_COLUMNS.containsKey(entry.getValue()),
                    entry.getKey() + " のresolution codeがschemaへ解決できない: " + entry.getValue());
        }
    }

    private Map<String, String> parseStableManifest(String content) {
        Map<String, String> result = new HashMap<>();
        boolean inManifest = false;
        for (String line : content.split("\\R")) {
            if (line.startsWith("### 3.5")) {
                inManifest = true;
                continue;
            }
            if (inManifest && line.startsWith("## 4.")) {
                break;
            }
            if (inManifest) {
                Matcher matcher = Pattern.compile("^\\| (FM-[CENL]-\\d{2}) \\| (SRC-[CENL]) \\| .* \\| ([A-Z_]+) \\|$").matcher(line);
                if (matcher.matches()) {
                    result.put(matcher.group(1), matcher.group(3));
                }
            }
        }
        return result;
    }

    private Set<String> parseSection4Codes(String content) {
        Set<String> result = new HashSet<>();
        boolean inSection4 = false;
        for (String line : content.split("\\R")) {
            if (line.startsWith("## 4. F1 schema resolution")) {
                inSection4 = true;
                continue;
            }
            if (inSection4 && line.startsWith("### ")) {
                break;
            }
            if (inSection4) {
                Matcher matcher = Pattern.compile("^\\| ([A-Z_]+) \\|.*").matcher(line);
                if (matcher.matches()) {
                    result.add(matcher.group(1));
                }
            }
        }
        return result;
    }

    private void assertTableExists(Statement statement, String table, String code) throws Exception {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE UPPER(table_name)=UPPER('" + table + "')"),
                code + " の保存先tableがschemaにない: " + table);
    }

    private void assertColumnExists(Statement statement, String table, String column, String code) throws Exception {
        assertEquals(1, queryInt(statement, "SELECT COUNT(*) FROM information_schema.columns "
                        + "WHERE UPPER(table_name)=UPPER('" + table + "') AND UPPER(column_name)=UPPER('" + column + "')"),
                code + " の保存先columnがschemaにない: " + table + "." + column);
    }

    private int queryInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }
}
