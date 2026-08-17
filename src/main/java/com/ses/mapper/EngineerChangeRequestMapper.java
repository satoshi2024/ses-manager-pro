package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.EngineerChangeRequest;
import org.apache.ibatis.annotations.Mapper;

/** 要員プロフィール/スキル変更申請（t_engineer_change_request）。 */
@Mapper
public interface EngineerChangeRequestMapper extends BaseMapper<EngineerChangeRequest> {
}
