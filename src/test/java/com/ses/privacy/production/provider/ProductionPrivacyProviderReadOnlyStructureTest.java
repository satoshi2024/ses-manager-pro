package com.ses.privacy.production.provider;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F2 provider の read-only 性と列 allow-list を構造で固定する（production-bridge-phased-plan.md §3.2 の完了条件）。
 *
 * <ul>
 *   <li>provider が持つ DB 接続口は {@link PrivacyReadOnlyQuery} だけ（mapper・JdbcTemplate・DataSource・HTTP client を持たない）</li>
 *   <li>{@link PrivacyReadOnlyQuery} は {@code select} 以外を公開せず、実行は {@code query} だけ（記録用 proxy で確認）</li>
 *   <li>実行する SQL は SELECT で、SELECT 句は allow-list と完全一致する（{@code SELECT *} を使わない）</li>
 *   <li>列 allow-list の内容を固定する（列を足す変更は本 test の更新と Review を要する）</li>
 * </ul>
 */
class ProductionPrivacyProviderReadOnlyStructureTest {

    private static final Map<String, Map<String, List<String>>> EXPECTED_ALLOW_LISTS = expectedAllowLists();

    private final List<String> calls = new ArrayList<>();

    @Test
    void 各providerの列allow_listは固定された内容と一致する() {
        Map<String, Map<String, List<String>>> actual = new LinkedHashMap<>();
        for (ProductionPrivacyProvider provider : providers(recordingQuery())) {
            actual.put(provider.providerId(), provider.columnAllowList());
        }
        assertThat(actual).isEqualTo(EXPECTED_ALLOW_LISTS);
    }

