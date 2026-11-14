package com.ses.service.security.epoch;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Document;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleTask;
import com.ses.entity.OrganizationUnit;
import com.ses.entity.SysUser;
import com.ses.mapper.AuthorizationEpochMapper;
import com.ses.mapper.LifecycleTaskMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U5（NF01細粒度epoch計画 §2.4 E-7・§4.5・§6.2）: epoch列とタスク世代を entity へ写像しないこと、
 * それらの列名を含むSQLが {@link AuthorizationEpochMapper} と {@link LifecycleTaskMapper} の専用メソッド
 * （名前に {@code TaskGeneration} を含むもの）と migration 以外に無いことを、Docker無しで固定する。
 *
 * <p>entityへ写像すると {@code updateById(entity)} が読取時の値を書き戻して増分を巻き戻す。注釈SQL・JdbcTemplate等の
 * 文字列にこれらの列名が現れれば、fenceを通らない書込み・読取である。新しい読取経路を足す場合は、本testの許可一覧を
 * 理由付きで更新するのではなく、{@link AuthorizationEpochMapper} へ専用メソッドを足すこと。</p>
 *
 * <p>例外: {@code LifecycleAuthorizationPreflight}（NF01 read-only preflight）は列の有無・定義をinformation_schemaと
 * 件数集計で読むだけの読取専用CLIのため、書込み語（UPDATE・INSERT・DELETE・REPLACE・ALTER）を含まない文字列に限って許す。</p>
 */
class AuthorizationEpochColumnIsolationTest {

    /** epoch列・タスク世代・全体epoch表の識別子（V189）。 */
    private static final List<String> GUARDED_IDENTIFIERS = List.of(
            "actor_authz_epoch", "engineer_scope_epoch", "org_scope_epoch", "evidence_eligibility_epoch",
            "task_generation", "t_authorization_global_epoch");

    private static final Set<String> GUARDED_FIELD_NAMES = Set.of(
            "actorauthzepoch", "engineerscopeepoch", "orgscopeepoch", "evidenceeligibilityepoch", "taskgeneration");

    private static final String EPOCH_MAPPER_SOURCE = "com/ses/mapper/AuthorizationEpochMapper.java";
    private static final String TASK_MAPPER_SOURCE = "com/ses/mapper/LifecycleTaskMapper.java";
    private static final String PREFLIGHT_SOURCE = "com/ses/nf01/preflight/LifecycleAuthorizationPreflight.java";
    private static final Pattern WRITE_KEYWORD = Pattern.compile("\\b(UPDATE|INSERT|DELETE|REPLACE|ALTER)\\b",
            Pattern.CASE_INSENSITIVE);

