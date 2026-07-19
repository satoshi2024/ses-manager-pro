package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.EngineerAccountLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface EngineerAccountLinkMapper extends BaseMapper<EngineerAccountLink> {

    @Select("SELECT * FROM t_engineer_account_link WHERE sys_user_id = #{sysUserId} LIMIT 1")
    EngineerAccountLink selectByUserId(@Param("sysUserId") Long sysUserId);

    @Select("SELECT * FROM t_engineer_account_link WHERE engineer_id = #{engineerId} LIMIT 1")
    EngineerAccountLink selectByEngineerId(@Param("engineerId") Long engineerId);
}
