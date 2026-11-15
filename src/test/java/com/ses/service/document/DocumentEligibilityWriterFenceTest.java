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
import java.util.TreeSet;
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
 *   <li>{@code DocumentLinkMapper} の挿入・更新・削除は {@link DocumentLinkCommandService} の {@code link}・
 *       {@code recordSkillSheetConfirmation} と、{@code DocumentMetadataCommitService} の初期link（{@code commitNew} だけが
 *       呼ぶ {@code link}）だけ。</li>
 *   <li>{@code DocumentDisposalRequestMapper} の挿入・更新・削除は {@link DocumentEligibilityService} の申請・承認・却下・
 *       執行・失敗記録・保存期限候補のメソッドだけ。</li>
 *   <li>{@code DocumentMapper} の更新・削除（legal_hold_flag・status・deleted_flag。静的には列を判定できないため
 *       更新系の呼出し全体）は {@link DocumentEligibilityService} のhold・執行・確定・AMENDEDと、
 *       {@code DocumentMetadataCommitService.commitVersion}（AMENDED）だけ。</li>
 *   <li>{@code DocumentVersionMapper} の更新・削除（scan状態の変化。現行writerなし、計画 §2.2）はどこにも無い。</li>
 *   <li>上記の表を書く注釈SQL・JdbcTemplate等の文字列が無い（epoch増分の専用SQLを除く）。文書系entityを
 *       {@code ServiceImpl}・{@code IService} で包まない（継承した汎用の書込みを持たせない）。</li>
 *   <li>文書種別 {@code SKILL_SHEET} を登録するのは {@code EngineerChangeRequestServiceImpl.confirmSkillSheet} だけ
 *       （現行契約§2第4段落の起点writer限定を緩和した代わりの固定。計画 §5.1）。</li>
 * </ul>
 *
 * <p>これはwriterの許可一覧であり、新しいwriterを足すと失敗する（漏れが許可ではなく失敗になる）。許可一覧を広げて
 * 通すのではなく、書込みを集約先へ移すこと。許可は集約先のclassだけでなくメソッド単位で持つ（集約先に別の書込み経路を
 * 足しても検出する）。{@link #DELEGATED}（未接続writer）は空でなければならない（W3-21aのCONFIRM開放の前提）。</p>
 *
 * <p>静的走査の限界（計画 §9 RR-10）: 反射・動的proxy・JdbcTemplateに渡す実行時組立てのSQL・手作業のDB変更は検出しない。</p>
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

    /** 守る書込み（mapper型、書込みとみなすメソッド名）。 */
    enum Guarded {
        LINK("DocumentLinkMapper", "(?:insert|update|delete)\\w*"),
        DISPOSAL("DocumentDisposalRequestMapper", "(?:insert|update|delete)\\w*"),
        DOCUMENT_STATE("DocumentMapper", "(?:update|delete)\\w*"),
        VERSION_STATE("DocumentVersionMapper", "(?:update|delete)\\w*");

        final String mapperType;
        final String writeMethods;

        Guarded(String mapperType, String writeMethods) {
            this.mapperType = mapperType;
            this.writeMethods = writeMethods;
        }
    }

    /** 書込みを許すsourceとメソッド（source → 種別 → メソッド名）。ここに無い組合せの書込みはすべて違反。 */
    static final Map<String, Map<Guarded, Set<String>>> ALLOWED = Map.of(
            LINK_COMMAND, Map.of(Guarded.LINK, Set.of("link", "recordSkillSheetConfirmation")),
            METADATA_COMMIT, Map.of(
                    Guarded.LINK, Set.of("link"),
                    Guarded.DOCUMENT_STATE, Set.of("commitVersion")),
            ELIGIBILITY, Map.of(
                    Guarded.DISPOSAL, Set.of("requestDisposal", "approveDisposal", "rejectDisposal", "executeDisposal",
                            "markDisposalStorageFailed", "registerRetentionDisposalCandidate"),
                    Guarded.DOCUMENT_STATE, Set.of("changeLegalHold", "executeDisposal", "confirm", "markAmended")));

    /**
     * 未接続のwriter（source + 種別 → 呼出し件数）。W3-17で資産貸与・返却・紛失のlinkを接続し空になった。
     * CONFIRM（W3-21a）は本一覧が空であることを開放の前提にする（{@link #未接続のwriterは残っていない_W3_21aのCONFIRM開放gate}）。
     */
    static final Map<String, Integer> DELEGATED = Map.of();

    /**
     * {@code DocumentRegisterRequest} の文書種別を変数で渡す登録経路（source → 件数）。いずれも呼出元が許可種別の一覧で
     * 絞っており、SKILL_SHEETを生成しない。新しい経路を足す場合は、SKILL_SHEETを通さないことを確かめてから追加すること。
     */
    static final Map<String, Integer> DYNAMIC_DOCUMENT_TYPE_REGISTRATIONS = Map.of(
            // SIGNED_PDF・ESIGN_CERT の固定値だけを渡す（CloudSignArtifactService.registerRequest の呼出元）
            "com/ses/service/cloudsign/CloudSignArtifactService.java", 1,
            // ComplianceDocumentGenerator.DOCUMENT_TYPES で検証済みの派遣系種別だけを渡す
            "com/ses/service/impl/ComplianceDocumentServiceImpl.java", 1);

    /** 文書の利用可否表を書くSQL（表名の後ろが識別子文字で続かないこと。t_document_type等の別表を拾わない）。 */
    private static final Pattern WRITE_SQL = Pattern.compile(
            "\\b(?:update(?:\\s+ignore)?|insert(?:\\s+ignore)?\\s+into|replace\\s+into|delete\\s+(?:\\w+\\s+)?from)\\s+`?"
                    + "(t_document|t_document_link|t_document_disposal_request|t_document_version)`?(?![A-Za-z0-9_])",
            Pattern.CASE_INSENSITIVE);

    /** 文書系entity・mapperを汎用serviceで包む宣言（継承した save/update/remove を持たせない）。 */
    private static final Pattern GENERIC_SERVICE = Pattern.compile(
            "\\b(?:ServiceImpl|IService)\\s*<[^;{]*?\\b(?:Document|DocumentLink|DocumentDisposalRequest|DocumentVersion)"
                    + "(?:Mapper)?\\s*[,>]");

    private static final String EPOCH_INCREMENT_SQL =
            "SET evidence_eligibility_epoch = evidence_eligibility_epoch + 1";

    @Test
    void 文書利用可否の表へのmapper書込みは集約先の許可メソッドの外に無い() throws IOException {
        Map<String, String> sources = mainSources();
        Map<String, Integer> delegatedSeen = new TreeMap<>();
        List<String> violations = new ArrayList<>();
        Map<String, Integer> allowedCounts = new TreeMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            String code = WriterScanner.code(source.getValue());
            Map<Guarded, Set<String>> allowedMethods = ALLOWED.getOrDefault(source.getKey(), Map.of());
            for (Guarded guarded : Guarded.values()) {
                int calls = WriterScanner.countWrites(code, guarded.mapperType, guarded.writeMethods);
                if (calls == 0) {
                    continue;
                }
                String key = source.getKey() + "|" + guarded.name();
                if (allowedMethods.containsKey(guarded)) {
                    Map<String, Integer> perMethod = WriterScanner.countWritesPerMethod(
                            code, guarded.mapperType, guarded.writeMethods);
                    int inMethods = 0;
                    for (Map.Entry<String, Integer> method : perMethod.entrySet()) {
                        inMethods += method.getValue();
                        if (allowedMethods.get(guarded).contains(method.getKey())) {
                            allowedCounts.merge(key + "#" + method.getKey(), method.getValue(), Integer::sum);
                        } else {
                            violations.add(source.getKey() + "#" + method.getKey() + "|" + guarded.name()
                                    + " (" + method.getValue() + "件)");
                        }
                    }
                    if (inMethods != calls) {
                        violations.add(key + " メソッドの外（field初期化子・初期化block等）に" + (calls - inMethods) + "件");
                    }
                } else if (DELEGATED.containsKey(key)) {
                    delegatedSeen.put(key, calls);
                } else {
                    violations.add(key + " (" + calls + "件)");
                }
            }
        }
        assertEquals(List.of(), violations,
                "文書の証跡利用可否を変える書込みは DocumentLinkCommandService / DocumentEligibilityService / "
                        + "DocumentMetadataCommitService の許可メソッドに集めること（計画 §4.3・U4c）");
        assertEquals(new TreeMap<>(DELEGATED), delegatedSeen, "未接続writerの件数が変わった");
        // 走査が空振りしていないこと（集約先の書込みを数えられている）
        for (String expected : List.of(
                LINK_COMMAND + "|LINK#link", LINK_COMMAND + "|LINK#recordSkillSheetConfirmation",
                METADATA_COMMIT + "|LINK#link", METADATA_COMMIT + "|DOCUMENT_STATE#commitVersion",
                ELIGIBILITY + "|DISPOSAL#requestDisposal", ELIGIBILITY + "|DISPOSAL#approveDisposal",
                ELIGIBILITY + "|DISPOSAL#rejectDisposal", ELIGIBILITY + "|DISPOSAL#executeDisposal",
                ELIGIBILITY + "|DISPOSAL#markDisposalStorageFailed",
                ELIGIBILITY + "|DISPOSAL#registerRetentionDisposalCandidate",
                ELIGIBILITY + "|DOCUMENT_STATE#changeLegalHold", ELIGIBILITY + "|DOCUMENT_STATE#executeDisposal",
                ELIGIBILITY + "|DOCUMENT_STATE#confirm", ELIGIBILITY + "|DOCUMENT_STATE#markAmended")) {
            assertTrue(allowedCounts.getOrDefault(expected, 0) >= 1, expected + " を数えられていること: " + allowedCounts);
        }
    }

    @Test
    void 初期linkの書込みはcommitNewからだけ呼ばれる() throws IOException {
        Map<String, String> bodies = WriterScanner.methodBodies(WriterScanner.code(mainSources().get(METADATA_COMMIT)));
        Pattern linkCall = Pattern.compile("(?<![\\w.])link\\s*\\(");
        List<String> callers = new ArrayList<>();
        for (Map.Entry<String, String> method : bodies.entrySet()) {
            if (!method.getKey().equals("link") && linkCall.matcher(method.getValue()).find()) {
                callers.add(method.getKey());
            }
        }
        assertEquals(List.of("commitNew"), callers,
                "既存文書へのlinkはDocumentLinkCommandService（文書行lock・epoch増分）で作ること。初期linkは新規文書だけ");
    }

    @Test
    void 未接続のwriterは残っていない_W3_21aのCONFIRM開放gate() {
        assertTrue(DELEGATED.isEmpty(), "文書linkの未接続writerが残っている間はCONFIRMを開放しない: " + DELEGATED);
    }

    @Test
    void 文書系entityを汎用serviceで包まない() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> source : mainSources().entrySet()) {
            Matcher matcher = GENERIC_SERVICE.matcher(WriterScanner.code(source.getValue()));
            while (matcher.find()) {
                violations.add(source.getKey() + ": " + matcher.group());
            }
        }
        assertEquals(List.of(), violations, "IService・ServiceImplの継承した書込みはfenceを通らないため使わない");
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
        Map<String, String> sources = mainSources();
        Map<String, Integer> occurrences = new TreeMap<>();
        Map<String, Integer> constantReferences = new TreeMap<>();
        Map<String, Integer> dynamicRegistrations = new TreeMap<>();
        Map<String, Integer> requestTypeSetters = new TreeMap<>();
        for (Map.Entry<String, String> source : sources.entrySet()) {
            int count = 0;
            for (String literal : WriterScanner.stringLiterals(source.getValue())) {
                if (literal.trim().equals("SKILL_SHEET")) {
                    count++;
                }
            }
            if (count > 0) {
                occurrences.put(source.getKey(), count);
            }
            String code = WriterScanner.code(source.getValue());
            int constants = WriterScanner.count(code, "\\bACCEPTED_DOCUMENT_TYPE\\b");
            if (constants > 0 && !source.getKey().equals(EVIDENCE_READER)) {
                constantReferences.put(source.getKey(), constants);
            }
            int dynamic = WriterScanner.nonLiteralRegistrationTypes(WriterScanner.stripComments(source.getValue()));
            if (dynamic > 0) {
                dynamicRegistrations.put(source.getKey(), dynamic);
            }
            int setters = WriterScanner.countWrites(code, "DocumentRegisterRequest", "setDocumentType");
            if (setters > 0) {
                requestTypeSetters.put(source.getKey(), setters);
            }
        }
        // 登録はconfirmSkillSheetの1箇所。判定reader（DocumentEvidenceEligibility）は許可種別の定数として1箇所だけ持つ。
        assertEquals(Map.of(CHANGE_REQUEST, 1, EVIDENCE_READER, 1), occurrences,
                "文書種別SKILL_SHEETの生成者を増やす場合は、証跡適格性（計画 §5.1）の前提を見直すこと");
        assertEquals(Map.of(), constantReferences, "許可種別の定数を登録経路で使い回してSKILL_SHEETを生成しない");
        assertEquals(new TreeMap<>(DYNAMIC_DOCUMENT_TYPE_REGISTRATIONS), dynamicRegistrations,
                "文書種別を変数で渡す登録経路が増えた。SKILL_SHEETを通さないことを確かめて一覧へ理由付きで追加すること");
        assertEquals(Map.of(), requestTypeSetters, "DocumentRegisterRequestの文書種別を後から書き換えない");

        String changeRequest = WriterScanner.stripComments(sources.get(CHANGE_REQUEST));
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
        expected.put("DocumentLinkCommandService#linkExistingDocument", Propagation.MANDATORY);
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
                    @Transactional(rollbackFor = Exception.class)
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
                        if (a) {
                            Runnable r = () -> links.delete(w);
                        }
                    }
                    void other() {
                        var alias = links;
                        alias.insert(a);
                        items.forEach(links::insert);
                    }
                }
                """;
        String code = WriterScanner.code(source);
        assertEquals(7, WriterScanner.countWrites(code, "DocumentLinkMapper", Guarded.LINK.writeMethods));
        assertEquals(Map.of("run", 5, "other", 2),
                WriterScanner.countWritesPerMethod(code, "DocumentLinkMapper", Guarded.LINK.writeMethods));
        assertEquals(0, WriterScanner.countWrites(code, "DocumentMapper", Guarded.DOCUMENT_STATE.writeMethods),
                "ContractDocumentMapper型の変数は数えない");
        assertEquals(0, WriterScanner.countWrites(code, "DocumentDisposalRequestMapper", Guarded.DISPOSAL.writeMethods));
        assertTrue(WRITE_SQL.matcher("UPDATE t_document SET status = 'X'").find());
        assertTrue(WRITE_SQL.matcher("delete dl from t_document_link dl").find());
        assertTrue(WRITE_SQL.matcher("INSERT INTO `t_document_disposal_request` (a) VALUES (1)").find());
        assertFalse(WRITE_SQL.matcher("UPDATE t_document_type SET x = 1").find());
        assertFalse(WRITE_SQL.matcher("INSERT INTO t_document_access_log (a) VALUES (1)").find());
        assertFalse(WRITE_SQL.matcher("SELECT * FROM t_document_link FOR UPDATE").find());
        assertTrue(GENERIC_SERVICE.matcher("class X extends ServiceImpl<DocumentLinkMapper, DocumentLink> {").find());
        assertTrue(GENERIC_SERVICE.matcher("interface Y extends IService<Document> {").find());
        assertFalse(GENERIC_SERVICE.matcher("class Z extends ServiceImpl<ContractDocumentMapper, ContractDocument> {").find());
        assertFalse(GENERIC_SERVICE.matcher("interface W extends IService<DocumentType> {").find());
        assertEquals(1, WriterScanner.nonLiteralRegistrationTypes(
                "DocumentRegisterRequest.builder().title(\"a\").documentType(type).build();"
                        + "DocumentRegisterRequest.builder().documentType(\"RECEIPT\").build();"));
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

        /** class本体の直下にあるメソッド宣言の末尾（名前と引数並び。引数の注釈の括弧は1段まで）。 */
        private static final Pattern METHOD_HEADER = Pattern.compile(
                "(\\w+)\\s*\\((?:[^()]|\\([^()]*\\))*\\)\\s*(?:throws\\s+[\\w.$,\\s]+)?\\s*$");

        private WriterScanner() {
        }

        /** 型 {@code mapperType} で宣言された変数（field・引数・局所変数・var別名）とObjectProvider経由の書込み呼出しを数える。 */
        static int countWrites(String code, String mapperType, String writeMethods) {
            return countCalls(code, variables(code, mapperType), providers(code, mapperType), writeMethods);
        }

        /** {@link #countWrites} をclass直下のメソッドごとに数える（変数の宣言はfile全体から集める）。 */
        static Map<String, Integer> countWritesPerMethod(String code, String mapperType, String writeMethods) {
            Set<String> variables = variables(code, mapperType);
            Set<String> providers = providers(code, mapperType);
            Map<String, Integer> counts = new TreeMap<>();
            for (Map.Entry<String, String> method : methodBodies(code).entrySet()) {
                int calls = countCalls(method.getValue(), variables, providers, writeMethods);
                if (calls > 0) {
                    counts.put(method.getKey(), calls);
                }
            }
            return counts;
        }

        private static Set<String> variables(String code, String mapperType) {
            Set<String> variables = new TreeSet<>();
            Matcher declaration = Pattern.compile("(?<![\\w.])(?:[\\w.]+\\.)?" + mapperType + "\\s+(\\w+)\\s*[;,)=]")
                    .matcher(code);
            while (declaration.find()) {
                variables.add(declaration.group(1));
            }
            // var別名（var alias = documentLinkMapper;）も同じ型として扱う
            boolean added = true;
            while (added) {
                added = false;
                Matcher alias = Pattern.compile("\\bvar\\s+(\\w+)\\s*=\\s*(?:this\\s*\\.\\s*)?(\\w+)\\s*;").matcher(code);
                while (alias.find()) {
                    if (variables.contains(alias.group(2)) && variables.add(alias.group(1))) {
                        added = true;
                    }
                }
            }
            return variables;
        }

        private static Set<String> providers(String code, String mapperType) {
            Set<String> providers = new TreeSet<>();
            Matcher provider = Pattern.compile("ObjectProvider\\s*<\\s*(?:[\\w.]+\\.)?" + mapperType
                    + "\\s*>\\s+(\\w+)\\s*[;,)=]").matcher(code);
            while (provider.find()) {
                providers.add(provider.group(1));
            }
            return providers;
        }

        private static int countCalls(String region, Set<String> variables, Set<String> providers, String writeMethods) {
            int count = 0;
            for (String variable : variables) {
                String name = Pattern.quote(variable);
                count += count(region, "(?<![\\w])" + name + "\\s*\\.\\s*" + writeMethods + "\\s*\\(");
                // メソッド参照（documentLinkMapper::insert）
                count += count(region, "(?<![\\w])" + name + "\\s*::\\s*" + writeMethods + "\\b");
                count += count(region, "(?:UpdateChainWrapper\\s*(?:<[^>]*>)?\\s*\\(|ChainWrappers\\s*\\.\\s*\\w*[uU]pdateChain\\s*\\()"
                        + "\\s*(?:this\\s*\\.\\s*)?" + name + "\\b");
            }
            for (String provider : providers) {
                count += count(region, "(?<![\\w])" + Pattern.quote(provider)
                        + "\\s*\\.\\s*(?:getIfAvailable|getObject|getIfUnique)\\s*\\(\\s*\\)\\s*\\.\\s*" + writeMethods + "\\s*\\(");
            }
            return count;
        }

        /**
         * class本体の直下（深さ1）にあるメソッド・コンストラクタの本体を名前ごとに返す（overloadは連結）。入力は
         * {@link #code} で文字列とコメントを消したもの（括弧の数え違いを防ぐ）。
         */
        static Map<String, String> methodBodies(String code) {
            Map<String, String> bodies = new LinkedHashMap<>();
            int depth = 0;
            int boundary = 0;
            for (int i = 0; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    if (depth == 1) {
                        Matcher header = METHOD_HEADER.matcher(code.substring(boundary, i));
                        if (header.find()) {
                            int end = matchingBrace(code, i);
                            bodies.merge(header.group(1), code.substring(i, end + 1), String::concat);
                            i = end;
                            boundary = end + 1;
                            continue;
                        }
                    }
                    depth++;
                    boundary = i + 1;
                } else if (c == '}') {
                    depth--;
                    boundary = i + 1;
                } else if (c == ';' && depth <= 1) {
                    boundary = i + 1;
                }
            }
            return bodies;
        }

        private static int matchingBrace(String code, int open) {
            int depth = 0;
            for (int i = open; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '{') {
                    depth++;
                } else if (c == '}' && --depth == 0) {
                    return i;
                }
            }
            return code.length() - 1;
        }

        /**
         * {@code DocumentRegisterRequest.builder()} から最初の {@code .build()} までの間で、{@code .documentType(...)} の
         * 引数が文字列literalでない箇所を数える。入力はコメントだけを消したもの（literalを残す）。
         */
        static int nonLiteralRegistrationTypes(String source) {
            int count = 0;
            int from = 0;
            while (true) {
                int start = source.indexOf("DocumentRegisterRequest.builder()", from);
                if (start < 0) {
                    return count;
                }
                int end = source.indexOf(".build()", start);
                String chain = source.substring(start, end < 0 ? source.length() : end);
                Matcher type = Pattern.compile("\\.documentType\\s*\\(\\s*([^)]*)\\)").matcher(chain);
                while (type.find()) {
                    if (!type.group(1).trim().matches("\"[^\"]*\"")) {
                        count++;
                    }
                }
                from = start + 1;
            }
        }

        static int count(String code, String regex) {
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