    @Test
    void anchorの5entityはepoch列とタスク世代のfieldを持たない() {
        List<String> violations = new ArrayList<>();
        for (Class<?> entity : List.of(SysUser.class, Engineer.class, OrganizationUnit.class, Document.class,
                LifecycleTask.class)) {
            for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
                for (Field field : type.getDeclaredFields()) {
                    String normalized = field.getName().replace("_", "").toLowerCase(Locale.ROOT);
                    if (GUARDED_FIELD_NAMES.contains(normalized) || normalized.contains("epoch")) {
                        violations.add(entity.getSimpleName() + "." + field.getName());
                    }
                    TableField tableField = field.getAnnotation(TableField.class);
                    if (tableField != null && containsGuarded(tableField.value())) {
                        violations.add(entity.getSimpleName() + "." + field.getName() + " @TableField(" + tableField.value() + ")");
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "epoch列はentityへ写像しない（updateByIdが増分を巻き戻すため。計画 §2.4 E-7）");
    }

    @Test
    void epoch列を読み書きする注釈SQLは専用Mapperの専用メソッドだけにある() throws Exception {
        assertFalse(BaseMapper.class.isAssignableFrom(AuthorizationEpochMapper.class),
                "AuthorizationEpochMapperは汎用の更新メソッドを持たない（BaseMapperを継承しない）");
        List<String> violations = new ArrayList<>();
        int epochMapperStatements = 0;
        for (Class<?> mapper : mapperInterfaces()) {
            for (Method method : mapper.getDeclaredMethods()) {
                for (String sql : annotatedSql(method)) {
                    if (!containsGuarded(sql)) {
                        continue;
                    }
                    if (mapper == AuthorizationEpochMapper.class) {
                        epochMapperStatements++;
                    } else if (!(mapper == LifecycleTaskMapper.class && method.getName().contains("TaskGeneration"))) {
                        violations.add(mapper.getSimpleName() + "#" + method.getName());
                    }
                }
            }
        }
        assertEquals(List.of(), violations, "epoch列・タスク世代のSQLは AuthorizationEpochMapper に置くこと");
        // 全体3・利用者3・要員3・組織3・文書3・タスク世代2（V189時点）
        assertTrue(epochMapperStatements >= 17, "AuthorizationEpochMapperの専用SQLを読めていること: " + epochMapperStatements);
    }

    @Test
    void main配下の文字列とresourceにepoch列名が現れるのは専用Mapperとpreflightとmigrationだけ() throws IOException {
        Path javaRoot = Path.of("src/main/java");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(javaRoot)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".java")).toList()) {
                String relative = javaRoot.relativize(path).toString().replace('\\', '/');
                if (relative.equals(EPOCH_MAPPER_SOURCE) || relative.equals(TASK_MAPPER_SOURCE)) {
                    continue; // 注釈SQLの単位はメソッドごとに別testで確認する
                }
                for (String literal : stringLiterals(Files.readString(path, StandardCharsets.UTF_8))) {
                    if (!containsGuarded(literal)) {
                        continue;
                    }
                    if (relative.equals(PREFLIGHT_SOURCE) && !WRITE_KEYWORD.matcher(literal).find()) {
                        continue;
                    }
                    violations.add(relative + ": \"" + abbreviate(literal) + "\"");
                }
            }
        }
        Path resourceRoot = Path.of("src/main/resources");
        try (Stream<Path> files = Files.walk(resourceRoot)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String relative = resourceRoot.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith("db/migration/") || !(relative.endsWith(".sql") || relative.endsWith(".xml"))) {
                    continue;
                }
                if (containsGuarded(Files.readString(path, StandardCharsets.UTF_8))) {
                    violations.add(relative);
                }
            }
        }
        assertEquals(List.of(), violations,
                "epoch列・タスク世代・全体epoch表を扱うSQLは AuthorizationEpochMapper（とmigration）だけに置くこと");
    }

    @Test
    void 文字列抽出はコメントを除き通常文字列とtext_blockを拾う() {
        List<String> literals = stringLiterals("""
                // "actor_authz_epoch" はコメント
                /* "t_authorization_global_epoch" もコメント */
                String a = "SELECT task_generation FROM t";
                String b = \"""
                    UPDATE t_engineer SET engineer_scope_epoch = 1
                    \""";
                char c = '"';
                String d = "escaped \\" org_scope_epoch";
                """);
        assertEquals(3, literals.size(), literals.toString());
        assertTrue(literals.get(0).contains("task_generation"));
        assertTrue(literals.get(1).contains("engineer_scope_epoch"));
        assertTrue(literals.get(2).contains("org_scope_epoch"));
    }

    private static boolean containsGuarded(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return GUARDED_IDENTIFIERS.stream().anyMatch(lower::contains);
    }

    private static List<String> annotatedSql(Method method) {
        List<String> statements = new ArrayList<>();
        Select select = method.getAnnotation(Select.class);
        if (select != null) statements.add(String.join(" ", select.value()));
        Update update = method.getAnnotation(Update.class);
        if (update != null) statements.add(String.join(" ", update.value()));
        Insert insert = method.getAnnotation(Insert.class);
        if (insert != null) statements.add(String.join(" ", insert.value()));
        Delete delete = method.getAnnotation(Delete.class);
        if (delete != null) statements.add(String.join(" ", delete.value()));
        return statements;
    }

    private static List<Class<?>> mapperInterfaces() throws IOException, ClassNotFoundException {
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:com/ses/mapper/**/*.class");
        Set<String> names = new TreeSet<>();
        for (Resource resource : resources) {
            String url = resource.getURL().toString().replace('\\', '/');
            if (url.contains("/test-classes/") || url.contains("-tests.jar")) {
                continue;
            }
            int index = url.indexOf("com/ses/mapper/");
            String name = url.substring(index).replace('/', '.').replace(".class", "");
            if (!name.contains("$")) {
                names.add(name);
            }
        }
        List<Class<?>> mappers = new ArrayList<>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        for (String name : names) {
            Class<?> type = Class.forName(name, false, loader);
            if (type.isInterface()) {
                mappers.add(type);
            }
        }
        assertTrue(mappers.contains(AuthorizationEpochMapper.class) && mappers.contains(LifecycleTaskMapper.class),
                "com.ses.mapper のMapperを読めていること");
        return mappers;
    }

    /** Javaソースから文字列literal（通常文字列とtext block）の中身を取り出す。コメントと文字literalは除く。 */
    static List<String> stringLiterals(String source) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        int length = source.length();
        while (i < length) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                int end = source.indexOf('\n', i);
                i = end < 0 ? length : end + 1;
            } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? length : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                while (end > 0 && source.charAt(end - 1) == '\\') {
                    end = source.indexOf("\"\"\"", end + 1);
                }
                int stop = end < 0 ? length : end;
                literals.add(source.substring(i + 3, stop));
                i = end < 0 ? length : end + 3;
            } else if (c == '"') {
                StringBuilder literal = new StringBuilder();
                i++;
                while (i < length && source.charAt(i) != '"' && source.charAt(i) != '\n') {
                    if (source.charAt(i) == '\\' && i + 1 < length) {
                        literal.append(source.charAt(i + 1));
                        i += 2;
                    } else {
                        literal.append(source.charAt(i));
                        i++;
                    }
                }
                literals.add(literal.toString());
                i++;
            } else if (c == '\'') {
                int end = i + 1;
                while (end < length && source.charAt(end) != '\'' && source.charAt(end) != '\n') {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                i = end + 1;
            } else {
                i++;
            }
        }
        return literals;
    }

    private static String abbreviate(String literal) {
        String flat = literal.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }
}