    @Test
    void providerはPrivacyReadOnlyQuery以外のDB部品や外部client部品を持たない() {
        for (ProductionPrivacyProvider provider : providers(recordingQuery())) {
            for (Field field : provider.getClass().getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                Class<?> type = field.getType();
                if (Modifier.isStatic(field.getModifiers())) {
                    assertThat(Set.of(String.class, ProviderTable.class, List.class)).as(field.toString()).contains(type);
                } else {
                    assertThat(type).as(field.toString()).isEqualTo(PrivacyReadOnlyQuery.class);
                }
            }
        }
        for (Field field : PrivacyReadOnlyQuery.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertThat(field.getType()).as(field.toString()).isEqualTo(NamedParameterJdbcOperations.class);
            }
        }
    }

    @Test
    void PrivacyReadOnlyQueryはselectだけを公開する() {
        Set<String> publicMethods = Arrays.stream(PrivacyReadOnlyQuery.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(publicMethods).containsExactly("select");
    }

    @Test
    void 全providerの照会はqueryだけでSELECT句はallow_listと一致する() {
        PrivacyReadOnlyQuery query = recordingQuery();
        for (ProductionPrivacyProvider provider : providers(query)) {
            for (PrivacySubjectType type : PrivacySubjectType.values()) {
                assertThat(provider.findBySubject("nf07-f2-a", PrivacySubjectRef.of(type, 7L))).isEmpty();
                provider.resolveStoredNames("nf07-f2-a", PrivacySubjectRef.of(type, 7L));
            }
        }
        assertThat(calls).as("providerがDBへ照会すること").isNotEmpty();
        Map<String, ProviderTable> tables = new LinkedHashMap<>();
        for (ProductionPrivacyProvider provider : providers(query)) {
            provider.tables().forEach(table -> tables.put(table.table(), table));
        }
        for (String call : calls) {
            assertThat(call).startsWith("query|SELECT ");
            String sql = call.substring("query|".length());
            String selectList = sql.substring("SELECT ".length(), sql.indexOf(" FROM "));
            String table = sql.substring(sql.indexOf(" FROM ") + " FROM ".length()).split(" ")[0];
            assertThat(tables).as(sql).containsKey(table);
            assertThat(selectList).as(sql).isEqualTo(String.join(", ", tables.get(table).columns()));
            assertThat(selectList).doesNotContain("*");
        }
    }

    @Test
    void WHERE句は文の区切りと書込みとlockの語を拒否し列名の一部は通す() {
        ProviderTable table = EngineerProfileProvider.ENGINEER;
        for (String where : List.of("id = 1; DELETE FROM t_engineer", "id = 1 -- x", "id IN (SELECT 1) FOR UPDATE",
                "id = 1 UNION SELECT 1 INTO OUTFILE 'x'", "id = (UPDATE t_engineer SET id = 1)", "id = 1 /* x */",
                "id = 1 LOCK IN SHARE MODE", " ")) {
            assertThatThrownBy(() -> PrivacyReadOnlyQuery.selectSql(table, where)).as(where)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(PrivacyReadOnlyQuery.selectSql(table, "updated_at IS NOT NULL AND deleted_flag = 0 AND created_at > :t"))
                .startsWith("SELECT id, tenant_id, full_name");
    }

    @Test
    void 表定義は式やワイルドカードの列とidの無いallow_listを拒否する() {
        assertThatThrownBy(() -> new ProviderTable("t_engineer", List.of("*"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderTable("t_engineer", List.of("id", "COUNT(*)"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderTable("t_engineer", List.of("full_name"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderTable("t_engineer", List.of("id"), List.of("photo_url")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProviderTable("t_engineer;", List.of("id"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void subject参照は種別付きの厳密な形式だけを受け付ける() {
        assertThat(PrivacySubjectRef.parse("ENGINEER:123")).contains(PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 123));
        assertThat(PrivacySubjectRef.of(PrivacySubjectType.CANDIDATE, 5).ref()).isEqualTo("CANDIDATE:5");
        for (String invalid : Arrays.asList(null, "", "engineer:1", "ENGINEER:", ":1", "ENGINEER:0", "ENGINEER:01",
                "ENGINEER:-1", "ENGINEER:1:2", "ENGINEER:1a", "UNKNOWN:1")) {
            assertThat(PrivacySubjectRef.parse(invalid)).as(String.valueOf(invalid)).isEmpty();
        }
        assertThatThrownBy(() -> PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- helper

    /** query だけを記録して空を返し、それ以外の操作は失敗させる記録用の接続口。 */
    private PrivacyReadOnlyQuery recordingQuery() {
        NamedParameterJdbcOperations operations = (NamedParameterJdbcOperations) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{NamedParameterJdbcOperations.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> "recording-query";
                        };
                    }
                    String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : "";
                    calls.add(method.getName() + "|" + sql);
                    if ("query".equals(method.getName()) && method.getReturnType() == List.class) {
                        return List.of();
                    }
                    throw new AssertionError("SELECT以外の操作は許可しない: " + method);
                });
        return new PrivacyReadOnlyQuery(operations);
    }

    static List<ProductionPrivacyProvider> providers(PrivacyReadOnlyQuery query) {
        return List.of(new EngineerProfileProvider(query), new CandidateProvider(query), new SysUserProvider(query),
                new DocumentFileProvider(query), new IngestionTextProvider(query), new AiLogProvider(query));
    }

    private static Map<String, Map<String, List<String>>> expectedAllowLists() {
        Map<String, Map<String, List<String>>> expected = new LinkedHashMap<>();
        expected.put("engineer-profile", Map.of("t_engineer", List.of("id", "tenant_id", "full_name", "full_name_kana",
                "initial_name", "gender", "birth_date", "nationality", "nearest_station", "prefecture", "railway_company",
                "phone", "employment_type", "status", "available_date", "experience_years", "japanese_level",
                "resume_summary", "photo_url", "remarks", "created_at", "updated_at", "deleted_flag")));
        expected.put("candidate", Map.of(
                "t_candidate", List.of("id", "tenant_id", "name", "contact_email", "contact_phone", "skill_summary",
                        "desired_rate", "source", "current_stage", "next_action_date", "converted_engineer_id", "remarks",
                        "created_at", "updated_at", "deleted_flag"),
                "t_candidate_activity", List.of("id", "candidate_id", "stage", "reason", "changed_at", "remarks")));
        expected.put("sys-user", Map.of("sys_user", List.of("id", "tenant_id", "username", "real_name", "email", "role",
                "status", "created_at", "updated_at", "deleted_flag")));
        expected.put("document-file", Map.of(
                "t_document", List.of("id", "tenant_id", "document_type", "document_no", "title", "transaction_date",
                        "status", "retention_until", "legal_hold_flag", "created_at", "updated_at", "deleted_flag"),
                "t_document_version", List.of("id", "tenant_id", "document_id", "version_no", "storage_key",
                        "original_name", "content_type", "size_bytes", "sha256", "scan_status", "created_at",
                        "deleted_flag")));
        expected.put("ingestion-text", Map.of(
                "t_resume_ingestion", List.of("id", "tenant_id", "original_file_name", "stored_file_name", "file_ext",
                        "status", "extracted_text", "parsed_json", "review_note", "converted_engineer_id", "candidate_id",
                        "created_at", "updated_at", "deleted_flag"),
                "t_bp_availability_ingestion", List.of("id", "original_file_name", "stored_file_name", "file_ext",
                        "status", "extracted_text", "parsed_json", "review_note", "converted_availability_id",
                        "created_at", "updated_at", "deleted_flag")));
        expected.put("ai-log", Map.of("t_ai_recommendation_run", List.of("id", "tenant_id", "trace_id", "use_case",
                "redacted_summary_json", "status", "created_at", "updated_at", "deleted_flag")));
        return expected;
    }
}
