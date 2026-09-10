package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceSlaEscalation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ServiceSlaEscalationMapper extends BaseMapper<ServiceSlaEscalation> {

    @Select("SELECT e.* FROM t_service_sla_escalation e "
            + "INNER JOIN t_service_request r ON r.id = e.service_request_id "
            + "WHERE e.dedupe_key = #{dedupeKey} AND r.tenant_id = #{tenantId} LIMIT 1")
    ServiceSlaEscalation selectByDedupeKeyAndTenant(@Param("dedupeKey") String dedupeKey,
                                                   @Param("tenantId") String tenantId);

    @Select("SELECT e.* FROM t_service_sla_escalation e "
            + "INNER JOIN t_service_request r ON r.id = e.service_request_id "
            + "WHERE e.sla_clock_id = #{clockId} AND r.tenant_id = #{tenantId} "
            + "AND e.status = 'RETRY'")
    List<ServiceSlaEscalation> selectRetryByClockAndTenant(@Param("clockId") Long clockId,
                                                            @Param("tenantId") String tenantId);
}
