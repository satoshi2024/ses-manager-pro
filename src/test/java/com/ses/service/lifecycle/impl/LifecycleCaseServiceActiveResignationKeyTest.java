package com.ses.service.lifecycle.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.LifecycleTemplateTask;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.LifecycleCaseSequenceMapper;
import com.ses.mapper.LifecycleEventMapper;
import com.ses.mapper.LifecycleTaskMapper;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.LifecycleTemplateTaskDepMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerSalesService;
import com.ses.service.lifecycle.LifecycleDagValidator;
import com.ses.service.lifecycle.LifecycleScopeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NF01 N1-1（L7）: 事前確認を並行起票ですり抜けた場合、V187の一意制約違反（uk_lifecycle_case_active_resignation）だけを
 * 事前確認と同じ 400 {@code error.lifecycle.duplicateActiveCase} へ変換し、他の一意制約違反は変換しない。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LifecycleCaseServiceActiveResignationKeyTest {

    private static final String TENANT = "tenant-l7";
    private static final Long APPLICANT_ID = 1L;
    private static final Long ENGINEER_ID = 20L;
    private static final Long TEMPLATE_ID = 30L;

    @Mock private LifecycleCaseMapper caseMapper;
    @Mock private LifecycleCaseSequenceMapper caseSequenceMapper;
    @Mock private LifecycleTaskMapper taskMapper;
    @Mock private LifecycleEventMapper eventMapper;
    @Mock private LifecycleTemplateMapper templateMapper;
    @Mock private LifecycleTemplateTaskMapper templateTaskMapper;
    @Mock private LifecycleTemplateTaskDepMapper templateTaskDepMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private LifecycleDagValidator dagValidator;
    @Mock private LifecycleScopeService scopeService;
    @Mock private EngineerSalesService engineerSalesService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private LifecycleCaseServiceImpl service;

    @Test
    void 事前確認をすり抜けた並行起票の一意制約違反は事前確認と同じ400に変換する() {
        stubCreatePathUntilInsert();
        doThrow(mysqlDuplicate("t_lifecycle_case.uk_lifecycle_case_active_resignation"))
                .when(caseMapper).insert(any(LifecycleCase.class));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createCase(APPLICANT_ID, resignation()));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.duplicateActiveCase", ex.getMessageKey());
        verify(taskMapper, never()).insert(any(com.ses.entity.LifecycleTask.class));
        verify(eventMapper, never()).insert(any(com.ses.entity.LifecycleEvent.class));
    }

    @Test
    void 案件番号など他の一意制約違反は業務上の重複へ変換せずそのまま送出する() {
        stubCreatePathUntilInsert();
        DuplicateKeyException caseNo = mysqlDuplicate("t_lifecycle_case.uk_lifecycle_case_no");
        doThrow(caseNo).when(caseMapper).insert(any(LifecycleCase.class));

        DuplicateKeyException thrown = assertThrows(DuplicateKeyException.class,
                () -> service.createCase(APPLICANT_ID, resignation()));

        assertSame(caseNo, thrown);
    }

    @Test
    void 事前確認で未終結の退社案件が見つかれば一意制約に頼らず400で拒否する() {
        stubCreatePathUntilInsert();
        when(caseMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createCase(APPLICANT_ID, resignation()));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.duplicateActiveCase", ex.getMessageKey());
        verify(caseMapper, never()).insert(any(LifecycleCase.class));
    }

    @Test
    void 一意index名の照合はMySQLとH2の表記と原因chainの深さに依存しない() {
        assertTrue(LifecycleCaseServiceImpl.violatesActiveResignationKey(
                mysqlDuplicate("t_lifecycle_case.uk_lifecycle_case_active_resignation")));
        assertTrue(LifecycleCaseServiceImpl.violatesActiveResignationKey(new DuplicateKeyException("insert failed",
                new RuntimeException("wrapped", new SQLIntegrityConstraintViolationException(
                        "Unique index or primary key violation: \"PUBLIC.UK_LIFECYCLE_CASE_ACTIVE_RESIGNATION ON "
                                + "PUBLIC.T_LIFECYCLE_CASE(TENANT_ID NULLS FIRST, ACTIVE_RESIGNATION_ENGINEER_ID NULLS FIRST)\"")))));
        assertFalse(LifecycleCaseServiceImpl.violatesActiveResignationKey(
                mysqlDuplicate("t_lifecycle_case.uk_lifecycle_case_no")));
        assertFalse(LifecycleCaseServiceImpl.violatesActiveResignationKey(new DuplicateKeyException(null)));
    }

    private void stubCreatePathUntilInsert() {
        SysUser applicant = new SysUser();
        applicant.setId(APPLICANT_ID);
        applicant.setTenantId(TENANT);
        applicant.setRole("HR");
        applicant.setStatus(1);
        when(sysUserMapper.selectById(APPLICANT_ID)).thenReturn(applicant);
        when(scopeService.requireTenant(applicant)).thenReturn(TENANT);

        Engineer engineer = new Engineer();
        engineer.setId(ENGINEER_ID);
        engineer.setTenantId(TENANT);
        engineer.setFullName("退社予定者");
        engineer.setEmploymentType("正社員");
        when(engineerMapper.selectOne(any())).thenReturn(engineer);
        when(caseMapper.selectCount(any())).thenReturn(0L);

        LifecycleTemplate template = LifecycleTemplate.builder().templateType("RESIGNATION").name("退社")
                .versionNo(1).status("ACTIVE").validFrom(LocalDate.now().minusDays(1)).build();
        template.setId(TEMPLATE_ID);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(template);
        LifecycleTemplateTask task = LifecycleTemplateTask.builder().templateId(TEMPLATE_ID)
                .taskCode("RESIGN_ASSET_RETURN").taskName("貸与資産返却").relativeDueDays(0)
                .assigneeRule("ROLE").assigneeRuleValue("HR").isMandatory(1).isBlocking(1)
                .evidenceType("NONE").isEngineerVisible(0).sortOrder(10).build();
        when(templateTaskMapper.selectByTemplateId(TEMPLATE_ID)).thenReturn(List.of(task));
        when(templateTaskDepMapper.selectByTemplateId(TEMPLATE_ID)).thenReturn(List.of());

        when(caseSequenceMapper.selectExistingCaseNos(eq(TENANT), any(), any())).thenReturn(List.of());
        when(caseSequenceMapper.incrementIfAvailable(eq(TENANT), anyString(), anyLong())).thenReturn(1);
        when(caseSequenceMapper.selectLastNumber(eq(TENANT), anyString())).thenReturn(1L);
        when(engineerSalesService.listActive(ENGINEER_ID)).thenReturn(List.of());
    }

    private CreateLifecycleCaseCommand resignation() {
        return CreateLifecycleCaseCommand.builder()
                .engineerId(ENGINEER_ID)
                .lifecycleType("RESIGNATION")
                .templateId(TEMPLATE_ID)
                .anchorDate(LocalDate.of(2026, 12, 31))
                .build();
    }

    private static DuplicateKeyException mysqlDuplicate(String key) {
        return new DuplicateKeyException("### Error updating database.",
                new SQLIntegrityConstraintViolationException("Duplicate entry 'tenant-l7-20' for key '" + key + "'"));
    }
}
