package com.ses.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ses.common.exception.BusinessException;
import com.ses.dto.engineersales.EngineerSalesDto;
import com.ses.entity.EngineerSales;
import com.ses.entity.SysUser;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerSalesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 要員担当営業サービスのビジネスルール検証（H2）
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
public class EngineerSalesServiceImplTest {

    @Autowired
    private EngineerSalesService engineerSalesService;

    @Autowired
    private SysUserMapper sysUserMapper;

    private Long salesUser1Id;
    private Long salesUser2Id;
    private Long hrUserId;
    private Long disabledSalesId;
    private static final Long ENGINEER_ID = 1L;

    @BeforeEach
    void setUp() {
        salesUser1Id = insertUser("sales1", "営業 一郎", "営業", 1);
        salesUser2Id = insertUser("sales2", "営業 二郎", "営業", 1);
        hrUserId = insertUser("hr1", "人事 花子", "HR", 1);
        disabledSalesId = insertUser("sales3", "営業 三郎", "営業", 0);
    }

    private Long insertUser(String username, String realName, String role, int status) {
        SysUser u = new SysUser();
        u.setUsername(username);
        u.setPassword("pass");
        u.setRealName(realName);
        u.setRole(role);
        u.setStatus(status);
        sysUserMapper.insert(u);
        return u.getId();
    }

    @Test
    void 最初の割当は自動的に主担当になる() {
        engineerSalesService.assign(ENGINEER_ID, salesUser1Id, false, null);

        List<EngineerSalesDto> actives = engineerSalesService.listActive(ENGINEER_ID);
        assertEquals(1, actives.size());
        assertEquals(1, actives.get(0).getPrimaryFlag());
        assertEquals(salesUser1Id, actives.get(0).getSalesUserId());
    }

    @Test
    void 主担当指定の割当で既存主担当が降格する() {
        engineerSalesService.assign(ENGINEER_ID, salesUser1Id, true, null);
        engineerSalesService.assign(ENGINEER_ID, salesUser2Id, true, null);

        List<EngineerSalesDto> actives = engineerSalesService.listActive(ENGINEER_ID);
        assertEquals(2, actives.size());
        long primaryCount = actives.stream().filter(a -> a.getPrimaryFlag() == 1).count();
        assertEquals(1, primaryCount);
        assertEquals(salesUser2Id, engineerSalesService.findPrimarySalesUserId(ENGINEER_ID));
    }

    @Test
    void 同一営業の重複現任割当は拒否される() {
        engineerSalesService.assign(ENGINEER_ID, salesUser1Id, false, null);
        assertThrows(BusinessException.class,
                () -> engineerSalesService.assign(ENGINEER_ID, salesUser1Id, false, null));
    }

    @Test
    void 営業ロール以外や無効ユーザーへの割当は拒否される() {
        assertThrows(BusinessException.class,
                () -> engineerSalesService.assign(ENGINEER_ID, hrUserId, false, null));
        assertThrows(BusinessException.class,
                () -> engineerSalesService.assign(ENGINEER_ID, disabledSalesId, false, null));
    }

    @Test
    void setPrimaryで主担当が入れ替わる() {
        engineerSalesService.assign(ENGINEER_ID, salesUser1Id, true, null);
        engineerSalesService.assign(ENGINEER_ID, salesUser2Id, false, null);

        Long secondAssignmentId = engineerSalesService.listActive(ENGINEER_ID).stream()
                .filter(a -> a.getSalesUserId().equals(salesUser2Id))
                .findFirst().orElseThrow().getId();
        engineerSalesService.setPrimary(ENGINEER_ID, secondAssignmentId);

        assertEquals(salesUser2Id, engineerSalesService.findPrimarySalesUserId(ENGINEER_ID));
        long primaryCount = engineerSalesService.listActive(ENGINEER_ID).stream()
                .filter(a -> a.getPrimaryFlag() == 1).count();
        assertEquals(1, primaryCount);
    }

    @Test
    void 他の現任担当が残る状態での主担当解除は拒否される() {
        engineerSalesService.assign(ENGINEER_ID, salesUser1Id, true, null);
        engineerSalesService.assign(ENGINEER_ID, salesUser2Id, false, null);

        Long primaryAssignmentId = engineerSalesService.listActive(ENGINEER_ID).stream()
                .filter(a -> a.getPrimaryFlag() == 1)
                .findFirst().orElseThrow().getId();
        assertThrows(BusinessException.class,
                () -> engineerSalesService.release(ENGINEER_ID, primaryAssignmentId));
    }

    @Test
    void 解除はreleasedAtを設定し履歴に残る() {
        engineerSalesService.assign(ENGINEER_ID, salesUser1Id, true, null);
        Long assignmentId = engineerSalesService.listActive(ENGINEER_ID).get(0).getId();

        engineerSalesService.release(ENGINEER_ID, assignmentId);

        assertTrue(engineerSalesService.listActive(ENGINEER_ID).isEmpty());
        List<EngineerSalesDto> history = engineerSalesService.listHistory(ENGINEER_ID);
        assertEquals(1, history.size());
        assertNotNull(history.get(0).getReleasedAt());
        // 物理・論理削除ではないこと
        assertEquals(1, engineerSalesService.count(new LambdaQueryWrapper<EngineerSales>()
                .eq(EngineerSales::getEngineerId, ENGINEER_ID)));
        assertNull(engineerSalesService.findPrimarySalesUserId(ENGINEER_ID));
    }

    @Test
    void 営業ユーザー選択肢は有効な営業のみ返す() {
        var options = engineerSalesService.salesUserOptions();
        assertTrue(options.stream().anyMatch(o -> o.getId().equals(salesUser1Id)));
        assertTrue(options.stream().anyMatch(o -> o.getId().equals(salesUser2Id)));
        assertTrue(options.stream().noneMatch(o -> o.getId().equals(hrUserId)));
        assertTrue(options.stream().noneMatch(o -> o.getId().equals(disabledSalesId)));
    }

    @Test
    void mapPrimaryByEngineerIdsは主担当のみ返す() {
        engineerSalesService.assign(ENGINEER_ID, salesUser1Id, true, null);
        engineerSalesService.assign(ENGINEER_ID, salesUser2Id, false, null);

        var map = engineerSalesService.mapPrimaryByEngineerIds(List.of(ENGINEER_ID, 999L));
        assertEquals(1, map.size());
        assertEquals(salesUser1Id, map.get(ENGINEER_ID).getSalesUserId());
        assertEquals("営業 一郎", map.get(ENGINEER_ID).getSalesUserName());
        assertTrue(engineerSalesService.mapPrimaryByEngineerIds(List.of()).isEmpty());
    }
}
