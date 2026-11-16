package com.ses.service.lifecycle.dual;

import com.ses.dto.lifecycle.dual.DualConfirmationDecisionRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationDocumentSelectionRequest;
import com.ses.dto.lifecycle.dual.DualConfirmationResultDto;
import com.ses.dto.lifecycle.dual.DualConfirmationSubmitRequest;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerSales;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTask;
import com.ses.entity.LifecycleTaskDep;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleTaskDepMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * M8・M9（実MySQLの試験クラス）の共通fixture。本class自身は試験ではない（shard一覧に載せない）。
 *
 * <p>B0の不変表はtriggerが削除を拒否するため、試験後に行を消せない（計画 §7.8 W3-18からの引継ぎ）。各試験クラスは専用の
 * MySQL containerを使い、fixtureごとに一意の利用者・要員・組織・案件・タスク・文書で分離する。B0の行と拒否eventは
 * fixtureのタスクID（拒否eventは {@code requested_task_id}）で絞って数え、container全体を数える問合せはしない。</p>
 *
 * <p>tenantは配備のtenant（test profileの {@code app.security.oidc.tenant-id: default}）に固定する。明示actorのaction判定
 * （{@code AuthorizationServiceImpl#isAllowedForActor}）は配備のtenant以外の利用者を常に拒否する（単一tenant配備の前提、
 * 計画 §9 RR-14）ため、fixtureごとに別のtenantを使うと提出・確認のroute判定がすべて403になる。</p>
 *
 * <p>構成: 担当営業（{@code 営業}、現任の担当営業割当あり）、確認者2名（{@code HR}）、管理者、マネージャー（組織scope有効時に
 * 要員の所属組織を主所属に持つ）、要員（所属組織あり）、ACTIVEの案件、IN_PROGRESSのDUALタスクとPENDINGの後続タスク、
 * 要員だけにlinkされたSKILL_SHEET文書とCLEAN版。</p>
 */
final class DualConfirmationMySqlFixtures {

    static final long LEGAL_ENTITY_ID = 1L;
    /** 配備のtenant（test profileの OIDC tenant-id）。 */
    static final String TENANT = "default";
    static final LocalDate VALID_FROM = LocalDate.of(2020, 1, 1);
    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);
    private static final AtomicInteger TEMPLATE_VERSION = new AtomicInteger(900_000);

    record Fixture(String tenant, long admin, long sales, long hr1, long hr2, long manager, long organization,
                   long engineer, long salesAssignment, long caseId, long taskId, long successorId,
                   long documentId, long versionId) {
    }

    private final JdbcTemplate jdbc;
    private final SysUserMapper sysUserMapper;
    private final EngineerMapper engineerMapper;
    private final EngineerSalesMapper engineerSalesMapper;
    private final UserOrganizationMapper userOrganizationMapper;
    private final LifecycleTemplateMapper templateMapper;
    private final LifecycleCaseMapper caseMapper;
    private final LifecycleTaskMapper taskMapper;
    private final LifecycleTaskDepMapper taskDepMapper;

    DualConfirmationMySqlFixtures(JdbcTemplate jdbc, SysUserMapper sysUserMapper, EngineerMapper engineerMapper,
                                  EngineerSalesMapper engineerSalesMapper, UserOrganizationMapper userOrganizationMapper,
                                  LifecycleTemplateMapper templateMapper, LifecycleCaseMapper caseMapper,
                                  LifecycleTaskMapper taskMapper, LifecycleTaskDepMapper taskDepMapper) {
        this.jdbc = jdbc;
        this.sysUserMapper = sysUserMapper;
        this.engineerMapper = engineerMapper;
        this.engineerSalesMapper = engineerSalesMapper;
        this.userOrganizationMapper = userOrganizationMapper;
        this.templateMapper = templateMapper;
        this.caseMapper = caseMapper;
        this.taskMapper = taskMapper;
        this.taskDepMapper = taskDepMapper;
    }

    /** 二者確認の前提一式を新しい行でcommitする（各文はautocommit）。{@code prefix} は診断用の名前に使う。 */
    Fixture create(String prefix) {
        String tenant = TENANT;
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix");
        }
        long admin = insertUser(tenant, "管理者");
        long sales = insertUser(tenant, "営業");
        long hr1 = insertUser(tenant, "HR");
        long hr2 = insertUser(tenant, "HR");
        long manager = insertUser(tenant, "マネージャー");
        long organization = insertOrganization();
        userOrganizationMapper.insert(UserOrganization.builder().tenantId(tenant).userId(manager)
                .organizationId(organization).primaryFlag(1).validFrom(VALID_FROM).version(0).build());
        long engineer = insertEngineer(tenant, organization);
        long salesAssignment = assignSales(engineer, sales);
        long caseId = insertCase(tenant, engineer, admin);
        long taskId = insertTask(caseId, admin, "DUAL_SKILLSHEET", "DUAL_CONFIRMATION", "IN_PROGRESS");
        long successorId = insertTask(caseId, admin, "AFTER_DUAL", "NONE", "PENDING");
        taskDepMapper.insert(LifecycleTaskDep.builder().caseId(caseId).predecessorTaskId(taskId)
                .successorTaskId(successorId).build());
        long documentId = insert("INSERT INTO t_document (tenant_id, document_type, direction, status, "
                        + "legal_hold_flag, version, retention_until, title) "
                        + "VALUES (?, 'SKILL_SHEET', 'INTERNAL', 'DRAFT', 0, 1, ?, 'W3-21a検証スキルシート')",
                tenant, java.sql.Date.valueOf(LocalDate.of(2099, 1, 1)));
        long versionId = insert("INSERT INTO t_document_version (tenant_id, document_id, version_no, storage_key, "
                        + "original_name, sha256, source_type, business_key, version_discriminator, scan_status) "
                        + "VALUES (?, ?, 1, ?, 'skill.xlsx', ?, 'GENERATED', ?, 'v1', 'CLEAN')",
                tenant, documentId, "nf01/w321a/" + documentId, "d".repeat(64), "NF01-W321A:" + documentId);
        jdbc.update("INSERT INTO t_document_link (tenant_id, document_id, target_type, target_id) "
                + "VALUES (?, ?, 'ENGINEER', ?)", tenant, documentId, engineer);
        return new Fixture(tenant, admin, sales, hr1, hr2, manager, organization, engineer, salesAssignment, caseId,
                taskId, successorId, documentId, versionId);
    }

    /** 同じ案件にPENDINGのタスクを1件足す（依存先の変化を起こす試験用）。 */
    long extraTask(Fixture f, String code) {
        return insertTask(f.caseId(), f.admin(), code, "NONE", "PENDING");
    }

    /** 同じtenantの別の要員（細粒度の確認用。案件は持たない）と担当営業割当。 */
    long[] otherEngineerWithSales(Fixture f) {
        long engineer = insertEngineer(f.tenant(), f.organization());
        long assignment = assignSales(engineer, f.sales());
        return new long[]{engineer, assignment};
    }

    static DualConfirmationSubmitRequest submitBody(long taskGeneration, Fixture f, String comment) {
        DualConfirmationDocumentSelectionRequest selection = new DualConfirmationDocumentSelectionRequest();
        selection.setDocumentId(f.documentId());
        selection.setDocumentVersionId(f.versionId());
        DualConfirmationSubmitRequest body = new DualConfirmationSubmitRequest();
        body.setTaskGeneration(taskGeneration);
        body.setDocumentSelections(List.of(selection));
        body.setComment(comment);
        return body;
    }

    static DualConfirmationDecisionRequest decisionBody(DualConfirmationResultDto submitted, String decision,
                                                        String comment) {
        DualConfirmationDecisionRequest body = new DualConfirmationDecisionRequest();
        body.setSubmissionRevision(submitted.getSubmissionRevision().longValue());
        body.setEvidenceSha256(submitted.getEvidenceSha256());
        body.setDecision(decision);
        body.setComment(comment);
        return body;
    }

    static LifecycleDualConfirmationCoordinator.Principal principal(Fixture f, long userId) {
        return new LifecycleDualConfirmationCoordinator.Principal(userId, f.tenant());
    }

    long taskGeneration(Fixture f) {
        return value("SELECT task_generation FROM t_lifecycle_task WHERE id = ?", f.taskId());
    }

    String taskStatus(long taskId) {
        return jdbc.queryForObject("SELECT status FROM t_lifecycle_task WHERE id = ?", String.class, taskId);
    }

    long count(String sql, Object... args) {
        return value(sql, args);
    }

    long value(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    /** fixtureのタスクへの要求の拒否eventのreason（古い順）。 */
    List<String> deniedReasons(Fixture f) {
        return jdbc.queryForList("SELECT reason_code FROM t_lifecycle_dual_event WHERE tenant_id = ? "
                + "AND requested_task_id = ? AND event_type = 'DENIED' ORDER BY id", String.class, f.tenant(), f.taskId());
    }

    /** fixtureのタスクの提出の件数。 */
    long submissions(Fixture f) {
        return value("SELECT COUNT(*) FROM t_lifecycle_dual_submission WHERE tenant_id = ? AND task_id = ?",
                f.tenant(), f.taskId());
    }

    /** fixtureのタスクの確認・差戻しの件数。 */
    long confirmations(Fixture f) {
        return value("SELECT COUNT(*) FROM t_lifecycle_dual_confirmation WHERE tenant_id = ? AND task_id = ?",
                f.tenant(), f.taskId());
    }

    static Throwable capture(Supplier<?> action) {
        try {
            action.get();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private long insertUser(String tenant, String role) {
        long seq = SEQUENCE.incrementAndGet();
        SysUser user = SysUser.builder().tenantId(tenant).username("w321a_" + seq).password("pass")
                .realName("W3-21a試験" + seq).email("w321a-" + seq + "@example.com").role(role).status(1).build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    private long insertOrganization() {
        String code = "W321A-" + SEQUENCE.incrementAndGet();
        jdbc.update("INSERT INTO m_organization_unit (legal_entity_id, code, name, type, parent_id, valid_from, status) "
                + "VALUES (?, ?, ?, '部', NULL, ?, '有効')", LEGAL_ENTITY_ID, code, "W3-21a組織 " + code, VALID_FROM);
        long id = value("SELECT id FROM m_organization_unit WHERE code = ?", code);
        jdbc.update("INSERT INTO t_organization_relation_history (organization_id, parent_id, status, valid_from) "
                + "VALUES (?, NULL, '有効', ?)", id, VALID_FROM);
        return id;
    }

    private long insertEngineer(String tenant, long organization) {
        Engineer engineer = Engineer.builder().tenantId(tenant).legalEntityId(LEGAL_ENTITY_ID)
                .organizationId(organization).fullName("W3-21a要員" + SEQUENCE.incrementAndGet())
                .status("Bench").employmentType("正社員").build();
        engineerMapper.insert(engineer);
        return engineer.getId();
    }

    private long assignSales(long engineer, long salesUser) {
        EngineerSales sales = EngineerSales.builder().engineerId(engineer).salesUserId(salesUser).primaryFlag(1)
                .assignedAt(VALID_FROM).build();
        engineerSalesMapper.insert(sales);
        return sales.getId();
    }

    private long insertCase(String tenant, long engineer, long admin) {
        long seq = SEQUENCE.incrementAndGet();
        LifecycleTemplate template = LifecycleTemplate.builder().templateType("RESIGNATION").name("W3-21a " + seq)
                .versionNo(TEMPLATE_VERSION.incrementAndGet()).status("ACTIVE").validFrom(LocalDate.now().minusDays(1))
                .createdBy(admin).updatedBy(admin).build();
        templateMapper.insert(template);
        LifecycleCase lcCase = LifecycleCase.builder().tenantId(tenant).caseNo("LC-W321A-" + seq)
                .lifecycleType("RESIGNATION").engineerId(engineer).templateId(template.getId())
                .templateVersion(template.getVersionNo()).anchorDate(LocalDate.now()).status("ACTIVE")
                .title("W3-21a").applicantUserId(admin).engineerSnapshotJson("{}")
                .createdBy(admin).updatedBy(admin).build();
        caseMapper.insert(lcCase);
        return lcCase.getId();
    }

    private long insertTask(long caseId, long admin, String code, String evidenceType, String status) {
        LifecycleTask task = LifecycleTask.builder().caseId(caseId).taskCode(code).taskName(code)
                .dueDate(LocalDate.now().plusDays(7)).assigneeRole("営業").isMandatory(1).isBlocking(1)
                .evidenceType(evidenceType).isEngineerVisible(0).status(status)
                .createdBy(admin).updatedBy(admin).build();
        taskMapper.insert(task);
        return task.getId();
    }

    private long insert(String sql, Object... args) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }
}
