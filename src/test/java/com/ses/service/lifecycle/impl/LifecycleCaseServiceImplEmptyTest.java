package com.ses.service.lifecycle.impl;

import com.ses.mapper.LifecycleCaseMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.service.lifecycle.LifecycleScopeService;
import com.ses.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LifecycleCaseServiceImplEmptyTest {

    @Mock
    private LifecycleCaseMapper caseMapper;

    @Mock
    private EngineerMapper engineerMapper;

    @Mock
    private LifecycleScopeService scopeService;

    @InjectMocks
    private LifecycleCaseServiceImpl service;

    @Test
    void 空の案件一覧は要員検索を実行せず空配列を返す() {
        when(caseMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(scopeService.requireTenant(any())).thenReturn("tenant-a");
        SysUser user = new SysUser();
        user.setId(1L);
        user.setRole("管理者");
        user.setTenantId("tenant-a");
        assertTrue(service.listCases(null, null, null, null, null, user).isEmpty());
        verifyNoInteractions(engineerMapper);
    }
}
