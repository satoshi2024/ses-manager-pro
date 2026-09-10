package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ServiceRequestSequence;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 月次リクエスト番号をDB行ロックで採番するmapper。 */
@Mapper
public interface ServiceRequestSequenceMapper extends BaseMapper<ServiceRequestSequence> {

    @Insert("INSERT INTO t_service_request_sequence (tenant_id, request_month, last_number) "
            + "VALUES (#{tenantId}, #{requestMonth}, 0) "
            + "ON DUPLICATE KEY UPDATE tenant_id = tenant_id")
    int ensureRow(@Param("tenantId") String tenantId, @Param("requestMonth") String requestMonth);

    @Update("UPDATE t_service_request_sequence SET last_number = last_number + 1, "
            + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = #{tenantId} "
            + "AND request_month = #{requestMonth} AND last_number < 9999")
    int incrementIfAvailable(@Param("tenantId") String tenantId, @Param("requestMonth") String requestMonth);

    @Select("SELECT last_number FROM t_service_request_sequence WHERE tenant_id = #{tenantId} "
            + "AND request_month = #{requestMonth}")
    Integer selectLastNumber(@Param("tenantId") String tenantId, @Param("requestMonth") String requestMonth);

    default int insertInitialIfAbsent(String month) {
        return ensureRow("default", month);
    }

    default int updateCurrentVal(String month, int nextVal) {
        return update(null, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ServiceRequestSequence>()
                .eq(ServiceRequestSequence::getTenantId, "default")
                .eq(ServiceRequestSequence::getRequestMonth, month)
                .set(ServiceRequestSequence::getLastNumber, nextVal));
    }
}
