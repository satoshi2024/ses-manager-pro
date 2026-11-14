package com.ses.service.portal.impl;

import com.ses.common.exception.BusinessException;
import com.ses.entity.AuditLog;
import com.ses.entity.Engineer;
import com.ses.entity.PortalOrganization;
import com.ses.entity.PortalUser;
import com.ses.entity.PortalUserEngineerLink;
import com.ses.entity.SysUser;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.PortalOrganizationMapper;
import com.ses.mapper.PortalUserEngineerLinkMapper;
import com.ses.mapper.PortalUserMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.AuditLogService;
import com.ses.test.TenantTestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * NF01 N1-4: 紐付けwriterは対象要員の t_engineer 行を FOR UPDATE してから紐付けを書き、最後に監査を書く
 * （NF01細粒度epoch計画 §3.1: 順7 ENGINEER → 順12 PORTAL_ENGINEER_LINK → 葉）。退社完了との直列化の前提。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PortalEngineerLinkServiceLockOrderTest {

    private static final String TENANT = "tenant-pel";
    private static final Long ADMIN_ID = 7L;
    private static final Long PORTAL_USER_ID = 11L;
    private static final Long ORG_ID = 12L;
    private static final Long ENGINEER_ID = 13L;
    private static final Long LINK_ID = 14L;

    @Mock private PortalUserEngineerLinkMapper linkMapper;
    @Mock private PortalUserMapper portalUserMapper;
    @Mock private PortalOrganizationMapper organizationMapper;
    @Mock private EngineerMapper engineerMapper;
    @Mock private LifecycleCaseMapper lifecycleCaseMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private AuditLogService auditLogService;

    @InjectMocks
    private PortalEngineerLinkServiceImpl service;

    @BeforeEach
    void setUp() {
        TenantTestSecurity.bindAs(ADMIN_ID, "pel-admin", TENANT, "管理者");
        SysUser admin = new SysUser();
        admin.setId(ADMIN_ID);
        admin.setUsername("pel-admin");
        admin.setRole("管理者");
        admin.setStatus(1);
        admin.setTenantId(TENANT);
        when(sysUserMapper.selectById(ADMIN_ID)).thenReturn(admin);
        PortalUser portalUser = new PortalUser();
        portalUser.setId(PORTAL_USER_ID);
        portalUser.setPortalOrgId(ORG_ID);
        when(portalUserMapper.selectById(PORTAL_USER_ID)).thenReturn(portalUser);
        PortalOrganization organization = new PortalOrganization();
        organization.setId(ORG_ID);
        organization.setTenantId(TENANT);
        when(organizationMapper.selectById(ORG_ID)).thenReturn(organization);
    }

    @AfterEach
    void tearDown() {
        TenantTestSecurity.clear();
    }

    @Test
    void 登録は要員行の排他lockの後に紐付けを挿入し最後に監査を書く() {
        when(engineerMapper.selectByIdForUpdateForTenant(ENGINEER_ID, TENANT)).thenReturn(engineer());
        when(linkMapper.insert(any(PortalUserEngineerLink.class))).thenReturn(1);

        service.register(PORTAL_USER_ID, ENGINEER_ID);

        InOrder order = inOrder(engineerMapper, linkMapper, auditLogService);
        order.verify(engineerMapper).selectByIdForUpdateForTenant(ENGINEER_ID, TENANT);
        order.verify(linkMapper).selectActiveByPortalUserId(PORTAL_USER_ID);
        order.verify(linkMapper).insert(any(PortalUserEngineerLink.class));
        ArgumentCaptor<AuditLog> audit = ArgumentCaptor.forClass(AuditLog.class);
        order.verify(auditLogService).recordDomainEventRequired(audit.capture());
        assertEquals("PORTAL_ENGINEER_LINK_REGISTERED", audit.getValue().getApplicationCode());
        assertEquals("HUMAN", audit.getValue().getActorType());
        assertEquals(ADMIN_ID, audit.getValue().getHumanUserId());
    }

    @Test
    void 解除は要員行の排他lockの後にCAS更新し最後に監査を書く() {
        PortalUserEngineerLink located = new PortalUserEngineerLink();
        located.setId(LINK_ID);
        located.setTenantId(TENANT);
        located.setPortalUserId(PORTAL_USER_ID);
        located.setEngineerId(ENGINEER_ID);
        located.setLinkedAt(LocalDateTime.now().minusDays(1));
        when(linkMapper.selectById(LINK_ID)).thenReturn(located);
        when(engineerMapper.selectByIdForUpdateForTenant(ENGINEER_ID, TENANT)).thenReturn(engineer());
        when(linkMapper.update(isNull(), any())).thenReturn(1);

        service.release(LINK_ID);

        InOrder order = inOrder(engineerMapper, linkMapper, auditLogService);
        order.verify(engineerMapper).selectByIdForUpdateForTenant(ENGINEER_ID, TENANT);
        order.verify(linkMapper).update(isNull(), any());
        order.verify(auditLogService).recordDomainEventRequired(any(AuditLog.class));
    }

    @Test
    void 要員行をlockできなければ紐付けも監査も書かず404にする() {
        when(engineerMapper.selectByIdForUpdateForTenant(ENGINEER_ID, TENANT)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.register(PORTAL_USER_ID, ENGINEER_ID));

        assertEquals(404, ex.getCode());
        verify(linkMapper, never()).insert(any(PortalUserEngineerLink.class));
        verify(auditLogService, never()).recordDomainEventRequired(any());
    }

    @Test
    void 並行登録の一意制約違反だけを409へ変換する() {
        when(engineerMapper.selectByIdForUpdateForTenant(ENGINEER_ID, TENANT)).thenReturn(engineer());
        when(linkMapper.insert(any(PortalUserEngineerLink.class))).thenThrow(new org.springframework.dao.DuplicateKeyException(
                "dup", new java.sql.SQLIntegrityConstraintViolationException(
                "Duplicate entry '11' for key 't_portal_user_engineer_link.uk_portal_user_engineer_link_active'")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.register(PORTAL_USER_ID, ENGINEER_ID));

        assertEquals(409, ex.getCode());
        assertEquals("error.portalAdmin.engineerLink.alreadyLinked", ex.getMessageKey());
        verify(auditLogService, never()).recordDomainEventRequired(any());
        assertTrue(PortalEngineerLinkServiceImpl.violatesActiveLinkKey(new org.springframework.dao.DuplicateKeyException(
                "Unique index or primary key violation: \"PUBLIC.UK_PORTAL_USER_ENGINEER_LINK_ACTIVE ON ...\"")));
    }

    private Engineer engineer() {
        Engineer engineer = new Engineer();
        engineer.setId(ENGINEER_ID);
        engineer.setTenantId(TENANT);
        engineer.setFullName("紐付け要員");
        return engineer;
    }
}
