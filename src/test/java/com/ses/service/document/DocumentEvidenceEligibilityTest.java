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
     * W3-21a review P3-5: 提出の discovery が適格性の詳細を返してよいのは、文書が tenant にあり案件の要員を指す link を
     * 持つ場合だけ。不在・他tenant・link無し・他の要員だけを指す文書は false（存在しない文書と同じ応答にする）。
     * 論理削除・hold・SKILL_SHEET以外・link形の不適合は可視性では拒否しない（適格性の条件が判定する）。
     */
    @Test
    void 文書の可視性は案件の要員を指すlinkがある同tenantの文書だけを真とする() {
        assertTrue(eligibility.linkedToEngineer(TENANT, DOCUMENT_ID, ENGINEER_ID));
        // 適格性では拒否されるが、要員の文書なので可視（詳細の応答を返してよい）
        document.setDeletedFlag(1);
        document.setLegalHoldFlag(1);
        document.setDocumentType("CONTRACT");
        links.add(link(TENANT, "ASSET_ASSIGNMENT", 99L));
        assertTrue(eligibility.linkedToEngineer(TENANT, DOCUMENT_ID, ENGINEER_ID));

        setUp();
        assertEquals(false, eligibility.linkedToEngineer(TENANT, DOCUMENT_ID, ENGINEER_ID + 1), "他の要員の文書");
        links.set(0, link(TENANT, "ASSET_ASSIGNMENT", ENGINEER_ID));
        assertEquals(false, eligibility.linkedToEngineer(TENANT, DOCUMENT_ID, ENGINEER_ID), "要員以外を指すlink");
        links.set(0, link("tenant-other", "ENGINEER", ENGINEER_ID));
        assertEquals(false, eligibility.linkedToEngineer(TENANT, DOCUMENT_ID, ENGINEER_ID), "他tenantのlink");
        links.clear();
        assertEquals(false, eligibility.linkedToEngineer(TENANT, DOCUMENT_ID, ENGINEER_ID), "linkが無い");

        setUp();
        document.setTenantId("tenant-other");
        assertEquals(false, eligibility.linkedToEngineer(TENANT, DOCUMENT_ID, ENGINEER_ID), "他tenantの文書");
        document = null;
        assertEquals(false, eligibility.linkedToEngineer(TENANT, DOCUMENT_ID, ENGINEER_ID), "文書が無い");
        assertEquals(false, eligibility.linkedToEngineer(" ", DOCUMENT_ID, ENGINEER_ID), "tenantが空");
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
