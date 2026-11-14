package com.ses.service.lifecycle;

import com.ses.common.exception.BusinessException;
import com.ses.dto.lifecycle.CreateLifecycleCaseCommand;
import com.ses.dto.lifecycle.LifecycleCaseDto;
import com.ses.dto.lifecycle.LifecycleTemplateDto;
import com.ses.dto.lifecycle.LifecycleTemplateTaskDto;
import com.ses.entity.Engineer;
import com.ses.entity.LifecycleCase;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NF01 N1-1（L7 / V187）: 同一tenant・同一要員の未終結退社案件はDBの一意制約で1件に限られる（H2 replay）。
 * 並行起票で事前確認を両方が通過した場合の400変換は LifecycleCaseServiceActiveResignationKeyTest、
 * 実MySQLでの2 session競合は LifecycleResignationUniquenessMySqlTest が確認する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class LifecycleResignationUniquenessTest {

    private static final AtomicLong SEQUENCE = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired private LifecycleCaseMapper caseMapper;
    @Autowired private EngineerMapper engineerMapper;
    @Autowired private SysUserMapper sysUserMapper;
    @Autowired private LifecycleCaseService caseService;
    @Autowired private LifecycleTemplateService templateService;

    private SysUser hrUser;
    private Engineer engineer;

    @BeforeEach
    void setUp() {
        long seq = SEQUENCE.incrementAndGet();
        hrUser = SysUser.builder().tenantId("default").username("lc_l7_hr_" + seq).password("pass")
                .realName("L7人事").role("HR").status(1).build();
        sysUserMapper.insert(hrUser);
        engineer = Engineer.builder().tenantId("default").fullName("L7要員-" + seq)
                .status("稼動中").employmentType("正社員").build();
        engineerMapper.insert(engineer);
    }

    @Test
    @DisplayName("同一tenant・同一要員の未終結退社案件は直接登録しても2件目がDBの一意制約で拒否される")
    void 同一要員の未終結退社案件は直接登録しても2件目が一意制約で拒否される() throws Exception {
        caseMapper.insert(resignationCase("default", "ACTIVE", "RESIGNATION", 0));

        DuplicateKeyException duplicate = assertThrows(DuplicateKeyException.class,
                () -> caseMapper.insert(resignationCase("default", "ON_HOLD", "RESIGNATION", 0)));
        assertTrue(violatesActiveResignationKey(duplicate),
                "H2の一意制約違反もindex名で判定でき、400へ変換できること: " + duplicate.getMessage());
        // DRAFTや想定外の状態も未終結として扱う（fail-closed）
        assertThrows(DuplicateKeyException.class,
                () -> caseMapper.insert(resignationCase("default", "DRAFT", "RESIGNATION", 0)));
    }

    @Test
    @DisplayName("終結済み・論理削除済み・退社以外・他tenantの案件は一意制約の対象外で、終結後は再び起票できる")
    void 終結済みと論理削除と他種別と他tenantは対象外で終結後は再び登録できる() {
        LifecycleCase active = resignationCase("default", "ACTIVE", "RESIGNATION", 0);
        caseMapper.insert(active);

        assertDoesNotThrow(() -> caseMapper.insert(resignationCase("default", "COMPLETED", "RESIGNATION", 0)));
        assertDoesNotThrow(() -> caseMapper.insert(resignationCase("default", "CANCELLED", "RESIGNATION", 0)));
        assertDoesNotThrow(() -> caseMapper.insert(resignationCase("default", "ACTIVE", "RESIGNATION", 1)));
        assertDoesNotThrow(() -> caseMapper.insert(resignationCase("default", "ACTIVE", "JOIN", 0)));
        assertDoesNotThrow(() -> caseMapper.insert(resignationCase("tenant-l7-other", "ACTIVE", "RESIGNATION", 0)));

        active.setStatus("CANCELLED");
        assertEquals(1, caseMapper.updateById(active));
        assertDoesNotThrow(() -> caseMapper.insert(resignationCase("default", "ACTIVE", "RESIGNATION", 0)),
                "終結した案件は一意判定から外れ、同じ要員の退社案件を新たに登録できること");
    }

    @Test
    @DisplayName("起票APIは未終結の退社案件がある要員を400で拒否し、中止後は同じ要員で再び起票できる")
    void 起票は未終結の退社案件がある要員を400で拒否し中止後は再び起票できる() {
        LifecycleTemplateDto template = templateService.createTemplate(LifecycleTemplateDto.builder()
                .templateType("RESIGNATION")
                .name("L7退社フロー-" + SEQUENCE.incrementAndGet())
                .validFrom(LocalDate.now().minusDays(1))
                .tasks(List.of(LifecycleTemplateTaskDto.builder()
                        .taskCode("RESIGN_ASSET_RETURN")
                        .taskName("貸与資産返却")
                        .assigneeRule("ROLE")
                        .assigneeRuleValue("HR")
                        .isMandatory(1)
                        .isBlocking(1)
                        .evidenceType("NONE")
                        .build()))
                .build(), hrUser.getId());
        LifecycleCaseDto first = caseService.createCase(hrUser.getId(), command(template.getId()));

        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> caseService.createCase(hrUser.getId(), command(template.getId())));
        assertEquals(400, duplicate.getCode());
        assertEquals("error.lifecycle.duplicateActiveCase", duplicate.getMessageKey());

        caseService.cancelCase(first.getId(), hrUser.getId(), "L7再起票の検証");
        LifecycleCaseDto second = caseService.createCase(hrUser.getId(), command(template.getId()));
        assertNotEquals(first.getId(), second.getId());
        assertEquals("ACTIVE", second.getStatus());
    }

    private CreateLifecycleCaseCommand command(Long templateId) {
        return CreateLifecycleCaseCommand.builder()
                .engineerId(engineer.getId())
                .lifecycleType("RESIGNATION")
                .templateId(templateId)
                .anchorDate(LocalDate.now().plusDays(30))
                .build();
    }

    private LifecycleCase resignationCase(String tenantId, String status, String type, int deletedFlag) {
        long seq = SEQUENCE.incrementAndGet();
        LifecycleCase lcCase = LifecycleCase.builder()
                .tenantId(tenantId)
                .caseNo("LC-L7-" + seq)
                .lifecycleType(type)
                .engineerId(engineer.getId())
                .templateId(1L)
                .templateVersion(1)
                .anchorDate(LocalDate.now())
                .status(status)
                .title("L7一意制約検証")
                .applicantUserId(hrUser.getId())
                .engineerSnapshotJson("{}")
                .version(0)
                .build();
        lcCase.setDeletedFlag(deletedFlag);
        return lcCase;
    }

    /** 実装のpackage-private判定を、H2が実際に返す例外で確認する。 */
    private static boolean violatesActiveResignationKey(Throwable failure) throws Exception {
        Method method = com.ses.service.lifecycle.impl.LifecycleCaseServiceImpl.class
                .getDeclaredMethod("violatesActiveResignationKey", Throwable.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, failure);
    }
}
