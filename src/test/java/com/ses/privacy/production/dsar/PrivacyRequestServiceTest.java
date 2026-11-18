package com.ses.privacy.production.dsar;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.AuditLog;
import com.ses.entity.privacy.PrivacyAction;
import com.ses.entity.privacy.PrivacyRequest;
import com.ses.entity.privacy.PrivacyRequestSubjectLink;
import com.ses.mapper.privacy.PrivacyActionMapper;
import com.ses.mapper.privacy.PrivacyRequestMapper;
import com.ses.mapper.privacy.PrivacyRequestSubjectLinkMapper;
import com.ses.privacy.production.PrivacyAccessGuard;
import com.ses.privacy.production.PrivacyApprovalProperties;
import com.ses.privacy.production.PrivacyFeatureProperties;
import com.ses.privacy.production.ProductionPrivacyProviderRegistry;
import com.ses.privacy.production.provider.DsarDeliveryClass;
import com.ses.privacy.production.provider.PrivacyRecord;
import com.ses.privacy.production.provider.PrivacySubjectRef;
import com.ses.privacy.production.provider.PrivacySubjectType;
import com.ses.privacy.production.provider.ProductionPrivacyProvider;
import com.ses.privacy.production.provider.ProviderDataElement;
import com.ses.privacy.production.provider.ProviderTable;
import com.ses.privacy.production.provider.SubjectLink;
import com.ses.privacy.production.retention.catalog.SubjectAttribution;
import com.ses.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * NF07-A2a: DSAR 受付〜SCOPED の service（production-bridge-phased-plan.md §3.5.1）。
 *
 * <p>H2 上に専用 tenant の case を作り、実 mapper で状態遷移・CAS・監査の巻き戻しを確かめる（class の transaction で
 * rollback し、他の tenant の行は読まない）。service の各呼出しは NESTED（savepoint）の transaction で包み、監査の失敗で
 * その呼出しの書込みだけが巻き戻ることを観測する。F2 provider は同姓同名の要員2名を返す fixture を使う。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PrivacyRequestServiceTest {

    private static final String TENANT = "nf07-a2a-dsar";
    private static final String OTHER_TENANT = "nf07-a2a-dsar-other";
    /** JST 2026-11-16 10:00。 */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneId.of("Asia/Tokyo"));
    private static final long ADMIN_ID = 1_516_001L;
    private static final String D06_ID = "D06-NF07-2026-0006";
    private static final String SAME_NAME = "山田 太郎";
    private static final String EMAIL_101 = "taro.yamada.101@example.invalid";
    private static final String EMAIL_102 = "taro.yamada.102@example.invalid";

    @Autowired
    private PrivacyRequestMapper requestMapper;
    @Autowired
    private PrivacyRequestSubjectLinkMapper linkMapper;
    @Autowired
    private PrivacyActionMapper actionMapper;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private AuditLogService realAuditLogService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    private PrivacyAccessGuard guard;
    private CapturingAuditLogService auditLog;
    private PrivacyApprovalProperties approval;
    private PrivacyFeatureProperties features;
    private SameNameEngineerProvider provider;
    private ProductionPrivacyProviderRegistry registry;
    private PrivacyRequestService service;

    @BeforeEach
    void setUp() {
        guard = mock(PrivacyAccessGuard.class);
        auditLog = new CapturingAuditLogService();
        approval = decisions(D06_ID);
        features = new PrivacyFeatureProperties();
        features.getDsar().setDueDays(30);
        provider = new SameNameEngineerProvider();
        registry = new ProductionPrivacyProviderRegistry(List.of(provider));
        service = service(TENANT);
    }

    // ---------------------------------------------------------------- 受付

    @Test
    void 受付はticket参照だけを保存しD06の期限規則から期限日を計算する() {
        PrivacyDsarViews.Transition result = service.intake(intake("TICKET-2026-0001", null));

        PrivacyRequest row = requestMapper.selectById(result.requestId());
        assertThat(row.getTenantId()).isEqualTo(TENANT);
        assertThat(row.getStatus()).isEqualTo("REQUESTED");
        assertThat(row.getRequestNo()).matches("DSAR-20261116-[0-9A-HJKMNP-TV-Z]{8}");
        assertThat(row.getIntakeTicketRef()).isEqualTo("TICKET-2026-0001");
        assertThat(row.getReceivedAt()).isEqualTo(LocalDateTime.of(2026, 11, 16, 10, 0));
        assertThat(row.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 16));
        assertThat(row.getIdentityState()).isEqualTo("UNVERIFIED");
        assertThat(row.getCreatedBy()).isEqualTo(ADMIN_ID);
        assertThat(row.getRowVersion()).isZero();
        assertThat(auditLog.codes).containsExactly("NF07_DSAR_INTAKE_RECORDED");
        assertThat(auditLog.uris.get(0)).contains("requestNo=" + row.getRequestNo()).contains("to=REQUESTED")
                .doesNotContain("TICKET-2026-0001");
    }

    @Test
    void 受付日時は過去366日以内の指定を受け付け未来と書式違反と自由文の参照は400にする() {
        PrivacyDsarViews.Transition past = service.intake(new PrivacyDsarCommands.Intake("DELETION", "POSTAL",
                "2026-11-10T09:30", "LETTER-77", null));
        PrivacyRequest row = requestMapper.selectById(past.requestId());
        assertThat(row.getReceivedAt()).isEqualTo(LocalDateTime.of(2026, 11, 10, 9, 30));
        assertThat(row.getDueDate()).isEqualTo(LocalDate.of(2026, 12, 10));

        assertInvalid(() -> service.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", "2026-11-17T09:00",
                "T-1", null)));
        assertInvalid(() -> service.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", "2025-11-01T09:00",
                "T-1", null)));
        assertInvalid(() -> service.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", "16/11/2026", "T-1", null)));
        assertInvalid(() -> service.intake(intake("taro@example.invalid", null)));
        assertInvalid(() -> service.intake(intake("山田 太郎", null)));
        assertInvalid(() -> service.intake(new PrivacyDsarCommands.Intake("access", "EMAIL", null, "T-1", null)));
        assertInvalid(() -> service.intake(new PrivacyDsarCommands.Intake("ACCESS", "FAX", null, "T-1", null)));
        assertInvalid(() -> service.intake(null));
        assertThat(auditLog.codes).containsExactly("NF07_DSAR_INTAKE_RECORDED");
    }

    // ---------------------------------------------------------------- D-06 の決定

    @Test
    void D06の決定IDが無いと受付は記録するが期限日は計算せず本人確認以降はDECISION_UNKNOWNで進めない() {
        approval.getD06().setDecisionId("");
        long id = service.intake(intake("T-NO-D06", null)).requestId();
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getDueDate()).as("規則の決定が無い間は期限日を計算しない").isNull();

        assertDecisionUnknown(() -> service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-1")));
        assertDecisionUnknown(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(0L,
                "ENGINEER:101")));
        assertDecisionUnknown(() -> service.resolveCandidate(id, 1L,
                new PrivacyDsarCommands.CandidateResolution(0L, "SELECTED", null)));
        assertDecisionUnknown(() -> service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(0L)));

        approval.getD06().setDecisionId("D06-TBD-0001");
        assertDecisionUnknown(() -> service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-1")));
        approval.getD06().setDecisionId(D06_ID);
        approval.getD01().setExpiresOn("2026-11-16");
        assertDecisionUnknown(() -> service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-1")));

        row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("REQUESTED");
        assertThat(row.getRowVersion()).isZero();
        assertThat(auditLog.codes).containsExactly("NF07_DSAR_INTAKE_RECORDED");
    }

    @Test
    void 期限規則が範囲外なら期限日を計算しない() {
        features.getDsar().setDueDays(0);
        assertThat(requestMapper.selectById(service.intake(intake("T-DUE-0", null)).requestId()).getDueDate()).isNull();
        features.getDsar().setDueDays(367);
        assertThat(requestMapper.selectById(service.intake(intake("T-DUE-367", null)).requestId()).getDueDate()).isNull();
        features.getDsar().setDueDays(null);
        assertThat(requestMapper.selectById(service.intake(intake("T-DUE-NULL", null)).requestId()).getDueDate()).isNull();
    }

    // ---------------------------------------------------------------- 本人確認

    @Test
    void 本人確認は証跡の参照と適用したD06の決定IDを記録しREQUESTED以外からは進めない() {
        long id = service.intake(intake("T-IDV", null)).requestId();

        assertConflict(() -> service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(5L, "EV-1")));
        assertInvalid(() -> service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "passport 123")));
        assertInvalid(() -> service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(null, "EV-1")));

        PrivacyDsarViews.Transition result = service.verifyIdentity(id,
                new PrivacyDsarCommands.IdentityVerification(0L, "IDV-EVIDENCE-0001"));

        assertThat(result.status()).isEqualTo("IDENTITY_VERIFIED");
        assertThat(result.rowVersion()).isEqualTo(1L);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getIdentityState()).isEqualTo("VERIFIED");
        assertThat(row.getIdentityEvidenceRef()).isEqualTo("IDV-EVIDENCE-0001");
        assertThat(row.getIdentityRuleRef()).isEqualTo(D06_ID);
        assertThat(row.getIdentityVerifiedBy()).isEqualTo(ADMIN_ID);
        assertThat(row.getIdentityVerifiedAt()).isEqualTo(LocalDateTime.of(2026, 11, 16, 10, 0));
        assertThat(row.getRowVersion()).isEqualTo(1L);

        assertConflict(() -> service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(1L, "EV-2")));
        assertThat(auditLog.codes).containsExactly("NF07_DSAR_INTAKE_RECORDED", "NF07_DSAR_IDENTITY_VERIFIED");
        assertThat(auditLog.uris.get(1)).doesNotContain("IDV-EVIDENCE-0001").doesNotContain(D06_ID);
    }

    // ---------------------------------------------------------------- 名寄せ・範囲確定

    @Test
    void 同姓同名の別人は自動で候補にならず範囲はSELECTEDがちょうど1件のときだけ確定する() {
        long id = verifiedRequest("T-SAME-NAME");

        PrivacyDsarViews.Transition added = service.addCandidate(id,
                new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        assertThat(added.rowVersion()).isEqualTo(2L);
        List<PrivacyRequestSubjectLink> links = links(id);
        assertThat(links).extracting(PrivacyRequestSubjectLink::getSubjectRef).containsExactly("ENGINEER:101");
        assertThat(links).extracting(PrivacyRequestSubjectLink::getLinkState).containsExactly("CANDIDATE");
        assertThat(provider.calls).as("同じ氏名で他の subject を探さない").containsOnly("ENGINEER:101");

        assertSubjectNotUnique(() -> service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(2L)));

        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(2L, "ENGINEER:102"));
        long first = linkId(id, "ENGINEER:101");
        long second = linkId(id, "ENGINEER:102");
        service.resolveCandidate(id, first, new PrivacyDsarCommands.CandidateResolution(3L, "SELECTED", null));
        service.resolveCandidate(id, second, new PrivacyDsarCommands.CandidateResolution(4L, "SELECTED",
                "SAME_NAME_CHECK"));
        assertSubjectNotUnique(() -> service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(5L)));
        assertThat(requestMapper.selectById(id).getStatus()).as("1件でなければ状態を変えない").isEqualTo("IDENTITY_VERIFIED");

        service.resolveCandidate(id, second, new PrivacyDsarCommands.CandidateResolution(5L, "REJECTED", "NOT_SAME_PERSON"));
        PrivacyDsarViews.Transition scoped = service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(6L));

        assertThat(scoped.status()).isEqualTo("SCOPED");
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("SCOPED");
        assertThat(row.getRowVersion()).isEqualTo(7L);
        assertThat(row.getScopeSnapshotHash()).matches("[0-9a-f]{64}");
        assertThat(linkStates(id)).containsEntry("ENGINEER:101", "SELECTED").containsEntry("ENGINEER:102", "REJECTED");
        PrivacyRequestSubjectLink rejected = linkMapper.selectById(second);
        assertThat(rejected.getResolvedBy()).isEqualTo(ADMIN_ID);
        assertThat(rejected.getResolutionCode()).isEqualTo("NOT_SAME_PERSON");
        assertThat(linkMapper.selectById(first).getResolutionCode()).isEqualTo("MANUAL_SELECTED");

        // A2b が同じ正規形で再計算できるよう、MANUAL_ACTION 行が as-of・hash・element 数を持つ
        List<PrivacyAction> actions = actions(id);
        assertThat(actions).hasSize(1);
        PrivacyAction scope = actions.get(0);
        assertThat(scope.getActionType()).isEqualTo("MANUAL_ACTION");
        assertThat(scope.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(scope.getEvidenceRef()).isEqualTo("SCOPE_SNAPSHOT");
        assertThat(scope.getResultHash()).isEqualTo(row.getScopeSnapshotHash());
        assertThat(scope.getResultCount()).isEqualTo(2);
        assertThat(scope.getIdempotencyKey()).isEqualTo("DSAR-SCOPE:" + id + ":7");
        // 再計算は DB の値だけで行う（D-06 は本人確認時の identity_rule_ref。review P2-1）
        PrivacyDsarScopeSnapshot.Snapshot recomputed = PrivacyDsarScopeSnapshot.of(row.getTenantId(), id,
                linkMapper.selectById(first).getSubjectRef(), row.getIdentityRuleRef(), scope.getCreatedAt(),
                registry.providers());
        assertThat(recomputed.hash()).isEqualTo(row.getScopeSnapshotHash());

        // SCOPED の後は候補を変えられず、詳細の表示でも provider を照会しない（review P3-2）
        int callsBeforeDetail = provider.calls.size();
        assertThat(service.detail(id).candidates()).allMatch(PrivacyDsarViews.Candidate::footprintOmitted)
                .noneMatch(PrivacyDsarViews.Candidate::footprintAvailable);
        assertThat(provider.calls).hasSize(callsBeforeDetail);
        assertConflict(() -> service.resolveCandidate(id, first,
                new PrivacyDsarCommands.CandidateResolution(7L, "REJECTED", null)));
        assertConflict(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(7L, "ENGINEER:102")));
        assertThat(provider.calls).as("受け付けられない候補の登録では記録を読まない（review P3-3）")
                .hasSize(callsBeforeDetail);
    }

    @Test
    void 本人確認の後にD06が差し替わると範囲を確定せず元の決定に戻れば確定する() {
        long id = verifiedRequest("T-D06-CHANGED");
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        service.resolveCandidate(id, linkId(id, "ENGINEER:101"),
                new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null));

        approval.getD06().setDecisionId("D06-NF07-2026-0016");
        assertThatThrownBy(() -> service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarDecisionChanged");
                });
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("IDENTITY_VERIFIED");
        assertThat(row.getScopeSnapshotHash()).isNull();
        assertThat(row.getIdentityRuleRef()).isEqualTo(D06_ID);
        assertThat(actions(id)).isEmpty();

        approval.getD06().setDecisionId(D06_ID);
        service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L));
        row = requestMapper.selectById(id);
        PrivacyAction scope = actions(id).get(0);
        assertThat(PrivacyDsarScopeSnapshot.of(TENANT, id, "ENGINEER:101", row.getIdentityRuleRef(),
                scope.getCreatedAt(), registry.providers()).hash()).isEqualTo(row.getScopeSnapshotHash());
    }

    /** NF07-A2b（delta review D-4）: D-06 が差し替わった後は候補の登録・裁定の時点で止め、範囲確定まで作業を続けさせない。 */
    @Test
    void 本人確認の後にD06が差し替わると候補の登録と裁定も409で止め記録を読まない() {
        long id = verifiedRequest("T-D06-EARLY");
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        provider.calls.clear();

        approval.getD06().setDecisionId("D06-NF07-2026-0016");
        assertThatThrownBy(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(2L, "ENGINEER:102")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarDecisionChanged"));
        assertThatThrownBy(() -> service.resolveCandidate(id, linkId(id, "ENGINEER:101"),
                new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarDecisionChanged"));
        assertThat(provider.calls).as("差し替わった後は対象者の記録を読まない").isEmpty();
        assertThat(linkStates(id)).containsExactly(Map.entry("ENGINEER:101", "CANDIDATE"));
        assertThat(requestMapper.selectById(id).getRowVersion()).isEqualTo(2L);
    }

    /** NF07-A2b（delta review D-4）: 本人確認の時点にも期限規則が無かった case は、範囲確定の時点の規則で受付日から期限日を補う。 */
    @Test
    void 本人確認の時点にも期限規則が無かったcaseは範囲確定の時点の規則で期限日を補う() {
        features.getDsar().setDueDays(null);
        long id = verifiedRequest("T-DUE-AT-SCOPE");
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        service.resolveCandidate(id, linkId(id, "ENGINEER:101"),
                new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null));
        assertThat(requestMapper.selectById(id).getDueDate()).isNull();

        features.getDsar().setDueDays(45);
        service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L));

        assertThat(requestMapper.selectById(id).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    /** NF07-A2b（review P2-2）: 開示以外の請求は SCOPED から手動対応の完了を記録して CONFIRMED にする（system は処分しない）。 */
    @Test
    void 開示以外の請求は手動対応の結果と証跡を記録して完了し開示請求と合わない結果と古い版は拒否する() {
        long id = scopedRequest("T-MANUAL", "DELETION");
        long access = scopedRequest("T-MANUAL-ACCESS", "ACCESS");

        assertThatThrownBy(() -> service.recordManualResolution(access,
                new PrivacyDsarCommands.ManualResolution(4L, "OTHER_RESOLVED", "ANS-1")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarManualResolutionAccess");
                });
        assertInvalid(() -> service.recordManualResolution(id,
                new PrivacyDsarCommands.ManualResolution(4L, "CORRECTED", "ANS-1")));
        assertInvalid(() -> service.recordManualResolution(id,
                new PrivacyDsarCommands.ManualResolution(4L, "DELETED", "taro@example.invalid")));
        assertInvalid(() -> service.recordManualResolution(id,
                new PrivacyDsarCommands.ManualResolution(4L, "DELETED", "SCOPE_SNAPSHOT")));
        assertConflict(() -> service.recordManualResolution(id,
                new PrivacyDsarCommands.ManualResolution(3L, "DELETED", "ANS-1")));
        approval.getD06().setDecisionId("D06-NF07-2026-0016");
        assertThatThrownBy(() -> service.recordManualResolution(id,
                new PrivacyDsarCommands.ManualResolution(4L, "DELETED", "ANS-1")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarDecisionChanged"));
        approval.getD06().setDecisionId(D06_ID);
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("SCOPED");

        PrivacyDsarViews.Transition done = service.recordManualResolution(id,
                new PrivacyDsarCommands.ManualResolution(4L, "DELETION_REFUSED_RETENTION", "ANS-2026-0001"));

        assertThat(done.status()).isEqualTo("CONFIRMED");
        assertThat(done.rowVersion()).isEqualTo(5L);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("CONFIRMED");
        assertThat(row.getDeliveredAt()).as("交付の列は開示請求だけが使う").isNull();
        assertThat(actions(id)).filteredOn(action -> action.getIdempotencyKey().startsWith("DSAR-MANUAL:"))
                .singleElement().satisfies(action -> {
                    assertThat(action.getActionType()).isEqualTo("MANUAL_ACTION");
                    assertThat(action.getStatus()).isEqualTo("SUCCEEDED");
                    assertThat(action.getBlockedReason()).isEqualTo("DELETION_REFUSED_RETENTION");
                    assertThat(action.getEvidenceRef()).isEqualTo("ANS-2026-0001");
                });
        assertThat(auditLog.codes).endsWith("NF07_DSAR_MANUAL_RESOLVED");
        assertThat(auditLog.uris.get(auditLog.uris.size() - 1)).contains("from=SCOPED").contains("to=CONFIRMED")
                .doesNotContain("ANS-2026-0001", "ENGINEER");
        PrivacyDsarViews.Detail detail = service.detail(id);
        assertThat(detail.nextActions()).isEmpty();
        assertThat(detail.closure()).as("完了は終了の記録として読まない").isNull();
        assertThat(service.detail(access).nextActions()).contains("PREVIEW").doesNotContain("RECORD_MANUAL_RESOLUTION");
    }

    @Test
    void 別人と判定した候補は詳細の表示で記録を照会しない() {
        long id = verifiedRequest("T-REJECTED-VIEW");
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(2L, "ENGINEER:102"));
        service.resolveCandidate(id, linkId(id, "ENGINEER:102"),
                new PrivacyDsarCommands.CandidateResolution(3L, "REJECTED", "NOT_SAME_PERSON"));
        provider.calls.clear();

        PrivacyDsarViews.Detail detail = service.detail(id);

        PrivacyDsarViews.Candidate selectedCandidate = detail.candidates().get(0);
        PrivacyDsarViews.Candidate rejected = detail.candidates().get(1);
        assertThat(selectedCandidate.footprintOmitted()).isFalse();
        assertThat(selectedCandidate.footprintAvailable()).isTrue();
        assertThat(rejected.linkState()).isEqualTo("REJECTED");
        assertThat(rejected.footprintOmitted()).isTrue();
        assertThat(rejected.footprint()).isEmpty();
        assertThat(provider.calls).containsExactly("ENGINEER:101");
    }

    @Test
    void 受け付けられない候補の登録は対象者の記録を読む前に拒否する() {
        long requested = service.intake(intake("T-PRECHECK", null)).requestId();
        assertConflict(() -> service.addCandidate(requested,
                new PrivacyDsarCommands.CandidateAddition(0L, "ENGINEER:101")));
        assertThat(provider.calls).isEmpty();

        long id = verifiedRequest("T-PRECHECK-DUP");
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        provider.calls.clear();
        assertConflict(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:102")));
        assertThatThrownBy(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(2L, "ENGINEER:101")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarCandidateDuplicate"));
        assertThat(provider.calls).as("古い版・重複の要求では記録を読まない").isEmpty();
    }

    @Test
    void 参照に書類の番号や電話番号に見える値は保存しない() {
        for (String numberLike : List.of("123456789012", "1234-5678-9012", "090-1234-5678", "03-1234-5678",
                "TK1234567", "4111111111111111")) {
            assertThatThrownBy(() -> service.intake(intake(numberLike, null))).as(numberLike)
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getCode()).isEqualTo(400);
                        assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarReferenceLooksLikeNumber");
                    });
        }
        long id = 0;
        for (String reference : List.of("TKT-0001", "2026-11-16-0001", "123456789", "SR-2026-0001234", "INC0012345")) {
            id = service.intake(intake(reference, null)).requestId();
            assertThat(requestMapper.selectById(id).getIntakeTicketRef()).isEqualTo(reference);
        }
        long verify = id;
        assertThatThrownBy(() -> service.verifyIdentity(verify,
                new PrivacyDsarCommands.IdentityVerification(0L, "123456789012")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarReferenceLooksLikeNumber"));
        assertThatThrownBy(() -> service.cancel(verify, new PrivacyDsarCommands.Closure(0L, "WITHDRAWN", "AB1234567")))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarReferenceLooksLikeNumber"));
        assertThat(requestMapper.selectById(verify).getStatus()).isEqualTo("REQUESTED");
    }

    @Test
    void 期限規則が無いまま受け付けたcaseは本人確認の時点の規則で受付日から期限日を補う() {
        approval.getD06().setDecisionId("");
        long id = service.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", "2026-11-10T09:00", "T-BACKFILL",
                null)).requestId();
        assertThat(requestMapper.selectById(id).getDueDate()).isNull();

        approval.getD06().setDecisionId(D06_ID);
        service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-BACKFILL"));

        assertThat(requestMapper.selectById(id).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 10));

        // 受付時に計算した期限日は書き換えない
        features.getDsar().setDueDays(10);
        long computed = service.intake(intake("T-NO-OVERWRITE", null)).requestId();
        features.getDsar().setDueDays(60);
        service.verifyIdentity(computed, new PrivacyDsarCommands.IdentityVerification(0L, "EV-NO-OVERWRITE"));
        assertThat(requestMapper.selectById(computed).getDueDate()).isEqualTo(LocalDate.of(2026, 11, 26));
    }

    @Test
    void 記録の無いsubjectと重複と不正な参照は候補にしない() {
        long id = verifiedRequest("T-CANDIDATE");

        assertThatThrownBy(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:999")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(400);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarCandidateNotFound");
                });
        assertInvalid(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "engineer:101")));
        assertInvalid(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, SAME_NAME)));
        assertInvalid(() -> service.resolveCandidate(id, 1L,
                new PrivacyDsarCommands.CandidateResolution(1L, "CANDIDATE", null)));

        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        assertThatThrownBy(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(2L, "ENGINEER:101")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarCandidateDuplicate");
                });
        assertThatThrownBy(() -> service.resolveCandidate(id, 987_654L,
                new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));

        provider.failing = true;
        assertThatThrownBy(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(2L, "ENGINEER:102")))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(503);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarSourceUnavailable");
                });
        assertThat(links(id)).hasSize(1);
    }

    @Test
    void 候補の表示はsubject参照と記録の件数だけで記録の値を含めない() throws Exception {
        long id = verifiedRequest("T-VIEW");
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));

        PrivacyDsarViews.Detail detail = service.detail(id);
        String json = new ObjectMapper().writeValueAsString(detail);

        assertThat(detail.candidates()).hasSize(1);
        PrivacyDsarViews.Candidate candidate = detail.candidates().get(0);
        assertThat(candidate.subjectRef()).isEqualTo("ENGINEER:101");
        assertThat(candidate.subjectType()).isEqualTo("ENGINEER");
        assertThat(candidate.footprintAvailable()).isTrue();
        assertThat(candidate.footprint()).extracting(PrivacyDsarViews.FootprintEntry::dataElementId,
                        PrivacyDsarViews.FootprintEntry::rowCount, PrivacyDsarViews.FootprintEntry::deliveryClass)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("FIXTURE_ENGINEER_PROFILE", 1, "MIXED_THIRD_PARTY"),
                        org.assertj.core.groups.Tuple.tuple("FIXTURE_ENGINEER_SKILL", 2, "SELF"));
        assertThat(json).doesNotContain(SAME_NAME).doesNotContain(EMAIL_101).doesNotContain("スキル本文")
                .doesNotContain("ENGINEER:102");
        assertThat(detail.nextActions()).containsExactly("ADD_CANDIDATE", "RESOLVE_CANDIDATE", "SCOPE", "BLOCK",
                "CANCEL");
        assertThat(detail.readiness().identityReady()).isTrue();

        provider.failing = true;
        PrivacyDsarViews.Candidate unavailable = service.detail(id).candidates().get(0);
        assertThat(unavailable.footprintAvailable()).isFalse();
        assertThat(unavailable.footprint()).isEmpty();
    }

    // ---------------------------------------------------------------- CAS・監査

    @Test
    void 同じ版の要求が競合すれば後の要求は409で先の裁定を上書きしない() {
        long id = verifiedRequest("T-CAS");
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(2L, "ENGINEER:102"));
        long first = linkId(id, "ENGINEER:101");
        long second = linkId(id, "ENGINEER:102");

        service.resolveCandidate(id, first, new PrivacyDsarCommands.CandidateResolution(3L, "SELECTED", null));
        assertConflict(() -> service.resolveCandidate(id, second,
                new PrivacyDsarCommands.CandidateResolution(3L, "SELECTED", null)));
        assertConflict(() -> service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L)));

        assertThat(linkStates(id)).containsEntry("ENGINEER:101", "SELECTED").containsEntry("ENGINEER:102", "CANDIDATE");
        assertThat(requestMapper.selectById(id).getRowVersion()).isEqualTo(4L);
        assertThat(requestMapper.selectById(id).getStatus()).isEqualTo("IDENTITY_VERIFIED");
    }

    @Test
    void 監査を記録できなければ操作ごと巻き戻す() {
        auditLog.failOnCode = "NF07_DSAR_INTAKE_RECORDED";
        assertThatThrownBy(() -> service.intake(intake("T-AUDIT-FAIL", null))).isInstanceOf(IllegalStateException.class);
        assertThat(requestMapper.selectCount(new LambdaQueryWrapper<PrivacyRequest>()
                .eq(PrivacyRequest::getTenantId, TENANT)
                .eq(PrivacyRequest::getIntakeTicketRef, "T-AUDIT-FAIL"))).isZero();

        auditLog.failOnCode = null;
        long id = service.intake(intake("T-AUDIT", null)).requestId();
        auditLog.failOnCode = "NF07_DSAR_IDENTITY_VERIFIED";
        assertThatThrownBy(() -> service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-1")))
                .isInstanceOf(IllegalStateException.class);
        PrivacyRequest row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("REQUESTED");
        assertThat(row.getRowVersion()).isZero();
        assertThat(row.getIdentityEvidenceRef()).isNull();

        auditLog.failOnCode = null;
        service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-1"));
        auditLog.failOnCode = "NF07_DSAR_CANDIDATE_ADDED";
        assertThatThrownBy(() -> service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(links(id)).isEmpty();
        assertThat(requestMapper.selectById(id).getRowVersion()).isEqualTo(1L);

        auditLog.failOnCode = null;
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        service.resolveCandidate(id, linkId(id, "ENGINEER:101"),
                new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null));
        auditLog.failOnCode = "NF07_DSAR_SCOPED";
        assertThatThrownBy(() -> service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L)))
                .isInstanceOf(IllegalStateException.class);
        row = requestMapper.selectById(id);
        assertThat(row.getStatus()).isEqualTo("IDENTITY_VERIFIED");
        assertThat(row.getScopeSnapshotHash()).isNull();
        assertThat(actions(id)).isEmpty();
    }

    @Test
    void 監査の本文には受付番号と状態遷移だけを入れsubject識別子と氏名と参照を入れない() {
        long id = verifiedRequest("TICKET-SECRET-REF");
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        service.resolveCandidate(id, linkId(id, "ENGINEER:101"),
                new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null));
        service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L));
        String hash = requestMapper.selectById(id).getScopeSnapshotHash();
        long other = service.intake(intake("T-CLOSE", null)).requestId();
        service.cancel(other, new PrivacyDsarCommands.Closure(0L, "WITHDRAWN", "EVIDENCE-SECRET"));

        assertThat(auditLog.codes).containsExactly("NF07_DSAR_INTAKE_RECORDED", "NF07_DSAR_IDENTITY_VERIFIED",
                "NF07_DSAR_CANDIDATE_ADDED", "NF07_DSAR_CANDIDATE_RESOLVED", "NF07_DSAR_SCOPED",
                "NF07_DSAR_INTAKE_RECORDED", "NF07_DSAR_CLOSED");
        for (String uri : auditLog.uris) {
            assertThat(uri).startsWith("/api/privacy/dsar#").contains("requestNo=DSAR-")
                    .doesNotContain("ENGINEER").doesNotContain(SAME_NAME).doesNotContain(EMAIL_101)
                    .doesNotContain("TICKET-SECRET-REF").doesNotContain("EVIDENCE-SECRET").doesNotContain("EV-")
                    .doesNotContain(hash).doesNotContain(D06_ID);
        }
        assertThat(auditLog.uris.get(3)).contains("linkFrom=CANDIDATE").contains("linkTo=SELECTED");
        assertThat(auditLog.uris.get(6)).contains("from=REQUESTED").contains("to=CANCELLED");
    }

    // ---------------------------------------------------------------- 終了・再申立

    @Test
    void 終了は理由コード付きで記録し終了後は遷移しない() {
        long id = verifiedRequest("T-BLOCK");
        assertInvalid(() -> service.block(id, new PrivacyDsarCommands.Closure(1L, "WITHDRAWN", null)));
        assertInvalid(() -> service.cancel(id, new PrivacyDsarCommands.Closure(1L, "MULTIPLE_CANDIDATES", null)));
        assertInvalid(() -> service.block(id, new PrivacyDsarCommands.Closure(1L, "MULTIPLE_CANDIDATES", "has space")));
        // 範囲確定の行の印は終了の証跡に使わせない（review P3-4）
        assertInvalid(() -> service.block(id, new PrivacyDsarCommands.Closure(1L, "MULTIPLE_CANDIDATES",
                "SCOPE_SNAPSHOT")));
        assertThat(actions(id)).isEmpty();

        PrivacyDsarViews.Transition blocked = service.block(id,
                new PrivacyDsarCommands.Closure(1L, "MULTIPLE_CANDIDATES", "CASE-NOTE-9"));

        assertThat(blocked.status()).isEqualTo("BLOCKED");
        List<PrivacyAction> actions = actions(id);
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).getStatus()).isEqualTo("BLOCKED");
        assertThat(actions.get(0).getBlockedReason()).isEqualTo("MULTIPLE_CANDIDATES");
        assertThat(actions.get(0).getEvidenceRef()).isEqualTo("CASE-NOTE-9");
        PrivacyDsarViews.Detail detail = service.detail(id);
        assertThat(detail.closure().reasonCode()).isEqualTo("MULTIPLE_CANDIDATES");
        assertThat(detail.nextActions()).isEmpty();
        assertConflict(() -> service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(2L)));
        assertConflict(() -> service.cancel(id, new PrivacyDsarCommands.Closure(2L, "WITHDRAWN", null)));

        // 決定が無くても取下げは記録できる（受付日時と同じく止めない）
        approval.getD06().setDecisionId("");
        long withdrawn = service.intake(intake("T-WITHDRAW", null)).requestId();
        assertThat(service.cancel(withdrawn, new PrivacyDsarCommands.Closure(0L, "WITHDRAWN", null)).status())
                .isEqualTo("CANCELLED");
        assertThat(actions(withdrawn).get(0).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(actions(withdrawn).get(0).getBlockedReason()).isEqualTo("WITHDRAWN");
    }

    @Test
    void 再申立は終了したcaseだけを元にでき元caseを書き換えない() {
        long active = service.intake(intake("T-ACTIVE", null)).requestId();
        assertThatThrownBy(() -> service.intake(intake("T-REOPEN-1", active)))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(409);
                    assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarReopenInvalid");
                });
        service.block(active, new PrivacyDsarCommands.Closure(0L, "IDENTITY_UNVERIFIED", null));
        PrivacyRequest closedBefore = requestMapper.selectById(active);

        long reopened = service.intake(intake("T-REOPEN-2", active)).requestId();

        assertThat(requestMapper.selectById(reopened).getReopenOfRequestId()).isEqualTo(active);
        assertThat(requestMapper.selectById(reopened).getStatus()).isEqualTo("REQUESTED");
        PrivacyRequest closedAfter = requestMapper.selectById(active);
        assertThat(closedAfter.getStatus()).isEqualTo("BLOCKED");
        assertThat(closedAfter.getRowVersion()).isEqualTo(closedBefore.getRowVersion());
        assertThatThrownBy(() -> service.intake(intake("T-REOPEN-3", 987_654_321L)))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
    }

    // ---------------------------------------------------------------- tenant・一覧・入口

    @Test
    void 他tenantのcaseは照会も操作もできず照会者のtenantが設定tenantと違えば403にする() {
        PrivacyRequest foreign = new PrivacyRequest();
        foreign.setTenantId(OTHER_TENANT);
        foreign.setRequestNo("DSAR-FOREIGN-0001");
        foreign.setRequestType("ACCESS");
        foreign.setStatus("REQUESTED");
        foreign.setChannel("EMAIL");
        foreign.setReceivedAt(LocalDateTime.of(2026, 11, 16, 9, 0));
        foreign.setIntakeTicketRef("T-FOREIGN");
        foreign.setIdentityState("UNVERIFIED");
        foreign.setRowVersion(0L);
        foreign.setCreatedBy(ADMIN_ID);
        requestMapper.insert(foreign);

        assertThatThrownBy(() -> service.detail(foreign.getId()))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> service.verifyIdentity(foreign.getId(),
                new PrivacyDsarCommands.IdentityVerification(0L, "EV-1")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(404));
        assertThatThrownBy(() -> service.intake(intake("T-X", foreign.getId())))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(409));
        assertThat(service.overview(null, 1, 100).requests())
                .noneMatch(summary -> summary.id() == foreign.getId());
        assertThat(requestMapper.selectById(foreign.getId()).getStatus()).isEqualTo("REQUESTED");

        PrivacyRequestService mismatched = service(OTHER_TENANT);
        assertThatThrownBy(() -> mismatched.overview(null, 1, 20))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(403));
    }

    @Test
    void 一覧は期限の接近と超過を示し状態で絞り込める() {
        long soon = service.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", "2026-10-21T09:00", "T-SOON", null))
                .requestId();
        long overdue = service.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", "2026-10-10T09:00", "T-LATE",
                null)).requestId();
        long later = service.intake(intake("T-LATER", null)).requestId();
        service.cancel(service.intake(new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", "2026-10-10T09:00", "T-DONE",
                null)).requestId(), new PrivacyDsarCommands.Closure(0L, "DUPLICATE", null));

        PrivacyDsarViews.Overview overview = service.overview(null, 1, 100);
        Map<Long, PrivacyDsarViews.Summary> byId = new HashMap<>();
        overview.requests().forEach(summary -> byId.put(summary.id(), summary));

        assertThat(byId.get(soon).dueSoon()).isTrue();
        assertThat(byId.get(soon).overdue()).isFalse();
        assertThat(byId.get(overdue).overdue()).isTrue();
        assertThat(byId.get(later).dueSoon()).isFalse();
        assertThat(byId.get(later).overdue()).isFalse();
        assertThat(overview.requests()).filteredOn(summary -> "CANCELLED".equals(summary.status()))
                .allMatch(summary -> !summary.overdue() && !summary.dueSoon());
        assertThat(service.overview("CANCELLED", 1, 100).requests()).extracting(PrivacyDsarViews.Summary::status)
                .containsOnly("CANCELLED");
        assertInvalid(() -> service.overview("NOT_A_STATE", 1, 20));
    }

    @Test
    void 公開スイッチOFFと権限なしはDBに触れずに例外をそのまま返す() {
        doThrow(new PrivacyAccessGuard.PrivacyEntryNotFoundException())
                .when(guard).requireAllowed(PrivacyAccessGuard.Capability.DSAR, "privacy.dsar.manage");
        assertThatThrownBy(() -> service.intake(intake("T-OFF", null)))
                .isInstanceOf(PrivacyAccessGuard.PrivacyEntryNotFoundException.class);

        doThrow(BusinessException.of(403, "error.accessDenied"))
                .when(guard).requireAllowed(eq(PrivacyAccessGuard.Capability.DSAR), eq("privacy.dsar.view"));
        assertThatThrownBy(service::requirePageAccess).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.overview(null, 1, 20)).isInstanceOf(BusinessException.class);
        assertThat(requestMapper.selectCount(new LambdaQueryWrapper<PrivacyRequest>()
                .eq(PrivacyRequest::getTenantId, TENANT))).isZero();
        assertThat(auditLog.codes).isEmpty();
    }

    /**
     * 本物の監査 service で {@code t_audit_log} へ書けること（H2 の列も本番と同じ {@code method VARCHAR(10)}）。以前の
     * {@code PRIVACY_DSAR}（12文字）では列に収まらず、required 監査の失敗で DSAR の全操作が失敗していた。
     */
    @Test
    void 監査は本番と同じ長さのmethod列へ本物の監査serviceで書ける() {
        PrivacyRequestService withRealAudit = service(TENANT, new PrivacyDsarAudit(realAuditLogService));

        PrivacyDsarViews.Transition result = withRealAudit.intake(intake("T-REAL-AUDIT", null));

        assertThat(jdbcTemplate.queryForList("SELECT method FROM t_audit_log WHERE uri = ?", String.class,
                "/api/privacy/dsar#INTAKE_RECORDED?requestNo=" + result.requestNo() + "&to=REQUESTED"))
                .containsExactly("PRIV_DSAR");
        assertThat(requestMapper.selectById(result.requestId()).getStatus()).isEqualTo("REQUESTED");
    }

    // ---------------------------------------------------------------- helpers

    private PrivacyRequestService service(String actorTenant) {
        return service(actorTenant, new PrivacyDsarAudit(auditLog));
    }

    private PrivacyRequestService service(String actorTenant, PrivacyDsarAudit audit) {
        OidcSecurityProperties oidc = new OidcSecurityProperties();
        oidc.setTenantId(TENANT);
        TransactionTemplate nested = new TransactionTemplate(transactionManager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        return new PrivacyRequestService(guard, oidc, new PrivacyDsarDecisions(approval, features, CLOCK), requestMapper,
                linkMapper, actionMapper, registry, audit, nested, CLOCK,
                () -> new PrivacyRequestService.Actor(actorTenant, ADMIN_ID, "privacy-admin-a"));
    }

    private long verifiedRequest(String ticketRef) {
        long id = service.intake(intake(ticketRef, null)).requestId();
        service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-" + ticketRef));
        return id;
    }

    /** 本人（ENGINEER:101）を選んで SCOPED（版4）まで進めた case。 */
    private long scopedRequest(String ticketRef, String requestType) {
        long id = service.intake(new PrivacyDsarCommands.Intake(requestType, "EMAIL", null, ticketRef, null)).requestId();
        service.verifyIdentity(id, new PrivacyDsarCommands.IdentityVerification(0L, "EV-" + ticketRef));
        service.addCandidate(id, new PrivacyDsarCommands.CandidateAddition(1L, "ENGINEER:101"));
        service.resolveCandidate(id, linkId(id, "ENGINEER:101"),
                new PrivacyDsarCommands.CandidateResolution(2L, "SELECTED", null));
        service.scope(id, new PrivacyDsarCommands.ScopeConfirmation(3L));
        return id;
    }

    private static PrivacyDsarCommands.Intake intake(String ticketRef, Long reopenOf) {
        return new PrivacyDsarCommands.Intake("ACCESS", "EMAIL", null, ticketRef, reopenOf);
    }

    private List<PrivacyRequestSubjectLink> links(long requestId) {
        return linkMapper.selectList(new LambdaQueryWrapper<PrivacyRequestSubjectLink>()
                .eq(PrivacyRequestSubjectLink::getTenantId, TENANT)
                .eq(PrivacyRequestSubjectLink::getRequestId, requestId)
                .orderByAsc(PrivacyRequestSubjectLink::getId));
    }

    private Map<String, String> linkStates(long requestId) {
        Map<String, String> states = new LinkedHashMap<>();
        links(requestId).forEach(link -> states.put(link.getSubjectRef(), link.getLinkState()));
        return states;
    }

    private long linkId(long requestId, String subjectRef) {
        return links(requestId).stream().filter(link -> subjectRef.equals(link.getSubjectRef())).findFirst()
                .orElseThrow().getId();
    }

    private List<PrivacyAction> actions(long requestId) {
        return actionMapper.selectList(new LambdaQueryWrapper<PrivacyAction>()
                .eq(PrivacyAction::getTenantId, TENANT)
                .eq(PrivacyAction::getRequestId, requestId)
                .orderByAsc(PrivacyAction::getId));
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(400);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarInvalidRequest");
        });
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarConflict");
        });
    }

    private static void assertDecisionUnknown(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarDecisionUnknown");
        });
    }

    private static void assertSubjectNotUnique(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getCode()).isEqualTo(409);
            assertThat(e.getMessageKey()).isEqualTo("error.privacy.dsarSubjectNotUnique");
        });
    }

    /** D-01（2026-11-01〜2027-10-31）と D-06（2026-11-05〜2027-11-04）の試験用決定。 */
    private static PrivacyApprovalProperties decisions(String d06Id) {
        PrivacyApprovalProperties approval = new PrivacyApprovalProperties();
        approval.getD01().setDecisionId("D01-NF07-2026-0001");
        approval.getD01().setSignedOn("2026-11-01");
        approval.getD01().setExpiresOn("2027-10-31");
        approval.getD06().setDecisionId(d06Id);
        approval.getD06().setSignedOn("2026-11-05");
        approval.getD06().setExpiresOn("2027-11-04");
        return approval;
    }

    /** 同姓同名の要員2名（ENGINEER:101・ENGINEER:102）の記録を返す fixture provider（行の値に氏名・連絡先を持つ）。 */
    static final class SameNameEngineerProvider implements ProductionPrivacyProvider {

        static final String PROFILE = "FIXTURE_ENGINEER_PROFILE";
        static final String SKILL = "FIXTURE_ENGINEER_SKILL";
        private static final ProviderTable TABLE = new ProviderTable("t_engineer",
                List.of("id", "tenant_id", "name", "email", "skill_note"), List.of());

        final List<String> calls = new ArrayList<>();
        boolean failing;

        @Override
        public String providerId() {
            return "dsar-fixture-engineer";
        }

        @Override
        public List<ProviderDataElement> dataElements() {
            List<SubjectLink> links = List.of(new SubjectLink(PrivacySubjectType.ENGINEER, "t_engineer.id"));
            return List.of(ProviderDataElement.exact(PROFILE, SubjectAttribution.ATTRIBUTABLE, links),
                    ProviderDataElement.exact(SKILL, SubjectAttribution.ATTRIBUTABLE, DsarDeliveryClass.SELF, links));
        }

        @Override
        public List<ProviderTable> tables() {
            return List.of(TABLE);
        }

        @Override
        public List<PrivacyRecord> findBySubject(String tenantId, PrivacySubjectRef subject) {
            calls.add(subject.ref());
            if (failing) {
                throw new IllegalStateException("provider unavailable");
            }
            if (!TENANT.equals(tenantId) || subject.type() != PrivacySubjectType.ENGINEER
                    || (subject.id() != 101L && subject.id() != 102L)) {
                return List.of();
            }
            String email = subject.id() == 101L ? EMAIL_101 : EMAIL_102;
            Map<String, Object> profile = new LinkedHashMap<>();
            profile.put("id", subject.id());
            profile.put("tenant_id", TENANT);
            profile.put("name", SAME_NAME);
            profile.put("email", email);
            Map<String, Object> skill = new LinkedHashMap<>(profile);
            skill.put("skill_note", "スキル本文");
            return List.of(new PrivacyRecord(providerId(), "t_engineer", subject.id(), List.of(PROFILE, SKILL), false,
                            profile, Set.of()),
                    new PrivacyRecord(providerId(), "t_engineer", subject.id() * 10, List.of(SKILL), false, skill,
                            Set.of()));
        }
    }

    /** required 監査を記録する（指定した application code だけ失敗させる）。 */
    static final class CapturingAuditLogService implements AuditLogService {

        final List<String> uris = new ArrayList<>();
        final List<String> codes = new ArrayList<>();
        String failOnCode;

        @Override
        public void record(String username, String method, String uri, int status) {
        }

        @Override
        public void recordRequired(String username, String method, String uri, int status, String applicationCode,
                                   boolean successFlag) {
            if (applicationCode.equals(failOnCode)) {
                throw new IllegalStateException("audit unavailable");
            }
            uris.add(uri);
            codes.add(applicationCode);
        }

        @Override
        public void recordDomainEventRequired(AuditLog entry) {
        }

        @Override
        public Page<AuditLog> page(long current, long size, String username, String method) {
            return new Page<>();
        }
    }
}
