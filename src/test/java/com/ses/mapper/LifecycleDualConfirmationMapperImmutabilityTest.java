package com.ses.mapper;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.common.mybatis.AppendOnlyMapper;
import com.ses.config.MybatisConfig;
import com.ses.entity.LifecycleDualConfirmation;
import com.ses.entity.LifecycleDualEvent;
import com.ses.entity.LifecycleDualIdempotency;
import com.ses.entity.LifecycleDualProjection;
import com.ses.entity.LifecycleDualSubmission;
import com.ses.entity.LifecycleDualSubmissionDocument;
import com.ses.entity.LifecycleDualSubmissionOrgFence;
import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.core.ResolvableType;
import org.springframework.core.io.ClassPathResource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NF01 B0（W3-18、計画 §5.12・§7.7 Demo(2)）の不変表をDocker無しで固定する静的guard。
 *
 * <ul>
 *   <li>共通基 {@link AppendOnlyMapper} は、{@link BaseMapper} から継承する更新・削除・insertOrUpdateの全メソッドを default で
 *       override する。BaseMapperに更新系メソッドが増えると本testが失敗する。共通基は {@code @MapperScan} の範囲外にある。</li>
 *   <li>不変6表のmapperは共通基を直接継承し、更新系メソッドを再定義しない。MyBatis-Plusの mapper proxy と同じく
 *       「default methodは本体を実行し、それ以外はSQLを実行する」proxyで呼び、{@code BaseMapper<T>} の消去型と共通基の両方の
 *       呼出しがSQLへ到達せず、表名を含む {@link UnsupportedOperationException} になることを確かめる。</li>
 *   <li>V195のtriggerは不変6表の BEFORE UPDATE / BEFORE DELETE に1件ずつ（計12件）で、mapperの不変集合と一致する。
 *       単文の SIGNAL で書き、DELIMITER・BEGIN を使わない（Flywayの文分割とV132と同じ形）。</li>
 *   <li>H2の2つのschema（replayと engineer-schema-h2.sql）が7表と同じCHECKを持ちtriggerを持たないこと、V1が作り直す表へのFKが
 *       replay前のprecleanupで外されること（外さないと2つ目のcontextでV1の DROP TABLE が失敗する）。</li>
 *   <li>不変6表のentityを {@code IService}・{@code ServiceImpl}・{@code Db}・{@code SqlHelper} で包まない（statement IDで
 *       直接実行する経路。{@code AppendOnlySqlInjector} が文を注入しないため実行時にも失敗する）。</li>
 * </ul>
 */
class LifecycleDualConfirmationMapperImmutabilityTest {

    private static final String MIGRATION = "db/migration/V195__nf01_dual_confirmation_coordinator.sql";
    private static final String H2_REPLAY = "sql/schema-nf01-dual-confirmation-h2.sql";
    private static final String H2_ENGINEER = "sql/engineer-schema-h2.sql";
    private static final String H2_PRECLEANUP = "sql/test-schema-import-precleanup-h2.sql";

    private record Table(Class<?> mapper, Class<?> entity, String name) { }

    /** 不変6表（UPDATE・DELETEをmapperとDB triggerで拒否する）。 */
    private static final List<Table> IMMUTABLE = List.of(
            new Table(LifecycleDualSubmissionMapper.class, LifecycleDualSubmission.class, "t_lifecycle_dual_submission"),
            new Table(LifecycleDualSubmissionDocumentMapper.class, LifecycleDualSubmissionDocument.class,
                    "t_lifecycle_dual_submission_document"),
            new Table(LifecycleDualSubmissionOrgFenceMapper.class, LifecycleDualSubmissionOrgFence.class,
                    "t_lifecycle_dual_submission_org_fence"),
            new Table(LifecycleDualConfirmationMapper.class, LifecycleDualConfirmation.class, "t_lifecycle_dual_confirmation"),
            new Table(LifecycleDualIdempotencyMapper.class, LifecycleDualIdempotency.class, "t_lifecycle_dual_idempotency"),
            new Table(LifecycleDualEventMapper.class, LifecycleDualEvent.class, "t_lifecycle_dual_event"));

    /** 唯一の可変表（projection_version のCASで更新する）。 */
    private static final Table PROJECTION = new Table(LifecycleDualProjectionMapper.class, LifecycleDualProjection.class,
            "t_lifecycle_dual_projection");

    /** 主キーが複合keyで {@code @TableId} を持たない表。 */
    private static final Set<String> COMPOSITE_KEY_TABLES = Set.of("t_lifecycle_dual_submission_document",
            "t_lifecycle_dual_submission_org_fence", "t_lifecycle_dual_projection");

