package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.CandidateActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 候補者ステージ変更履歴マッパー
 */
@Mapper
public interface CandidateActivityMapper extends BaseMapper<CandidateActivity> {

    @Select("SELECT a.* FROM t_candidate_activity a "
            + "INNER JOIN t_candidate c ON c.id = a.candidate_id AND c.deleted_flag = 0 "
            + "WHERE a.candidate_id = #{candidateId} AND c.tenant_id = #{tenantId} "
            + "ORDER BY a.changed_at DESC, a.id DESC")
    List<CandidateActivity> selectByCandidateIdForTenant(@Param("candidateId") Long candidateId,
                                                         @Param("tenantId") String tenantId);
}
