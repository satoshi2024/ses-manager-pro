package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceSlaClock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * SLA計時・ラウンド履歴マッパー
 */
@Mapper
public interface ServiceSlaClockMapper extends BaseMapper<ServiceSlaClock> {

    /** requestのtenantをSQL joinで検証したSLA母集団。 */
    @Select("SELECT c.* FROM t_service_sla_clock c "
            + "INNER JOIN t_service_request r ON r.id = c.service_request_id "
            + "WHERE c.status = 'RUNNING' AND r.tenant_id = #{tenantId}")
    List<ServiceSlaClock> selectRunningByTenant(@Param("tenantId") String tenantId);
}
