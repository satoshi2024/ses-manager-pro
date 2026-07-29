package com.ses.service.security;

import com.ses.common.exception.BusinessException;
import com.ses.config.OidcSecurityProperties;
import com.ses.entity.BreakGlassIncident;
import com.ses.entity.SysUser;
import com.ses.mapper.AuditLogMapper;
import com.ses.mapper.BreakGlassIncidentMapper;
import com.ses.mapper.SysUserMapper;
import com.ses.service.security.impl.BreakGlassServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BreakGlassServiceImplTest {

    private final BreakGlassIncidentMapper incidentMapper = mock(BreakGlassIncidentMapper.class);
    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final AuditLogMapper auditMapper = mock(AuditLogMapper.class);
    private final PersistentSessionService sessionService = mock(PersistentSessionService.class);
    private final OidcSecurityProperties properties = new OidcSecurityProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-29T12:00:00Z"), ZoneOffset.UTC);
    private BreakGlassServiceImpl service;

    @BeforeEach
    void setUp() {
        properties.setBreakGlassLoginEnabled(true);
        properties.setBreakGlassUsernames(Set.of("BG-01", "BG-02"));
        when(auditMapper.insert(any(com.ses.entity.AuditLog.class))).thenReturn(1);
        when(incidentMapper.updateById(any(BreakGlassIncident.class))).thenReturn(1);
        when(userMapper.selectById(any())).thenAnswer(invocation -> admin(invocation.getArgument(0)));
        service = new BreakGlassServiceImpl(incidentMapper, userMapper, auditMapper,
                sessionService, properties, clock);
    }

    @Test
    void activeIncidentがない設定usernameはlocalLoginできない() {
        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(null);
        assertFalse(service.isLoginAllowed("BG-01"));
    }

    @Test
    void 二人の別管理者が承認した時だけactiveになる() {
        BreakGlassIncident incident = pending();
        when(incidentMapper.selectByIdForUpdate("default", 10L)).thenReturn(incident);

        assertEquals("PENDING", service.approve(1L, 10L).getStatus());
        assertEquals("ACTIVE", service.approve(2L, 10L).getStatus());
        when(incidentMapper.selectActive("default", java.time.LocalDateTime.now(clock))).thenReturn(incident);
        assertTrue(service.isLoginAllowed("BG-01"));
    }

    @Test
    void 同一管理者による二重承認は拒否する() {
        BreakGlassIncident incident = pending();
        incident.setApprovedBy1(1L);
        when(incidentMapper.selectByIdForUpdate("default", 10L)).thenReturn(incident);

        assertThrows(BusinessException.class, () -> service.approve(1L, 10L));
    }

    @Test
    void 申請者自身による承認は拒否する() {
        BreakGlassIncident incident = pending();
        when(incidentMapper.selectByIdForUpdate("default", 10L)).thenReturn(incident);

        assertThrows(BusinessException.class, () -> service.approve(99L, 10L));
    }

    @Test
    void 監査が保存できないincident起票は失敗する() {
        when(incidentMapper.insert(any(BreakGlassIncident.class))).thenReturn(1);
        when(auditMapper.insert(any(com.ses.entity.AuditLog.class))).thenReturn(0);

        assertThrows(BusinessException.class,
                () -> service.create(1L, "IdP outage", true, 30, "INC-2026-001"));
    }

    private BreakGlassIncident pending() {
        BreakGlassIncident incident = new BreakGlassIncident();
        incident.setId(10L);
        incident.setTenantId("default");
        incident.setStatus("PENDING");
        incident.setRequestedBy(99L);
        incident.setIdpOutageConfirmed(1);
        incident.setEnabledUntil(java.time.LocalDateTime.now(clock).plusMinutes(30));
        incident.setCorrelationId("INC-2026-001");
        return incident;
    }

    private SysUser admin(Long id) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setRole("管理者");
        user.setStatus(1);
        return user;
    }
}
