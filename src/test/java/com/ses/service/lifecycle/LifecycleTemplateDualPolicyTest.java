package com.ses.service.lifecycle;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.config.feature.FeatureSwitchProperties;
import com.ses.config.feature.FeatureSwitches;
import com.ses.config.feature.IsolatedFeatureSwitches;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.LifecycleTemplate;
import com.ses.entity.LifecycleTemplateTask;
import com.ses.entity.SysUser;
import com.ses.mapper.LifecycleTemplateMapper;
import com.ses.mapper.LifecycleTemplateTaskMapper;
import com.ses.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * U13（NF01細粒度epoch計画 §5.11・§6.2、W3-21b）: DUAL_CONFIRMATIONタスクを含むテンプレートの新規作成・改定は、
 * 子スイッチ {@code nf01-dual-confirmation} の実効値（親NF-01との論理積）がONのときだけ受け付ける。
 * OFFでは400で拒否し、テンプレート・タスク定義を1件も書き込まない（W3-02 N1-2 の拒否をスイッチ連動に変更）。
 * ONでは表記揺れを正規の {@code DUAL_CONFIRMATION} にそろえて保存する（完了経路の完全一致判定を迂回させない）。
 * 証跡種別は許可値一覧で検査し、Unicodeの表記揺れ（NBSP・ゼロ幅文字・全角文字）や一覧外の値は400で1件も書かない
 * （review P2-1）。子スイッチOFFの間はDUALを含むテンプレートを有効に戻せない（review P3-1）。
 *
 * <p>test profileは子スイッチON。OFFの試験は試験専用のスイッチ設定を切り替える（W3-21b review P3-4）。
 * 共有contextの {@link FeatureSwitchProperties} は書き換えず、{@code @MockBean FeatureSwitches} の判定を
 * 試験ごとに作る専用の設定の実物へ委ねる（{@link IsolatedFeatureSwitches}。親子の論理積は実物が判定する）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleTemplateDualPolicyTest {

    @Autowired private LifecycleTemplateService templateService;
    @Autowired private LifecycleTemplateMapper templateMapper;
    @Autowired private LifecycleTemplateTaskMapper templateTaskMapper;
    @Autowired private SysUserMapper sysUserMapper;
    /** test profileの値を写す元（読むだけで変えない）。 */
    @Autowired private FeatureSwitchProperties featureSwitchProperties;
    @MockBean private FeatureSwitches featureSwitches;
    /** 実物の FeatureSwitches と同じ実行時条件（context の bean。無ければ null）。 */
    @Autowired(required = false) private java.util.List<com.ses.config.feature.FeatureRuntimeCondition> runtimeConditions;

    private SysUser hrUser;
    /** 試験専用のスイッチ設定（試験ごとに作り直す）。 */
    private FeatureSwitchProperties switchProperties;

    @BeforeEach
    void setUp() {
        switchProperties = IsolatedFeatureSwitches.isolate(featureSwitches, featureSwitchProperties,
                runtimeConditions);
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
        for (String variant : List.of("dual_confirmation", " DUAL_CONFIRMATION ", "Dual_Confirmation",
                "\u3000DUAL_CONFIRMATION\u3000", "\tdual_confirmation\n")) {
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
        for (String variant : List.of("dual_confirmation", " DUAL_CONFIRMATION ", "Dual_Confirmation",
                "\u3000DUAL_CONFIRMATION\u3000", "\tdual_confirmation\n")) {
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

    // ===================================================================== 証跡種別の許可値一覧（review P2-1）

    /** 見た目は既存の種別と同じでも、完全一致の判定をすり抜ける値・一覧外の値。 */
    static Stream<String> invalidEvidenceTypes() {
        return Stream.of(
                "DUAL_CONFIRMATION\u00A0",
                "\u00A0DOCUMENT_LINK",
                "DUAL_CONFIRMATION\u200B",
                "\u200BDOCUMENT_LINK",
                "DUAL_\u200BCONFIRMATION",
                "\uFEFFDUAL_CONFIRMATION",
                "\uFF24\uFF35\uFF21\uFF2C\uFF3F\uFF23\uFF2F\uFF2E\uFF26\uFF29\uFF32\uFF2D\uFF21\uFF34\uFF29\uFF2F\uFF2E",
                "\uFF24\uFF2F\uFF23\uFF35\uFF2D\uFF25\uFF2E\uFF34\uFF3F\uFF2C\uFF29\uFF2E\uFF2B",
                "DOCUMENT LINK",
                "unknown",
                "",
                "   ",
                "DUAL_CONFIRMATION_WITH_A_VERY_LONG_SUFFIX");
    }

    @ParameterizedTest
    @MethodSource("invalidEvidenceTypes")
    void 許可値一覧に無い証跡種別とASCII以外を含む証跡種別は子スイッチONでも400で1件も書かない(String evidenceType) {
        switches(true, true);
        String name = "二者確認方針不正-" + System.nanoTime();

        BusinessException ex = assertThrows(BusinessException.class, () -> templateService.createTemplate(
                template(name, task("POLICY_OK", "NONE"), task("POLICY_BAD", evidenceType)), hrUser.getId()));

        assertEquals(400, ex.getCode(), evidenceType);
        assertEquals("error.lifecycle.invalidEvidenceType", ex.getMessageKey(), evidenceType);
        assertEquals(0L, countByName(name), evidenceType);
    }

    @Test
    void 許可値一覧に無い証跡種別を含む改定は400で旧版も新版も変わらない() {
        switches(true, true);
        LifecycleTemplateDto existing = templateService.createTemplate(
                template("二者確認方針不正改定-" + System.nanoTime(), task("POLICY_BASE", "DOCUMENT_LINK")),
                hrUser.getId());
        Long versionsBefore = countByType("TRANSFER");

        BusinessException ex = assertThrows(BusinessException.class, () -> templateService.updateTemplate(
                existing.getId(), template(existing.getName(), task("POLICY_BASE", "DOCUMENT_LINK\u200B")),
                hrUser.getId()));

        assertEquals("error.lifecycle.invalidEvidenceType", ex.getMessageKey());
        assertNull(templateMapper.selectById(existing.getId()).getValidTo());
        assertEquals(versionsBefore, countByType("TRANSFER"));
    }

    /** 前後のUnicode空白（U+3000等）と大文字小文字は正規の表記にそろえる。DOCUMENT_LINKも同じ（W3-02以来の揺れ）。 */
    static Stream<Arguments> normalizedEvidenceTypes() {
        return Stream.of(
                Arguments.of("document_link", "DOCUMENT_LINK"),
                Arguments.of("\u3000Document_Link\u3000", "DOCUMENT_LINK"),
                Arguments.of(" DOCUMENT_LINK\t", "DOCUMENT_LINK"),
                Arguments.of("\u3000dual_confirmation", "DUAL_CONFIRMATION"),
                Arguments.of("comment", "COMMENT"),
                Arguments.of("self_declaration", "SELF_DECLARATION"),
                Arguments.of("System_Check", "SYSTEM_CHECK"),
                Arguments.of("NONE", "NONE"),
                Arguments.of(null, "NONE"));
    }

    @ParameterizedTest
    @MethodSource("normalizedEvidenceTypes")
    void 前後のUnicode空白と大文字小文字は正規の表記にそろえて保存する(String evidenceType, String stored) {
        switches(true, true);

        LifecycleTemplateDto created = templateService.createTemplate(
                template("二者確認方針正規化-" + System.nanoTime(), task("POLICY_NORMALIZED", evidenceType)),
                hrUser.getId());

        assertEquals(stored, storedEvidenceTypes(created.getId()).get("POLICY_NORMALIZED"), String.valueOf(evidenceType));
    }

    // ===================================================================== 有効化（review P3-1）

    @Test
    void 子スイッチOFFの間はDUALを含むテンプレートを無効にできるが有効に戻せない() {
        switches(true, true);
        LifecycleTemplateDto dual = templateService.createTemplate(
                template("二者確認方針有効化-" + System.nanoTime(), task("POLICY_DUAL", "DUAL_CONFIRMATION")),
                hrUser.getId());
        switches(true, false);

        templateService.toggleStatus(dual.getId(), "INACTIVE", hrUser.getId());
        BusinessException ex = assertThrows(BusinessException.class,
                () -> templateService.toggleStatus(dual.getId(), "ACTIVE", hrUser.getId()));

        assertEquals(400, ex.getCode());
        assertEquals("error.lifecycle.dualConfirmationDisabled", ex.getMessageKey());
        assertEquals("INACTIVE", templateMapper.selectById(dual.getId()).getStatus());
    }

    @Test
    void 子スイッチONならDUALを含むテンプレートを有効に戻せOFFでもDUALを含まないテンプレートは有効に戻せる() {
        switches(true, true);
        LifecycleTemplateDto dual = templateService.createTemplate(
                template("二者確認方針再有効ON-" + System.nanoTime(), task("POLICY_DUAL", "DUAL_CONFIRMATION")),
                hrUser.getId());
        templateService.toggleStatus(dual.getId(), "INACTIVE", hrUser.getId());
        templateService.toggleStatus(dual.getId(), "ACTIVE", hrUser.getId());
        assertEquals("ACTIVE", templateMapper.selectById(dual.getId()).getStatus());

        switches(true, false);
        LifecycleTemplateDto plain = templateService.createTemplate(
                template("二者確認方針再有効OFF-" + System.nanoTime(), task("POLICY_DOC", "DOCUMENT_LINK")),
                hrUser.getId());
        templateService.toggleStatus(plain.getId(), "INACTIVE", hrUser.getId());
        templateService.toggleStatus(plain.getId(), "ACTIVE", hrUser.getId());
        assertEquals("ACTIVE", templateMapper.selectById(plain.getId()).getStatus());
    }

    // ===================================================================== helpers

    private void switches(boolean nf01, boolean dualConfirmation) {
        switchProperties.getNf01().setEnabled(nf01);
        switchProperties.getNf01DualConfirmation().setEnabled(dualConfirmation);
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
