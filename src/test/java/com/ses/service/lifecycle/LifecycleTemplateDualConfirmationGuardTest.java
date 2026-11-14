package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.SysUser;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * NF01 N1-2（決定 B-NF01-3）: 確認機能（CONFIRM）の公開前は、DUAL_CONFIRMATIONタスクを含むテンプレートの
 * 新規作成・改定を400で拒否し、テンプレート・タスク定義を1件も書き込まない。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleTemplateDualConfirmationGuardTest {

    @Autowired private LifecycleTemplateService templateService;
    @Autowired private LifecycleTemplateMapper templateMapper;
    @Autowired private LifecycleTemplateTaskMapper templateTaskMapper;
    @Autowired private SysUserMapper sysUserMapper;

    private SysUser hrUser;

    @BeforeEach
    void setUp() {
        hrUser = SysUser.builder()
                .tenantId("default")
                .username("lc_dual_guard_hr_" + System.nanoTime())
                .password("pass")
                .realName("二者確認ガード人事")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);
    }

    @Test
    @DisplayName("DUAL_CONFIRMATIONを含む新規作成は400で拒否され、テンプレートが残らない")
    void DUAL_CONFIRMATIONを含む新規作成は400で拒否されテンプレートが残らない() {
        String name = "二者確認ガード作成-" + System.nanoTime();

        BusinessException ex = assertThrows(BusinessException.class, () -> templateService.createTemplate(
                template(name, task("GUARD_OK", "NONE"), task("GUARD_DUAL", "DUAL_CONFIRMATION")), hrUser.getId()));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey());
        assertEquals(0L, templateMapper.selectCount(new LambdaQueryWrapper<LifecycleTemplate>()
                .eq(LifecycleTemplate::getName, name)));
    }

    @Test
    @DisplayName("大文字小文字・前後空白が違うDUAL_CONFIRMATIONも完全一致判定をすり抜けないよう拒否する")
    void 表記揺れのDUAL_CONFIRMATIONも拒否する() {
        for (String variant : List.of("dual_confirmation", " DUAL_CONFIRMATION ", "Dual_Confirmation")) {
            BusinessException ex = assertThrows(BusinessException.class, () -> templateService.createTemplate(
                    template("二者確認ガード表記-" + System.nanoTime(), task("GUARD_VARIANT", variant)),
                    hrUser.getId()));
            assertEquals(400, ex.getCode(), variant);
            assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey(), variant);
        }
    }

    @Test
    @DisplayName("DUAL_CONFIRMATIONを含む改定は400で拒否され、旧版の有効期限も新版も変わらない")
    void DUAL_CONFIRMATIONを含む改定は400で拒否され旧版も新版も変わらない() {
        LifecycleTemplateDto existing = templateService.createTemplate(
                template("二者確認ガード改定-" + System.nanoTime(), task("GUARD_BASE", "DOCUMENT_LINK")), hrUser.getId());
        Long versionsBefore = templateMapper.selectCount(new LambdaQueryWrapper<LifecycleTemplate>()
                .eq(LifecycleTemplate::getTemplateType, "TRANSFER"));

        BusinessException ex = assertThrows(BusinessException.class, () -> templateService.updateTemplate(
                existing.getId(),
                template(existing.getName(), task("GUARD_BASE", "DOCUMENT_LINK"), task("GUARD_DUAL", "DUAL_CONFIRMATION")),
                hrUser.getId()));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey());
        assertNull(templateMapper.selectById(existing.getId()).getValidTo(), "旧版の有効期限を閉じないこと");
        assertEquals(versionsBefore, templateMapper.selectCount(new LambdaQueryWrapper<LifecycleTemplate>()
                .eq(LifecycleTemplate::getTemplateType, "TRANSFER")), "新版を作らないこと");
        assertEquals(1, templateTaskMapper.selectByTemplateId(existing.getId()).size());
    }

    @Test
    @DisplayName("DUAL_CONFIRMATIONを含まない作成・改定は従来どおり受け付ける")
    void DUAL_CONFIRMATIONを含まない作成と改定は受け付ける() {
        LifecycleTemplateDto created = templateService.createTemplate(
                template("二者確認ガード通常-" + System.nanoTime(), task("GUARD_SELF", "SELF_DECLARATION")), hrUser.getId());
        LifecycleTemplateDto revised = templateService.updateTemplate(created.getId(),
                template(created.getName(), task("GUARD_SELF", "SELF_DECLARATION"), task("GUARD_DOC", "DOCUMENT_LINK")),
                hrUser.getId());

        assertEquals(created.getVersionNo() + 1, revised.getVersionNo());
        assertEquals(2, revised.getTasks().size());
    }

    private LifecycleTemplateDto template(String name, LifecycleTemplateTaskDto... tasks) {
        return LifecycleTemplateDto.builder()
                .templateType("TRANSFER")
                .name(name)
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(tasks))
                .build();
    }

    private LifecycleTemplateTaskDto task(String code, String evidenceType) {
        return LifecycleTemplateTaskDto.builder()
                .taskCode(code)
                .taskName(code)
                .assigneeRule("ROLE")
                .assigneeRuleValue("HR")
                .isMandatory(1)
                .isBlocking(1)
                .evidenceType(evidenceType)
                .build();
    }
}
