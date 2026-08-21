package com.ses.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.proposal.ProposalKanbanDto;
import com.ses.entity.Customer;
import com.ses.entity.Engineer;
import com.ses.entity.Project;
import com.ses.entity.Proposal;
import com.ses.mapper.CustomerMapper;
import com.ses.mapper.EngineerMapper;
import com.ses.mapper.ProjectMapper;
import com.ses.mapper.ProposalMapper;
import com.ses.service.ProposalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * SC-01: Kanban ページングが SQL 段階で行われることを、非 MockBean の実サービスで固定する。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
@Sql("/sql/engineer-schema-h2.sql")
@DisplayName("提案Kanban SQLページング (SC-01)")
class ProposalServiceKanbanSqlPageTest {

    @Autowired
    private ProposalService proposalService;

    @SpyBean
    private ProposalMapper proposalMapper;

    @Autowired
    private EngineerMapper engineerMapper;

    @Autowired
    private CustomerMapper customerMapper;

    @Autowired
    private ProjectMapper projectMapper;

    @Test
    @DisplayName("statusフィルタとページ境界をSQLへ下し selectKanbanList を呼ばない")
    void getKanbanPage_usesSqlPaginationNotFullListSubList() {
        Customer customer = new Customer();
        customer.setCompanyName("Kanban顧客");
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("Kanban案件");
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        for (int i = 1; i <= 12; i++) {
            Engineer engineer = new Engineer();
            engineer.setFullName("Kanban要員" + i);
            engineer.setEmploymentType("正社員");
            engineer.setStatus("提案中");
            engineerMapper.insert(engineer);

            Proposal proposal = new Proposal();
            proposal.setEngineerId(engineer.getId());
            proposal.setProjectId(project.getId());
            proposal.setStatus(i <= 8 ? "書類選考中" : "一次面接");
            proposal.setProposedUnitPrice(new BigDecimal("500000"));
            proposalMapper.insert(proposal);
        }

        Page<ProposalKanbanDto> page = proposalService.getKanbanPage("書類選考中", 1L, 5L, null);

        assertEquals(8L, page.getTotal(), "status=書類選考中 の件数がSQL COUNTと一致すること");
        assertEquals(5, page.getRecords().size(), "1ページ目はsize件のみ返すこと");
        assertTrue(page.getRecords().stream().allMatch(r -> "書類選考中".equals(r.getStatus())));

        verify(proposalMapper, never()).selectKanbanList();
        verify(proposalMapper, atLeastOnce()).selectKanbanPage(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("書類選考中"),
                org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("keywordもSQL段階で絞り込みページングする")
    void getKanbanPage_keywordIsAppliedInSql() {
        Customer customer = new Customer();
        customer.setCompanyName("検索顧客XYZ");
        customerMapper.insert(customer);

        Project project = new Project();
        project.setProjectName("通常案件");
        project.setCustomerId(customer.getId());
        projectMapper.insert(project);

        for (int i = 1; i <= 6; i++) {
            Engineer engineer = new Engineer();
            engineer.setFullName(i == 1 ? "キーワード太郎" : "別要員" + i);
            engineer.setEmploymentType("正社員");
            engineer.setStatus("提案中");
            engineerMapper.insert(engineer);

            Proposal proposal = new Proposal();
            proposal.setEngineerId(engineer.getId());
            proposal.setProjectId(project.getId());
            proposal.setStatus("書類選考中");
            proposal.setProposedUnitPrice(new BigDecimal("400000"));
            proposalMapper.insert(proposal);
        }

        Page<ProposalKanbanDto> page = proposalService.getKanbanPage("書類選考中", 1L, 20L, "キーワード");

        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getRecords().size());
        assertEquals("キーワード太郎", page.getRecords().get(0).getEngineerName());
        verify(proposalMapper, never()).selectKanbanList();
    }
}
