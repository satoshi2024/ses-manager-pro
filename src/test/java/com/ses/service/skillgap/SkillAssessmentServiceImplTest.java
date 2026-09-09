package com.ses.service.skillgap;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ses.common.exception.BusinessException;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.EngineerSkill;
import com.ses.entity.EngineerSkillAssessment;
import com.ses.entity.LearningDecisionEvent;
import com.ses.entity.SysUser;
import com.ses.entity.UserOrganization;
import com.ses.mapper.EngineerAccountLinkMapper;
import com.ses.mapper.EngineerSkillAssessmentMapper;
import com.ses.mapper.LearningDecisionEventMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.mapper.UserOrganizationMapper;
import com.ses.service.EngineerSkillService;
import com.ses.service.security.TenantOwnershipResolver;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillAssessmentServiceImplTest {

    @Mock private EngineerSkillAssessmentMapper assessmentMapper;
    @Mock private LearningDecisionEventMapper decisionEventMapper;
    @Mock private EngineerAccountLinkMapper accountLinkMapper;
    @Mock private UserOrganizationMapper userOrganizationMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private EngineerSkillService engineerSkillService;
    @Mock private TenantOwnershipResolver tenantOwnershipResolver;

    @BeforeEach
    void setTenantContext() {
        AccountingTenantContextHolder.setTenantId("default");
    }

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void selfとmanagerはproposalだけで公式projectionを変更しない() {
        stubAccount(501L);
        when(tenantOwnershipResolver.selectEngineer("default", 10L)).thenReturn(engineer(10L));
        when(sysUserMapper.selectByIdAndTenant(700L, "default")).thenReturn(user(700L, "マネージャー"));
        UserOrganization assignment = new UserOrganization();
        assignment.setUserId(501L);
        assignment.setManagerUserId(700L);
        assignment.setPrimaryFlag(1);
        assignment.setValidFrom(LocalDate.of(2026, 1, 1));
        when(userOrganizationMapper.selectManagerAssignmentByTenant(eq("default"), eq(501L), eq(700L), any()))
                .thenReturn(List.of(assignment));
        doAnswer(invocation -> {
            EngineerSkillAssessment assessment = invocation.getArgument(0);
            assessment.setId(80L);
            return 1;
        }).when(assessmentMapper).insert(any(EngineerSkillAssessment.class));

        SkillAssessmentService service = service();
        EngineerSkillAssessment self = service.submitSelf(10L, 1L, "中級", date(), 501L, "本人申告");
        EngineerSkillAssessment manager = service.submitManager(10L, 1L, "上級", date(), 700L, "面談確認");

        assertEquals(EngineerSkillAssessment.TYPE_SELF, self.getAssessmentType());
        assertEquals(EngineerSkillAssessment.TYPE_MANAGER, manager.getAssessmentType());
        assertEquals("PROPOSED", self.getAssessmentState());
        verify(engineerSkillService, never()).replaceSkills(any(), any());
    }

    @Test
    void 本人でないactorとmanagerでないactorはproposalを作れない() {
        stubAccount(501L);
        SkillAssessmentService service = service();

        assertThrows(BusinessException.class,
                () -> service.submitSelf(10L, 1L, "中級", date(), 502L, "他人です"));
        when(tenantOwnershipResolver.selectEngineer("default", 10L)).thenReturn(engineer(10L));
        when(sysUserMapper.selectByIdAndTenant(700L, "default")).thenReturn(user(700L, "営業"));
        assertThrows(BusinessException.class,
                () -> service.submitManager(10L, 1L, "中級", date(), 700L, "営業確認"));
    }

    @Test
    void HR_FINALだけが共通service経由で公式skillを更新しdecision監査を残す() {
        when(tenantOwnershipResolver.selectEngineer("default", 10L)).thenReturn(engineer(10L));
        when(sysUserMapper.selectByIdAndTenant(900L, "default")).thenReturn(user(900L, "HR"));
        EngineerSkill existing = new EngineerSkill();
        existing.setEngineerId(10L);
        existing.setSkillId(1L);
        existing.setProficiency("初級");
        when(engineerSkillService.listForTenant(10L))
                .thenReturn(List.of(existing));
        doAnswer(invocation -> {
            EngineerSkillAssessment assessment = invocation.getArgument(0);
            assessment.setId(81L);
            return 1;
        }).when(assessmentMapper).insert(any(EngineerSkillAssessment.class));

        SkillAssessmentService service = service();
        EngineerSkillAssessment result = service.finalizeByHr(10L, 1L, "上級", date(), 900L, "HR確定");

        assertEquals(EngineerSkillAssessment.TYPE_HR_FINAL, result.getAssessmentType());
        assertEquals("FINAL", result.getAssessmentState());
        ArgumentCaptor<List<EngineerSkill>> skills = ArgumentCaptor.forClass(List.class);
        verify(engineerSkillService).replaceSkills(org.mockito.ArgumentMatchers.eq(10L), skills.capture());
        assertEquals("上級", skills.getValue().get(0).getProficiency());
        ArgumentCaptor<LearningDecisionEvent> event = ArgumentCaptor.forClass(LearningDecisionEvent.class);
        verify(decisionEventMapper).insertEvent(event.capture());
        assertEquals("SKILL_LEVEL", event.getValue().getDecisionDomain());
        assertEquals(900L, event.getValue().getHumanActorUserId());
    }

    @Test
    void AI相当のassessmentTypeや理由なしは人の確定として受け付けない() {
        SkillAssessmentService service = service();
        assertThrows(BusinessException.class,
                () -> service.submitSelf(10L, 1L, "AI", date(), 501L, "候補"));
        assertThrows(BusinessException.class,
                () -> service.submitSelf(10L, 1L, "中級", date(), 501L, ""));
    }

    private SkillAssessmentService service() {
        return new SkillAssessmentServiceImpl(assessmentMapper, decisionEventMapper, accountLinkMapper,
                userOrganizationMapper, sysUserMapper, engineerSkillService,
                Clock.fixed(Instant.parse("2026-08-28T00:00:00Z"), ZoneId.of("Asia/Tokyo")),
                new ObjectMapper().registerModule(new JavaTimeModule()), tenantOwnershipResolver);
    }

    private void stubAccount(Long userId) {
        EngineerAccountLink link = new EngineerAccountLink();
        link.setEngineerId(10L);
        link.setSysUserId(userId);
        when(tenantOwnershipResolver.selectEngineer("default", 10L)).thenReturn(engineer(10L));
        when(accountLinkMapper.selectByEngineerIdAndTenant(10L, "default")).thenReturn(link);
        lenient().when(sysUserMapper.selectByIdAndTenant(userId, "default")).thenReturn(user(userId, "要員"));
    }

    private SysUser user(Long id, String role) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole(role);
        user.setStatus(1);
        return user;
    }

    private com.ses.entity.Engineer engineer(Long id) {
        com.ses.entity.Engineer engineer = new com.ses.entity.Engineer();
        engineer.setId(id);
        engineer.setTenantId("default");
        return engineer;
    }

    private LocalDate date() {
        return LocalDate.of(2026, 8, 28);
    }
}
