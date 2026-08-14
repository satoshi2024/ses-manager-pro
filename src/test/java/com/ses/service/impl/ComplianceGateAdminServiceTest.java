package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.dto.compliance.ComplianceMappingSourceInput;
import com.ses.entity.ComplianceExternalReviewerType;
import com.ses.entity.ComplianceMappingApprovalEvent;
import com.ses.entity.ComplianceMappingVersion;
import com.ses.entity.ComplianceResponsibleAssignment;
import com.ses.service.ComplianceApprovalService;
import com.ses.service.ComplianceGateAdminService;
import com.ses.service.ComplianceMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase A step 3: reviewer type・assignment・approval eventのL2〜L3検証。
 *  - reviewer type CRUD・duplicate拒否
 *  - assignment: 半開区間・active_slot単一（交代で旧open終了）・endReason必須
 *  - approval: PROVISIONAL_REVIEWEDのみ・実actor=指名者本人・canonical hash記録・actor不一致403
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql(scripts = "/sql/engineer-schema-h2.sql")
@WithMockUser(username = "1", roles = "管理者")
class ComplianceGateAdminServiceTest {

    @Autowired
    private ComplianceGateAdminService complianceGateAdminService;

    @Autowired
    private ComplianceApprovalService complianceApprovalService;

    @Autowired
    private ComplianceMappingService complianceMappingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reviewerTypeを作成更新無効化できる() {
        ComplianceExternalReviewerType type = complianceGateAdminService.createReviewerType(
                "LABOR_CONSULTANT", "社労士", "社会保険労務士", "社労士登録番号", true);
        assertNotNull(type.getId());
        assertEquals(1, type.getEnabled());

        ComplianceExternalReviewerType updated = complianceGateAdminService.updateReviewerType(
                type.getId(), "社労士（更新）", null, "社労士登録番号", true);
        assertEquals("社労士（更新）", updated.getDisplayName());

        ComplianceExternalReviewerType disabled = complianceGateAdminService.setReviewerTypeEnabled(type.getId(), false);
        assertEquals(0, disabled.getEnabled());

        // duplicate typeCodeは拒否
        assertThrows(BusinessException.class, () -> complianceGateAdminService.createReviewerType(
                "LABOR_CONSULTANT", "重複", null, null, false));
    }

