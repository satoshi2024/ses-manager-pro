package com.ses.oneonone;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSales;
import com.ses.entity.Notification;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.EngineerSalesMapper;
import com.ses.mapper.NotificationMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.SystemConfigService;
import com.ses.service.oneonone.OneOnOneRequestService;
import com.ses.service.survey.SurveyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1on1 / survey / privacy（T092 B2）統合テスト。
 * 1on1状態機械・confidential可視範囲（HR/管理者のみ）、surveyの未回答除外集計・匿名閾値・
 * confidential質問の非表示・キャンペーン配信通知を検証する（design §5/§6.1/§6.2, R4.1〜R4.4）。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class OneOnOneSurveyFlowIntegrationTest {

    @Autowired
    private OneOnOneRequestService oneOnOneService;
    @Autowired
    private SurveyService surveyService;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerAccountLinkMapper accountLinkMapper;
    @Autowired
    private EngineerSalesMapper engineerSalesMapper;
    @Autowired
    private NotificationMapper notificationMapper;
    @Autowired
    private SystemConfigService systemConfigService;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
    @Autowired
    private com.ses.mapper.UserOrganizationMapper userOrganizationMapper;

    @BeforeEach
    void setUp() {
        // 匿名閾値を1にして平均値を確認可能にする（threshold検証はテスト末尾で3へ変更する）
        systemConfigService.put("survey.min-answers", "1", "テスト用閾値");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void oneOnOneフローが申請から日程確定実施済まで進みconfidentialは営業から見えない() {
        long engineerUser = insertUser("管理者");
        long salesUser = insertUser("営業");
        long hrUser = insertUser("HR");
        long orgId = createOrg();
        long engineerId = createEngineer(orgId);
        link(engineerId, engineerUser);
        assignSales(engineerId, salesUser);

        authenticate(engineerUser, "要員");
        OneOnOneRequestService.OneOnOneDto created = oneOnOneService.create(engineerId, salesUser,
                List.of(LocalDate.now().plusDays(3)));
        assertEquals("申請", created.status());

        // 営業: 担当要員の1on1を閲覧・日程確定・実施済
        authenticate(salesUser, "営業");
        OneOnOneRequestService.OneOnOneDto detailSales = oneOnOneService.detailManagement(created.id());
        assertNull(detailSales.privateNoteRef(), "営業にはprivate_note_refを出さない（design §6.2）");

        OneOnOneRequestService.OneOnOneDto scheduled =
                oneOnOneService.schedule(created.id(), LocalDate.now().plusDays(4));
        assertEquals("日程確定", scheduled.status());
        assertNotNull(scheduled.scheduledAt());

        OneOnOneRequestService.OneOnOneDto done =
                oneOnOneService.complete(created.id(), "面談実施。問題なし。");
        assertEquals("実施済", done.status());

        // HR: confidentialを保存して閲覧できる。営業の詳細にはprivate_note_refが入らない
        authenticate(hrUser, "HR");
        OneOnOneRequestService.OneOnOneDto hrView = oneOnOneService.savePrivateNote(created.id(), "要員が負荷を訴えている（HR限定向け）");
        assertNotNull(hrView.privateNoteRef());

        authenticate(salesUser, "営業");
        OneOnOneRequestService.OneOnOneDto salesView = oneOnOneService.detailManagement(created.id());
        assertNull(salesView.privateNoteRef());
        assertEquals("面談実施。問題なし。", salesView.employeeVisibleNote());

        // マネージャーにもprivateは出ない（配下要員として組織scope設定）
        long managerUser = insertUser("マネージャー");
        assignManager(managerUser, orgId);
        authenticate(managerUser, "マネージャー");
        OneOnOneRequestService.OneOnOneDto managerView = oneOnOneService.detailManagement(created.id());
        assertNull(managerView.privateNoteRef());

        // 本人は自分の実施記録を見られる
        authenticate(engineerUser, "要員");
        OneOnOneRequestService.OneOnOneDto own = oneOnOneService.detailOwn(engineerId, created.id());
        assertEquals("面談実施。問題なし。", own.employeeVisibleNote());
    }

    @Test
    void surveyは未回答を母数に含めず匿名閾値未満を非表示にする() {
        long engineerUserA = insertUser("管理者");
        long engineerUserB = insertUser("管理者");
        long orgId = createOrg();
        long engineerA = createEngineer(orgId);
        long engineerB = createEngineer(orgId);
        link(engineerA, engineerUserA);
        link(engineerB, engineerUserB);

        authenticate(engineerUserA, "要員");
        assertThrows(BusinessException.class, () -> surveyService.createTemplate(
                        "satisfaction-" + System.nanoTime(), "稼働満足度", "", List.of(
                                new SurveyService.QuestionDef("q1", "満足度", "SCALE1_5", false))),
                "要員はテンプレートを作成できない");

        long hrUser = insertUser("HR");
        authenticate(hrUser, "HR");
        SurveyService.TemplateDto template = surveyService.createTemplate(
                "satisfaction-" + System.nanoTime(), "稼働満足度",
                "", List.of(
                        new SurveyService.QuestionDef("q1", "満足度", "SCALE1_5", false),
                        new SurveyService.QuestionDef("q2", "負荷感", "SCALE1_5_COMMENT", true)));
        SurveyService.CampaignDto campaign = surveyService.createCampaign(template.id(), "2026-08期", null, null);

        authenticate(engineerUserA, "要員");
        assertThrows(BusinessException.class, () -> surveyService.activateCampaign(campaign.id()),
                "要員はキャンペーンを配信開始できない");

        authenticate(hrUser, "HR");
        surveyService.activateCampaign(campaign.id());

        // 配信通知
        assertEquals(1, countNotification(engineerUserA, "SURVEY_CAMPAIGN", "survey-campaign:" + campaign.id()));

        // 回答（A: q1=4のみ（q2未回答）、B: q1=3、q2=2＋confidentialコメント）
        authenticate(engineerUserA, "要員");
        surveyService.submitAnswers(engineerA, campaign.id(), true, List.of(
                new SurveyService.AnswerInput("q1", 4, null, "PUBLIC")));
        authenticate(engineerUserB, "要員");
        surveyService.submitAnswers(engineerB, campaign.id(), true, List.of(
                new SurveyService.AnswerInput("q1", 3, null, "PUBLIC"),
                new SurveyService.AnswerInput("q2", 2, "confidential相談", "CONFIDENTIAL")));

        // HR集計: q1平均=(4+3)/2=3.5、q2平均=2.00（Aのq2未回答は母数から除外。0点として数えると1.00になる）
        authenticate(hrUser, "HR");
        SurveyService.AggregateResult aggregate = surveyService.aggregate(campaign.id());
        SurveyService.QuestionAggregate q1 = aggregate.questions().get(0);
        SurveyService.QuestionAggregate q2 = aggregate.questions().get(1);
        assertEquals(2, q1.answeredCount());
        assertEquals(new BigDecimal("3.50"), q1.average());
        assertEquals(1, q2.answeredCount(), "未回答（Aのq2）は母数から除外する（design §6.1）");
        assertEquals(new BigDecimal("2.00"), q2.average(), "未回答を0点として数えると(2+0)/2=1.00になるはず");

        // マネージャー: confidential質問（q2）は非表示（配下要員として組織scope設定）
        long managerUser = insertUser("マネージャー");
        assignManager(managerUser, orgId);
        authenticate(managerUser, "マネージャー");
        SurveyService.AggregateResult managerAgg = surveyService.aggregate(campaign.id());
        assertTrue(managerAgg.questions().get(1).hidden(), "confidential質問はマネージャーへ非表示（design §6.2）");
        assertTrue(managerAgg.questions().get(0).average() != null);

        // 匿名閾値: 回答数2 < 閾値3 → 非表示（design §5）
        systemConfigService.put("survey.min-answers", "3", "テスト用閾値");
        SurveyService.AggregateResult thresholdAgg = surveyService.aggregate(campaign.id());
        assertTrue(thresholdAgg.questions().get(0).hidden(), "最低回答数未満の質問は非表示（design §5）");

        // 個別回答はHR/管理者のみ。マネージャーは403
        authenticate(managerUser, "マネージャー");
        assertThrows(BusinessException.class, () -> surveyService.responses(campaign.id()));
        authenticate(hrUser, "HR");
        List<SurveyService.ResponseView> responses = surveyService.responses(campaign.id());
        assertTrue(responses.stream().anyMatch(r -> "confidential相談".equals(r.comment())
                && "CONFIDENTIAL".equals(r.commentVisibility())));
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

    long insertUser(String role) {
        SysUser user = SysUser.builder()
                .username("b2-" + System.nanoTime())
                .password("x")
                .realName("B2テスト")
                .role(role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    long createEngineer(Long organizationId) {
        Engineer engineer = Engineer.builder()
                .fullName("B2要員-" + System.nanoTime())
                .employmentType("正社員")
                .status("Bench")
                .organizationId(organizationId)
                .build();
        engineerMapper.insert(engineer);
        jdbcTemplate.update("DELETE FROM t_engineer_accounting_history WHERE engineer_id = ?", engineer.getId());
        return engineer.getId();
    }

    long createEngineer() {
        return createEngineer(null);
    }

    long createOrg() {
        String code = "B2ORG-" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO m_organization_unit (tenant_id, legal_entity_id, code, name, type, valid_from, status) "
                + "VALUES (1, 70002, ?, ?, '部門', '2026-01-01', '有効')", code, "B2組織-" + System.nanoTime());
        return jdbcTemplate.queryForObject("SELECT id FROM m_organization_unit WHERE code = ?", Long.class, code);
    }

    void assignManager(Long managerUserId, Long organizationId) {
        jdbcTemplate.update("DELETE FROM t_user_organization WHERE user_id = ?", managerUserId);
        com.ses.entity.UserOrganization row = new com.ses.entity.UserOrganization();
        row.setUserId(managerUserId);
        row.setOrganizationId(organizationId);
        row.setPrimaryFlag(1);
        row.setValidFrom(LocalDate.of(2026, 1, 1));
        row.setValidTo(null);
        userOrganizationMapper.insert(row);
    }

    void link(Long engineerId, Long sysUserId) {
        accountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getEngineerId, engineerId));
        accountLinkMapper.delete(new LambdaQueryWrapper<EngineerAccountLink>()
                .eq(EngineerAccountLink::getSysUserId, sysUserId));
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(engineerId);
        link.setSysUserId(sysUserId);
        accountLinkMapper.insert(link);
    }

    void assignSales(Long engineerId, Long salesUserId) {
        EngineerSales sales = EngineerSales.builder()
                .engineerId(engineerId)
                .salesUserId(salesUserId)
                .primaryFlag(1)
                .assignedAt(LocalDate.now())
                .build();
        engineerSalesMapper.insert(sales);
    }

    void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
