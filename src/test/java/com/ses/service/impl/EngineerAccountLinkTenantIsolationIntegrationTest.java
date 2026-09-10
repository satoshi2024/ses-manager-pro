package com.ses.service.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.Engineer;
import com.ses.entity.EngineerAccountLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.EngineerAccountLinkService;
import com.ses.service.accounting.AccountingTenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 要員アカウント紐付けの作成・参照・解除が同一tenant内に閉じることをH2で検証する。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class EngineerAccountLinkTenantIsolationIntegrationTest {

    @Autowired
    private EngineerAccountLinkService linkService;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @AfterEach
    void clearTenantContext() {
        AccountingTenantContextHolder.clear();
    }

    @Test
    void tenantAのlinkはtenantBから参照解除できず異なるtenantの主体も結べない() {
        Engineer engineerA = insertEngineer("tenant-a", "要員A");
        Engineer engineerB = insertEngineer("tenant-b", "要員B");
        SysUser userA = insertUser("tenant-a", "要員ユーザーA");
        SysUser userB = insertUser("tenant-b", "要員ユーザーB");

        AccountingTenantContextHolder.setTenantId("tenant-a");
        EngineerAccountLink created = linkService.link(engineerA.getId(), userA.getId(), null);
        assertEquals("tenant-a", created.getTenantId());
        assertEquals(engineerA.getId(), linkService.findEngineerIdByUserId(userA.getId()));
        assertNull(linkService.findEngineerIdByUserId(userB.getId()));
        assertNull(linkService.findByEngineerId(engineerB.getId()));
        assertThrows(BusinessException.class,
                () -> linkService.link(engineerA.getId(), userB.getId(), null));
        assertThrows(BusinessException.class,
                () -> linkService.link(engineerB.getId(), userA.getId(), null));

        AccountingTenantContextHolder.setTenantId("tenant-b");
        assertNull(linkService.findByEngineerId(engineerA.getId()));
        linkService.unlinkByEngineerId(engineerA.getId());

        AccountingTenantContextHolder.setTenantId("tenant-a");
        assertEquals(engineerA.getId(), linkService.findEngineerIdByUserId(userA.getId()));
        linkService.unlinkByEngineerId(engineerA.getId());
        assertNull(linkService.findEngineerIdByUserId(userA.getId()));
    }

    private Engineer insertEngineer(String tenantId, String name) {
        Engineer engineer = Engineer.builder()
                .tenantId(tenantId)
                .fullName(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .status("稼動中")
                .employmentType("正社員")
                .build();
        engineerMapper.insert(engineer);
        return engineer;
    }

    private SysUser insertUser(String tenantId, String name) {
        SysUser user = SysUser.builder()
                .tenantId(tenantId)
                .username(name + "-" + UUID.randomUUID().toString().substring(0, 6))
                .password("test")
                .realName(name)
                .role("要員")
                .status(1)
                .build();
        sysUserMapper.insert(user);
        return user;
    }
}
