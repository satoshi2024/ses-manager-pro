package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.SystemConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * システム設定マッパー。
 */
@Mapper
public interface SystemConfigMapper extends BaseMapper<SystemConfig> {
    
    @Select("SELECT * FROM m_system_config WHERE config_key = #{configKey} FOR UPDATE")
    SystemConfig selectByIdForUpdate(String configKey);
}