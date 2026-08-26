package com.ses.oneonone;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.oneonone.OneOnOneRequestService;
import com.ses.service.survey.SurveyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R1-P1-09 境界値検証: 1on1候補日とsurvey回答期間の境界を固定Clockで検証する。
 * - 1on1: 前日（400）、当日（400）、翌日（200）
 * - survey: 開始前（400/非表示）、開始日（200）、終了日（200）、終了日翌日（400/非表示）
 * decision table §6.1 の「翌日以降（date >= today.plusDays(1)）のみ有効」「両端Inclusive」と一致する。
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(OneOnOneSurveyBoundaryClockTest.FixedClockConfig.class)
@Transactional
class OneOnOneSurveyBoundaryClockTest {

    /** 固定Clock: 2026-08-18（Asia/Tokyo）。decision table §6.1の「システム時計はAsia/Tokyo」に合わせる。 */
    static final LocalDate TODAY = LocalDate.of(2026, 8, 18);

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        public Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-08-18T00:00:00+09:00"), ZoneId.of("Asia/Tokyo"));
        }
    }

    @Autowired
    private OneOnOneRequestService oneOnOneService;
    @Autowired
    private SurveyService surveyService;
    @Autowired
    private com.ses.service.SystemConfigService systemConfigService;
    @Autowired
    private EngineerMapper engineerMapper;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private EngineerAccountLinkMapper accountLinkMapper;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        systemConfigService.put("survey.min-answers", "1", "テスト用閾値");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void oneOnOne候補日は前日当日が400翌日以降が200() {
        long engineerUser = insertUser("要員");
        long hrUser = insertUser("HR");
        long engineerId = createEngineer();
        link(engineerId, engineerUser);

        authenticate(engineerUser, "要員");

        // 前日（today-1）は拒否
        BusinessException prev = assertThrows(BusinessException.class, () ->
                oneOnOneService.create(engineerId, hrUser, List.of(TODAY.minusDays(1))));
        assertEquals(400, prev.getCode(), "前日は400であるべき（decision table §6.1）");

        // 当日（today）は拒否（R1-P1-09: 従来はisBefore(today)で当日が通っていた）
        BusinessException today = assertThrows(BusinessException.class, () ->
                oneOnOneService.create(engineerId, hrUser, List.of(TODAY)));
        assertEquals(400, today.getCode(), "当日は400であるべき（翌日以降のみ有効）");

        // 翌日（today+1）は許可
        OneOnOneRequestService.OneOnOneDto ok = oneOnOneService.create(engineerId, hrUser, List.of(TODAY.plusDays(1)));
        assertNotNull(ok);
        assertEquals(TODAY.plusDays(1), ok.candidateDates().get(0));
    }

    @Test
    void surveyは開始前と終了日翌日が400開始日終了日が200() {
        long hrUser = insertUser("HR");
        long engineerUser = insertUser("要員");
        long engineerId = createEngineer();
        link(engineerId, engineerUser);

        authenticate(hrUser, "HR");
        SurveyService.TemplateDto template = surveyService.createTemplate(
                "KEY-BOUND-CLOCK-" + System.nanoTime(), "期間テスト", null,
                List.of(new SurveyService.QuestionDef("q1", "設問1", "SCALE1_5", false)));

        // 開始日=当日（2026-08-18）、終了日=2026-08-20 → 当日回答可能
        SurveyService.CampaignDto cStartToday = surveyService.createCampaign(
                template.id(), "開始日当日", TODAY, TODAY.plusDays(2));
        surveyService.activateCampaign(cStartToday.id());

        // 開始前（period_from=2026-08-19、今日は18日）→ 配信一覧から除外・回答400
        SurveyService.CampaignDto cBeforeStart = surveyService.createCampaign(
                template.id(), "開始前", TODAY.plusDays(1), TODAY.plusDays(3));
        surveyService.activateCampaign(cBeforeStart.id());

        // 終了日=当日（period_to=2026-08-18）→ 当日回答可能
        SurveyService.CampaignDto cEndToday = surveyService.createCampaign(
                template.id(), "終了日当日", TODAY.minusDays(3), TODAY);
        surveyService.activateCampaign(cEndToday.id());

        // 終了日翌日（period_to=2026-08-17、今日は18日）→ 配信一覧から除外・回答400
        SurveyService.CampaignDto cAfterEnd = surveyService.createCampaign(
                template.id(), "終了日翌日", TODAY.minusDays(5), TODAY.minusDays(1));
        surveyService.activateCampaign(cAfterEnd.id());

        authenticate(engineerUser, "要員");

        // 開始日当日・終了日当日はactive一覧に含まれ回答可能
        List<SurveyService.CampaignDto> active = surveyService.myActiveCampaigns(engineerId);
        assertTrue(active.stream().anyMatch(c -> c.id().equals(cStartToday.id())), "開始日当日はactive一覧に含まれる");
        assertTrue(active.stream().anyMatch(c -> c.id().equals(cEndToday.id())), "終了日当日はactive一覧に含まれる");
        assertTrue(active.stream().noneMatch(c -> c.id().equals(cBeforeStart.id())), "開始前はactive一覧に含まれない");
        assertTrue(active.stream().noneMatch(c -> c.id().equals(cAfterEnd.id())), "終了日翌日はactive一覧に含まれない");

        surveyService.submitAnswers(engineerId, cStartToday.id(), true, List.of(
                new SurveyService.AnswerInput("q1", 4, null, "PUBLIC")));
        surveyService.submitAnswers(engineerId, cEndToday.id(), true, List.of(
                new SurveyService.AnswerInput("q1", 5, null, "PUBLIC")));

        // 開始前: myCampaignDetail / submitAnswers とも400
        BusinessException beforeStart = assertThrows(BusinessException.class, () ->
                surveyService.submitAnswers(engineerId, cBeforeStart.id(), true, List.of(
                        new SurveyService.AnswerInput("q1", 3, null, "PUBLIC"))));
        assertEquals(400, beforeStart.getCode(), "開始前は400であるべき");

        // 終了日翌日: 400
        BusinessException afterEnd = assertThrows(BusinessException.class, () ->
                surveyService.submitAnswers(engineerId, cAfterEnd.id(), true, List.of(
                        new SurveyService.AnswerInput("q1", 2, null, "PUBLIC"))));
        assertEquals(400, afterEnd.getCode(), "終了日翌日は400であるべき");
    }

    // ----------------------------------------------------------------
    // ヘルパー
    // ----------------------------------------------------------------

    long insertUser(String role) {
        // H2のsys_user.role ENUMはV32未適用のため'要員'を持たない。DBは'管理者'で保存し、
        // 実際のロールはauthenticate()の認証コンテキストで表現する（既存テストと同じ規約）。
        SysUser user = SysUser.builder()
                .username("b2-clock-" + System.nanoTime())
                .password("x")
                .realName("境界テスト")
                .role("要員".equals(role) ? "管理者" : role)
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user.getId();
    }

    long createEngineer() {
        Engineer engineer = Engineer.builder()
                .fullName("境界要員-" + System.nanoTime())
                .employmentType("正社員")
                .status("Bench")
                .build();
        engineerMapper.insert(engineer);
        jdbcTemplate.update("DELETE FROM t_engineer_accounting_history WHERE engineer_id = ?", engineer.getId());
        return engineer.getId();
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

    void authenticate(long userId, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(String.valueOf(userId), "n/a",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}