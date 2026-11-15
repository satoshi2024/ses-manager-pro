package com.ses.service.document;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * U4c（NF01細粒度epoch計画 §4.5・§6.2、W3-17）: 文書の証跡利用可否を変える書込みが、epochを進める集約先の外に
 * 無いことをDocker無しで固定する。
 *
 * <ul>
 *   <li>{@code DocumentLinkMapper} の挿入・更新・削除は {@link DocumentLinkCommandService} と
 *       {@code DocumentMetadataCommitService}（新規文書の初期link）だけ。</li>
 *   <li>{@code DocumentDisposalRequestMapper} の挿入・更新・削除は {@link DocumentEligibilityService} だけ。</li>
 *   <li>{@code DocumentMapper} の更新・削除（legal_hold_flag・status・deleted_flag。静的には列を判定できないため
 *       更新系の呼出し全体）は {@link DocumentEligibilityService} と {@code DocumentMetadataCommitService}（AMENDED）だけ。</li>
 *   <li>{@code DocumentVersionMapper} の更新・削除（scan状態の変化。現行writerなし、計画 §2.2）はどこにも無い。</li>
 *   <li>上記の表を書く注釈SQL・JdbcTemplate等の文字列が無い（epoch増分の専用SQLを除く）。</li>
 *   <li>文書種別 {@code SKILL_SHEET} を登録するのは {@code EngineerChangeRequestServiceImpl.confirmSkillSheet} だけ
 *       （現行契約§2第4段落の起点writer限定を緩和した代わりの固定。計画 §5.1）。</li>
 * </ul>
 *
 * <p>これはwriterの許可一覧であり、新しいwriterを足すと失敗する（漏れが許可ではなく失敗になる）。許可一覧を広げて
 * 通すのではなく、書込みを集約先へ移すこと。{@link #DELEGATED} は他バッチの所有ファイルのため本バッチで接続していない
 * 既知の未接続writerで、件数が一致しなければ失敗する（接続して0件になったら一覧から外すこと）。</p>
 */
class DocumentEligibilityWriterFenceTest {

    private static final Path JAVA_ROOT = Path.of("src/main/java");
    private static final Path RESOURCE_ROOT = Path.of("src/main/resources");

    private static final String LINK_COMMAND = "com/ses/service/document/DocumentLinkCommandService.java";
    private static final String ELIGIBILITY = "com/ses/service/document/DocumentEligibilityService.java";
    private static final String METADATA_COMMIT = "com/ses/service/impl/DocumentMetadataCommitService.java";
    private static final String EPOCH_MAPPER = "com/ses/mapper/AuthorizationEpochMapper.java";
    private static final String CHANGE_REQUEST = "com/ses/service/changerequest/impl/EngineerChangeRequestServiceImpl.java";
    private static final String EVIDENCE_READER = "com/ses/service/document/DocumentEvidenceEligibility.java";

    /** 守る書込み（mapper型、書込みとみなすメソッド名、許可するsource）。 */
    enum Guarded {
        LINK("DocumentLinkMapper", "(?:insert|update|delete)\\w*", Set.of(LINK_COMMAND, METADATA_COMMIT)),
        DISPOSAL("DocumentDisposalRequestMapper", "(?:insert|update|delete)\\w*", Set.of(ELIGIBILITY)),
        DOCUMENT_STATE("DocumentMapper", "(?:update|delete)\\w*", Set.of(ELIGIBILITY, METADATA_COMMIT)),
        VERSION_STATE("DocumentVersionMapper", "(?:update|delete)\\w*", Set.of());

        final String mapperType;
        final String writeMethods;
        final Set<String> allowedSources;

        Guarded(String mapperType, String writeMethods, Set<String> allowedSources) {
            this.mapperType = mapperType;
            this.writeMethods = writeMethods;
            this.allowedSources = allowedSources;
        }
    }

    /**
     * 他バッチへ委譲した未接続writer（source + 種別 → 呼出し件数）。資産・紛失（AssetAssignmentServiceImpl・
     * AssetLostIncidentServiceImpl）はG4rが変更中のため本バッチでは触らない（W3-17指示）。F-3（linkのtenant未設定）も同様。
     */
    static final Map<String, Integer> DELEGATED = Map.of(
            "com/ses/service/impl/AssetAssignmentServiceImpl.java|LINK", 2,
            "com/ses/service/impl/AssetLostIncidentServiceImpl.java|LINK", 1);

    /** 文書の利用可否表を書くSQL（表名の後ろが識別子文字で続かないこと。t_document_type等の別表を拾わない）。 */
    private static final Pattern WRITE_SQL = Pattern.compile(
            "\\b(?:update(?:\\s+ignore)?|insert(?:\\s+ignore)?\\s+into|replace\\s+into|delete\\s+(?:\\w+\\s+)?from)\\s+`?"
                    + "(t_document|t_document_link|t_document_disposal_request|t_document_version)`?(?![A-Za-z0-9_])",
            Pattern.CASE_INSENSITIVE);

    private static final String EPOCH_INCREMENT_SQL =
            "SET evidence_eligibility_epoch = evidence_eligibility_epoch + 1";

    @Test
    void 文書利用可否の表へのmapper書込みは集約先の外に無い() throws IOException {
        Map<String, String> sources = mainSources();
        Map<String, Integer> delegatedSeen = new TreeMap<>();
        List<String> violations = new ArrayList<>();
        Map<String, Integer> allowedCounts = new TreeMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String code = WriterScanner.code(source.getValue());
            for (Guarded guarded : Guarded.values()) {
                int calls = WriterScanner.countWrites(code, guarded.mapperType, guarded.writeMethods);
                if (calls == 0) {
                    continue;
                }
                String key = source.getKey() + "|" + guarded.name();
                if (guarded.allowedSources.contains(source.getKey())) {
                    allowedCounts.merge(key, calls, Integer::sum);
                } else if (DELEGATED.containsKey(key)) {
                    delegatedSeen.put(key, calls);
                } else {
                    violations.add(key + " (" + calls + "件)");
                }
            }
        }
        assertEquals(List.of(), violations,
                "文書の証跡利用可否を変える書込みは DocumentLinkCommandService / DocumentEligibilityService / "
                        + "DocumentMetadataCommitService に集めること（計画 §4.3・U4c）");
        assertEquals(new TreeMap<>(DELEGATED), delegatedSeen,
                "委譲中の未接続writerの件数が変わった。接続したら DELEGATED から外し、増えたなら集約先へ移すこと");
        // 走査が空振りしていないこと（集約先の書込みを数えられている）
        assertTrue(allowedCounts.getOrDefault(LINK_COMMAND + "|LINK", 0) >= 2, allowedCounts.toString());
        assertTrue(allowedCounts.getOrDefault(METADATA_COMMIT + "|LINK", 0) >= 1, allowedCounts.toString());
        assertTrue(allowedCounts.getOrDefault(ELIGIBILITY + "|DISPOSAL", 0) >= 6, allowedCounts.toString());
        assertTrue(allowedCounts.getOrDefault(ELIGIBILITY + "|DOCUMENT_STATE", 0) >= 4, allowedCounts.toString());
        assertTrue(allowedCounts.getOrDefault(METADATA_COMMIT + "|DOCUMENT_STATE", 0) >= 1, allowedCounts.toString());
    }

    @Test
    void 文書利用可否の表を書くSQL文字列はepoch増分の専用SQLの外に無い() throws IOException {
        List<String> violations = new ArrayList<>();
        int epochIncrements = 0;
        for (Map.Entry<String, String> source : mainSources().entrySet()) {
            for (String literal : WriterScanner.stringLiterals(source.getValue())) {
                if (!WRITE_SQL.matcher(literal).find()) {
                    continue;
                }
                if (source.getKey().equals(EPOCH_MAPPER) && literal.contains(EPOCH_INCREMENT_SQL)) {
                    epochIncrements++;
                    continue;
                }
                violations.add(source.getKey() + ": \"" + abbreviate(literal) + "\"");
            }
        }
        try (Stream<Path> files = Files.walk(RESOURCE_ROOT)) {
            for (Path path : files.filter(Files::isRegularFile).toList()) {
                String relative = RESOURCE_ROOT.relativize(path).toString().replace('\\', '/');
                if (relative.startsWith("db/") || !(relative.endsWith(".sql") || relative.endsWith(".xml"))) {
                    continue;
                }
                if (WRITE_SQL.matcher(Files.readString(path, StandardCharsets.UTF_8)).find()) {
                    violations.add(relative);
                }
            }
        }
        assertEquals(List.of(), violations, "文書の利用可否表を書くSQLは集約先のmapper呼出しで行うこと（U4c）");
        assertEquals(1, epochIncrements, "AuthorizationEpochMapperの文書epoch増分SQLを読めていること");
    }

    @Test
    void SKILL_SHEETを登録するのはconfirmSkillSheetだけ() throws IOException {
        Map<String, Integer> occurrences = new TreeMap<>();
        for (Map.Entry<String, String> source : mainSources().entrySet()) {
            int count = 0;
            for (String literal : WriterScanner.stringLiterals(source.getValue())) {
                if ("SKILL_SHEET".equals(literal.trim())) {
                    count++;
                }
            }
            if (count > 0) {
                occurrences.put(source.getKey(), count);
            }
        }
        // 登録はconfirmSkillSheetの1箇所。判定reader（DocumentEvidenceEligibility）は許可種別の定数として1箇所だけ持つ。
        assertEquals(Map.of(CHANGE_REQUEST, 1, EVIDENCE_READER, 1), occurrences,
                "文書種別SKILL_SHEETの生成者を増やす場合は、証跡適格性（計画 §5.1）の前提を見直すこと");

        String changeRequest = WriterScanner.stripComments(mainSources().get(CHANGE_REQUEST));
        int method = changeRequest.indexOf("public SkillSheetConfirmResult confirmSkillSheet(");
        int register = changeRequest.indexOf(".documentType(\"SKILL_SHEET\")");
        int nextMethod = changeRequest.indexOf("@Override", method + 1);
        assertTrue(method > 0 && register > method && (nextMethod < 0 || register < nextMethod),
                "SKILL_SHEETの登録はconfirmSkillSheetの中にあること");
    }

    @Test
    void 集約先のwriterは呼出元のtransactionに参加し保存期限jobと失敗記録だけが自前のtransactionを持つ() {
        Map<String, Propagation> expected = new LinkedHashMap<>();
        expected.put("DocumentLinkCommandService#link", Propagation.MANDATORY);
        expected.put("DocumentLinkCommandService#recordSkillSheetConfirmation", Propagation.MANDATORY);
        expected.put("DocumentEligibilityService#changeLegalHold", Propagation.MANDATORY);
        expected.put("DocumentEligibilityService#requestDisposal", Propagation.MANDATORY);
        expected.put("DocumentEligibilityService#approveDisposal", Propagation.MANDATORY);
        expected.put("DocumentEligibilityService#rejectDisposal", Propagation.MANDATORY);
        expected.put("DocumentEligibilityService#executeDisposal", Propagation.MANDATORY);
        expected.put("DocumentEligibilityService#confirm", Propagation.MANDATORY);
        expected.put("DocumentEligibilityService#markAmended", Propagation.MANDATORY);
        // 保存期限jobはtransactionを持たず文書ごとに呼ぶ（文書ごとの短tx）。
        expected.put("DocumentEligibilityService#registerRetentionDisposalCandidate", Propagation.REQUIRED);
        // afterCommitから呼ぶため、元のtransactionに参加しない。
        expected.put("DocumentEligibilityService#markDisposalStorageFailed", Propagation.REQUIRES_NEW);

        Map<String, Propagation> actual = new LinkedHashMap<>();
        for (Class<?> type : List.of(DocumentLinkCommandService.class, DocumentEligibilityService.class)) {
            for (Method method : type.getDeclaredMethods()) {
                if (!Modifier.isPublic(method.getModifiers())) {
                    continue;
                }
                Transactional tx = method.getAnnotation(Transactional.class);
                assertTrue(tx != null, type.getSimpleName() + "#" + method.getName() + " は@Transactionalを持つこと");
                assertTrue(List.of(tx.rollbackFor()).contains(Exception.class), method.getName());
                actual.put(type.getSimpleName() + "#" + method.getName(), tx.propagation());
            }
        }
        assertEquals(new TreeMap<>(expected), new TreeMap<>(actual));
    }

    @Test
    void 証跡適格性readerは操作者とtenant_contextを読まない() throws IOException {
        for (String relative : List.of(EVIDENCE_READER, "com/ses/service/document/DocumentEvidenceStorageVerifier.java")) {
            String code = WriterScanner.stripComments(mainSources().get(relative));
            for (String forbidden : List.of("SecurityContextHolder", "SecurityUtils", "AccountingTenantContextHolder",
                    "requireTenantContext", "LoginUser")) {
                assertFalse(code.contains(forbidden), relative + " は " + forbidden + " を使わない（actorに依存しない判定）");
            }
        }
    }

    @Test
    void 走査器はコメントを除き型で宣言した変数の書込みだけを数える() {
        String source = """
                class Sample {
                    private final DocumentLinkMapper links;
                    private final ContractDocumentMapper documentMapper;
                    private final ObjectProvider<DocumentLinkMapper> linkProvider;
                    void run(DocumentDisposalRequestMapper requests) {
                        // links.insert(x) はコメント
                        /* links.update(null, w) もコメント */
                        String s = "links.delete(1) は文字列 // ではない";
                        links.insert(a);
                        this.links.updateById(b);
                        documentMapper.update(null, w);
                        requests.selectCount(q);
                        linkProvider.getIfAvailable().deleteById(3);
                        new LambdaUpdateChainWrapper<>(links).set(c, 1).update();
                    }
                }
                """;
        String code = WriterScanner.code(source);
        assertEquals(4, WriterScanner.countWrites(code, "DocumentLinkMapper", Guarded.LINK.writeMethods));
        assertEquals(0, WriterScanner.countWrites(code, "DocumentMapper", Guarded.DOCUMENT_STATE.writeMethods),
                "ContractDocumentMapper型の変数は数えない");
        assertEquals(0, WriterScanner.countWrites(code, "DocumentDisposalRequestMapper", Guarded.DISPOSAL.writeMethods));
        assertTrue(WRITE_SQL.matcher("UPDATE t_document SET status = 'X'").find());
        assertTrue(WRITE_SQL.matcher("delete dl from t_document_link dl").find());
        assertTrue(WRITE_SQL.matcher("INSERT INTO `t_document_disposal_request` (a) VALUES (1)").find());
        assertFalse(WRITE_SQL.matcher("UPDATE t_document_type SET x = 1").find());
        assertFalse(WRITE_SQL.matcher("INSERT INTO t_document_access_log (a) VALUES (1)").find());
        assertFalse(WRITE_SQL.matcher("SELECT * FROM t_document_link FOR UPDATE").find());
    }

    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(JAVA_ROOT)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".java")).toList()) {
                sources.put(JAVA_ROOT.relativize(path).toString().replace('\\', '/'),
                        Files.readString(path, StandardCharsets.UTF_8));
            }
        }
        assertTrue(sources.containsKey(LINK_COMMAND) && sources.containsKey(ELIGIBILITY), "src/main/javaを読めていること");
        return sources;
    }

    private static String abbreviate(String literal) {
        String flat = literal.replaceAll("\\s+", " ").trim();
        return flat.length() <= 120 ? flat : flat.substring(0, 120) + "...";
    }

    /** Javaソースの簡易走査（コメント除去・文字列literal抽出・型で宣言した変数の書込み呼出しの計数）。 */
    static final class WriterScanner {

        private WriterScanner() {
        }

        /** 型 {@code mapperType} で宣言された変数（field・引数・局所変数）と、そのObjectProvider経由の書込み呼出しを数える。 */
        static int countWrites(String code, String mapperType, String writeMethods) {
            Set<String> variables = new java.util.TreeSet<>();
            Matcher declaration = Pattern.compile("(?<![\\w.])(?:[\\w.]+\\.)?" + mapperType + "\\s+(\\w+)\\s*[;,)=]")
                    .matcher(code);
            while (declaration.find()) {
                variables.add(declaration.group(1));
            }
            Set<String> providers = new java.util.TreeSet<>();
            Matcher provider = Pattern.compile("ObjectProvider\\s*<\\s*(?:[\\w.]+\\.)?" + mapperType
                    + "\\s*>\\s+(\\w+)\\s*[;,)=]").matcher(code);
            while (provider.find()) {
                providers.add(provider.group(1));
            }
            int count = 0;
            for (String variable : variables) {
                count += count(code, "(?<![\\w])" + Pattern.quote(variable) + "\\s*\\.\\s*" + writeMethods + "\\s*\\(");
                count += count(code, "(?:UpdateChainWrapper\\s*(?:<[^>]*>)?\\s*\\(|ChainWrappers\\s*\\.\\s*\\w*[uU]pdateChain\\s*\\()"
                        + "\\s*(?:this\\s*\\.\\s*)?" + Pattern.quote(variable) + "\\b");
            }
            for (String name : providers) {
                count += count(code, "(?<![\\w])" + Pattern.quote(name)
                        + "\\s*\\.\\s*(?:getIfAvailable|getObject|getIfUnique)\\s*\\(\\s*\\)\\s*\\.\\s*" + writeMethods + "\\s*\\(");
            }
            return count;
        }

        private static int count(String code, String regex) {
            Matcher matcher = Pattern.compile(regex).matcher(code);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }

        /** コメントと文字列literalの中身を除いたコード（呼出しの計数用。文字列中の「x.insert(」を数えない）。 */
        static String code(String source) {
            return strip(source, true);
        }

        /** コメントを空白に置き換える（文字列literal・text block・文字literalの中身は残す）。 */
        static String stripComments(String source) {
            return strip(source, false);
        }

        private static String strip(String source, boolean blankLiterals) {
            StringBuilder out = new StringBuilder(source.length());
            int i = 0;
            int length = source.length();
            while (i < length) {
                char c = source.charAt(i);
                if (c == '/' && i + 1 < length && source.charAt(i + 1) == '/') {
                    int end = source.indexOf('\n', i);
                    i = end < 0 ? length : end;
                } else if (c == '/' && i + 1 < length && source.charAt(i + 1) == '*') {
                    int end = source.indexOf("*/", i + 2);
                    i = end < 0 ? length : end + 2;
                    out.append(' ');
                } else if (source.startsWith("\"\"\"", i)) {
                    int end = source.indexOf("\"\"\"", i + 3);
                    while (end > 0 && source.charAt(end - 1) == '\\') {
                        end = source.indexOf("\"\"\"", end + 1);
                    }
                    int stop = end < 0 ? length : end + 3;
                    out.append(blankLiterals ? "\"\"" : source.substring(i, stop));
                    i = stop;
                } else if (c == '"' || c == '\'') {
                    int end = i + 1;
                    while (end < length && source.charAt(end) != c && source.charAt(end) != '\n') {
                        end += source.charAt(end) == '\\' ? 2 : 1;
                    }
                    int stop = Math.min(length, end + 1);
                    out.append(blankLiterals ? "\"\"" : source.substring(i, stop));
                    i = stop;
                } else {
                    out.append(c);
                    i++;
                }
            }
            return out.toString();
        }

        /** 文字列literal（通常文字列とtext block）の中身を取り出す。コメントと文字literalは除く。 */
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
    }
}
