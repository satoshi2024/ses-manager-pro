package com.ses.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code t_audit_log.method} へ書く固定値が列の長さに収まることを、main の source から静的に確かめる（DB・Docker 不要）。
 *
 * <p>列は V11 で {@code VARCHAR(10)}。MySQL の strict mode では長すぎる値の insert が失敗し、required 監査
 * （{@code recordRequired}・{@code recordDomainEventRequired}）を書く操作が全て失敗する。監査を mock・fake にした test では
 * 検出できない（NF07-REL・NF07-A2a で {@code PRIVACY_HOLD}・{@code PRIVACY_DSAR} の12文字が見つかった）。列の長さは V11 の DDL から
 * 読み、後続の migration が列を変えていないことも確かめる（変えた場合は本 test の読み先を直す）。
 *
 * <p>対象（監査を書く経路）:
 * <ol>
 *   <li>{@code AuditLogService} 型の receiver（field・引数・局所変数、{@code ObjectProvider<AuditLogService>} の
 *       {@code getIfAvailable()}・{@code getObject()}）の {@code record} / {@code recordRequired} / {@code recordCaptured}
 *       （RB-QUIESCE の解除後の監査。D4-P3-3）の第2引数</li>
 *   <li>{@code AuditLog} 型の変数の {@code setMethod(...)} の引数（{@code recordDomainEventRequired} へ渡す entry）</li>
 *   <li>{@code AuditLog.builder()} を使う file の {@code .method("...")}</li>
 *   <li>上の経路を持つ file の、名前に {@code METHOD} を含む String 定数</li>
 * </ol>
 * 引数が文字列 literal か String 定数（同じ file、または {@code Class.NAME} で参照する main の class）なら値を解決する。
 * 連結・method 呼出し・変数は実行時の値なので対象外とする（HTTP method をそのまま書く filter 等）。他の型の receiver
 * （例: {@code PrivacyHoldAudit.record(username, EVENT, ...)}、{@code ExternalApiAudit.setMethod}）は対象にしない。
 *
 * <p>{@code recordCaptured}（取っておいた時刻・主体で書く監査）と {@code revokeCaptured}（取っておいた時刻で書く session の失効）を
 * 呼んでよいのは、backup の静止の解除後の書込みの一覧 {@code BackupQuiesceDeferredWrites}（と入れ子の class）だけである。過去の時刻の
 * 監査の行を任意の場所から作れないよう、main の bytecode の呼出し（method 参照を含む）を走査して固定する。
 */
class AuditLogMethodLengthGuardTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    /** {@code t_audit_log} を作る migration（列の長さの正本）。 */
    private static final String AUDIT_TABLE_MIGRATION = "V11__create_audit_log.sql";

    private static final Pattern CREATE_AUDIT_TABLE = Pattern.compile(
            "CREATE\\s+TABLE\\s+t_audit_log\\s*\\(([\\s\\S]*?)\\)\\s*ENGINE", Pattern.CASE_INSENSITIVE);
    private static final Pattern METHOD_COLUMN = Pattern.compile(
            "(?m)^\\s*method\\s+VARCHAR\\s*\\(\\s*(\\d+)\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_AUDIT_TABLE = Pattern.compile(
            "ALTER\\s+TABLE\\s+`?t_audit_log`?\\b([^;]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTERS_METHOD_COLUMN = Pattern.compile(
            "\\b(MODIFY|CHANGE)\\s+(COLUMN\\s+)?`?method`?\\b|\\bDROP\\s+(COLUMN\\s+)?`?method`?\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SERVICE_VAR = Pattern.compile("\\b(?:[\\w.]*\\.)?AuditLogService\\s+(\\w+)\\b");
    private static final Pattern PROVIDER_VAR = Pattern.compile(
            "ObjectProvider\\s*<\\s*(?:[\\w.]*\\.)?AuditLogService\\s*>\\s+(\\w+)\\b");
    private static final Pattern ENTRY_VAR = Pattern.compile("\\b(?:[\\w.]*\\.)?AuditLog\\s+(\\w+)\\s*[=;,)]");
    private static final Pattern VAR_NEW_ENTRY = Pattern.compile(
            "\\bvar\\s+(\\w+)\\s*=\\s*new\\s+(?:[\\w.]*\\.)?AuditLog\\s*\\(");
    private static final Pattern STRING_CONSTANT = Pattern.compile(
            "\\bString\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");
    private static final Pattern METHOD_CONSTANT = Pattern.compile(
            "\\bString\\s+(\\w*METHOD\\w*)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");
    private static final Pattern BUILDER_METHOD = Pattern.compile("\\.method\\s*\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*\\)");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern CONSTANT_REF = Pattern.compile("(?:([A-Z]\\w*)\\.)?([A-Z][A-Z0-9_]*)");

    @Test
    void 監査ログのmethod列の長さはV11のDDLから読み後続のmigrationで変えていない() throws IOException {
        assertThat(methodColumnLength()).as("V11 の t_audit_log.method").isEqualTo(10);

        List<String> alterations = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".sql")).sorted().toList()) {
                String sql = Files.readString(file, StandardCharsets.UTF_8);
                Matcher alter = ALTER_AUDIT_TABLE.matcher(sql);
                while (alter.find()) {
                    if (ALTERS_METHOD_COLUMN.matcher(alter.group(1)).find()) {
                        alterations.add(file.getFileName().toString());
                    }
                }
                if (!file.getFileName().toString().equals(AUDIT_TABLE_MIGRATION)
                        && Pattern.compile("CREATE\\s+TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?`?t_audit_log`?\\b",
                        Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
                    alterations.add(file.getFileName().toString() + " (CREATE)");
                }
            }
        }
        assertThat(alterations)
                .as("t_audit_log.method を V11 以外で変えています。本 test の列の長さの読み先を直してください: %s", alterations)
                .isEmpty();
    }

    @Test
    void mainから監査ログのmethod列へ書く固定値は列の長さに収まる() throws IOException {
        int maxLength = methodColumnLength();
        List<Finding> findings = scan(mainSources());

        assertThat(findings).as("監査の method へ書く固定値を検出できない（走査の空振り）").hasSizeGreaterThan(10);
        assertThat(findings).extracting(Finding::value).contains("SECURITY", "PRIV_DSAR", "PRIV_HOLD");
        List<Finding> violations = findings.stream().filter(finding -> finding.value().length() > maxLength).toList();
        assertThat(violations)
                .as("t_audit_log.method（VARCHAR(%d)、V11）に収まらない値を書いています: %s", maxLength, violations)
                .isEmpty();
    }

    @Test
    void 検出規則は監査の書込み経路の固定値を拾い他の型の同名呼出しは拾わない() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("x/Direct.java", """
                class Direct {
                    private final AuditLogService auditLogService;
                    void f() { auditLogService.recordRequired(user, "DIRECT_LONG_1", uri, 200, "CODE", true); }
                }
                """);
        sources.put("x/Constant.java", """
                class Constant {
                    static final String METHOD = "CONST_LONG_2";
                    void f(com.ses.service.AuditLogService audit) { audit.record(u, METHOD, x, 1); }
                }
                """);
        sources.put("x/Entry.java", """
                class Entry {
                    void f() { AuditLog entry = new AuditLog(); entry.setMethod("ENTRY_LONG_3"); entry.setMethod(dynamic()); }
                }
                """);
        sources.put("x/Provider.java", """
                class Provider {
                    private final ObjectProvider<AuditLogService> p;
                    void f() { p.getIfAvailable().recordRequired(name("a", 1), Other.LONG_ONE, u, 1, "c", true); }
                }
                """);
        sources.put("x/Other.java", """
                class Other { public static final String LONG_ONE = "OTHER_LONG_4"; }
                """);
        sources.put("x/NotAudit.java", """
                class NotAudit {
                    private final PrivacyHoldAudit audit;
                    // auditLogService.recordRequired(u, "COMMENT_LONG_X", ...) はコメントなので拾わない
                    void f() {
                        audit.record(u, "START_REQUESTED_LONG", f, 1, true);
                        ExternalApiAudit entry = new ExternalApiAudit();
                        entry.setMethod("VERY_LONG_HTTP_METHOD");
                    }
                }
                """);
        sources.put("x/Short.java", """
                class Short {
                    private final AuditLogService auditLogService;
                    void f() { auditLogService.record(u, "POST", "/x", 200); auditLogService.record(u, request.getMethod(), "/x", 1); }
                }
                """);
        sources.put("x/Captured.java", """
                class Captured {
                    void f(AuditLogService audit, ObjectProvider<AuditLogService> p) {
                        audit.recordCaptured(u, "CAPTURED_LONG_5", "/logout", 302, "LOGOUT", true, context);
                        p.getObject().recordCaptured(u, method, "/logout", 302, "LOGOUT", true, context);
                    }
                }
                """);

        List<Finding> findings = scan(sources);

        assertThat(findings).extracting(Finding::value).containsExactlyInAnyOrder(
                "DIRECT_LONG_1", "CONST_LONG_2", "CONST_LONG_2", "ENTRY_LONG_3", "OTHER_LONG_4", "POST",
                "CAPTURED_LONG_5");
        assertThat(findings).filteredOn(finding -> finding.value().equals("DIRECT_LONG_1"))
                .extracting(Finding::location).containsExactly("x/Direct.java:3");
    }

    // ---------------------------------------------------------------- 取っておいた値で書く呼出しの呼出し元（D4-P3-3）

    /** 取っておいた値で書く method（owner の内部名 → method 名）。 */
    private static final Map<String, String> CAPTURED_WRITERS = Map.of(
            "com/ses/service/AuditLogService", "recordCaptured",
            "com/ses/service/impl/AuditLogServiceImpl", "recordCaptured",
            "com/ses/service/security/PersistentSessionService", "revokeCaptured",
            "com/ses/service/security/impl/PersistentSessionServiceImpl", "revokeCaptured");
    /** 呼んでよい class（入れ子の class を含む）。 */
    private static final String CAPTURED_WRITER_CALLER = "com/ses/backup/quiesce/BackupQuiesceDeferredWrites";

    @Test
    void 取っておいた値で監査と失効を書く呼出しは解除後の書込みの一覧だけにある() throws IOException {
        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("target/classes"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                callers.addAll(capturedWriterCalls(Files.readAllBytes(file)));
            }
        }

        assertThat(callers).as("解除後の書込みの一覧が呼んでいない（走査の空振り）")
                .anyMatch(call -> call.startsWith(CAPTURED_WRITER_CALLER + "$") && call.endsWith("recordCaptured"))
                .anyMatch(call -> call.startsWith(CAPTURED_WRITER_CALLER + "$") && call.endsWith("revokeCaptured"));
        assertThat(callers.stream().filter(call -> !isAllowedCapturedCaller(call)).toList())
                .as("recordCaptured・revokeCaptured を BackupQuiesceDeferredWrites の外から呼んでいます")
                .isEmpty();
    }

    @Test
    void 一覧の外からの呼出しとmethod参照を検出する() throws IOException {
        List<String> calls = new ArrayList<>();
        for (Class<?> fixture : List.of(CapturedAuditFixture.class, CapturedReferenceFixture.class)) {
            try (var stream = fixture.getResourceAsStream("/" + fixture.getName().replace('.', '/') + ".class")) {
                assertThat(stream).isNotNull();
                calls.addAll(capturedWriterCalls(stream.readAllBytes()));
            }
        }

        assertThat(calls).hasSize(2).noneMatch(AuditLogMethodLengthGuardTest::isAllowedCapturedCaller);
        assertThat(calls).anyMatch(call -> call.contains("CapturedAuditFixture") && call.endsWith("recordCaptured"))
                .anyMatch(call -> call.contains("CapturedReferenceFixture") && call.endsWith("revokeCaptured"));
    }

    private static boolean isAllowedCapturedCaller(String call) {
        String caller = call.substring(0, call.indexOf(' '));
        return caller.equals(CAPTURED_WRITER_CALLER) || caller.startsWith(CAPTURED_WRITER_CALLER + "$");
    }

    /** class の bytecode の中の、取っておいた値で書く method の呼出し（{@code <caller> -> <owner>.<name>}）。 */
    static List<String> capturedWriterCalls(byte[] classBytes) {
        List<String> calls = new ArrayList<>();
        org.springframework.asm.ClassReader reader = new org.springframework.asm.ClassReader(classBytes);
        String caller = reader.getClassName();
        reader.accept(new org.springframework.asm.ClassVisitor(org.springframework.asm.SpringAsmInfo.ASM_VERSION) {
            @Override
            public org.springframework.asm.MethodVisitor visitMethod(int access, String name, String descriptor,
                                                                    String signature, String[] exceptions) {
                return new org.springframework.asm.MethodVisitor(org.springframework.asm.SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String method, String desc,
                                                boolean isInterface) {
                        record(owner, method);
                    }

                    @Override
                    public void visitInvokeDynamicInsn(String method, String desc,
                                                       org.springframework.asm.Handle bootstrap, Object... arguments) {
                        for (Object argument : arguments) {
                            if (argument instanceof org.springframework.asm.Handle handle) {
                                record(handle.getOwner(), handle.getName());
                            }
                        }
                    }

                    private void record(String owner, String method) {
                        if (method.equals(CAPTURED_WRITERS.get(owner))) {
                            calls.add(caller + " -> " + owner + "." + method);
                        }
                    }
                };
            }
        }, org.springframework.asm.ClassReader.SKIP_DEBUG | org.springframework.asm.ClassReader.SKIP_FRAMES);
        return calls;
    }

    /** 一覧の外から recordCaptured を直接呼ぶ fixture（検出されることを確かめる）。 */
    static final class CapturedAuditFixture {
        void write(com.ses.service.AuditLogService audit) {
            audit.recordCaptured("u", "AUTH", "/logout", 302, "LOGOUT", true, null);
        }
    }

    /** 一覧の外から revokeCaptured を method 参照で渡す fixture。 */
    static final class CapturedReferenceFixture {
        java.util.function.Consumer<com.ses.service.security.PersistentSessionService.CapturedRevocation> write(
                com.ses.service.security.PersistentSessionService sessions) {
            return sessions::revokeCaptured;
        }
    }

    // ---------------------------------------------------------------- 走査

    /** 検出した固定値（location は {@code <path>:<line>}）。 */
    record Finding(String location, String value) {
        @Override
        public String toString() {
            return location + " \"" + value + "\"(" + value.length() + ")";
        }
    }

    static int methodColumnLength() throws IOException {
        String ddl = Files.readString(MIGRATIONS.resolve(AUDIT_TABLE_MIGRATION), StandardCharsets.UTF_8);
        Matcher table = CREATE_AUDIT_TABLE.matcher(ddl);
        assertThat(table.find()).as("%s に CREATE TABLE t_audit_log が無い", AUDIT_TABLE_MIGRATION).isTrue();
        Matcher column = METHOD_COLUMN.matcher(table.group(1));
        assertThat(column.find()).as("%s の t_audit_log に method VARCHAR(n) が無い", AUDIT_TABLE_MIGRATION).isTrue();
        return Integer.parseInt(column.group(1));
    }

    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                sources.put(MAIN_JAVA.relativize(file).toString().replace('\\', '/'),
                        Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    static List<Finding> scan(Map<String, String> rawSources) {
        Map<String, String> sources = new LinkedHashMap<>();
        Map<String, Map<String, String>> constantsByClass = new LinkedHashMap<>();
        rawSources.forEach((path, raw) -> {
            String code = blankComments(raw);
            sources.put(path, code);
            constantsByClass.put(simpleClassName(path), constants(code));
        });

        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String path = source.getKey();
            String code = source.getValue();
            Map<String, String> constants = constantsByClass.get(simpleClassName(path));
            Set<String> services = names(SERVICE_VAR, code);
            Set<String> providers = names(PROVIDER_VAR, code);
            Set<String> entries = names(ENTRY_VAR, code);
            entries.addAll(names(VAR_NEW_ENTRY, code));
            boolean builder = code.contains("AuditLog.builder(");
            if (services.isEmpty() && providers.isEmpty() && entries.isEmpty() && !builder) {
                continue;
            }
            for (String service : services) {
                Matcher call = Pattern.compile("\\b" + Pattern.quote(service)
                        + "\\s*\\.\\s*(?:record|recordRequired|recordCaptured)\\s*\\(").matcher(code);
                while (call.find()) {
                    argument(code, call.end(), 1).ifPresent(argument -> resolve(argument, constants, constantsByClass)
                            .ifPresent(value -> add(findings, seen, path, code, call.start(), value)));
                }
            }
            for (String provider : providers) {
                Matcher call = Pattern.compile("\\b" + Pattern.quote(provider)
                        + "\\s*\\.\\s*(?:getIfAvailable|getObject)\\s*\\(\\s*\\)\\s*\\.\\s*(?:record|recordRequired|recordCaptured)\\s*\\(")
                        .matcher(code);
                while (call.find()) {
                    argument(code, call.end(), 1).ifPresent(argument -> resolve(argument, constants, constantsByClass)
                            .ifPresent(value -> add(findings, seen, path, code, call.start(), value)));
                }
            }
            for (String entry : entries) {
                Matcher call = Pattern.compile("\\b" + Pattern.quote(entry) + "\\s*\\.\\s*setMethod\\s*\\(").matcher(code);
                while (call.find()) {
                    argument(code, call.end(), 0).ifPresent(argument -> resolve(argument, constants, constantsByClass)
                            .ifPresent(value -> add(findings, seen, path, code, call.start(), value)));
                }
            }
            if (builder) {
                Matcher call = BUILDER_METHOD.matcher(code);
                while (call.find()) {
                    add(findings, seen, path, code, call.start(), call.group(1));
                }
            }
            Matcher constant = METHOD_CONSTANT.matcher(code);
            while (constant.find()) {
                add(findings, seen, path, code, constant.start(), constant.group(2));
            }
        }
        return findings;
    }

    private static void add(List<Finding> findings, Set<String> seen, String path, String code, int index,
                            String value) {
        String location = path + ":" + lineOf(code, index);
        if (seen.add(location + "\u0000" + value)) {
            findings.add(new Finding(location, value));
        }
    }

    /** 引数が literal か String 定数なら値。式・変数・未解決の定数は空。 */
    private static java.util.Optional<String> resolve(String argument, Map<String, String> constants,
                                                      Map<String, Map<String, String>> constantsByClass) {
        String trimmed = argument.trim();
        Matcher literal = LITERAL.matcher(trimmed);
        if (literal.matches()) {
            return java.util.Optional.of(literal.group(1));
        }
        Matcher reference = CONSTANT_REF.matcher(trimmed);
        if (reference.matches()) {
            Map<String, String> owner = reference.group(1) == null ? constants : constantsByClass.get(reference.group(1));
            return owner == null ? java.util.Optional.empty() : java.util.Optional.ofNullable(owner.get(reference.group(2)));
        }
        return java.util.Optional.empty();
    }

    /** {@code open} は呼出しの開き括弧の直後。最上位のカンマで区切った {@code index} 番目の引数。 */
    private static java.util.Optional<String> argument(String code, int open, int index) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = open;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                i = skipString(code, i);
            } else if (c == '\'') {
                i = skipChar(code, i);
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) {
                    arguments.add(code.substring(start, i));
                    break;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(code.substring(start, i));
                start = i + 1;
            }
        }
        return index < arguments.size() ? java.util.Optional.of(arguments.get(index)) : java.util.Optional.empty();
    }

    private static int skipString(String code, int quote) {
        if (code.startsWith("\"\"\"", quote)) {
            int end = code.indexOf("\"\"\"", quote + 3);
            return end < 0 ? code.length() - 1 : end + 2;
        }
        for (int i = quote + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"' || c == '\n') {
                return i;
            }
        }
        return code.length() - 1;
    }

    private static int skipChar(String code, int quote) {
        for (int i = quote + 1; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '\'' || c == '\n') {
                return i;
            }
        }
        return code.length() - 1;
    }

    /** コメントを空白に置き換える（文字列 literal と改行は保つ。行番号を変えない）。 */
    static String blankComments(String source) {
        StringBuilder out = new StringBuilder(source);
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"') {
                i = skipString(source, i) + 1;
            } else if (c == '\'') {
                i = skipChar(source, i) + 1;
            } else if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                end = end < 0 ? source.length() : end;
                blank(out, i, end);
                i = end;
            } else if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                end = end < 0 ? source.length() : end + 2;
                blank(out, i, end);
                i = end;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static void blank(StringBuilder out, int start, int end) {
        for (int i = start; i < end; i++) {
            if (out.charAt(i) != '\n') {
                out.setCharAt(i, ' ');
            }
        }
    }

    private static Set<String> names(Pattern declaration, String code) {
        Set<String> names = new LinkedHashSet<>();
        Matcher matcher = declaration.matcher(code);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Map<String, String> constants(String code) {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher matcher = STRING_CONSTANT.matcher(code);
        while (matcher.find()) {
            constants.putIfAbsent(matcher.group(1), matcher.group(2));
        }
        return constants;
    }

    private static String simpleClassName(String path) {
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
    }

    private static int lineOf(String code, int index) {
        int line = 1;
        for (int i = 0; i < index && i < code.length(); i++) {
            if (code.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
