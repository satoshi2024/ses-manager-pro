package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.StaffingScenarioAllocation;
import org.apache.ibatis.annotations.Mapper;

/** シナリオ内の仮配置（日単位） */
@Mapper
public interface StaffingScenarioAllocationMapper extends BaseMapper<StaffingScenarioAllocation> {
}