    @Test
    void 共通基はBaseMapperの更新削除系の全メソッドをdefaultでoverrideしMapperScanの範囲外にある() {
        List<Method> modifying = modifyingBaseMapperMethods();
        assertThat(modifying).extracting(Method::getName)
                .as("BaseMapperの更新系メソッドを読めていること")
                .contains("updateById", "update", "deleteById", "delete", "deleteByMap", "deleteBatchIds", "deleteByIds",
                        "insertOrUpdate");
        List<String> missing = new ArrayList<>();
        for (Method base : modifying) {
            Method override = findOverride(AppendOnlyMapper.class, base);
            if (override == null || !override.isDefault()) {
                missing.add(signature(base));
            }
        }
        assertThat(missing).as("AppendOnlyMapperにdefaultのoverrideが無いBaseMapperの更新系メソッド").isEmpty();

        assertThat(AppendOnlyMapper.class.isAnnotationPresent(Mapper.class)).isFalse();
        String[] scanned = MybatisConfig.class.getAnnotation(MapperScan.class).value();
        assertThat(scanned).isNotEmpty();
        for (String basePackage : scanned) {
            assertThat(AppendOnlyMapper.class.getPackageName())
                    .as("共通基は @MapperScan(%s) の範囲外に置く（範囲内ではentityの無いmapper beanになる）", basePackage)
                    .isNotEqualTo(basePackage).doesNotStartWith(basePackage + ".");
        }
    }

    @Test
    void 不変6表のmapperは共通基を直接継承し更新削除系を再定義せずproxy経由の呼出しを拒否する() {
        Set<String> modifyingNames = modifyingBaseMapperMethods().stream().map(Method::getName)
                .collect(Collectors.toCollection(TreeSet::new));
        List<String> violations = new ArrayList<>();
        for (Table table : IMMUTABLE) {
            Class<?> mapper = table.mapper();
            assertThat(mapper.isAnnotationPresent(Mapper.class)).as(mapper.getSimpleName()).isTrue();
            assertThat(mapper.getInterfaces()).as("%s は AppendOnlyMapper を直接継承する", mapper.getSimpleName())
                    .contains(AppendOnlyMapper.class);
            assertThat(ResolvableType.forClass(mapper).as(AppendOnlyMapper.class).resolveGeneric(0))
                    .as(mapper.getSimpleName()).isEqualTo(table.entity());
            for (Method declared : mapper.getDeclaredMethods()) {
                if (!declared.isSynthetic() && !declared.isBridge() && modifyingNames.contains(declared.getName())) {
                    violations.add(mapper.getSimpleName() + "#" + signature(declared) + ": 更新系を再定義している");
                }
            }

            Object proxy = sqlExecutingProxy(mapper);
            Object tableName = invokeReturning(appendOnlyTableMethod(), proxy);
            assertThat(tableName).as("%s の appendOnlyTable() は @TableName と同じ", mapper.getSimpleName())
                    .isEqualTo(table.entity().getAnnotation(TableName.class).value()).isEqualTo(table.name());
            for (Method base : modifyingBaseMapperMethods()) {
                String label = mapper.getSimpleName() + "#" + signature(base);
                checkRejected(violations, label + "（BaseMapper経由）", invoke(base, proxy), table.name());
                Method shared = findOverride(AppendOnlyMapper.class, base);
                if (shared != null) {
                    checkRejected(violations, label + "（AppendOnlyMapper経由）", invoke(shared, proxy), table.name());
                }
            }
        }
        assertThat(violations).as("不変表のmapperは更新・削除をSQLの発行前に拒否すること").isEmpty();
    }

    @Test
    void 不変6表のmapperは追記のinsertと読取を継承のまま使える() {
        for (Table table : IMMUTABLE) {
            assertThat(Arrays.stream(table.mapper().getDeclaredMethods()).map(Method::getName))
                    .as("%s は insert を上書きしない", table.mapper().getSimpleName())
                    .doesNotContain("insert");
            assertThat(Arrays.stream(AppendOnlyMapper.class.getDeclaredMethods()).map(Method::getName))
                    .as("共通基は insert・select を上書きしない")
                    .doesNotContain("insert", "selectList", "selectById", "selectCount");
        }
    }

