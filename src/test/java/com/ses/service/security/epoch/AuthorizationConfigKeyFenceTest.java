package com.ses.service.security.epoch;

import com.ses.service.impl.SystemConfigServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U3（NF01細粒度epoch計画 §4.5・§6.2、W3-15）: 認可キー台帳 {@link AuthorizationConfigKeys} の閉包をDocker無しで固定する。
 *
 * <ul>
 *   <li>認可判定クラスが {@code systemConfigService.get*} と {@code systemConfigMapper.select*} で読むキーは、すべて台帳にある
 *       （台帳に無いキーを判定に使い始めると、その値の変更で全体epochが進まず、旧提出を確認できてしまう）。</li>
 *   <li>{@code SystemConfigServiceImpl} 以外で {@code m_system_config} を直接書くクラス・{@code put} を呼ぶクラスは、
 *       台帳のキーを使わない（全体epochを進めずに認可キーを書く経路を作らない）。</li>
 *   <li>台帳のキーは画面から更新できる既知キー（{@code SystemConfigServiceImpl} のschema）である。</li>
 * </ul>
 *
 * <p>キーを静的に解決できない呼出し（変数・連結式）は許可ではなく違反にする。</p>
 */
class AuthorizationConfigKeyFenceTest {

    /** 認可判定クラス（計画 §4.5 U3）。DocumentServiceImplはACL部（現状は設定を読まない）を含めてファイル単位で見る。 */
    private static final List<String> AUTHORIZATION_DECISION_SOURCES = List.of(
            "com/ses/service/impl/DataScopeServiceImpl.java",
            "com/ses/service/security/impl/OrganizationScopeServiceImpl.java",
            "com/ses/service/lifecycle/LifecycleScopeService.java",
            "com/ses/service/security/impl/AuthorizationServiceImpl.java",
            "com/ses/service/impl/DocumentServiceImpl.java",
            "com/ses/service/security/ExplicitActorAuthorizationEvaluator.java");

    private static final String SYSTEM_CONFIG_SERVICE_IMPL = "com/ses/service/impl/SystemConfigServiceImpl.java";

