package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.LifecycleTemplateTask;
import com.ses.entity.SysUser;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.mapper.SysUserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * U13（NF01細粒度epoch計画 §5.11・§6.2、W3-21b）: DUAL_CONFIRMATIONタスクを含むテンプレートの新規作成・改定は、
 * 子スイッチ {@code nf01-dual-confirmation} の実効値（親NF-01との論理積）がONのときだけ受け付ける。
 * OFFでは400で拒否し、テンプレート・タスク定義を1件も書き込まない（W3-02 N1-2 の拒否をスイッチ連動に変更）。
 * ONでは表記揺れを正規の {@code DUAL_CONFIRMATION} にそろえて保存する（完了経路の完全一致判定を迂回させない）。
 *
 * <p>test profileは子スイッチON。OFFの試験はスイッチ設定を試験の中で切り替え、試験後に元へ戻す
 * （設定値は {@code FeatureSwitches} が毎回読むため再起動は要らない。JUnitの並列実行は無効）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleTemplateDualPolicyTest {

    @Autowired private LifecycleTemplateService templateService;
    @Autowired private LifecycleTemplateMapper templateMapper;
    @Autowired private LifecycleTemplateTaskMapper templateTaskMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private FeatureSwitchProperties featureSwitchProperties;

    private SysUser hrUser;
    private boolean nf01Before;
    private boolean dualBefore;

    @BeforeEach
    void setUp() {
        nf01Before = featureSwitchProperties.getNf01().isEnabled();
        dualBefore = featureSwitchProperties.getNf01DualConfirmation().isEnabled();
        hrUser = SysUser.builder()
                .tenantId("default")
                .username("lc_dual_policy_hr_" + System.nanoTime())
                .password("pass")
                .realName("二者確認方針人事")
                .role("HR")
                .status(1)
                .build();
        sysUserMapper.insert(hrUser);
    }

    @AfterEach
    void restoreSwitches() {
        featureSwitchProperties.getNf01().setEnabled(nf01Before);
        featureSwitchProperties.getNf01DualConfirmation().setEnabled(dualBefore);
    }

    // ===================================================================== 子スイッチOFF

    @Test
    @DisplayName("子スイッチOFFではDUAL_CONFIRMATIONを含む新規作成は400で拒否され、テンプレートが残らない")
    void 子スイッチOFFではDUAL_CONFIRMATIONを含む新規作成は400で拒否されテンプレートが残らない() {
        switches(true, false);
        String name = "二者確認方針作成OFF-" + System.nanoTime();

        BusinessException ex = assertThrows(BusinessException.class, () -> templateService.createTemplate(
                template(name, task("POLICY_OK", "NONE"), task("POLICY_DUAL", "DUAL_CONFIRMATION")), hrUser.getId()));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey());
        assertEquals(0L, countByName(name));
    }

    @Test
    @DisplayName("子スイッチOFFでは大文字小文字・前後空白が違うDUAL_CONFIRMATIONも完全一致判定をすり抜けないよう拒否する")
    void 子スイッチOFFでは表記揺れのDUAL_CONFIRMATIONも拒否する() {
        switches(true, false);
        for (String variant : List.of("dual_confirmation", " DUAL_CONFIRMATION ", "Dual_Confirmation")) {
            String name = "二者確認方針表記OFF-" + System.nanoTime();
            BusinessException ex = assertThrows(BusinessException.class, () -> templateService.createTemplate(
                    template(name, task("POLICY_VARIANT", variant)), hrUser.getId()));
            assertEquals(400, ex.getCode(), variant);
            assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey(), variant);
            assertEquals(0L, countByName(name), variant);
        }
    }

    @Test
    @DisplayName("子スイッチOFFではDUAL_CONFIRMATIONを含む改定は400で拒否され、旧版の有効期限も新版も変わらない")
    void 子スイッチOFFではDUAL_CONFIRMATIONを含む改定は400で拒否され旧版も新版も変わらない() {
        switches(true, false);
        LifecycleTemplateDto existing = templateService.createTemplate(
                template("二者確認方針改定OFF-" + System.nanoTime(), task("POLICY_BASE", "DOCUMENT_LINK")),
                hrUser.getId());
        Long versionsBefore = countByType("TRANSFER");

        BusinessException ex = assertThrows(BusinessException.class, () -> templateService.updateTemplate(
                existing.getId(),
                template(existing.getName(), task("POLICY_BASE", "DOCUMENT_LINK"), task("POLICY_DUAL", "DUAL_CONFIRMATION")),
                hrUser.getId()));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey());
        assertNull(templateMapper.selectById(existing.getId()).getValidTo(), "旧版の有効期限を閉じないこと");
        assertEquals(versionsBefore, countByType("TRANSFER"), "新版を作らないこと");
        assertEquals(1, templateTaskMapper.selectByTemplateId(existing.getId()).size());
    }

    @Test
    @DisplayName("子スイッチOFFでもDUAL_CONFIRMATIONを含まない作成・改定は従来どおり受け付ける（DUALを外す改定を含む）")
    void 子スイッチOFFでもDUAL_CONFIRMATIONを含まない作成と改定は受け付ける() {
        switches(true, false);
        LifecycleTemplateDto created = templateService.createTemplate(
                template("二者確認方針通常-" + System.nanoTime(), task("POLICY_SELF", "SELF_DECLARATION")), hrUser.getId());
        LifecycleTemplateDto revised = templateService.updateTemplate(created.getId(),
                template(created.getName(), task("POLICY_SELF", "SELF_DECLARATION"), task("POLICY_DOC", "DOCUMENT_LINK")),
                hrUser.getId());

        assertEquals(created.getVersionNo() + 1, revised.getVersionNo());
        assertEquals(2, revised.getTasks().size());
    }

    @Test
    @DisplayName("親NF-01がOFFなら子スイッチの設定値がONでもDUAL_CONFIRMATIONを含む作成は400")
    void 親NF01がOFFなら子スイッチの設定値がONでもDUAL_CONFIRMATIONを含む作成は400() {
        switches(false, true);
        String name = "二者確認方針親OFF-" + System.nanoTime();

        BusinessException ex = assertThrows(BusinessException.class, () -> templateService.createTemplate(
                template(name, task("POLICY_DUAL", "DUAL_CONFIRMATION")), hrUser.getId()));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey());
        assertEquals(0L, countByName(name));
    }

    // ===================================================================== 子スイッチON

    @Test
    @DisplayName("子スイッチONならDUAL_CONFIRMATIONを含む新規作成を受け付け、証跡種別をそのまま保存する")
    void 子スイッチONならDUAL_CONFIRMATIONを含む新規作成を受け付ける() {
        switches(true, true);

        LifecycleTemplateDto created = templateService.createTemplate(
                template("二者確認方針作成ON-" + System.nanoTime(), task("POLICY_OK", "NONE"),
                        task("POLICY_DUAL", "DUAL_CONFIRMATION")), hrUser.getId());

        Map<String, String> stored = storedEvidenceTypes(created.getId());
        assertEquals("DUAL_CONFIRMATION", stored.get("POLICY_DUAL"));
        assertEquals("NONE", stored.get("POLICY_OK"));
    }

    @Test
    @DisplayName("子スイッチONでは表記揺れのDUAL_CONFIRMATIONを正規の表記にそろえて保存する（通常完了の409を迂回させない）")
    void 子スイッチONでは表記揺れのDUAL_CONFIRMATIONを正規の表記で保存する() {
        switches(true, true);
        for (String variant : List.of("dual_confirmation", " DUAL_CONFIRMATION ", "Dual_Confirmation")) {
            LifecycleTemplateDto created = templateService.createTemplate(
                    template("二者確認方針表記ON-" + System.nanoTime(), task("POLICY_VARIANT", variant)),
                    hrUser.getId());
            assertEquals("DUAL_CONFIRMATION", storedEvidenceTypes(created.getId()).get("POLICY_VARIANT"), variant);
        }
    }

    @Test
    @DisplayName("子スイッチONならDUAL_CONFIRMATIONを加える改定を受け付けて新版を作る")
    void 子スイッチONならDUAL_CONFIRMATIONを加える改定を受け付ける() {
        switches(true, true);
        LifecycleTemplateDto existing = templateService.createTemplate(
                template("二者確認方針改定ON-" + System.nanoTime(), task("POLICY_BASE", "DOCUMENT_LINK")),
                hrUser.getId());

        LifecycleTemplateDto revised = templateService.updateTemplate(existing.getId(),
                template(existing.getName(), task("POLICY_BASE", "DOCUMENT_LINK"), task("POLICY_DUAL", "DUAL_CONFIRMATION")),
                hrUser.getId());

        assertEquals(existing.getVersionNo() + 1, revised.getVersionNo());
        assertEquals("DUAL_CONFIRMATION", storedEvidenceTypes(revised.getId()).get("POLICY_DUAL"));
    }

    // ===================================================================== helpers

    private void switches(boolean nf01, boolean dualConfirmation) {
        featureSwitchProperties.getNf01().setEnabled(nf01);
        featureSwitchProperties.getNf01DualConfirmation().setEnabled(dualConfirmation);
    }

    private Map<String, String> storedEvidenceTypes(Long templateId) {
        return templateTaskMapper.selectByTemplateId(templateId).stream()
                .collect(Collectors.toMap(LifecycleTemplateTask::getTaskCode, LifecycleTemplateTask::getEvidenceType));
    }

    private Long countByName(String name) {
        return templateMapper.selectCount(new LambdaQueryWrapper<LifecycleTemplate>().eq(LifecycleTemplate::getName, name));
    }

    private Long countByType(String templateType) {
        return templateMapper.selectCount(new LambdaQueryWrapper<LifecycleTemplate>()
                .eq(LifecycleTemplate::getTemplateType, templateType));
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
