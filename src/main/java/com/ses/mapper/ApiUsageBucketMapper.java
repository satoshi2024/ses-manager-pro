package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.integrationhub.ApiUsageBucket;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** NF-05 multi-node quota bucket mapper。 */
@Mapper
public interface ApiUsageBucketMapper extends BaseMapper<ApiUsageBucket> {
    @Select("SELECT * FROM t_api_usage_bucket WHERE client_id = #{clientId} AND scope_code = #{scopeCode} "
            + "AND tenant_id = #{tenantId} AND route_template = #{routeTemplate} FOR UPDATE")
    ApiUsageBucket selectSubjectForUpdate(@Param("clientId") String clientId,
                                          @Param("scopeCode") String scopeCode,
                                          @Param("tenantId") String tenantId,
                                          @Param("routeTemplate") String routeTemplate);

    @Update("UPDATE t_api_usage_bucket SET minute_window_start = #{bucket.minuteWindowStart}, "
            + "minute_count = #{bucket.minuteCount}, day_window_start = #{bucket.dayWindowStart}, "
            + "day_count = #{bucket.dayCount}, burst_tokens = #{bucket.burstTokens}, "
            + "burst_last_refill_at = #{bucket.burstLastRefillAt}, version = version + 1, "
            + "updated_at = #{bucket.updatedAt} WHERE id = #{bucket.id} AND version = #{bucket.version}")
    int updateCounters(@Param("bucket") ApiUsageBucket bucket);
}
