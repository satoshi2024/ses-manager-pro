package com.ses.service.impl;

import com.ses.entity.Engineer;
import com.ses.mapper.ContractMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.EngineerSalesService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.Serializable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 要員削除時の割当解放の順序（review-fixes G3）を検証する単体テスト。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EngineerServiceImplTest {

    @Mock
    private ContractMapper contractMapper;
    @Mock
    private ProposalMapper proposalMapper;
    @Mock
    private EngineerSalesService engineerSalesService;
    @Mock
    private EngineerMapper engineerMapper;
    @Mock
    private com.ses.service.EngineerAccountLinkService engineerAccountLinkService;
    @Mock
    private com.ses.mapper.SysUserMapper sysUserMapper;

    @Mock
    private com.ses.service.security.ScopeChangeInvalidator scopeChangeInvalidator;

    private EngineerServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EngineerServiceImpl(contractMapper, proposalMapper, engineerSalesService,
                engineerAccountLinkService, sysUserMapper);
        ReflectionTestUtils.setField(service, "baseMapper", engineerMapper);
        ReflectionTestUtils.setField(service, "scopeChangeInvalidator", scopeChangeInvalidator);
        // 削除ガードは通過する状態にする
        when(contractMapper.selectCount(any())).thenReturn(0L);
        when(proposalMapper.selectCount(any())).thenReturn(0L);
    }

    @Test
    void removeById_削除成功時のみ割当を解除する() {
        when(engineerMapper.deleteById(any(Serializable.class))).thenReturn(1);

        assertTrue(service.removeById(1L));
        verify(engineerSalesService, times(1)).releaseAllByEngineerId(1L);
    }

    @Test
    void removeById_削除失敗時は割当を解除しない() {
        // 並行削除等で対象行が既に無く removeById が false を返すケース
        when(engineerMapper.deleteById(any(Serializable.class))).thenReturn(0);

        assertFalse(service.removeById(1L));
        verify(engineerSalesService, never()).releaseAllByEngineerId(any());
    }

    /**
     * 要員自身の所属組織（organizationId）はscope派生SQLの一次情報。変更後にDataScope世代を
     * 進めないと、Dashboardキャッシュ経由でTTLが切れるまで旧scopeの母集団が返る
     * （第十四次Review P1-3）。
     */
    @Test
    void updateWithStatusGuard_所属組織変更時はscope世代を進める() {
        Engineer old = Engineer.builder().fullName("要員A").organizationId(100L).build();
        old.setId(1L);
        when(engineerMapper.selectById(1L)).thenReturn(old);
        when(engineerMapper.updateById(any(Engineer.class))).thenReturn(1);

        Engineer changed = Engineer.builder().fullName("要員A").organizationId(200L).build();
        changed.setId(1L);
        changed.setVersion(0);
        when(engineerMapper.selectOne(any())).thenReturn(null);

        service.updateWithStatusGuard(changed);

        verify(scopeChangeInvalidator, times(1)).invalidate();
    }

    @Test
    void updateWithStatusGuard_所属組織が変わらなければscope世代を進めない() {
        Engineer old = Engineer.builder().fullName("要員A").organizationId(100L).build();
        old.setId(1L);
        when(engineerMapper.selectById(1L)).thenReturn(old);
        when(engineerMapper.updateById(any(Engineer.class))).thenReturn(1);

        Engineer unchanged = Engineer.builder().fullName("要員A").organizationId(100L).build();
        unchanged.setId(1L);
        unchanged.setVersion(0);

        service.updateWithStatusGuard(unchanged);

        verify(scopeChangeInvalidator, never()).invalidate();
    }
}
