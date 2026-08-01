package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Opportunity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OpportunityMapper extends BaseMapper<Opportunity> {

    /** 状態遷移・変換を直列化するため商機行をロックして取得する。 */
    @Select("SELECT * FROM t_opportunity WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Opportunity selectByIdForUpdate(@Param("id") Long id);
}
