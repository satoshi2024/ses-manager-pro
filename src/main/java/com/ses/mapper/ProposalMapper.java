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
            "LEFT JOIN m_customer c ON pj.customer_id = c.id " +
            "WHERE p.deleted_flag = 0 AND (e.deleted_flag = 0 OR e.deleted_flag IS NULL) AND (pj.deleted_flag = 0 OR pj.deleted_flag IS NULL)")
    List<ProposalKanbanDto> selectKanbanList();

    /**
     * かんばん列の SQL 段階フィルタ＋ページング。
     * status / keyword / データスコープ ID 集合を WHERE へ下し、Java 側の全件 subList を禁止する。
     */
    @Select("<script>" +
            "SELECT p.id, p.engineer_id, p.project_id, e.full_name AS engineerName, e.initial_name AS engineerInitial, " +
            "pj.project_name AS projectName, c.company_name AS customerName, p.proposed_unit_price, p.status, p.ai_match_score, p.proposed_at " +
            "FROM t_proposal p " +
            "LEFT JOIN t_engineer e ON p.engineer_id = e.id " +
            "LEFT JOIN t_project pj ON p.project_id = pj.id " +
            "LEFT JOIN m_customer c ON pj.customer_id = c.id " +
            "WHERE p.deleted_flag = 0 " +
            "AND (e.deleted_flag = 0 OR e.deleted_flag IS NULL) " +
            "AND (pj.deleted_flag = 0 OR pj.deleted_flag IS NULL) " +
            "<if test='status != null and status != \"\"'> AND p.status = #{status} </if>" +
            "<if test='keyword != null and keyword != \"\"'> " +
            " AND (" +
            "  LOWER(COALESCE(e.full_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') " +
            "  OR LOWER(COALESCE(pj.project_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%') " +
            "  OR LOWER(COALESCE(c.company_name, '')) LIKE CONCAT('%', LOWER(#{keyword}), '%')" +
            " ) " +
            "</if>" +
            "<if test='allowedIds != null'>" +
            " <choose>" +
            "  <when test='allowedIds.size() == 0'> AND 1 = 0 </when>" +
            "  <otherwise> AND p.id IN " +
            "   <foreach item='id' collection='allowedIds' open='(' separator=',' close=')'>#{id}</foreach>" +
            "  </otherwise>" +
            " </choose>" +
            "</if>" +
            "ORDER BY p.id DESC" +
            "</script>")
    com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProposalKanbanDto> selectKanbanPage(
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<ProposalKanbanDto> page,
            @org.apache.ibatis.annotations.Param("status") String status,
            @org.apache.ibatis.annotations.Param("keyword") String keyword,
            @org.apache.ibatis.annotations.Param("allowedIds") java.util.Collection<Long> allowedIds);

    /** 提案行を FOR UPDATE でロックして取得する（ステータス変更の並行実行を直列化する）。 */
    @Select("SELECT * FROM t_proposal WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Proposal selectByIdForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);

    /** 孤児ファイル清掃用: 参照中のスキルシート保存名を軽量取得する。 */
    @Select("SELECT skill_sheet_path FROM t_proposal WHERE deleted_flag = 0 AND skill_sheet_path IS NOT NULL")
    List<String> selectAllSkillSheetPaths();

    @Select("<script>" +
            "SELECT p.id, p.engineer_id, p.project_id, e.full_name AS engineerName, e.initial_name AS engineerInitial, " +
            "pj.project_name AS projectName, c.company_name AS customerName, p.proposed_unit_price, p.status, p.ai_match_score, p.proposed_at " +
            "FROM t_proposal p " +
            "LEFT JOIN t_engineer e ON p.engineer_id = e.id " +
            "LEFT JOIN t_project pj ON p.project_id = pj.id " +
            "LEFT JOIN m_customer c ON pj.customer_id = c.id " +
            "WHERE p.deleted_flag = 0 AND pj.deleted_flag = 0 AND e.deleted_flag = 0 AND p.engineer_id = #{engineerId} " +
            "AND pj.customer_id = #{customerId} " +
            "<if test='excludeId != null'> AND p.id != #{excludeId} </if> " +
            "AND p.status IN " +
            "<foreach item='item' collection='activeStatuses' open='(' separator=',' close=')'>#{item}</foreach>" +
            "</script>")
    List<ProposalKanbanDto> selectActiveDuplicates(@org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
                                                   @org.apache.ibatis.annotations.Param("customerId") Long customerId,
                                                   @org.apache.ibatis.annotations.Param("excludeId") Long excludeId,
                                                   @org.apache.ibatis.annotations.Param("activeStatuses") java.util.Set<String> activeStatuses);

    @Select("<script>" +
            "SELECT p.id, p.engineer_id, p.project_id, e.full_name AS engineerName, e.initial_name AS engineerInitial, " +
            "pj.project_name AS projectName, c.company_name AS customerName, p.proposed_unit_price, p.status, p.ai_match_score, p.proposed_at " +
            "FROM t_proposal p " +
            "LEFT JOIN t_engineer e ON p.engineer_id = e.id " +
            "LEFT JOIN t_project pj ON p.project_id = pj.id " +
            "LEFT JOIN m_customer c ON pj.customer_id = c.id " +
            "WHERE p.deleted_flag = 0 AND pj.deleted_flag = 0 AND e.deleted_flag = 0 AND p.engineer_id = #{engineerId} " +
            "<if test='allowedCustomerIds != null'>" +
            " AND pj.customer_id IN " +
            "<foreach item='id' collection='allowedCustomerIds' open='(' separator=',' close=')'>#{id}</foreach>" +
            "</if>" +
            "ORDER BY p.proposed_at DESC, p.id DESC" +
            "</script>")
    List<ProposalKanbanDto> selectProposalHistory(@org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
                                                  @org.apache.ibatis.annotations.Param("allowedCustomerIds") java.util.Collection<Long> allowedCustomerIds);
}
