package com.ses.privacy.production.provider;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F2 provider の read-only 性と列 allow-list を構造で固定する（production-bridge-phased-plan.md §3.2 の完了条件）。
 *
 * <ul>
 *   <li>{@code com.ses} 配下（main）の {@link ProductionPrivacyProvider} 実装を classpath から全て列挙し、本 test の
 *       一覧・列 allow-list の固定と一致させる（新しい provider は本 test の更新と Review を要する。P2-2）</li>
 *   <li>列挙した全実装の DB 接続口は {@link PrivacyReadOnlyQuery} だけ（mapper・JdbcTemplate・DataSource・HTTP client を持たない）</li>
 *   <li>{@link PrivacyReadOnlyQuery} は {@code select} 以外を公開せず、read-only transaction で実行し、実行は {@code query} だけ</li>
 *   <li>実行する SQL は SELECT で、SELECT 句は allow-list と完全一致し、集合演算を含まず、WHERE の副問合せは
 *       絞り込みのキー列を1つだけ選ぶ（{@code UNION} 等で allow-list 外の列を返す経路を作らない。P2-1）</li>
 * </ul>
 */
class ProductionPrivacyProviderReadOnlyStructureTest {

    private static final Map<String, Map<String, List<String>>> EXPECTED_ALLOW_LISTS = expectedAllowLists();

    /** WHERE の副問合せが選んでよいキー列。 */
    private static final Set<String> SUBQUERY_KEY_COLUMNS = Set.of("id", "run_id", "document_id", "private_note_ref",
            "invoice_id", "order_id", "xml_document_id");

    private static final Pattern SUBQUERY = Pattern.compile("(?i)\\bselect\\s+([a-z])\\.([a-z_]+)\\s+from\\b");

    private final List<String> calls = new ArrayList<>();