    @Test
    void projectionのmapperは可変で更新削除を拒否しない() {
        assertThat(AppendOnlyMapper.class.isAssignableFrom(PROJECTION.mapper())).isFalse();
        List<String> overridden = new ArrayList<>();
        for (Method base : modifyingBaseMapperMethods()) {
            if (findOverride(PROJECTION.mapper(), base) != null) {
                overridden.add(signature(base));
            }
        }
        assertThat(overridden).as("projectionはCASで更新するため拒否しない").isEmpty();
        assertThat(ResolvableType.forClass(PROJECTION.mapper()).as(BaseMapper.class).resolveGeneric(0))
                .isEqualTo(PROJECTION.entity());
    }

    @Test
    void 不変6表のentityをIServiceやServiceImplやDbやSqlHelperで包まない() throws Exception {
        List<String> violations = new ArrayList<>();
        Path root = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (Table table : IMMUTABLE) {
                    String entity = Pattern.quote(table.entity().getSimpleName());
                    Pattern wrapped = Pattern.compile("\\b(?:IService|ServiceImpl)\\s*<[^;{]*\\b" + entity + "\\b"
                            + "|\\b(?:Db|SqlHelper)\\s*\\.\\s*\\w+\\s*\\([^;]*\\b" + entity + "\\b");
                    if (wrapped.matcher(source).find()) {
                        violations.add(root.relativize(file) + ": " + table.entity().getSimpleName());
                    }
                }
            }
        }
        assertThat(violations).as("不変表のentityは汎用の更新を持つ型で包まない（計画 §7.8）").isEmpty();
    }

    @Test
    void entityの表名と主キーはV195の定義と一致する() throws Exception {
        String migration = read(MIGRATION);
        Set<String> created = matches(migration, "CREATE\\s+TABLE\\s+(t_lifecycle_dual_\\w+)\\s*\\(");
        assertThat(created).containsExactlyInAnyOrderElementsOf(allTables().stream().map(Table::name).toList());

        for (Table table : allTables()) {
            TableName tableName = table.entity().getAnnotation(TableName.class);
            assertThat(tableName).as(table.entity().getSimpleName()).isNotNull();
            assertThat(tableName.value()).isEqualTo(table.name());
            List<String> idFields = Arrays.stream(table.entity().getDeclaredFields())
                    .filter(field -> field.isAnnotationPresent(TableId.class)).map(Field::getName).toList();
            if (COMPOSITE_KEY_TABLES.contains(table.name())) {
                assertThat(idFields).as("%s は複合主キーのため @TableId を置かない", table.name()).isEmpty();
            } else {
                assertThat(idFields).as("%s の主キー", table.name()).containsExactly("id");
            }
        }
    }

    @Test
    void V195のtriggerは不変6表のUPDATEとDELETEに1件ずつでmapperの不変集合と一致する() throws Exception {
        String migration = stripLineComments(read(MIGRATION));
        Matcher trigger = Pattern.compile("CREATE\\s+TRIGGER\\s+(\\w+)\\s+BEFORE\\s+(UPDATE|DELETE)\\s+ON\\s+(\\w+)\\s+"
                + "FOR\\s+EACH\\s+ROW\\s+SIGNAL\\s+SQLSTATE\\s+'45000'\\s+SET\\s+MESSAGE_TEXT\\s*=\\s*'([^']+)'\\s*;",
                Pattern.CASE_INSENSITIVE).matcher(migration);
        Map<String, Set<String>> eventsByTable = new TreeMap<>();
        Set<String> triggerNames = new TreeSet<>();
        int count = 0;
        while (trigger.find()) {
            count++;
            triggerNames.add(trigger.group(1));
            eventsByTable.computeIfAbsent(trigger.group(3), key -> new TreeSet<>()).add(trigger.group(2).toUpperCase());
            assertThat(trigger.group(4)).as(trigger.group(1)).contains(trigger.group(3));
        }
        assertThat(count).as("CREATE TRIGGERの総数").isEqualTo(12)
                .isEqualTo(countMatches(migration, "CREATE\\s+TRIGGER"));
        assertThat(triggerNames).hasSize(12);

        Map<String, Set<String>> expected = new TreeMap<>();
        for (Table table : IMMUTABLE) {
            expected.put(table.name(), new TreeSet<>(Set.of("DELETE", "UPDATE")));
        }
        assertThat(eventsByTable).isEqualTo(expected);
        assertThat(eventsByTable).doesNotContainKey(PROJECTION.name());
        assertThat(migration).as("単文のtriggerだけで、区切り文字の変更を要しない")
                .doesNotContainIgnoringCase("DELIMITER").doesNotContainIgnoringCase("BEGIN");
    }

    @Test
    void 冪等性keyのCHECKはV195とH2の2つのschemaで同じ式を持つ() throws Exception {
        String expression = "CONSTRAINT ck_dual_idem_key CHECK (CHAR_LENGTH(idempotency_key) >= 1 "
                + "AND NOT REGEXP_LIKE(idempotency_key, '[^A-Za-z0-9._~-]', 'c'))";
        for (String source : List.of(MIGRATION, H2_REPLAY, H2_ENGINEER)) {
            assertThat(normalizeSpaces(stripLineComments(read(source))))
                    .as("%s の ck_dual_idem_key", source).contains(expression);
        }
        assertThat(read(MIGRATION)).contains("idempotency_key VARCHAR(190) CHARACTER SET ascii COLLATE ascii_bin NOT NULL");
    }

    @Test
    void H2の2つのschemaは7表を持ちtriggerを持たずV1が作り直す表へのFKはprecleanupで外す() throws Exception {
        String replay = read(H2_REPLAY);
        String engineer = read(H2_ENGINEER);
        for (Table table : allTables()) {
            String create = "CREATE TABLE IF NOT EXISTS " + table.name() + " (";
            assertThat(replay).as("replay: " + table.name()).contains(create);
            assertThat(engineer).as("engineer-schema-h2: " + table.name()).contains(create);
        }
        assertThat(countMatches(replay, "CREATE\\s+TRIGGER")).isZero();
        assertThat(countMatches(engineer, "CREATE\\s+TRIGGER[^;]*t_lifecycle_dual")).isZero();

        // sys_user (tenant_id, id) の一意制約はFKより先に明示する（暗黙の一意制約に頼らない。W3-18 review P3-7）
        String uniqueSysUser = "ALTER TABLE sys_user ADD CONSTRAINT IF NOT EXISTS uk_sys_user_tenant_id UNIQUE (tenant_id, id);";
        for (String sql : List.of(replay, engineer)) {
            int unique = sql.indexOf(uniqueSysUser);
            assertThat(unique).isPositive();
            assertThat(unique).isLessThan(sql.indexOf("fk_dual_submission_submitter"));
        }

        // FKは ADD CONSTRAINT IF NOT EXISTS で置き、replayのたびに付け直せる形にする
        Map<String, String> replayForeignKeys = foreignKeyTargets(replay);
        Map<String, String> engineerForeignKeys = foreignKeyTargets(engineer);
        // 提出3・提出文書2・組織2・確認2・projection3・冪等性2（eventはFKを持たない）
        assertThat(replayForeignKeys).hasSize(14).isEqualTo(engineerForeignKeys);
        assertThat(replayForeignKeys.keySet())
                .containsExactlyInAnyOrderElementsOf(matches(stripLineComments(read(MIGRATION)),
                        "CONSTRAINT\\s+(fk_dual_\\w+)\\s+FOREIGN\\s+KEY"));

        Set<String> droppedByV1 = matches(read("db/migration/V1__create_tables.sql"),
                "DROP\\s+TABLE\\s+IF\\s+EXISTS\\s+(\\w+)\\s*;");
        Set<String> released = matches(read(H2_PRECLEANUP),
                "ALTER\\s+TABLE\\s+IF\\s+EXISTS\\s+t_lifecycle_dual_\\w+\\s+DROP\\s+CONSTRAINT\\s+IF\\s+EXISTS\\s+(fk_dual_\\w+)\\s*;");
        Set<String> mustRelease = replayForeignKeys.entrySet().stream()
                .filter(entry -> droppedByV1.contains(entry.getValue())).map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(mustRelease).as("V1が作り直す表への参照").containsExactlyInAnyOrder(
                "fk_dual_submission_submitter", "fk_dual_doc_version", "fk_dual_org_unit",
                "fk_dual_confirmation_confirmer", "fk_dual_idem_actor");
        assertThat(released).as("precleanupはV1が作り直す表へのFKだけを外す").isEqualTo(mustRelease);

        String yaml = read("application-test.yml");
        int previous = yaml.indexOf("classpath:sql/schema-nf08-dispatch-claim-h2.sql");
        int current = yaml.indexOf("classpath:" + H2_REPLAY);
        assertThat(previous).isPositive();
        assertThat(current).as("V194のH2 schemaの後に登録する").isGreaterThan(previous);
        assertThat(yaml.indexOf("classpath:sql/schema-authorization-epoch-h2.sql")).isPositive().isLessThan(current);
        assertThat(yaml.indexOf("classpath:" + H2_PRECLEANUP)).isPositive().isLessThan(
                yaml.indexOf("classpath:db/migration/V1__create_tables.sql"));
    }

    // ===== helpers =====

    private static List<Table> allTables() {
        List<Table> tables = new ArrayList<>(IMMUTABLE);
        tables.add(PROJECTION);
        return tables;
    }

    /** {@link BaseMapper} の更新・削除・insertOrUpdate系メソッド。 */
    private static List<Method> modifyingBaseMapperMethods() {
        return Arrays.stream(BaseMapper.class.getMethods())
                .filter(method -> !Modifier.isStatic(method.getModifiers()) && !method.isSynthetic())
                .filter(method -> method.getName().startsWith("update") || method.getName().startsWith("delete")
                        || method.getName().startsWith("insertOrUpdate") || method.getName().startsWith("saveOrUpdate"))
                .sorted((a, b) -> signature(a).compareTo(signature(b)))
                .toList();
    }

    private static Method appendOnlyTableMethod() {
        try {
            return AppendOnlyMapper.class.getMethod("appendOnlyTable");
        } catch (NoSuchMethodException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** typeが宣言する、baseの引数型をtypeの型引数で解決した型と完全一致するoverride。 */
    private static Method findOverride(Class<?> type, Method base) {
        Class<?>[] expected = new Class<?>[base.getParameterCount()];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = ResolvableType.forMethodParameter(base, i, type).resolve(Object.class);
        }
        Class<?> expectedReturn = ResolvableType.forMethodReturnType(base, type).resolve(Object.class);
        for (Method candidate : type.getDeclaredMethods()) {
            if (candidate.isBridge() || candidate.isSynthetic() || Modifier.isStatic(candidate.getModifiers())
                    || !candidate.getName().equals(base.getName())) {
                continue;
            }
            if (Arrays.equals(candidate.getParameterTypes(), expected) && candidate.getReturnType() == expectedReturn) {
                return candidate;
            }
        }
        return null;
    }

    /** MyBatis-Plusの mapper proxy と同じ分岐: default methodは本体を実行し、それ以外はSQLの実行に当たる。 */
    private static Object sqlExecutingProxy(Class<?> mapper) {
        return Proxy.newProxyInstance(mapper.getClassLoader(), new Class<?>[]{mapper}, (proxy, method, args) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, args);
            }
            throw new AssertionError("SQLの実行へ到達した: " + method);
        });
    }

    private static Object invokeReturning(Method method, Object target) {
        try {
            return method.invoke(target);
        } catch (InvocationTargetException failure) {
            throw new AssertionError(method + " が失敗した", failure.getCause());
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Throwable invoke(Method method, Object target) {
        Object[] args = Arrays.stream(method.getParameterTypes())
                .map(LifecycleDualConfirmationMapperImmutabilityTest::defaultValue).toArray();
        try {
            method.invoke(target, args);
            return null;
        } catch (InvocationTargetException failure) {
            return failure.getCause();
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("未対応の引数型: " + type);
        }
        return null;
    }

    private static void checkRejected(List<String> violations, String label, Throwable thrown, String table) {
        if (!(thrown instanceof UnsupportedOperationException)) {
            violations.add(label + ": UnsupportedOperationExceptionではない: " + thrown);
        } else if (thrown.getMessage() == null || !thrown.getMessage().contains(table + " は追記専用")) {
            violations.add(label + ": 表名を含む理由が無い: " + thrown.getMessage());
        }
    }

    private static String signature(Method method) {
        return method.getName() + Arrays.stream(method.getParameterTypes()).map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")"));
    }

    /** {@code ALTER TABLE x ADD CONSTRAINT IF NOT EXISTS fk_dual_* FOREIGN KEY (...) REFERENCES 表} の名前と参照先。 */
    private static Map<String, String> foreignKeyTargets(String sql) {
        Matcher matcher = Pattern.compile("ADD\\s+CONSTRAINT\\s+IF\\s+NOT\\s+EXISTS\\s+(fk_dual_\\w+)\\s+FOREIGN\\s+KEY\\s*"
                + "\\([^)]*\\)\\s*REFERENCES\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
        Map<String, String> targets = new TreeMap<>();
        while (matcher.find()) {
            targets.put(matcher.group(1), matcher.group(2));
        }
        return targets;
    }

    private static Set<String> matches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        Set<String> found = new LinkedHashSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static int countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String stripLineComments(String sql) {
        return sql.lines().map(line -> {
            int comment = line.indexOf("--");
            return comment < 0 ? line : line.substring(0, comment);
        }).collect(Collectors.joining("\n"));
    }

    private static String normalizeSpaces(String text) {
        return text.replaceAll("\\s+", " ");
    }

    private static String read(String classpath) throws Exception {
        return new ClassPathResource(classpath).getContentAsString(StandardCharsets.UTF_8);
    }
}