    private static final Pattern SERVICE_READ = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*get(?:String|Int|Decimal)\\s*\\(\\s*([^,)]+)");
    private static final Pattern MAPPER_READ = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(?:selectById|selectByIdForUpdate)\\s*\\(\\s*([^,)]+)\\)");
    private static final Pattern MAPPER_WRITE = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(?:insert|insertOrUpdate|update|updateById|delete|deleteById|deleteBatchIds|deleteByMap)\\s*\\(");
    /** 直接書込みクラスでキーを渡す箇所（行の作成・キーの設定・キーでの照会）。 */
    private static final Pattern NEW_CONFIG = Pattern.compile("new\\s+(?:[\\w.]+\\.)?SystemConfig\\s*\\(");
    private static final Pattern SET_CONFIG_KEY = Pattern.compile("\\.\\s*setConfigKey\\s*\\(");
    private static final Pattern EQ_CONFIG_KEY = Pattern.compile("\\.\\s*eq\\s*\\(\\s*(?:SystemConfig::getConfigKey|\"config_key\")\\s*,");
    private static final Pattern MAPPER_KEY_READ = Pattern.compile(
            "\\b(\\w+)\\s*\\.\\s*(?:selectById|selectByIdForUpdate|deleteById)\\s*\\(");
    private static final Pattern SERVICE_PUT = Pattern.compile("\\b(\\w+)\\s*\\.\\s*put\\s*\\(\\s*([^,)]+)\\s*,");

    @Test
    void 認可判定クラスが読む設定キーはすべて台帳にある() {
        List<String> violations = new ArrayList<>();
        int resolvedReads = 0;
        for (String relative : AUTHORIZATION_DECISION_SOURCES) {
            String source = WriterGuardSources.mainSource(relative);
            Map<String, String> constants = WriterGuardSources.stringConstants(source);
            List<String> serviceVariables = WriterGuardSources.variablesOfType(source, "SystemConfigService");
            List<String> mapperVariables = WriterGuardSources.variablesOfType(source, "SystemConfigMapper");
            for (Pattern pattern : List.of(SERVICE_READ, MAPPER_READ)) {
                List<String> variables = pattern == SERVICE_READ ? serviceVariables : mapperVariables;
                Matcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    if (!variables.contains(matcher.group(1))) {
                        continue;
                    }
                    String key = WriterGuardSources.resolveKey(matcher.group(2), constants);
                    if (key == null) {
                        violations.add(relative + ": 設定キーを静的に解決できません: " + matcher.group(2).trim());
                    } else if (!AuthorizationConfigKeys.contains(key)) {
                        violations.add(relative + ": 台帳に無い設定キーを読んでいます: " + key);
                    } else {
                        resolvedReads++;
                    }
                }
            }
        }
        assertEquals(List.of(), violations,
                "認可判定に使う設定キーは AuthorizationConfigKeys へ載せ、変更時に全体epochを進めること（計画 §4.1）");
        // DataScopeServiceImpl（getString）と ExplicitActorAuthorizationEvaluator（selectById）の2件を読めていること。
        assertTrue(resolvedReads >= 2, "認可判定クラスの設定読取を検出できていること: " + resolvedReads);
    }

    @Test
    void SystemConfigServiceImpl以外の直接書込みとputは台帳のキーを使わない() {
        List<String> violations = new ArrayList<>();
        int directWriters = 0;
        int putCalls = 0;
        for (Map.Entry<String, String> entry : WriterGuardSources.mainSources().entrySet()) {
            String relative = entry.getKey();
            String source = entry.getValue();
            if (relative.equals(SYSTEM_CONFIG_SERVICE_IMPL)) {
                continue;
            }
            List<String> mapperVariables = WriterGuardSources.variablesOfType(source, "SystemConfigMapper");
            boolean writesDirectly = false;
            Matcher write = MAPPER_WRITE.matcher(source);
            while (write.find()) {
                if (mapperVariables.contains(write.group(1))) {
                    writesDirectly = true;
                }
            }
            if (writesDirectly) {
                directWriters++;
                if (source.contains("AuthorizationConfigKeys")) {
                    violations.add(relative + ": m_system_configを直接書くクラスが認可キー台帳を参照しています");
                }
                for (String literal : AuthorizationEpochColumnIsolationTest.stringLiterals(source)) {
                    for (String key : AuthorizationConfigKeys.KEYS) {
                        if (literal.contains(key)) {
                            violations.add(relative + ": m_system_configを直接書くクラスが認可キーを扱っています: " + key);
                        }
                    }
                }
                // 動的に組み立てるキーも、定数・literalの完全一致か「定数・literalの接頭辞 + 変数」に解決できなければ違反にする
                // （W3-15 review P3-9。解決した完全一致・接頭辞が認可キーに当たる場合も違反）。
                violations.addAll(directWriteKeyViolations(relative, source, mapperVariables, constants(source)));
            }
            List<String> serviceVariables = WriterGuardSources.variablesOfType(source, "SystemConfigService");
            Map<String, String> constants = WriterGuardSources.stringConstants(source);
            Matcher put = SERVICE_PUT.matcher(source);
            while (put.find()) {
                if (!serviceVariables.contains(put.group(1))) {
                    continue;
                }
                putCalls++;
                String key = WriterGuardSources.resolveKey(put.group(2), constants);
                if (key == null) {
                    violations.add(relative + ": putの設定キーを静的に解決できません: " + put.group(2).trim());
                } else if (AuthorizationConfigKeys.contains(key)) {
                    violations.add(relative + ": 認可キーをputで書いています（updateAllだけが書ける）: " + key);
                }
            }
        }
        assertEquals(List.of(), violations, "認可キーを書けるのは SystemConfigServiceImpl.updateAll だけ（計画 §4.1・§4.5）");
        // 勤怠同期・勤怠差異・会計照合・本番bootstrapの直接書込みと、勤怠同期・ポータル規約のputを検出できていること。
        assertTrue(directWriters >= 4, "m_system_configの直接書込みクラスを検出できていること: " + directWriters);
        assertTrue(putCalls >= 2, "SystemConfigService.putの呼出しを検出できていること: " + putCalls);
    }

    @Test
    void 台帳のキーは画面から更新できる既知の設定キーである() throws Exception {
        Field schemas = SystemConfigServiceImpl.class.getDeclaredField("SCHEMAS");
        schemas.setAccessible(true);
        Map<?, ?> known = (Map<?, ?>) schemas.get(null);
        for (String key : AuthorizationConfigKeys.KEYS) {
            assertTrue(known.containsKey(key), "台帳のキーがSystemConfigServiceImplのschemaにありません: " + key);
        }
        assertTrue(AuthorizationConfigKeys.contains(AuthorizationConfigKeys.SALES_OWN_DATA_ONLY));
        assertTrue(!AuthorizationConfigKeys.contains(null) && !AuthorizationConfigKeys.contains("notice.bench-warn-days"),
                "台帳外のキーとnullは認可キーではない");
    }

    @Test
    void 直接書込みのキー解決は解決できない動的キーと認可キーに当たる接頭辞を違反にする() {
        String source = WriterGuardSources.stripComments("""
                class Sample {
                    static final String SAFE_PREFIX = "attendance.sync.freee.cursor.le.";
                    static final String UNSAFE_PREFIX = "scope.";
                    private final SystemConfigMapper systemConfigMapper;
                    private String cursorKey(Long id) {
                        return id == null ? "attendance.sync.freee.cursor" : SAFE_PREFIX + id;
                    }
                    void safe(Long id) {
                        String key = cursorKey(id);
                        systemConfigMapper.selectById(key);
                        systemConfigMapper.insert(new SystemConfig(key, "v", "d"));
                    }
                    void unsafe(String raw, Long id) {
                        systemConfigMapper.insert(new SystemConfig(raw, "v", "d"));
                        SystemConfig config = new SystemConfig();
                        config.setConfigKey(UNSAFE_PREFIX + id);
                        systemConfigMapper.selectById("scope.sales-own-data-only");
                    }
                }
                """);
        List<String> violations = directWriteKeyViolations("Sample.java", source, List.of("systemConfigMapper"),
                WriterGuardSources.stringConstants(source));
        assertEquals(3, violations.size(), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("raw")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains("scope.")), violations.toString());
        assertTrue(violations.stream().anyMatch(v -> v.contains(AuthorizationConfigKeys.SALES_OWN_DATA_ONLY)),
                violations.toString());
    }

    // ===== 直接書込みのキー解決 =====

    /** キーの解決結果（完全一致、または「接頭辞 + 変数」の接頭辞）。 */
    private record KeyCandidate(String value, boolean prefix) {
    }

    private static Map<String, String> constants(String source) {
        return WriterGuardSources.stringConstants(source);
    }

    /** 直接書込みクラスがキーを渡す各箇所を解決し、解決できない・認可キーに当たるものを違反として返す。 */
    private static List<String> directWriteKeyViolations(String relative, String source, List<String> mapperVariables,
                                                         Map<String, String> constants) {
        List<String> expressions = new ArrayList<>();
        for (Pattern pattern : List.of(NEW_CONFIG, SET_CONFIG_KEY)) {
            Matcher matcher = pattern.matcher(source);
            while (matcher.find()) {
                String first = WriterGuardSources.splitTopLevel(
                        WriterGuardSources.callArguments(source, matcher.end()), ',').get(0);
                if (!first.isEmpty()) {
                    expressions.add(first);
                }
            }
        }
        Matcher eq = EQ_CONFIG_KEY.matcher(source);
        while (eq.find()) {
            // eq(列, 値) の値の側（eq.end() は列の後の区切りの直後で、eqの閉じ括弧までが値）
            expressions.add(WriterGuardSources.splitTopLevel(
                    WriterGuardSources.callArguments(source, eq.end()), ',').get(0));
        }
        Matcher read = MAPPER_KEY_READ.matcher(source);
        while (read.find()) {
            if (mapperVariables.contains(read.group(1))) {
                expressions.add(WriterGuardSources.splitTopLevel(
                        WriterGuardSources.callArguments(source, read.end()), ',').get(0));
            }
        }
        List<String> violations = new ArrayList<>();
        for (String expression : expressions) {
            List<KeyCandidate> candidates = keyCandidates(expression, source, constants, 0);
            if (candidates == null) {
                violations.add(relative + ": m_system_configを直接書くクラスのキーを静的に解決できません: " + expression);
                continue;
            }
            for (KeyCandidate candidate : candidates) {
                boolean conflicts = candidate.prefix()
                        ? candidate.value().isEmpty()
                        || AuthorizationConfigKeys.KEYS.stream().anyMatch(key -> key.startsWith(candidate.value()))
                        : AuthorizationConfigKeys.contains(candidate.value());
                if (conflicts) {
                    violations.add(relative + ": m_system_configを直接書くクラスのキーが認可キーに当たります: "
                            + candidate.value() + (candidate.prefix() ? "（接頭辞）" : ""));
                }
            }
        }
        return violations;
    }

    /**
     * キーの式を完全一致・接頭辞へ解決する。literal・定数・{@code AuthorizationConfigKeys.X}、三項演算子の両辺、
     * {@code 先頭 + ...} の連結（先頭を接頭辞として）、同じファイルの {@code String 変数 = 式;} と
     * {@code メソッド(...) { return 式; }} を3段まで辿る。解決できなければ {@code null}。
     */
    private static List<KeyCandidate> keyCandidates(String expression, String source, Map<String, String> constants,
                                                    int depth) {
        String expr = expression.trim();
        while (expr.startsWith("(") && expr.endsWith(")")
                && WriterGuardSources.callArguments(expr, 1).length() == expr.length() - 2) {
            expr = expr.substring(1, expr.length() - 1).trim();
        }
        if (depth > 3 || expr.isEmpty()) {
            return null;
        }
        List<String> question = WriterGuardSources.splitTopLevel(expr, '?');
        if (question.size() == 2) {
            List<String> branches = WriterGuardSources.splitTopLevel(question.get(1), ':');
            if (branches.size() != 2) {
                return null;
            }
            List<KeyCandidate> left = keyCandidates(branches.get(0), source, constants, depth + 1);
            List<KeyCandidate> right = keyCandidates(branches.get(1), source, constants, depth + 1);
            if (left == null || right == null) {
                return null;
            }
            List<KeyCandidate> both = new ArrayList<>(left);
            both.addAll(right);
            return both;
        }
        List<String> terms = WriterGuardSources.splitTopLevel(expr, '+');
        if (terms.size() > 1) {
            String head = WriterGuardSources.resolveKey(terms.get(0), constants);
            return head == null ? null : List.of(new KeyCandidate(head, true));
        }
        String exact = WriterGuardSources.resolveKey(expr, constants);
        if (exact != null) {
            return List.of(new KeyCandidate(exact, false));
        }
        if (expr.matches("\\w+")) {
            Matcher assignment = Pattern.compile("(?:String|var)\\s+" + Pattern.quote(expr) + "\\s*=\\s*([^;]+);")
                    .matcher(source);
            List<KeyCandidate> all = new ArrayList<>();
            while (assignment.find()) {
                List<KeyCandidate> resolved = keyCandidates(assignment.group(1), source, constants, depth + 1);
                if (resolved == null) {
                    return null;
                }
                all.addAll(resolved);
            }
            return all.isEmpty() ? null : all;
        }
        Matcher call = Pattern.compile("(\\w+)\\s*\\(.*\\)", Pattern.DOTALL).matcher(expr);
        if (call.matches()) {
            Matcher body = Pattern.compile("\\b" + Pattern.quote(call.group(1))
                    + "\\s*\\([^)]*\\)\\s*\\{\\s*return\\s+([^;]+);").matcher(source);
            return body.find() ? keyCandidates(body.group(1), source, constants, depth + 1) : null;
        }
        return null;
    }
}
