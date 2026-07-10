package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Proposal;
import org.apache.ibatis.annotations.Mapper;

import com.ses.dto.proposal.ProposalKanbanDto;
import org.apache.ibatis.annotations.Select;
import java.util.List;

/**
 * 提案マッパー
 */
@Mapper
public interface ProposalMapper extends BaseMapper<Proposal> {

    @Select("SELECT p.id, p.engineer_id, p.project_id, e.full_name AS engineerName, e.initial_name AS engineerInitial, " +
            "pj.project_name AS projectName, c.company_name AS customerName, p.proposed_unit_price, p.status, p.ai_match_score " +
            "FROM t_proposal p " +
            "LEFT JOIN t_engineer e ON p.engineer_id = e.id " +
            "LEFT JOIN t_project pj ON p.project_id = pj.id " +
            "LEFT JOIN t_customer c ON pj.customer_id = c.id " +
            "WHERE p.deleted_flag = 0 AND (e.deleted_flag = 0 OR e.deleted_flag IS NULL) AND (pj.deleted_flag = 0 OR pj.deleted_flag IS NULL)")
    List<ProposalKanbanDto> selectKanbanList();
}