    @Test
    void classpath上の全provider実装は固定した一覧と一致する() throws Exception {
        Set<String> scanned = mainProviderClasses().stream().map(Class::getName).collect(Collectors.toCollection(TreeSet::new));
        Set<String> listed = providers(recordingQuery()).stream().map(p -> p.getClass().getName())
                .collect(Collectors.toCollection(TreeSet::new));

        assertThat(scanned).as("main の ProductionPrivacyProvider 実装").isEqualTo(listed);
        assertThat(providers(recordingQuery())).extracting(ProductionPrivacyProvider::providerId)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_ALLOW_LISTS.keySet());
    }

    @Test
    void 各providerの列allow_listは固定された内容と一致する() {
        Map<String, Map<String, List<String>>> actual = new LinkedHashMap<>();
        for (ProductionPrivacyProvider provider : providers(recordingQuery())) {
            actual.put(provider.providerId(), provider.columnAllowList());
        }
        assertThat(actual).isEqualTo(EXPECTED_ALLOW_LISTS);
    }

    @Test
    void classpath上の全providerはPrivacyReadOnlyQuery以外のDB部品や外部client部品を持たない() throws Exception {
        for (Class<?> type : mainProviderClasses()) {
            assertThat(fieldViolations(type)).as(type.getName()).isEmpty();
        }
        for (Field field : PrivacyReadOnlyQuery.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertThat(field.getType()).as(field.toString()).isEqualTo(NamedParameterJdbcOperations.class);
            }
        }
    }

    @Test
    void JdbcTemplateやmapperを持つproviderは構造検査で検出される() {
        assertThat(fieldViolations(LeakyJdbcProvider.class)).isNotEmpty();
        assertThat(fieldViolations(LeakyMapperProvider.class)).isNotEmpty();
        assertThat(fieldViolations(EngineerProfileProvider.class)).isEmpty();
    }

    @Test
    void PrivacyReadOnlyQueryはselectだけを公開しread_only_transactionで実行する() throws Exception {
        Set<String> publicMethods = Arrays.stream(PrivacyReadOnlyQuery.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .collect(Collectors.toSet());
        assertThat(publicMethods).containsExactly("select");
        Method select = PrivacyReadOnlyQuery.class.getMethod("select", ProviderTable.class, String.class, Map.class);
        Transactional transactional = select.getAnnotation(Transactional.class);
        assertThat(transactional).isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }

    @Test
    void 全providerの照会はqueryだけでSELECT句はallow_listと一致し集合演算と複数列の副問合せを持たない() {
        PrivacyReadOnlyQuery query = recordingQuery();
        for (ProductionPrivacyProvider provider : providers(query)) {
            for (PrivacySubjectType type : PrivacySubjectType.values()) {
                assertThat(provider.findBySubject("nf07-f2-a", PrivacySubjectRef.of(type, 7L))).isEmpty();
                provider.resolveStoredNames("nf07-f2-a", PrivacySubjectRef.of(type, 7L));
            }
        }
        assertRecordedSql(query);
    }

    /**
     * 各照会に合成の1行を返し、前の結果に依存する照会（AI の parameter_hash 照合など）も実行させて同じ検査をする
     * （空の結果だけでは後続の SQL が記録されない。NF07-F2 delta review P3-2）。
     */
    @Test
    void 合成の行を返しても全providerの後続の照会まで同じ規則を満たす() {
        PrivacyReadOnlyQuery query = recordingQuery(true);
        for (ProductionPrivacyProvider provider : providers(query)) {
            for (PrivacySubjectType type : PrivacySubjectType.values()) {
                provider.findBySubject("nf07-f2-a", PrivacySubjectRef.of(type, 7L));
            }
        }
        assertThat(calls).as("AI の要員→案件の照合（hash 照会）まで実行されること")
                .anyMatch(call -> call.contains("parameter_hash IN"));
        assertRecordedSql(query);
    }

    private void assertRecordedSql(PrivacyReadOnlyQuery query) {
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
            String where = sql.substring(sql.indexOf(" WHERE ") + " WHERE ".length());
            assertThat(where.toUpperCase()).as(sql).doesNotContain("UNION").doesNotContain("INTERSECT")
                    .doesNotContain("EXCEPT");
            int selects = where.toUpperCase().split("\\bSELECT\\b", -1).length - 1;
            Matcher subquery = SUBQUERY.matcher(where);
            int keyed = 0;
            while (subquery.find()) {
                assertThat(SUBQUERY_KEY_COLUMNS).as(sql).contains(subquery.group(2));
                keyed++;
            }
            assertThat(keyed).as("副問合せは全てキー列1つの形: " + sql).isEqualTo(selects);
        }
    }

    @Test
    void WHERE句は文の区切りと書込みとlockと集合演算と複数列の副問合せを拒否し列名の一部は通す() {
        ProviderTable table = EngineerProfileProvider.ENGINEER;
        for (String where : List.of("id = 1; DELETE FROM t_engineer", "id = 1 -- x", "id IN (SELECT 1) FOR UPDATE",
                "id = 1 UNION SELECT 1 INTO OUTFILE 'x'", "id = (UPDATE t_engineer SET id = 1)", "id = 1 /* x */",
                "id = 1 LOCK IN SHARE MODE", " ", "id = 1 UNION SELECT password FROM sys_user",
                "id = 1 union all select u.password from sys_user u", "id IN (SELECT a.id FROM t_a a INTERSECT SELECT b.id FROM t_b b)",
                "id IN (SELECT a.id FROM t_a a EXCEPT SELECT b.id FROM t_b b)", "GET_LOCK('x', 1) = 1",
                "RELEASE_LOCK('x') = 1", "id IN (SELECT a.id, a.password FROM sys_user a)",
                "id IN (SELECT password FROM sys_user)", "id IN (SELECT a.id FROM (SELECT * FROM sys_user) a)",
                "id IN (SELECT u.password FROM sys_user u)", "id IN (SELECT k.engineer_id FROM t_contract k)",
                "id IN (TABLE sys_user)", "id IN (VALUES ROW(1))", "EXTRACTVALUE(1, 'x') = 1",
                "UPDATEXML(1, 'x', 'y') = 1", "SOURCE_POS_WAIT('f', 1) = 0", "MASTER_POS_WAIT('f', 1) = 0",
                "WAIT_FOR_EXECUTED_GTID_SET('x') = 0", "WAIT_UNTIL_SQL_THREAD_AFTER_GTIDS('x') = 0")) {
            assertThatThrownBy(() -> PrivacyReadOnlyQuery.selectSql(table, where)).as(where)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(PrivacyReadOnlyQuery.selectSql(table, "updated_at IS NOT NULL AND deleted_flag = 0 AND created_at > :t "
                + "AND id IN (SELECT k.id FROM t_contract k WHERE k.engineer_id = :c)"))
                .startsWith("SELECT id, tenant_id, full_name");
        assertThat(PrivacyReadOnlyQuery.SUBQUERY_KEY_COLUMNS).isEqualTo(SUBQUERY_KEY_COLUMNS);
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
                "ENGINEER:-1", "ENGINEER:1:2", "ENGINEER:1a", "UNKNOWN:1", "ENGINEER:１２３", "ENGINEER:\u0661\u0662\u0663",
                "ENGINEER:1234567890123456789", "ENGINEER: 1")) {
            assertThat(PrivacySubjectRef.parse(invalid)).as(String.valueOf(invalid)).isEmpty();
        }
        assertThatThrownBy(() -> PrivacySubjectRef.of(PrivacySubjectType.ENGINEER, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- helper

    /** main（test-classes 以外）にある {@link ProductionPrivacyProvider} の具象実装。 */
    static List<Class<?>> mainProviderClasses() throws Exception {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(ProductionPrivacyProvider.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition candidate : scanner.findCandidateComponents("com.ses")) {
            Class<?> type = Class.forName(candidate.getBeanClassName());
            String location = type.getProtectionDomain().getCodeSource().getLocation().toString();
            if (!location.contains("test-classes")) {
                classes.add(type);
            }
        }
        assertThat(classes).as("main の provider 実装を検出できること").isNotEmpty();
        return classes;
    }

    /**
     * DB 接続口の規則に反する field（instance field は PrivacyReadOnlyQuery だけ、static は定数だけ）。
     * 親 class の field も Object の手前まで遡って検査する（P3-3）。
     */
    static List<String> fieldViolations(Class<?> type) {
        List<String> violations = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.isSynthetic()) {
                    continue;
                }
                Class<?> fieldType = field.getType();
                boolean allowed = Modifier.isStatic(field.getModifiers())
                        ? fieldType.isPrimitive() || Set.of(String.class, ProviderTable.class, List.class, Map.class)
                        .contains(fieldType)
                        : fieldType == PrivacyReadOnlyQuery.class;
                if (!allowed) {
                    violations.add(field.toString());
                }
            }
        }
        return violations;
    }

    /** query だけを記録して空を返し、それ以外の操作は失敗させる記録用の接続口。 */
    private PrivacyReadOnlyQuery recordingQuery() {
        return recordingQuery(false);
    }

    /**
     * query だけを記録し、それ以外の操作は失敗させる記録用の接続口。{@code syntheticRow} なら各照会に SELECT 句の列から
     * 作った合成の1行を返す（ID 系は 1、対象種別は PROJECT、文書種別は CONTRACT、論理削除は 0、他は文字列）。
     */
    private PrivacyReadOnlyQuery recordingQuery(boolean syntheticRow) {
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
                        return syntheticRow ? List.of(syntheticRow(sql)) : List.of();
                    }
                    throw new AssertionError("SELECT以外の操作は許可しない: " + method);
                });
        return new PrivacyReadOnlyQuery(operations);
    }

    private static Map<String, Object> syntheticRow(String sql) {
        Map<String, Object> row = new LinkedHashMap<>();
        String selectList = sql.substring("SELECT ".length(), sql.indexOf(" FROM "));
        for (String column : selectList.split(", ")) {
            Object value = switch (column) {
                case "target_type" -> "PROJECT";
                case "document_type" -> "CONTRACT";
                case "deleted_flag" -> 0;
                case "rank_no" -> 1L;
                default -> column.equals("id") || column.endsWith("_id") ? (Object) 1L : "x";
            };
            row.put(column, value);
        }
        return row;
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
        expected.put("ai-log", Map.of(
                "t_ai_recommendation_run", List.of("id", "tenant_id", "trace_id", "use_case", "redacted_summary_json",
                        "status", "created_at", "updated_at", "deleted_flag"),
                "t_ai_recommendation_item", List.of("id", "tenant_id", "run_id", "rank_no", "target_type", "target_id")));
        return expected;
    }

    /** 構造検査の検出確認用: PrivacyReadOnlyQuery を通さず JdbcTemplate を持つ provider（登録しない）。 */
    static final class LeakyJdbcProvider {
        @SuppressWarnings("unused")
        private JdbcTemplate jdbcTemplate;
    }

    /** 構造検査の検出確認用: mapper を持つ provider（登録しない）。 */
    static final class LeakyMapperProvider {
        @SuppressWarnings("unused")
        private com.ses.mapper.SysUserMapper sysUserMapper;
    }
}