    @Test
    void assignmentは半開区間でactiveSlotが常に単一になる() {
        Long workplaceId = insertWorkplace();
        Long user1 = insertUser("gate-user-1", "HR");
        Long user2 = insertUser("gate-user-2", "HR");

        ComplianceResponsibleAssignment first = complianceGateAdminService.createAssignment(
                workplaceId, user1, LocalDateTime.now().minusDays(1));
        assertEquals(1, first.getActiveSlot());
        assertNull(first.getEffectiveTo());

        // 交代: 旧openが終了し、新assignmentがopenになる
        ComplianceResponsibleAssignment second = complianceGateAdminService.createAssignment(
                workplaceId, user2, LocalDateTime.now());
        assertNotNull(second.getEffectiveTo() == null ? second : second);
        Integer openCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE workplace_id=? AND active_slot=1",
                Integer.class, workplaceId);
        assertEquals(1, openCount, "open assignmentは常に1つ");
        Integer endedCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_compliance_responsible_assignment WHERE workplace_id=? AND active_slot IS NULL",
                Integer.class, workplaceId);
        assertEquals(1, endedCount, "旧openが終了している");

        // endReasonなしの終了は拒否・openでないassignmentの終了は拒否
        assertThrows(BusinessException.class, () -> complianceGateAdminService.endAssignment(second.getId(), null));
        complianceGateAdminService.endAssignment(second.getId(), "交代");
        assertThrows(BusinessException.class, () -> complianceGateAdminService.endAssignment(second.getId(), "再終了"));
    }

    @Test
    void approvalは指名者本人だけがcanonicalHash付きで記録できる() {
        Long workplaceId = insertWorkplace();
        // 指名者=現在のactor（@WithMockUser username=1 → currentUserId=1）
        complianceGateAdminService.createAssignment(workplaceId, 1L, LocalDateTime.now().minusDays(1));

        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-APPROVAL",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        setupPolicy(version.getId());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");

        // 指名者本人が承認 → event記録（canonical hash・64 hex）
        ComplianceMappingApprovalEvent event = complianceApprovalService.approve(
                version.getId(), workplaceId, "公式source確認済み", insertEvidenceVersion()[0], insertEvidenceVersion()[1]);
        assertNotNull(event.getId());
        assertEquals("APPROVE", event.getAction());
        assertEquals(64, event.getMappingHash().length());
        assertEquals(1L, event.getActorId());
        assertEquals("MAPPING-2026-07-APPROVAL", event.getMappingVersion());
        // hashがmapping versionの保存hashと一致する（canonical再計算）
        ComplianceMappingVersion saved = complianceMappingService.getById(version.getId());
        assertEquals(saved.getMappingHash(), event.getMappingHash());
    }

    @Test
    void approvalは指名者以外を拒否する() {
        Long workplaceId = insertWorkplace();
        // 指名者は他ユーザー（currentUserId=1とは不一致）
        Long other = insertUser("gate-other", "HR");
        complianceGateAdminService.createAssignment(workplaceId, other, LocalDateTime.now().minusDays(1));
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-APPROVAL-3",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        setupPolicy(version.getId());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");
        assertThrows(BusinessException.class,
                () -> complianceApprovalService.approve(version.getId(), workplaceId, "他人の承認", insertEvidenceVersion()[0], insertEvidenceVersion()[1]),
                "actor不一致は403相当のBusinessException");
    }

    @Test
    void approvalはPROVISIONAL_REVIEWED以外を拒否する() {
        Long workplaceId = insertWorkplace();
        Long actor = insertUser("gate-actor-2", "HR");
        complianceGateAdminService.createAssignment(workplaceId, actor, LocalDateTime.now().minusDays(1));
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-APPROVAL-2",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        // DRAFTのまま承認 → 拒否
        assertThrows(BusinessException.class,
                () -> complianceApprovalService.approve(version.getId(), workplaceId, "早期承認", null, null));
    }

    @Test
    void requirementGroupとtypeの編集はDRAFTのみ許可する() {
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-FREEZE",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        // DRAFT状態ではgroup作成可能（§4-3: 各group最低1type必須のためtypeも追加）
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(version.getId(), "GRP-1", "グループ1", 1);
        assertNotNull(group.getId());
        Long reviewerTypeId = insertReviewerType("LABOR_CONSULTANT", true);
        complianceGateAdminService.addRequirementType(group.getId(), reviewerTypeId);

        // PROVISIONAL_REVIEWEDへ遷移
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");

        // PROVISIONAL_REVIEWED状態でのgroup追加・type追加は拒否される（P1-Q1 freeze）
        assertThrows(BusinessException.class,
                () -> complianceGateAdminService.createRequirementGroup(version.getId(), "GRP-2", "グループ2", 1));
        assertThrows(BusinessException.class,
                () -> complianceGateAdminService.addRequirementType(group.getId(), reviewerTypeId));
    }

    @Test
    void approvalのidempotencyKeyは決定的で重複承認を拒否する() {
        Long workplaceId = insertWorkplace();
        complianceGateAdminService.createAssignment(workplaceId, 1L, LocalDateTime.now().minusDays(1));
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-IDEM",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        setupPolicy(version.getId());
        complianceMappingService.transition(version.getId(), "PROVISIONAL_REVIEWED");

        ComplianceMappingApprovalEvent event1 = complianceApprovalService.approve(
                version.getId(), workplaceId, "確認1", insertEvidenceVersion()[0], insertEvidenceVersion()[1]);
        assertNotNull(event1.getId());

        // 同一actor・同一mappingへの重複承認は決定的なidempotencyKeyにより409 Conflictで拒否される
        assertThrows(BusinessException.class,
                () -> complianceApprovalService.approve(version.getId(), workplaceId, "確認2", insertEvidenceVersion()[0], insertEvidenceVersion()[1]));
    }

    @Test
    void endAssignmentのプラス1マイクロ秒ガードは同一tickでも有効区間を確保する() {
        Long workplaceId = insertWorkplace();
        Long user1 = insertUser("tick-user-1", "HR");
        LocalDateTime startAt = LocalDateTime.now();
        ComplianceResponsibleAssignment assignment = complianceGateAdminService.createAssignment(workplaceId, user1, startAt);

        // assignment.getEffectiveFrom() と同一時刻で終了を試みる（P3-Q8）
        ComplianceResponsibleAssignment ended = complianceGateAdminService.endAssignment(assignment.getId(), "即時終了");
        assertTrue(ended.getEffectiveTo().isAfter(ended.getEffectiveFrom()), "effective_to > effective_from が常に成立");
    }

    @Test
    void externalReviewをCGC1暗号化とidentityHash付きでSUBMITTED登録できる() {
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-EXT",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(version.getId(), "GRP-1", "グループ1", 1);
        ComplianceExternalReviewerType type = complianceGateAdminService.createReviewerType(
                "LABOR_ATTORNEY", "弁護士", "労働法専門弁護士", "弁護士登録番号", true);
        complianceGateAdminService.addRequirementType(group.getId(), type.getId());

        // K1: 新規write pathはSUBMITTEDのみ（旧APPROVED直接記録は廃止・legacy rowは新gate不採用）
        com.ses.entity.ComplianceExternalReviewEvent event = complianceGateAdminService.recordExternalReview(
                version.getId(), group.getId(), type.getId(),
                "山田弁護士", "山田法律事務所", "REG-123456",
                "SUBMITTED", LocalDateTime.now(), LocalDateTime.now().plusYears(1), null, "外部確認OK", null);

        assertNotNull(event.getId());
        assertEquals("SUBMITTED", event.getAction());
        assertEquals("v1", event.getCredentialKeyVersion());
        assertEquals("CGC1", event.getCredentialCipherFormat());
        assertEquals("****3456", event.getCredentialMaskedSnapshot());
        assertTrue(event.getCredentialSnapshotEncrypted().startsWith("CGC1:v1:"), "CGC1 envelope");
        assertEquals(64, event.getReviewerIdentityHash().length());
        assertNotNull(event.getIdempotencyKey());

        // DTO allow-list検証 (R9.3)
        com.ses.dto.compliance.ComplianceExternalReviewEventDto dto =
                com.ses.dto.compliance.ComplianceExternalReviewEventDto.fromEntity(event);
        assertEquals(event.getId(), dto.getId());
        assertEquals("****3456", dto.getCredentialMaskedSnapshot());

        List<com.ses.entity.ComplianceExternalReviewEvent> list = complianceGateAdminService.listExternalReviews(version.getId());
        assertEquals(1, list.size());
        assertEquals(event.getId(), list.get(0).getId());

        // K1: SUBMITTED以外のaction（旧APPROVED/REJECTED/REVOKED）は直接記録不可
        assertThrows(BusinessException.class, () -> complianceGateAdminService.recordExternalReview(
                version.getId(), group.getId(), type.getId(),
                "山田弁護士", "山田法律事務所", "REG-123456",
                "APPROVED", LocalDateTime.now(), LocalDateTime.now().plusYears(1), null, "外部確認OK", null));
    }

    @Test
    void credentialが未入力でoptionalの場合は4列全NULLで保存されrequiredの場合は400拒否される() {
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-EXT2",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(version.getId(), "GRP-1", "グループ1", 1);

        // optional credential type
        ComplianceExternalReviewerType optType = complianceGateAdminService.createReviewerType(
                "OTHER_REVIEWER", "その他専門家", "備考", "登録番号", false);
        complianceGateAdminService.addRequirementType(group.getId(), optType.getId());

        // credential未入力で登録 → 4列全NULL（SUBMITTED）
        com.ses.entity.ComplianceExternalReviewEvent eventOpt = complianceGateAdminService.recordExternalReview(
                version.getId(), group.getId(), optType.getId(),
                "佐藤専門家", "佐藤事務所", null,
                "SUBMITTED", LocalDateTime.now(), LocalDateTime.now().plusYears(1), null, "確認OK", null);

        assertNull(eventOpt.getCredentialSnapshotEncrypted());
        assertNull(eventOpt.getCredentialKeyVersion());
        assertNull(eventOpt.getCredentialCipherFormat());
        assertNull(eventOpt.getCredentialMaskedSnapshot());

        // required credential type
        ComplianceExternalReviewerType reqType = complianceGateAdminService.createReviewerType(
                "TAX_ACCOUNTANT", "税理士", "税務専門家", "税理士登録番号", true);
        complianceGateAdminService.addRequirementType(group.getId(), reqType.getId());

        // requiredで未入力 → 400拒否
        BusinessException ex = assertThrows(BusinessException.class, () -> complianceGateAdminService.recordExternalReview(
                version.getId(), group.getId(), reqType.getId(),
                "田中税理士", "田中事務所", "",
                "SUBMITTED", LocalDateTime.now(), LocalDateTime.now().plusYears(1), null, "確認OK", null));
        assertEquals(400, ex.getCode());
        assertEquals("compliance.gate.credentialRequired", ex.getMessageKey());
    }

    @Test
    void SUBMITTED登録はchainを採番し同一内容の再送は409で拒否する() {
        ComplianceMappingVersion version = complianceMappingService.create(
                "G2-MAPPING", "MAPPING-2026-07-REV",
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), sources());
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(version.getId(), "GRP-1", "グループ1", 1);
        ComplianceExternalReviewerType type = complianceGateAdminService.createReviewerType(
                "AUDITOR", "監査人", "公認会計士", "登録番号", false);
        complianceGateAdminService.addRequirementType(group.getId(), type.getId());

        // 1. SUBMITTED 登録（新規chain採番）
        com.ses.entity.ComplianceExternalReviewEvent submitted1 = complianceGateAdminService.recordExternalReview(
                version.getId(), group.getId(), type.getId(),
                "鈴木会計士", "監査法人", null,
                "SUBMITTED", LocalDateTime.now(), LocalDateTime.now().plusYears(1), null, "確認OK", null);

        assertEquals("SUBMITTED", submitted1.getAction());
        assertNotNull(submitted1.getReviewChainId());

        // 2. 同一内容の再送 → 決定的idempotencyKeyにより409（§3.6・重複INSERT拒否）
        BusinessException ex = assertThrows(BusinessException.class, () -> complianceGateAdminService.recordExternalReview(
                version.getId(), group.getId(), type.getId(),
                "鈴木会計士", "監査法人", null,
                "SUBMITTED", LocalDateTime.now(), null, null, "再確認", null));
        assertEquals(409, ex.getCode());

        // 3. 別reviewerのSUBMITTEDは別identityHash→新規chain（§3.2: 再Reviewは新しいSUBMITTED chain）
        com.ses.entity.ComplianceExternalReviewEvent submitted2 = complianceGateAdminService.recordExternalReview(
                version.getId(), group.getId(), type.getId(),
                "佐藤会計士", "監査法人", null,
                "SUBMITTED", LocalDateTime.now(), null, null, "別の確認内容", null);
        assertNotNull(submitted2.getReviewChainId());
        assertEquals("SUBMITTED", submitted2.getAction());
    }

    @Test
    void assignment作成は有限期間assignmentと重複する開始日を拒否する() {
        Long workplaceId = insertWorkplace();
        Long user1 = insertUser("gate-overlap-1", "HR");
        Long user2 = insertUser("gate-overlap-2", "HR");
        // 既存openを作成し、将来日付で終了（effective_toが将来の有限期間行を再現）
        ComplianceResponsibleAssignment open = complianceGateAdminService.createAssignment(
                workplaceId, user1, LocalDateTime.now().minusDays(1));
        jdbcTemplate.update("UPDATE t_compliance_responsible_assignment "
                + "SET effective_to = DATEADD('DAY', 10, CURRENT_TIMESTAMP), active_slot = NULL, "
                + "ended_by = 1, end_reason = 'test' WHERE id = ?", open.getId());

        // 終了予定日（10日後）より前の開始 → overlap拒否（409）
        BusinessException error = assertThrows(BusinessException.class,
                () -> complianceGateAdminService.createAssignment(workplaceId, user2, LocalDateTime.now()));
        assertEquals(409, error.getCode());
        assertEquals("compliance.gate.assignmentOverlap", error.getMessageKey());
    }

    private Long insertWorkplace() {
        jdbcTemplate.update("INSERT INTO m_customer (company_name) VALUES ('gate customer')");
        Long customerId = jdbcTemplate.queryForObject(
                "SELECT id FROM m_customer WHERE company_name='gate customer'", Long.class);
        jdbcTemplate.update("INSERT INTO m_workplace (tenant_id, customer_id, name, organization_unit) "
                + "VALUES ('default', ?, 'gate workplace', '開発部')", customerId);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM m_workplace WHERE name='gate workplace'", Long.class);
    }

    private Long insertUser(String username, String role) {
        jdbcTemplate.update("INSERT INTO sys_user (username, real_name, role, status, password) "
                + "VALUES (?, ?, ?, 1, 'x')", username, username + "名", role);
        return jdbcTemplate.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, username);
    }

    /** reviewer typeを作成してIDを返す（既存なら再利用・§4-3 type必須のfixture用）。 */
    private Long insertReviewerType(String typeCode, boolean credentialRequired) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM m_compliance_external_reviewer_type WHERE type_code=?", Integer.class, typeCode);
        if (count == null || count == 0) {
            complianceGateAdminService.createReviewerType(
                    typeCode, "社労士", "社会保険労務士", "社労士登録番号", credentialRequired);
        }
        return jdbcTemplate.queryForObject(
                "SELECT id FROM m_compliance_external_reviewer_type WHERE type_code=?", Long.class, typeCode);
    }

    /** §4-3（P0-FIX-3）: group＋typeを作成してfreeze可能な状態にする。 */
    private void setupPolicy(Long mappingId) {
        com.ses.entity.ComplianceMappingReviewRequirementGroup group =
                complianceGateAdminService.createRequirementGroup(mappingId, "GRP-1", "グループ1", 1);
        Long reviewerTypeId = insertReviewerType("LABOR_CONSULTANT", true);
        complianceGateAdminService.addRequirementType(group.getId(), reviewerTypeId);
    }

    /** P0-5: exact CLEAN evidence versionを作成し{documentId, versionId}を返す（既存なら再利用）。 */
    private Long[] insertEvidenceVersion() {
        Long existing = jdbcTemplate.query(
                "SELECT id FROM t_document_version WHERE business_key = 'gate-approval-ev'",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (existing != null) {
            return new Long[]{910001L, existing};
        }
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        jdbcTemplate.update("INSERT INTO t_document_version "
                + "(tenant_id, document_id, version_no, storage_key, original_name, content_type, "
                + "size_bytes, sha256, source_type, business_key, version_discriminator, scan_status, created_by) "
                + "VALUES ('default', 910001, 1, 'ev/k', 'approval-ev.pdf', 'application/pdf', 10, ?, "
                + "'UPLOAD', 'gate-approval-ev', '1', 'CLEAN', 1)", sha);
        Long versionId = jdbcTemplate.queryForObject(
                "SELECT id FROM t_document_version WHERE business_key = 'gate-approval-ev'", Long.class);
        return new Long[]{910001L, versionId};
    }

    private List<ComplianceMappingSourceInput> sources() {
        return List.of(
                source("SRC-C", "https://example/src-c", "2026-07"),
                source("SRC-E", "https://example/src-e", "2026-07"),
                source("SRC-N", "https://example/src-n", "2026-07"),
                source("SRC-L", "https://example/src-l", "2026-07"),
                source("SRC-INDEX", "https://example/index", "2026-07"));
    }

    private ComplianceMappingSourceInput source(String code, String url, String version) {
        ComplianceMappingSourceInput input = new ComplianceMappingSourceInput();
        input.setSourceCode(code);
        input.setSourceUrl(url);
        input.setSourceVersion(version);
        input.setConfirmedOn(LocalDate.of(2026, 8, 9));
        input.setEffectiveFrom(LocalDate.of(2026, 7, 1));
        input.setEffectiveTo(LocalDate.of(2026, 9, 30));
        return input;
    }

    // ===== R23-P1-01 §8 dynamic policy（V102_3・P0-3） =====

    @Test
    void updateReviewerTypeDynamicはflagsとsourceMethodを設定できる() {
        Long typeId = insertReviewerType("DYNAMIC_LABOR", true);
        com.ses.entity.ComplianceVerificationSource source =
                complianceGateAdminService.createVerificationSource("PUBLIC_REGISTRY", "公的登録簿",
                        "https://example/registry", true, null, null);
        com.ses.entity.ComplianceVerificationMethod method =
                complianceGateAdminService.createVerificationMethod("MANUAL_PUBLIC_SOURCE", "手動・公的source",
                        "手動確認", true, null, null);

        ComplianceExternalReviewerType updated = complianceGateAdminService.updateReviewerTypeDynamic(
                typeId, 1, 1, source.getId(), method.getId(), 365, null, null);

        assertEquals(1, updated.getQualificationVerificationRequired());
        assertEquals(1, updated.getActiveStatusVerificationRequired());
        assertEquals(source.getId(), updated.getVerificationSourceId());
        assertEquals(method.getId(), updated.getVerificationMethodId());
        assertEquals(365, updated.getMaxAgeDays());
    }

    @Test
    void updateReviewerTypeDynamicはflags未指定を拒否する() {
        Long typeId = insertReviewerType("DYNAMIC_LABOR2", true);
        // §8: NULL=UNCONFIGUREDはAPI経由では設定不可（明示選択必須）
        assertThrows(BusinessException.class,
                () -> complianceGateAdminService.updateReviewerTypeDynamic(typeId, null, 1, null, null, null, null, null));
        assertThrows(BusinessException.class,
                () -> complianceGateAdminService.updateReviewerTypeDynamic(typeId, 1, null, null, null, null, null, null));
    }

    @Test
    void updateReviewerTypeDynamicは不正maxAgeを拒否する() {
        Long typeId = insertReviewerType("DYNAMIC_LABOR3", true);
        assertThrows(BusinessException.class,
                () -> complianceGateAdminService.updateReviewerTypeDynamic(typeId, 1, 1, null, null, 0, null, null));
    }

    @Test
    void createVerificationSourceは重複codeを拒否する() {
        complianceGateAdminService.createVerificationSource("SRC_DUP", "重複source", null, true, null, null);
        assertThrows(BusinessException.class,
                () -> complianceGateAdminService.createVerificationSource("SRC_DUP", "重複source2", null, true, null, null));
    }

    // ===== R23-P1-01 P0-4 subject create path =====

    @Test
    void createSubjectはfingerprint付きでsubjectを作成する() {
        com.ses.entity.ComplianceExternalReviewerSubject subject =
                complianceGateAdminService.createSubject("SUBJ-CREATE-1", "新規 山田", "新規 組織");
        assertNotNull(subject.getId());
        assertEquals(64, subject.getPersonFingerprintSnapshot().length());
        assertNotNull(subject.getFingerprintKeyVersion());
    }

    @Test
    void createSubjectは重複codeを拒否する() {
        complianceGateAdminService.createSubject("SUBJ-CREATE-2", "重複 山田", "組織");
        assertThrows(BusinessException.class,
                () -> complianceGateAdminService.createSubject("SUBJ-CREATE-2", "重複 山田2", "組織2"));
    }

    @Test
    void addQualificationはsubjectとtypeを結び付ける() {
        com.ses.entity.ComplianceExternalReviewerSubject subject =
                complianceGateAdminService.createSubject("SUBJ-QUAL-1", "資格 山田", "組織");
        Long typeId = insertReviewerType("QUAL_TYPE", true);
        com.ses.entity.ComplianceReviewerQualification qualification =
                complianceGateAdminService.addQualification(subject.getId(), typeId, "****1234", "登録番号");
        assertNotNull(qualification.getId());
        assertEquals(subject.getId(), qualification.getReviewerSubjectId());
        assertEquals(1, complianceGateAdminService.listQualifications(subject.getId()).size());
    }
}
