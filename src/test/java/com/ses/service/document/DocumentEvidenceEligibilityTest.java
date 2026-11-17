package com.ses.service.document;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.ses.entity.Document;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.entity.DocumentLink;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentLinkMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.document.DocumentEvidenceEligibility.Candidate;
import com.ses.service.document.DocumentEvidenceEligibility.Result;
import com.ses.service.document.DocumentEvidenceEligibility.Verdict;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * U9（NF01細粒度epoch計画 §5.1 条件3〜9・§5.9・§6.2）: {@link DocumentEvidenceEligibility} が条件ごとの否定を
 * 期待の結果（HTTP状態・message key・拒否reason）にし、tenantの一致しないlink行を含めばfail-closedにすることを確かめる。
 */
class DocumentEvidenceEligibilityTest {

    private static final String TENANT = "tenant-a";
    private static final long DOCUMENT_ID = 11L;
    private static final long VERSION_ID = 12L;
    private static final long ENGINEER_ID = 7L;
    private static final String SHA = "ab".repeat(32);

    private DocumentMapper documentMapper;
    private DocumentVersionMapper versionMapper;
    private DocumentLinkMapper linkMapper;
    private DocumentDisposalRequestMapper disposalMapper;
    private DocumentEvidenceEligibility eligibility;

    private Document document;
    private DocumentVersion version;
    private List<DocumentLink> links;
    private long blockingDisposals;

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DocumentVersion.class);
        TableInfoHelper.initTableInfo(assistant, DocumentDisposalRequest.class);
    }

    @BeforeEach
    void setUp() {
        documentMapper = mock(DocumentMapper.class);
        versionMapper = mock(DocumentVersionMapper.class);
        linkMapper = mock(DocumentLinkMapper.class);
        disposalMapper = mock(DocumentDisposalRequestMapper.class);
        eligibility = new DocumentEvidenceEligibility(documentMapper, versionMapper, linkMapper, disposalMapper);

        document = new Document();
        document.setId(DOCUMENT_ID);
        document.setTenantId(TENANT);
        document.setDocumentType("SKILL_SHEET");
        document.setStatus("DRAFT");
        document.setLegalHoldFlag(0);
        document.setDeletedFlag(0);
        version = new DocumentVersion();
        version.setId(VERSION_ID);
        version.setTenantId(TENANT);
        version.setDocumentId(DOCUMENT_ID);
        version.setVersionNo(2);
        version.setStorageKey("k/1");
        version.setSha256(SHA);
        version.setSizeBytes(100L);
        version.setScanStatus("CLEAN");
        links = new ArrayList<>(List.of(link(TENANT, "ENGINEER", ENGINEER_ID)));
        blockingDisposals = 0L;

        when(documentMapper.selectEvidenceCandidate(DOCUMENT_ID, TENANT)).thenAnswer(invocation -> document);
        when(versionMapper.selectOne(any())).thenAnswer(invocation -> version);
        when(linkMapper.findByDocumentId(DOCUMENT_ID)).thenAnswer(invocation -> links);
        when(disposalMapper.selectCount(any())).thenAnswer(invocation -> blockingDisposals);
    }

    @Test
    void 全条件を満たすSKILL_SHEETの版は適格で版の不変事実を返す() {
        for (String status : List.of("DRAFT", "CONFIRMED", "AMENDED")) {
            document.setStatus(status);
            Result result = evaluate();
            assertEquals(Verdict.ELIGIBLE, result.verdict(), status);
            assertTrue(result.eligible());
            assertEquals(new DocumentEvidenceEligibility.EligibleVersion(TENANT, DOCUMENT_ID, VERSION_ID, 2, "k/1", SHA, 100L),
                    result.version());
        }
    }

    @Test
    void 版IDの無い指定はDBを読まずに拒否する() {
        Result result = eligibility.evaluate(new Candidate(TENANT, DOCUMENT_ID, null, ENGINEER_ID));

        assertVerdict(Verdict.VERSION_REQUIRED, 400, "error.lifecycle.evidenceVersionRequired",
                "EVIDENCE_VERSION_REQUIRED", result);
        verifyNoInteractions(documentMapper, versionMapper, linkMapper, disposalMapper);
    }

    @Test
    void 条件3_文書が無い他tenant削除済み() {
        expect(Verdict.DOCUMENT_NOT_FOUND, d -> document = null);
        expect(Verdict.DOCUMENT_NOT_FOUND, d -> document.setTenantId("tenant-other"));
        expect(Verdict.DOCUMENT_UNAVAILABLE, d -> document.setDeletedFlag(1));
        assertVerdict(Verdict.DOCUMENT_NOT_FOUND, 400, "error.document.notFound", "NOT_FOUND_OR_FOREIGN",
                Result.denied(Verdict.DOCUMENT_NOT_FOUND));
    }

    @Test
    void 条件4_SKILL_SHEET以外の種別は既知未知を問わず拒否する() {
        for (String type : Arrays.asList("CONTRACT", "RECEIPT", "CHANGE_REQUEST_ATTACHMENT", "FUTURE_TYPE", "skill_sheet", null)) {
            expect(Verdict.DOCUMENT_TYPE_UNSUPPORTED, d -> document.setDocumentType(type));
        }
        assertVerdict(Verdict.DOCUMENT_TYPE_UNSUPPORTED, 400, "error.lifecycle.evidenceDocumentTypeUnsupported",
                "EVIDENCE_TYPE_UNSUPPORTED", Result.denied(Verdict.DOCUMENT_TYPE_UNSUPPORTED));
    }

    @Test
    void 条件5と6_状態不適合と法的holdは利用不可() {
        for (String status : Arrays.asList("DISPOSED", "CANCELLED", "UNKNOWN", null)) {
            expect(Verdict.DOCUMENT_UNAVAILABLE, d -> document.setStatus(status));
        }
        expect(Verdict.DOCUMENT_UNAVAILABLE, d -> document.setLegalHoldFlag(1));
        expect(Verdict.DOCUMENT_UNAVAILABLE, d -> document.setLegalHoldFlag(null));
        assertVerdict(Verdict.DOCUMENT_UNAVAILABLE, 409, "error.lifecycle.evidenceDocumentUnavailable",
                "EVIDENCE_UNAVAILABLE", Result.denied(Verdict.DOCUMENT_UNAVAILABLE));
    }

    @Test
    void 条件7_却下以外の廃棄申請があれば利用不可() {
        expect(Verdict.DOCUMENT_UNAVAILABLE, d -> blockingDisposals = 1L);
    }

    @Test
    void 条件8_link形の不適合はtenantを問わず数えてfail_closedにする() {
        expect(Verdict.TARGET_UNSUPPORTED, d -> links.clear());
        expect(Verdict.TARGET_UNSUPPORTED, d -> links.add(link(TENANT, "CUSTOMER", 3L)));
        // F-3修正前のtenant未設定linkは列DEFAULTの'default'になる。1件でもあれば正しいlinkと併存しても不適格
        expect(Verdict.TARGET_UNSUPPORTED, d -> links.add(link("default", "ASSET_ASSIGNMENT", 4L)));
        expect(Verdict.TARGET_UNSUPPORTED, d -> links.set(0, link("default", "ENGINEER", ENGINEER_ID)));
        expect(Verdict.TARGET_UNSUPPORTED, d -> links.set(0, link(TENANT, "ASSET_ASSIGNMENT", ENGINEER_ID)));
        expect(Verdict.TARGET_UNSUPPORTED, d -> links.set(0, link(TENANT, "ENGINEER", ENGINEER_ID + 1)));
        assertVerdict(Verdict.TARGET_UNSUPPORTED, 400, "error.lifecycle.evidenceDocumentTargetUnsupported",
                "EVIDENCE_TARGET_UNSUPPORTED", Result.denied(Verdict.TARGET_UNSUPPORTED));
    }

    @Test
    void 条件9_版の不一致とCLEAN以外() {
        expect(Verdict.VERSION_NOT_FOUND, d -> version = null);
        expect(Verdict.VERSION_NOT_FOUND, d -> version.setTenantId("tenant-other"));
        expect(Verdict.VERSION_NOT_FOUND, d -> version.setDocumentId(DOCUMENT_ID + 1));
        for (String scan : Arrays.asList("PENDING", "INFECTED", "FAILED", null)) {
            expect(Verdict.VERSION_NOT_CLEAN, d -> version.setScanStatus(scan));
        }
        assertVerdict(Verdict.VERSION_NOT_FOUND, 400, "error.document.notFound", "NOT_FOUND_OR_FOREIGN",
                Result.denied(Verdict.VERSION_NOT_FOUND));
        assertVerdict(Verdict.VERSION_NOT_CLEAN, 400, "error.lifecycle.evidenceVersionNotClean",
                "EVIDENCE_VERSION_NOT_CLEAN", Result.denied(Verdict.VERSION_NOT_CLEAN));
    }

    @Test
    void 判定は条件の順に最初の不成立を返す() {
        // 種別も状態もlinkも不適合なら、先に評価する種別（条件4）の結果になる
        document.setDocumentType("CONTRACT");
        document.setStatus("DISPOSED");
        links.clear();
        assertEquals(Verdict.DOCUMENT_TYPE_UNSUPPORTED, evaluate().verdict());
        // 状態と版が不適合なら状態（条件5）
        document.setDocumentType("SKILL_SHEET");
        version.setScanStatus("PENDING");
        assertEquals(Verdict.DOCUMENT_UNAVAILABLE, evaluate().verdict());
    }

    @Test
    void 判定対象と結果の不正な組は作れない() {
        assertThrows(IllegalArgumentException.class, () -> new Candidate(" ", DOCUMENT_ID, VERSION_ID, ENGINEER_ID));
        assertThrows(IllegalArgumentException.class, () -> new Candidate(TENANT, 0, VERSION_ID, ENGINEER_ID));
        assertThrows(IllegalArgumentException.class, () -> new Candidate(TENANT, DOCUMENT_ID, VERSION_ID, 0));
        assertThrows(IllegalArgumentException.class, () -> new Result(Verdict.ELIGIBLE, null));
        assertNull(Result.denied(Verdict.DOCUMENT_UNAVAILABLE).version());
    }

    /**
     * W3-21a review P3-5・RB-NF01 review RB-P3-1: 提出の discovery が適格性の詳細を返してよいのは、文書が tenant にある
     * {@code SKILL_SHEET} で、案件の要員を指す link を持つ場合だけ。不在・他tenant・SKILL_SHEET以外（削除済みでも、
     * 文書ダウンロードの専用ACL種別でも）・link無し・他の要員だけを指す文書は false（存在しない文書と同じ応答にする）。
     * 論理削除・hold・link形の不適合は、可視のSKILL_SHEETについてだけ適格性の条件が判定する。
     */
    @Test
    void 文書の可視性は案件の要員を指すlinkがある同tenantのSKILL_SHEETだけを真とする() {
        assertTrue(eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID));
        // 適格性では拒否されるが、要員の SKILL_SHEET なので可視（詳細の応答を返してよい）
        document.setDeletedFlag(1);
        document.setLegalHoldFlag(1);
        links.add(link(TENANT, "ASSET_ASSIGNMENT", 99L));
        assertTrue(eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID));

        // SKILL_SHEET 以外は、削除済みでも削除前でも、文書ダウンロードのACLが厳しい種別でも、要員を指す link があっても不可視
        for (String type : Arrays.asList("RECEIPT", "CHANGE_REQUEST_ATTACHMENT", "PRIVATE_NOTE", "INVOICE_IN",
                "INVOICE_OUT", "CONTRACT", "skill_sheet", "SKILL_SHEET ", null)) {
            for (int deleted : new int[] {0, 1}) {
                setUp();
                document.setDocumentType(type);
                document.setDeletedFlag(deleted);
                assertEquals(false, eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID),
                        type + " deleted=" + deleted);
            }
        }

        setUp();
        assertEquals(false, eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID + 1), "他の要員の文書");
        links.set(0, link(TENANT, "ASSET_ASSIGNMENT", ENGINEER_ID));
        assertEquals(false, eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID), "要員以外を指すlink");
        links.set(0, link("tenant-other", "ENGINEER", ENGINEER_ID));
        assertEquals(false, eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID), "他tenantのlink");
        links.clear();
        assertEquals(false, eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID), "linkが無い");

        setUp();
        document.setTenantId("tenant-other");
        assertEquals(false, eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID), "他tenantの文書");
        document = null;
        assertEquals(false, eligibility.visibleEvidenceCandidate(TENANT, DOCUMENT_ID, ENGINEER_ID), "文書が無い");
        assertEquals(false, eligibility.visibleEvidenceCandidate(" ", DOCUMENT_ID, ENGINEER_ID), "tenantが空");
    }

    /**
     * RB-NF01 review RB-P3-1: 可視性の前に呼出側が行う文書ACL（管理者、または要員の data scope）が文書ダウンロード
     * （{@code DocumentServiceImpl#assertDocumentAccessAllowed}）と同じ判定になるのは、受け付ける唯一の種別
     * {@code SKILL_SHEET} がダウンロードの専用ACL種別にも電子インボイスにも当たらず、{@code ENGINEER} link の data scope の分岐を
     * 通るためである。種別ごとの規則に SKILL_SHEET が加わったら、二者確認の可視性も同じ規則に合わせ直す。
     */
    @Test
    void 受け付ける種別は文書ダウンロードの専用ACL種別にも電子インボイスにも当たらない() throws Exception {
        for (String field : List.of("FILE_SCOPE_SPECIAL_DOCUMENT_TYPES", "DIGITAL_INVOICE_DOCUMENT_TYPES")) {
            java.lang.reflect.Field declared = com.ses.service.impl.DocumentServiceImpl.class.getDeclaredField(field);
            declared.setAccessible(true);
            java.util.Set<?> types = (java.util.Set<?>) declared.get(null);
            assertTrue(!types.isEmpty(), field + " が読めること");
            assertTrue(!types.contains(DocumentEvidenceEligibility.ACCEPTED_DOCUMENT_TYPE),
                    field + " に " + DocumentEvidenceEligibility.ACCEPTED_DOCUMENT_TYPE + " が無いこと");
        }
    }

    /**
     * RB-NF01 delta review D-P3-1: 上の反射は2つの定数しか見ない。ダウンロード ACL には、method の中に直接書いた種別の規則
     * （HR の ORDER_*／ACCEPTANCE 拒否、ACCEPTANCE の契約 scope）と、{@code FileScopeValidationService} 自身の種別ごとの規則が
     * ある。どちらかへ SKILL_SHEET が加わると、二者確認の可視性（ENGINEER link の data scope だけを見る）がダウンロードより
     * 広くなり得るため、source を読んで次を固定する。
     * <ul>
     *   <li>{@code DocumentServiceImpl#assertDocumentAccessAllowed} と、そこから呼ぶ同じ file の private method の本体に書いた
     *       種別らしい文字列（大文字・数字・{@code _}）と、参照する {@code *_DOCUMENT_TYPE(S)} 定数（反射で値を読む）に
     *       SKILL_SHEET が無い。</li>
     *   <li>{@code FileScopeValidationService} で {@code documentType} と比べる種別と {@code *_DOCUMENT_TYPE(S)} 定数に SKILL_SHEET が
     *       無い。</li>
     * </ul>
     * method 名の変更や抽出の失敗は、既知の種別が見つからないことで失敗させる（tripwire）。
     */
    @Test
    void ダウンロードACLのmethodに直接書いた種別とFileScopeValidationServiceの種別にもSKILL_SHEETが無い() throws Exception {
        String accepted = DocumentEvidenceEligibility.ACCEPTED_DOCUMENT_TYPE;
        java.nio.file.Path documentService = java.nio.file.Path.of(
                "src/main/java/com/ses/service/impl/DocumentServiceImpl.java");
        String source = com.ses.testsupport.JavaSourceText.stripComments(
                java.nio.file.Files.readString(documentService, java.nio.charset.StandardCharsets.UTF_8));
        String body = methodBody(source, "public void assertDocumentAccessAllowed(Document doc)");
        StringBuilder scanned = new StringBuilder(body);
        // 呼出し先の private method を推移的にたどる（isDigitalInvoiceDocument のように、さらに先で定数を参照する）。
        java.util.Set<String> helpers = new java.util.TreeSet<>();
        java.util.Deque<String> pending = new java.util.ArrayDeque<>(List.of(body));
        while (!pending.isEmpty()) {
            java.util.regex.Matcher call = java.util.regex.Pattern.compile("\\b([a-z]\\w*)\\s*\\(").matcher(pending.pop());
            while (call.find()) {
                String name = call.group(1);
                java.util.regex.Matcher declaration = java.util.regex.Pattern.compile(
                        "private\\s+[\\w<>.,\\s]+\\s" + name + "\\s*\\([^)]*\\)\\s*\\{").matcher(source);
                if (declaration.find() && helpers.add(name)) {
                    String helperBody = methodBody(source, declaration.group());
                    scanned.append('\n').append(helperBody);
                    pending.push(helperBody);
                }
            }
        }
        assertTrue(helpers.contains("assertDigitalInvoiceDocumentAccess") && helpers.contains("assertFileScopeSpecialDocumentAccess"),
                "前提: 呼出し先の private method も読む: " + helpers);
        java.util.Set<String> literals = documentTypeLiterals(scanned.toString());
        assertTrue(literals.containsAll(List.of("ORDER_RECEIVED", "ORDER_ACKNOWLEDGEMENT", "ACCEPTANCE")),
                "前提: method に直接書いた種別を読めている: " + literals);
        assertTrue(!literals.contains(accepted), "ダウンロード ACL の method に直接書いた種別に " + accepted + " が無いこと");
        java.util.Set<String> constants = constantReferences(scanned.toString());
        assertTrue(constants.containsAll(List.of("FILE_SCOPE_SPECIAL_DOCUMENT_TYPES", "DIGITAL_INVOICE_DOCUMENT_TYPES")),
                "前提: 参照する種別の定数を読めている: " + constants);
        for (String constant : constants) {
            assertTrue(!constantValues(com.ses.service.impl.DocumentServiceImpl.class, constant).contains(accepted),
                    constant + " に " + accepted + " が無いこと");
        }

        java.nio.file.Path fileScope = java.nio.file.Path.of(
                "src/main/java/com/ses/service/security/impl/FileScopeValidationService.java");
        String fileScopeSource = com.ses.testsupport.JavaSourceText.stripComments(
                java.nio.file.Files.readString(fileScope, java.nio.charset.StandardCharsets.UTF_8));
        java.util.Set<String> fileScopeTypes = new java.util.TreeSet<>();
        java.util.regex.Matcher compared = java.util.regex.Pattern.compile(
                "\"([A-Z][A-Z0-9_]*)\"\\s*\\.\\s*equals\\s*\\(\\s*documentType\\s*\\)").matcher(fileScopeSource);
        while (compared.find()) fileScopeTypes.add(compared.group(1));
        assertTrue(fileScopeTypes.containsAll(List.of("PRIVATE_NOTE", "RECEIPT", "CHANGE_REQUEST_ATTACHMENT",
                "CERTIFICATION_EVIDENCE", "MANAGEMENT_REPORT")), "前提: FileScope の種別ごとの規則を読めている: " + fileScopeTypes);
        assertTrue(!fileScopeTypes.contains(accepted), "FileScopeValidationService の種別ごとの規則に " + accepted + " が無いこと");
        for (String constant : constantReferences(fileScopeSource)) {
            assertTrue(!constantValues(com.ses.service.security.impl.FileScopeValidationService.class, constant)
                    .contains(accepted), "FileScopeValidationService." + constant + " に " + accepted + " が無いこと");
        }
    }

    /** {@code signature} から始まる method の本体（波括弧の対応で切り出す。文字列の中の波括弧は数えない）。 */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "method が見つからない: " + signature);
        int open = source.indexOf('{', start + signature.length() - 1);
        int depth = 0;
        boolean inString = false;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(open, i + 1);
        }
        throw new AssertionError("method の終わりが見つからない: " + signature);
    }

    /** 種別らしい文字列（大文字で始まり大文字・数字・{@code _} だけの literal）。 */
    private static java.util.Set<String> documentTypeLiterals(String text) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        java.util.regex.Matcher literal = java.util.regex.Pattern.compile("\"([A-Z][A-Z0-9_]*)\"").matcher(text);
        while (literal.find()) found.add(literal.group(1));
        return found;
    }

    /** 参照する種別の定数（{@code *_DOCUMENT_TYPE}・{@code *_DOCUMENT_TYPES}）。 */
    private static java.util.Set<String> constantReferences(String text) {
        java.util.Set<String> found = new java.util.TreeSet<>();
        java.util.regex.Matcher constant = java.util.regex.Pattern.compile("\\b([A-Z][A-Z0-9_]*_DOCUMENT_TYPES?)\\b").matcher(text);
        while (constant.find()) found.add(constant.group(1));
        return found;
    }

    /** 定数の値（Set は要素、String は1件）。 */
    private static java.util.Set<String> constantValues(Class<?> owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        java.util.Set<String> values = new java.util.TreeSet<>();
        if (value instanceof java.util.Collection<?> collection) collection.forEach(v -> values.add(String.valueOf(v)));
        else values.add(String.valueOf(value));
        return values;
    }

    private void expect(Verdict expected, Consumer<Void> mutation) {
        setUp();
        mutation.accept(null);
        assertEquals(expected, evaluate().verdict());
    }

    private Result evaluate() {
        return eligibility.evaluate(new Candidate(TENANT, DOCUMENT_ID, VERSION_ID, ENGINEER_ID));
    }

    private static void assertVerdict(Verdict expected, int status, String key, String reason, Result result) {
        assertEquals(expected, result.verdict());
        assertEquals(status, expected.httpStatus());
        assertEquals(key, expected.messageKey());
        assertEquals(reason, expected.reasonCode());
    }

    private static DocumentLink link(String tenantId, String targetType, long targetId) {
        DocumentLink link = new DocumentLink();
        link.setTenantId(tenantId);
        link.setDocumentId(DOCUMENT_ID);
        link.setTargetType(targetType);
        link.setTargetId(targetId);
        link.setDeletedFlag(0);
        return link;
    }
}
