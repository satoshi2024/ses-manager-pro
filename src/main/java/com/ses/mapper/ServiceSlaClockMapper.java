package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceSlaClock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Collection;

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

    /** 顧客ヘルス集計用。SLA clock単体にはtenantを持たせず、親requestのtenantで限定する。 */
    @Select("<script>SELECT c.* FROM t_service_sla_clock c "
            + "INNER JOIN t_service_request r ON r.id = c.service_request_id "
            + "WHERE r.tenant_id = #{tenantId} AND c.service_request_id IN "
            + "<foreach collection='requestIds' item='id' open='(' separator=',' close=')'>#{id}</foreach></script>")
    List<ServiceSlaClock> selectByRequestIdsForTenant(@Param("requestIds") Collection<Long> requestIds,
                                                      @Param("tenantId") String tenantId);
}
