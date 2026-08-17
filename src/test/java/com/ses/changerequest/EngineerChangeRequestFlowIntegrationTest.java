package com.ses.changerequest;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.ApprovalRequest;
import com.ses.entity.ApprovalRoute;
import com.ses.entity.ApprovalRouteStep;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerChangeRequest;
import com.ses.entity.EngineerSkill;
import com.ses.entity.Notification;
import com.ses.entity.SysUser;
import com.ses.mapper.ApprovalRequestMapper;
import com.ses.mapper.ApprovalRouteMapper;
import com.ses.mapper.ApprovalRouteStepMapper;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerChangeRequestMapper;
import com.ses.mapper.EngineerSkillMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.SkillTagMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.approval.ApprovalEngineService;
import com.ses.service.changerequest.EngineerChangeRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 変更申請（T089 A1）統合テスト。実approval engine・routeを経て
 * 下書き→申請→承認→反映の一気通貫、承認前master不変、競合→再申請、二重反映なし、
 * scope（本人A/本人B）、skill差し替え、原価/commission非公開を検証する（design §6.3 / R1.1〜R1.4, R5）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EngineerChangeRequestFlowIntegrationTest {

    @Autowired
    private EngineerChangeRequestService changeRequestService;
    @Autowired
    private ApprovalEngineService approvalEngineService;
    @Autowired
    private EngineerChangeRequestMapper changeRequestMapper;
    @Autowired
    private ApprovalRequestMapper approvalRequestMapper;
    @Autowired
    private ApprovalRouteMapper approvalRouteMapper;
    @Autowired
    private ApprovalRouteStepMapper approvalRouteStepMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private EngineerAccountLinkMapper engineerAccountLinkMapper;
    @Autowired
    private EngineerSkillMapper engineerSkillMapper;
    @Autowired
    private SkillTagMapper skillTagMapper;
    @Autowired
    private NotificationMapper notificationMapper;

    static final java.util.concurrent.atomic.AtomicInteger ROUTE_SEQ =
            new java.util.concurrent.atomic.AtomicInteger(2000);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void プロフィール変更が承認後に1回だけ反映され承認前はmaster不変() {
        long applicant = insertUser();
        long approver = insertUser();
        long engineerId = createEngineer();
        link(engineerId, applicant);
        insertRoute("profile.change", List.of(List.of(approver)));
        authenticate(applicant, "要員");

        EngineerChangeRequestService.ChangeRequestDto draft = changeRequestService.createDraft(engineerId,
                "profile.change", Map.of("nearestStation", "新駅", "experienceYears", 8));
        assertEquals("下書き", draft.status());

        // allowlist外のfieldは拒否される（design §6.3）
        assertThrows(BusinessException.class, () -> changeRequestService.createDraft(engineerId,
                "profile.change", Map.of("costPrice", 999999)));

        // 承認前はmaster不変（R5）
        assertEquals("旧駅", engineerMapper.selectById(engineerId).getNearestStation());

        EngineerChangeRequestService.ChangeRequestDto applied = changeRequestService.submit(engineerId, draft.id());
        assertEquals("申請中", applied.status());
        assertNotNull(applied.approvalRequestId());

        authenticate(approver, "管理者");
        approvalEngineService.approve(applied.approvalRequestId(), approver, "OK");

        EngineerChangeRequest after = changeRequestMapper.selectById(draft.id());
        assertEquals("反映済", after.getStatus());
        assertNotNull(after.getAppliedAt());
        Engineer master = engineerMapper.selectById(engineerId);
        assertEquals("新駅", master.getNearestStation());
        assertEquals(8, master.getExperienceYears());

        // 反映通知（本人向け。dedupeKeyは#u{userId}付与）
        assertEquals(1, countNotification(applicant, "CHANGE_REQUEST_APPLIED",
                "change-request-applied:" + draft.id()));

        // 二重反映なし（再approveはengineが拒否し、masterも不変のまま）
        int versionAtApproved = changeRequestMapper.selectById(draft.id()).getVersion();
        assertThrows(BusinessException.class, () -> approvalEngineService.approve(
                applied.approvalRequestId(), approver, "再承認"));
        assertEquals("反映済", changeRequestMapper.selectById(draft.id()).getStatus());
        assertEquals(versionAtApproved, changeRequestMapper.selectById(draft.id()).getVersion());
        assertEquals("新駅", engineerMapper.selectById(engineerId).getNearestStation());
    }

    @Test
    void master同時更新は競合になり再申請で反映される() {
        long applicant = insertUser();
        long approver = insertUser();
        long engineerId = createEngineer();
        link(engineerId, applicant);
        insertRoute("profile.change", List.of(List.of(approver)));
        authenticate(applicant, "要員");

        EngineerChangeRequestService.ChangeRequestDto draft = changeRequestService.createDraft(engineerId,
                "profile.change", Map.of("nearestStation", "変更駅"));
        EngineerChangeRequestService.ChangeRequestDto applied = changeRequestService.submit(engineerId, draft.id());

        // 申請後に他者（管理操作）がmasterを同時更新
        Engineer current = engineerMapper.selectById(engineerId);
        current.setPrefecture("東京");
        engineerMapper.updateById(current);

        authenticate(approver, "管理者");
        approvalEngineService.approve(applied.approvalRequestId(), approver, "承認");
        ApprovalRequest approval = approvalRequestMapper.selectById(applied.approvalRequestId());
        assertEquals("conflict", approval.getStatus());
        assertEquals("申請中", changeRequestMapper.selectById(draft.id()).getStatus());
        assertEquals("旧駅", engineerMapper.selectById(engineerId).getNearestStation());

        // 再申請（engineが最新fingerprintで再snapshot）→ 承認 → 反映
        authenticate(applicant, "要員");
        changeRequestService.resubmit(engineerId, draft.id());
        authenticate(approver, "管理者");
        approvalEngineService.approve(applied.approvalRequestId(), approver, "再承認");
        assertEquals("反映済", changeRequestMapper.selectById(draft.id()).getStatus());
        assertEquals("変更駅", engineerMapper.selectById(engineerId).getNearestStation());
    }

    @Test
    void 本人Aの申請を本人Bは参照できない() {
        long userA = insertUser();
        long userB = insertUser();
        long engineerA = createEngineer();
        long engineerB = createEngineer();
        link(engineerA, userA);
        link(engineerB, userB);
        authenticate(userA, "要員");
        EngineerChangeRequestService.ChangeRequestDto draft = changeRequestService.createDraft(engineerA,
                "profile.change", Map.of("nearestStation", "A駅"));

        authenticate(userB, "要員");
        assertThrows(BusinessException.class, () -> changeRequestService.detailOwn(engineerB, draft.id()));
        assertThrows(BusinessException.class, () -> changeRequestService.submit(engineerB, draft.id()));
    }

    @Test
    void スキル変更申請が承認後に差し替えられる() {
        long applicant = insertUser();
        long approver = insertUser();
        long engineerId = createEngineer();
        link(engineerId, applicant);
        insertRoute("skill.change", List.of(List.of(approver)));

        long tagId = insertSkillTag();
        EngineerSkill skill = new EngineerSkill();
        skill.setEngineerId(engineerId);
        skill.setSkillId(tagId);
        skill.setProficiency("初級");
        skill.setExperienceYears(1);
        engineerSkillMapper.insert(skill);

        authenticate(applicant, "要員");
        EngineerChangeRequestService.ChangeRequestDto draft = changeRequestService.createDraft(engineerId,
                "skill.change", Map.of("skills", List.of(
                        Map.of("skillId", tagId, "proficiency", "上級", "experienceYears", 4))));
        EngineerChangeRequestService.ChangeRequestDto applied = changeRequestService.submit(engineerId, draft.id());

        authenticate(approver, "管理者");
        approvalEngineService.approve(applied.approvalRequestId(), approver, "OK");

        EngineerSkill after = engineerSkillMapper.selectOne(new LambdaQueryWrapper<EngineerSkill>()
                .eq(EngineerSkill::getEngineerId, engineerId).eq(EngineerSkill::getSkillId, tagId));
        assertNotNull(after);
        assertEquals("上級", after.getProficiency());
        assertEquals(4, after.getExperienceYears());
        assertEquals("反映済", changeRequestMapper.selectById(draft.id()).getStatus());
    }

    @Test
    void 本人プロフィールレスポンスに原価commissionが含まれない() {
        long applicant = insertUser();
        long engineerId = createEngineer();
        link(engineerId, applicant);
        authenticate(applicant, "要員");

        EngineerChangeRequestService.MyProfileView view =
                changeRequestService.myProfile(engineerId);

        // 本人レスポンス（MyProfileView / PublicContract）の構造に金銭項目が無いことを固定する
        java.util.List<String> profileFields = java.util.Arrays.stream(
                        EngineerChangeRequestService.MyProfileView.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        java.util.List<String> contractFields = java.util.Arrays.stream(
                        EngineerChangeRequestService.PublicContract.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName).toList();
        assertTrue(profileFields.stream().noneMatch(f -> f.toLowerCase().contains("cost")
                        || f.toLowerCase().contains("commission")
                        || f.toLowerCase().contains("selling")),
                "本人profileレスポンスに原価/commission/売価が含まれている: " + profileFields);
        assertTrue(contractFields.stream().noneMatch(f -> f.toLowerCase().contains("cost")
                        || f.toLowerCase().contains("commission")
                        || f.toLowerCase().contains("selling")
                        || f.toLowerCase().contains("price")
                        || f.toLowerCase().contains("amount")
                        || f.toLowerCase().contains("billing")),
                "契約の公開条件に金銭項目が含まれている: " + contractFields);
        assertNotNull(view);
    }

    // ----------------------------------------------------------------
    // ヘルパー
    // ----------------------------------------------------------------

    private long countNotification(Long userId, String type, String dedupeKeyBase) {
        return notificationMapper.selectCount(new LambdaQueryWrapper<Notification>()
                .eq(Notification::getType, type)
                .eq(Notification::getDedupeKey, dedupeKeyBase + "#u" + userId)
                .eq(Notification::getRecipientUserId, userId));
    }

    long insertUser() {
        SysUser user = SysUser.builder()
                .username("cr-" + System.nanoTime())
                .password("x")
                .realName("変更申請テスト")
                .role("管理者")
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    long createEngineer() {
        Engineer engineer = Engineer.builder()
                .fullName("変更申請要員-" + System.nanoTime())
                .fullNameKana("ヘンコウ")
                .employmentType("正社員")
                .status("Bench")
                .nearestStation("旧駅")
                .build();
        engineerMapper.insert(engineer);
        return engineer.getId();
    }

    void link(Long engineerId, Long sysUserId) {
        // 共有H2には他classが残したlink行がありうるため、該当engineer/userの既存linkを先に削除する
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getEngineerId, engineerId));
        engineerAccountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, sysUserId));
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        engineerAccountLinkMapper.insert(link);
    }

    long insertSkillTag() {
        // 共有H2には同名tagが残りうるため一意名で作成する
        com.ses.entity.SkillTag tag = new com.ses.entity.SkillTag();
        tag.setSkillName("Java-" + System.nanoTime());
        tag.setCategory("言語");
        skillTagMapper.insert(tag);
        return tag.getId();
    }

    void insertRoute(String requestType, List<List<Long>> steps) {
        // 共有H2に同typeのrouteが残っても最新が採用されるようversion_noを一意にする
        ApprovalRoute route = ApprovalRoute.builder()
                .tenantId(1L).requestType(requestType).organizationId(null)
                .minAmount(null).maxAmount(null)
                .versionNo(ROUTE_SEQ.incrementAndGet())
                .validFrom(LocalDate.now().minusDays(1)).activeFlag(1)
                .build();
        approvalRouteMapper.insert(route);
        for (int i = 0; i < steps.size(); i++) {
            int stepNo = i + 1;
            for (Long approverId : steps.get(i)) {
                ApprovalRouteStep step = ApprovalRouteStep.builder()
                        .routeId(route.getId()).stepNo(stepNo).parallelGroup(stepNo)
                        .approverType("USER").approverValue(String.valueOf(approverId))
                        .build();
                approvalRouteStepMapper.insert(step);
            }
        }
    }

    void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
