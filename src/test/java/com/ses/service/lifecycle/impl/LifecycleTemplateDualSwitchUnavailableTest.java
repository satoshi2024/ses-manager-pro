package com.ses.service.lifecycle.impl;

import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitches;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.LifecycleTemplateTask;
import com.ses.entity.SysUser;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.LifecycleTemplateTaskDepMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.lifecycle.LifecycleDagValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * W3-21b review P3-5: 機能スイッチ（{@link FeatureSwitches}）を取得できない場合、テンプレートの作成・改定・有効化は
 * 二者確認をOFFとして扱い（fail-closed）、DUAL_CONFIRMATIONを含むものを書込み前に400で拒否する（Spring context・DB不要）。
 */
class LifecycleTemplateDualSwitchUnavailableTest {

    private static final long HR_USER = 1L;

    private LifecycleTemplateMapper templateMapper;
    private LifecycleTemplateTaskMapper templateTaskMapper;
    private LifecycleTemplateTaskDepMapper templateTaskDepMapper;
    private SysUserMapper sysUserMapper;
    private LifecycleTemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        templateMapper = mock(LifecycleTemplateMapper.class);
        templateTaskMapper = mock(LifecycleTemplateTaskMapper.class);
        templateTaskDepMapper = mock(LifecycleTemplateTaskDepMapper.class);
        sysUserMapper = mock(SysUserMapper.class);
        SysUser hr = new SysUser();
        hr.setId(HR_USER);
        hr.setRole("HR");
        hr.setStatus(1);
        when(sysUserMapper.selectById(HR_USER)).thenReturn(hr);
        @SuppressWarnings("unchecked")
        ObjectProvider<FeatureSwitches> unavailable = mock(ObjectProvider.class);
        when(unavailable.getIfAvailable()).thenReturn(null);
        service = newService(unavailable);
    }

    @Test
    void 機能スイッチを取得できなければDUALを含む作成は400で書き込まない() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createTemplate(template(task("DUAL_CONFIRMATION")), HR_USER));

        assertDisabled(ex);
        verify(templateMapper, never()).insert(any(LifecycleTemplate.class));
        verify(templateTaskMapper, never()).insert(any(LifecycleTemplateTask.class));
    }

    @Test
    void 機能スイッチのproviderが無くてもDUALを含む作成は400で書き込まない() {
        LifecycleTemplateServiceImpl withoutProvider = newService(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> withoutProvider.createTemplate(template(task("dual_confirmation\u3000")), HR_USER));

        assertDisabled(ex);
        verify(templateMapper, never()).insert(any(LifecycleTemplate.class));
    }

    @Test
    void 機能スイッチを取得できなければDUALを含む改定は400で旧版を閉じない() {
        LifecycleTemplate existing = LifecycleTemplate.builder().templateType("TRANSFER").name("既存").versionNo(1)
                .status("ACTIVE").validFrom(LocalDate.now().minusDays(1)).build();
        existing.setId(5L);
        when(templateMapper.selectById(5L)).thenReturn(existing);
        when(templateMapper.selectMaxVersionNo("TRANSFER")).thenReturn(1);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateTemplate(5L, template(task("DUAL_CONFIRMATION")), HR_USER));

        assertDisabled(ex);
        verify(templateMapper, never()).updateById(any(LifecycleTemplate.class));
        verify(templateMapper, never()).insert(any(LifecycleTemplate.class));
    }

    @Test
    void 機能スイッチを取得できなければDUALを含むテンプレートを有効に戻さない() {
        LifecycleTemplate inactive = LifecycleTemplate.builder().templateType("TRANSFER").name("停止中").status("INACTIVE")
                .build();
        inactive.setId(6L);
        when(templateMapper.selectById(6L)).thenReturn(inactive);
        when(templateTaskMapper.selectByTemplateId(6L)).thenReturn(List.of(
                LifecycleTemplateTask.builder().templateId(6L).taskCode("DUAL").evidenceType("DUAL_CONFIRMATION").build()));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.toggleStatus(6L, "ACTIVE", HR_USER));

        assertDisabled(ex);
        verify(templateMapper, never()).updateById(any(LifecycleTemplate.class));
    }

    private LifecycleTemplateServiceImpl newService(ObjectProvider<FeatureSwitches> switches) {
        return new LifecycleTemplateServiceImpl(templateMapper, templateTaskMapper, templateTaskDepMapper,
                mock(LifecycleDagValidator.class), sysUserMapper, switches);
    }

    private static void assertDisabled(BusinessException ex) {
        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey());
    }

    private static LifecycleTemplateDto template(LifecycleTemplateTaskDto task) {
        return LifecycleTemplateDto.builder().templateType("TRANSFER").name("二者確認スイッチ不明")
                .validFrom(LocalDate.now().minusDays(1)).tasks(List.of(task)).build();
    }

    private static LifecycleTemplateTaskDto task(String evidenceType) {
        return LifecycleTemplateTaskDto.builder().taskCode("DUAL").taskName("二者確認").assigneeRule("ROLE")
                .assigneeRuleValue("HR").evidenceType(evidenceType).build();
    }
}
