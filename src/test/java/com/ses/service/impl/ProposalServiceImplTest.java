package com.ses.service.impl;

import com.ses.entity.Proposal;
import com.ses.entity.ProposalHistory;
import com.ses.entity.Project;
import com.ses.entity.Contract;
import com.ses.common.exception.BusinessException;
import com.ses.mapper.ProposalMapper;
import com.ses.mapper.ProposalHistoryMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ContractMapper;
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
@org.springframework.test.context.jdbc.Sql(scripts = "/sql/engineer-schema-h2.sql")
public class ProposalServiceImplTest {

    @Autowired
    private ProposalServiceImpl proposalService;

    @Autowired
    private ProposalMapper proposalMapper;

    @Autowired
    private ProposalHistoryMapper proposalHistoryMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Autowired
    private ContractMapper contractMapper;

    @MockBean
    private EngineerStatusService engineerStatusService;

    /** createDraftFromProposal が案件を解決できるよう、指定IDの案件を用意する。 */
    private Long seedProject(Long customerId) {
        Project prj = new Project();
        prj.setProjectName("テスト案件");
        prj.setCustomerId(customerId);
        projectMapper.insert(prj);
        return prj.getId();
    }

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

        assertTrue(exception.getMessage().contains("error.proposal.statusTransitionInvalid"));
    }

    @Test
    public void testChangeStatusToWon() {
        Long projectId = seedProject(3L);
        Proposal p = new Proposal();
        p.setProjectId(projectId);
        p.setEngineerId(1L);
        p.setStatus("結果待ち");
        p.setProposedUnitPrice(new java.math.BigDecimal(700000));
        proposalMapper.insert(p);

        proposalService.changeStatus(p.getId(), "成約");

        Proposal updated = proposalMapper.selectById(p.getId());
        assertEquals("成約", updated.getStatus());
        assertNotNull(updated.getClosedAt());

        // 成約により契約ドラフト(準備中)が自動生成されること
        Contract draft = contractMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Contract>()
                        .eq(Contract::getProposalId, p.getId()));
        assertNotNull(draft, "契約ドラフトが生成されていること");
        assertEquals("準備中", draft.getStatus());
        assertEquals(projectId, draft.getProjectId());
        assertEquals(3L, draft.getCustomerId());
        assertEquals(0, new java.math.BigDecimal(700000).compareTo(draft.getSellingPrice()));
        assertNotNull(draft.getContractNo());

        // Cannot transition from terminal state
        assertThrows(BusinessException.class, () -> {
            proposalService.changeStatus(p.getId(), "一次面接");
        });
    }

    @Test
    public void testChangeStatusToWon_failsWhenProjectMissing() {
        Proposal p = new Proposal();
        p.setProjectId(9999L); // 存在しない案件
        p.setEngineerId(1L);
        p.setStatus("結果待ち");
        proposalMapper.insert(p);

        // 案件が解決できなければ BusinessException。changeStatus は @Transactional(rollbackFor=Exception)
        // のため、実運用では成約遷移ごとロールバックされる(本テストはテスト側トランザクション内の
        // read-your-writes のためステータス反転は観測せず、契約が未生成であることを確認する)。
        assertThrows(BusinessException.class, () -> proposalService.changeStatus(p.getId(), "成約"));

        long count = contractMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Contract>()
                        .eq(Contract::getProposalId, p.getId()));
        assertEquals(0, count);
    }

    @Test
    public void testSave_LinksEngineerStatusToProposing() {
        Proposal p = new Proposal();
        p.setProjectId(1L);
        p.setEngineerId(7L);
        p.setProposedBy(99L);
        p.setProposedAt(LocalDateTime.now().minusDays(1));

        proposalService.save(p);

        Proposal saved = proposalMapper.selectById(p.getId());
        assertEquals("書類選考中", saved.getStatus());
        assertEquals(1L, saved.getProposedBy());
        assertNotNull(saved.getProposedAt());
        assertTrue(saved.getProposedAt().isAfter(LocalDateTime.now().minusMinutes(1)));

        // 提案作成時にBench要員を「提案中」へ連動させる呼び出しが行われること
        verify(engineerStatusService, times(1)).onProposalCreated(7L);
    }

    @Test
    public void testSave_RejectsNonInitialStatus() {
        Proposal p = new Proposal();
        p.setProjectId(1L);
        p.setEngineerId(8L);
        p.setStatus("成約");

        BusinessException ex = assertThrows(BusinessException.class, () -> proposalService.save(p));

        assertEquals("error.proposal.statusTransitionInvalid", ex.getMessage());
        assertNull(p.getId());
        verify(engineerStatusService, never()).onProposalCreated(8L);
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
