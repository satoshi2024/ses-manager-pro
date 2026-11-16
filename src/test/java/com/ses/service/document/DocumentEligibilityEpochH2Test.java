package com.ses.service.document;

import com.ses.entity.Document;
import com.ses.entity.DocumentDisposalRequest;
import com.ses.entity.DocumentVersion;
import com.ses.mapper.DocumentDisposalRequestMapper;
import com.ses.mapper.DocumentMapper;
import com.ses.mapper.DocumentVersionMapper;
import com.ses.service.DocumentService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import com.ses.service.document.DocumentEvidenceEligibility.Candidate;
import com.ses.service.document.DocumentEvidenceEligibility.Verdict;
import com.ses.service.job.DocumentRetentionJob;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.TestSecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NF01 F3（計画 §2.2 document行・§4.3・§7.6 Demo）をH2の実SQLで確かめる。各testはテストtransactionの中で動き、
 * 最後にrollbackする（fixtureは試験ごとに一意なtenantで作り、他の行を読まない）。
 *
 * <ul>
 *   <li>変化の無い書込みではepochが進まない。変化があれば1進む。同じtransactionで複数変化しても1だけ進む。</li>
 *   <li>保存期限jobはtenant contextが無くても、各文書行自身のtenantで進める。</li>
 *   <li>証跡適格性readerは、hold・廃棄申請・link・削除に追従する（tenantを問わずlinkを数える）。</li>
 *   <li>link挿入は一意key違反を受け止めて既存行を照合する（別tenantは403、論理削除済みは409）。</li>
 *   <li>資産貸与の受渡証跡のlinkも文書のtenantで作り1進める（§7.6 Demo (3)、F-3）。</li>
 * </ul>
 *
 * <p>同じtransaction内の重複除去のため、1つのtestで「変化ごとに+1」を観測することはできない
 * （別transactionでの増分は {@link DocumentEligibilityServiceTest}・{@link DocumentLinkCommandServiceTest} で確かめる）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DocumentEligibilityEpochH2Test {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final String SHA = "c".repeat(64);

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DocumentService documentService;
    @Autowired private DocumentLinkCommandService linkCommandService;
    @Autowired private DocumentEligibilityService eligibilityService;
    @Autowired private DocumentEvidenceEligibility evidenceEligibility;
    @Autowired private DocumentMapper documentMapper;
    @Autowired private DocumentVersionMapper documentVersionMapper;
    @Autowired private DocumentDisposalRequestMapper disposalRequestMapper;
    @Autowired private com.ses.service.AssetService assetService;
    @Autowired private com.ses.service.AssetAssignmentService assetAssignmentService;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        TestSecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();
    }

    @Test
    void 法的holdの設定で1進み同じ値の再設定では進まず同じtransactionの解除でも1のまま() {
        String tenant = tenant();
        TenantTestSecurity.bindAs(tenant, "管理者");
        long documentId = insertDocument(tenant, "CONTRACT", "CONFIRMED", LocalDate.of(2030, 1, 1));

        documentService.placeLegalHold(documentId, true, "訴訟");
        assertEquals(1L, epoch(documentId));
        documentService.placeLegalHold(documentId, true, "再設定");
        assertEquals(1L, epoch(documentId), "同じ値の再設定では進まない");
        documentService.placeLegalHold(documentId, false, "解除");
        assertEquals(1L, epoch(documentId), "同じtransactionの変化は1回だけ数える");
        assertEquals(0, jdbc.queryForObject("SELECT legal_hold_flag FROM t_document WHERE id = ?", Integer.class, documentId));
    }

    @Test
    void linkの追加で1進み既存linkの再追加では進まずlinkのtenantは文書のtenant() {
        String tenant = tenant();
        TenantTestSecurity.bindAs(tenant, "管理者");
        long documentId = insertDocument(tenant, "SKILL_SHEET", "DRAFT", null);

        documentService.link(documentId, "ENGINEER", 7L);
        documentService.link(documentId, "ENGINEER", 7L);

        assertEquals(1L, epoch(documentId));
        assertEquals(tenant, jdbc.queryForObject("SELECT tenant_id FROM t_document_link WHERE document_id = ? "
                + "AND target_type = 'ENGINEER' AND target_id = 7", String.class, documentId));
        assertEquals(1, count("SELECT COUNT(*) FROM t_document_link WHERE document_id = ?", documentId));
    }

    @Test
    void 確定は利用可否を変えないため進まない() {
        String tenant = tenant();
        TenantTestSecurity.bindAs(tenant, "管理者");
        long documentId = insertDocument(tenant, "SKILL_SHEET", "DRAFT", null);

        documentService.confirm(documentId);

        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM t_document WHERE id = ?", String.class, documentId));
        assertEquals(0L, epoch(documentId));
    }

    @Test
    void 適格性readerはlink廃棄申請却下と削除に追従し同じtransactionの複数変化でepochは1() {
        String tenant = tenant();
        TenantTestSecurity.bindAs(tenant, "管理者");
        long documentId = insertDocument(tenant, "SKILL_SHEET", "CONFIRMED", LocalDate.of(2030, 1, 1));
        long versionId = insertVersion(tenant, documentId);
        Candidate candidate = new Candidate(tenant, documentId, versionId, 7L);

        assertEquals(Verdict.TARGET_UNSUPPORTED, evidenceEligibility.evaluate(candidate).verdict(), "linkが無い");
        documentService.link(documentId, "ENGINEER", 7L);
        assertEquals(Verdict.ELIGIBLE, evidenceEligibility.evaluate(candidate).verdict());

        var request = documentService.requestDisposal(documentId, "保存期間満了");
        assertEquals(Verdict.DOCUMENT_UNAVAILABLE, evidenceEligibility.evaluate(candidate).verdict(), "廃棄申請PENDING");
        documentService.rejectDisposal(request.getId(), "継続利用");
        assertEquals(Verdict.ELIGIBLE, evidenceEligibility.evaluate(candidate).verdict(), "却下だけなら適格");
        assertEquals(1L, epoch(documentId), "link・申請・却下を同じtransactionで行っても1回");

        // F-3修正前のtenant未設定link（列DEFAULT）が1件でもあればfail-closed
        jdbc.update("INSERT INTO t_document_link (tenant_id, document_id, target_type, target_id) "
                + "VALUES ('default', ?, 'ASSET_ASSIGNMENT', 99)", documentId);
        assertEquals(Verdict.TARGET_UNSUPPORTED, evidenceEligibility.evaluate(candidate).verdict());
        jdbc.update("DELETE FROM t_document_link WHERE document_id = ? AND target_type = 'ASSET_ASSIGNMENT'", documentId);

        jdbc.update("UPDATE t_document SET deleted_flag = 1 WHERE id = ?", documentId);
        assertEquals(Verdict.DOCUMENT_UNAVAILABLE, evidenceEligibility.evaluate(candidate).verdict(), "論理削除");
        assertEquals(Verdict.DOCUMENT_NOT_FOUND,
                evidenceEligibility.evaluate(new Candidate("tenant-unknown", documentId, versionId, 7L)).verdict());
    }

    @Test
    void 廃棄の承認と執行でDISPOSEDになり適格でなくなる() {
        String tenant = tenant();
        TenantTestSecurity.bindAs(tenant, "管理者");
        long documentId = insertDocument(tenant, "SKILL_SHEET", "CONFIRMED", LocalDate.of(2030, 1, 1));
        long requestId = insertPendingRequest(documentId, "期限");

        documentService.approveDisposal(requestId);
        documentService.executeDisposal(requestId);

        assertEquals("DISPOSED", jdbc.queryForObject("SELECT status FROM t_document WHERE id = ?", String.class, documentId));
        assertEquals("DISPOSED", jdbc.queryForObject("SELECT status FROM t_document_disposal_request WHERE id = ?",
                String.class, requestId));
        assertEquals(1L, epoch(documentId));
        assertEquals(Verdict.DOCUMENT_UNAVAILABLE, evidenceEligibility.evaluate(
                new Candidate(tenant, documentId, 1L, 7L)).verdict());
    }

    @Test
    void 保存期限jobはtenant_contextなしで各文書自身のtenantのepochを進める() {
        String tenantA = tenant();
        String tenantB = tenant();
        LocalDate past = LocalDate.now().minusDays(1);
        long expiredA = insertDocument(tenantA, "CONTRACT", "CONFIRMED", past);
        long expiredB = insertDocument(tenantB, "CONTRACT", "CONFIRMED", past);
        long notYet = insertDocument(tenantA, "CONTRACT", "CONFIRMED", LocalDate.now().plusDays(30));
        long alreadyPending = insertDocument(tenantB, "CONTRACT", "CONFIRMED", past);
        insertPendingRequest(alreadyPending, "手動");
        SecurityContextHolder.clearContext();
        AccountingTenantContextHolder.clear();

        // ShedLockのproxyを通さずjob本体を実行する（serviceは実beanでtransaction境界を持つ）
        new DocumentRetentionJob(documentMapper, eligibilityService, com.ses.privacy.production.retention.hold.PrivacyHoldTestGuards.noHoldsGuard()).processExpiredDocuments();

        assertEquals(1L, epoch(expiredA));
        assertEquals(1L, epoch(expiredB));
        assertEquals(0L, epoch(notYet));
        assertEquals(0L, epoch(alreadyPending), "既に申請がある文書は登録せず進めない");
        assertEquals(1, count("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ? "
                + "AND status = 'PENDING' AND requested_by = -1", expiredA));
        assertEquals(1, count("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ? "
                + "AND status = 'PENDING' AND requested_by = -1", expiredB));
        assertEquals(1, count("SELECT COUNT(*) FROM t_document_disposal_request WHERE document_id = ?", alreadyPending));
    }

    @Test
    void link命令は呼出元が渡した文書自身のtenantの行だけを進める() {
        String tenantA = tenant();
        String tenantB = tenant();
        long documentA = insertDocument(tenantA, "SKILL_SHEET", "DRAFT", null);
        long documentB = insertDocument(tenantB, "SKILL_SHEET", "DRAFT", null);
        AccountingTenantContextHolder.setTenantId(tenantA);

        linkCommandService.link(tenantB, documentB, "ENGINEER", 8L);

        assertEquals(0L, epoch(documentA));
        assertEquals(1L, epoch(documentB));
        assertEquals(tenantB, jdbc.queryForObject("SELECT tenant_id FROM t_document_link WHERE document_id = ?",
                String.class, documentB));
    }

    @Test
    void 重複linkは実DBの一意key違反を受け止め別tenantは403論理削除済みは409で増分しない() {
        String tenant = tenant();
        long documentId = insertDocument(tenant, "SKILL_SHEET", "DRAFT", null);
        // F-3修正前のtenant未設定link（列DEFAULT）と、論理削除済みのlinkを直接置く（fenceを通さないためepochは0のまま）
        jdbc.update("INSERT INTO t_document_link (tenant_id, document_id, target_type, target_id) "
                + "VALUES ('default', ?, 'ENGINEER', 7)", documentId);
        jdbc.update("INSERT INTO t_document_link (tenant_id, document_id, target_type, target_id, deleted_flag) "
                + "VALUES (?, ?, 'ENGINEER', 8, 1)", tenant, documentId);

        var foreign = org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> linkCommandService.link(tenant, documentId, "ENGINEER", 7L));
        assertEquals(403, foreign.getCode());
        var deleted = org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> linkCommandService.link(tenant, documentId, "ENGINEER", 8L));
        assertEquals(409, deleted.getCode());
        assertEquals("error.document.linkDeletedConflict", deleted.getMessageKey());
        assertEquals(0L, epoch(documentId), "挿入できなかった重複では進めない");

        // 一意key違反（DBの文単位rollback）の後も、同じtransactionでlinkの書込みを続けられる
        org.junit.jupiter.api.Assertions.assertTrue(linkCommandService.link(tenant, documentId, "EXPENSE_REQUEST", 9L));
        assertEquals(1L, epoch(documentId));
        assertEquals(3, count("SELECT COUNT(*) FROM t_document_link WHERE document_id = ?", documentId));
    }

    @Test
    void 資産貸与の受渡証跡にSKILL_SHEETをlinkすると1進みlinkは文書のtenantで適格性はlink形の不適合になる() {
        String tenant = tenant();
        TenantTestSecurity.bindAs(tenant, "管理者");
        long documentId = insertDocument(tenant, "SKILL_SHEET", "DRAFT", null);
        long versionId = insertVersion(tenant, documentId);
        // 要員へのENGINEER linkはfenceを通さずに置く（同じtransactionの重複除去で資産linkの+1を隠さないため）
        jdbc.update("INSERT INTO t_document_link (tenant_id, document_id, target_type, target_id) "
                + "VALUES (?, ?, 'ENGINEER', 7)", tenant, documentId);
        Candidate candidate = new Candidate(tenant, documentId, versionId, 7L);
        assertEquals(Verdict.ELIGIBLE, evidenceEligibility.evaluate(candidate).verdict());
        com.ses.entity.Asset asset = com.ses.entity.Asset.builder().assetTag("AST-NF01-F3-" + SEQUENCE.incrementAndGet())
                .assetName("F3検証端末").category("PC").status("IN_STOCK").build();
        assetService.createAsset(asset, 1L);

        com.ses.entity.AssetAssignment assignment = assetAssignmentService.createAssignment(asset.getId(), "ENGINEER", 7L,
                LocalDate.now(), LocalDate.now().plusDays(30), documentId, "受渡", 1L);

        assertEquals(1L, epoch(documentId));
        assertEquals(tenant, jdbc.queryForObject("SELECT tenant_id FROM t_document_link WHERE document_id = ? "
                + "AND target_type = 'ASSET_ASSIGNMENT' AND target_id = ?", String.class, documentId, assignment.getId()));
        assertEquals(Verdict.TARGET_UNSUPPORTED, evidenceEligibility.evaluate(candidate).verdict());
    }

    @Test
    void 資産貸与に存在しない証跡文書を指定すると404で貸与を作らない() {
        com.ses.entity.Asset asset = com.ses.entity.Asset.builder().assetTag("AST-NF01-F3-" + SEQUENCE.incrementAndGet())
                .assetName("F3検証端末").category("PC").status("IN_STOCK").build();
        assetService.createAsset(asset, 1L);

        var missing = org.junit.jupiter.api.Assertions.assertThrows(com.ses.common.exception.BusinessException.class,
                () -> assetAssignmentService.createAssignment(asset.getId(), "ENGINEER", 7L,
                        LocalDate.now(), LocalDate.now().plusDays(30), Long.MAX_VALUE, "受渡", 1L));
        assertEquals(404, missing.getCode());
    }

    private long insertDocument(String tenant, String type, String status, LocalDate retentionUntil) {
        Document document = new Document();
        document.setTenantId(tenant);
        document.setDocumentType(type);
        document.setDirection("INTERNAL");
        document.setStatus(status);
        document.setLegalHoldFlag(0);
        document.setVersion(1L);
        document.setRetentionUntil(retentionUntil);
        document.setCurrency("JPY");
        document.setTitle("F3検証");
        documentMapper.insert(document);
        return document.getId();
    }

    private long insertVersion(String tenant, long documentId) {
        DocumentVersion version = new DocumentVersion();
        version.setTenantId(tenant);
        version.setDocumentId(documentId);
        version.setVersionNo(1);
        version.setStorageKey("nf01/f3/" + documentId);
        version.setOriginalName("f3.pdf");
        version.setSha256(SHA);
        version.setSourceType("GENERATED");
        version.setBusinessKey("NF01-F3:" + documentId);
        version.setVersionDiscriminator("v1");
        version.setScanStatus("CLEAN");
        documentVersionMapper.insert(version);
        return version.getId();
    }

    private long insertPendingRequest(long documentId, String reason) {
        DocumentDisposalRequest request = new DocumentDisposalRequest();
        request.setDocumentId(documentId);
        request.setRequestedBy(2L);
        request.setStatus("PENDING");
        request.setReason(reason);
        disposalRequestMapper.insert(request);
        return request.getId();
    }

    private long epoch(long documentId) {
        Long value = jdbc.queryForObject("SELECT evidence_eligibility_epoch FROM t_document WHERE id = ?",
                Long.class, documentId);
        return value == null ? -1L : value;
    }

    private int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String tenant() {
        return "nf01-f3-" + SEQUENCE.incrementAndGet();
    }
}
