package com.ses.service.impl;

import com.ses.entity.Proposal;
import com.ses.entity.ProposalHistory;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ProposalHistoryMapper;
import com.ses.service.EngineerStatusService;
import com.ses.common.util.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class ProposalServiceImplTest {

    @Autowired
    private ProposalServiceImpl proposalService;

    @Autowired
    private ProposalMapper proposalMapper;

    @Autowired
    private ProposalHistoryMapper proposalHistoryMapper;

    @MockBean
    private EngineerStatusService engineerStatusService;

    private MockedStatic<SecurityUtils> mockedSecurityUtils;

    @BeforeEach
    public void setUp() {
        mockedSecurityUtils = Mockito.mockStatic(SecurityUtils.class);
        mockedSecurityUtils.when(SecurityUtils::currentUserId).thenReturn(1L);
    }

    @AfterEach
    public void tearDown() {
        if (mockedSecurityUtils != null && !mockedSecurityUtils.isClosed()) {
            mockedSecurityUtils.close();
        }
    }

    @Test
    public void testChangeStatusAllowed() {
        Proposal p = new Proposal();
        p.setProjectId(1L);
        p.setEngineerId(1L);
        p.setStatus("書類選考中");
        p.setProposedUnitPrice(new java.math.BigDecimal(500000));
        proposalMapper.insert(p);

        proposalService.changeStatus(p.getId(), "一次面接");

        Proposal updated = proposalMapper.selectById(p.getId());
        assertEquals("一次面接", updated.getStatus());
        assertNull(updated.getClosedAt());

        var histories = proposalHistoryMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProposalHistory>()
                        .eq(ProposalHistory::getProposalId, p.getId())
        );
        assertEquals(1, histories.size());
        assertEquals("書類選考中", histories.get(0).getFromStatus());
        assertEquals("一次面接", histories.get(0).getToStatus());
        assertEquals(1L, histories.get(0).getChangedBy());
    }

    @Test
    public void testChangeStatusNotAllowed() {
        Proposal p = new Proposal();
        p.setProjectId(1L);
        p.setEngineerId(1L);
        p.setStatus("書類選考中");
        proposalMapper.insert(p);

        BusinessException exception = assertThrows(BusinessException.class, () -> {
            proposalService.changeStatus(p.getId(), "結果待ち");
        });

        assertTrue(exception.getMessage().contains("変更できません"));
    }

    @Test
    public void testChangeStatusToWon() {
        Proposal p = new Proposal();
        p.setProjectId(1L);
        p.setEngineerId(1L);
        p.setStatus("結果待ち");
        proposalMapper.insert(p);

        proposalService.changeStatus(p.getId(), "成約");

        Proposal updated = proposalMapper.selectById(p.getId());
        assertEquals("成約", updated.getStatus());
        assertNotNull(updated.getClosedAt());
        
        // Cannot transition from terminal state
        assertThrows(BusinessException.class, () -> {
            proposalService.changeStatus(p.getId(), "一次面接");
        });
    }

    @Test
    public void testChangeStatusToLost() {
        Proposal p = new Proposal();
        p.setProjectId(1L);
        p.setEngineerId(1L);
        p.setStatus("一次面接");
        proposalMapper.insert(p);

        proposalService.changeStatus(p.getId(), "見送り");

        Proposal updated = proposalMapper.selectById(p.getId());
        assertEquals("見送り", updated.getStatus());
        assertNotNull(updated.getClosedAt());
        
        verify(engineerStatusService, times(1)).releaseIfIdle(1L);
    }
}
