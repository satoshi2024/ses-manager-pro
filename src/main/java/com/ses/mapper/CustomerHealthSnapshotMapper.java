package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.CustomerHealthSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 顧客ヘルススナップショットマッパー
 */
@Mapper
public interface CustomerHealthSnapshotMapper extends BaseMapper<CustomerHealthSnapshot> {

    /** 同一顧客・対象月の版採番を直列化する。 */
    @Select("SELECT s.* FROM t_customer_health_snapshot s "
            + "WHERE s.customer_id = #{customerId} AND s.snapshot_date = #{snapshotDate} "
            + "AND EXISTS (SELECT 1 FROM t_service_request r "
            + "WHERE r.customer_id = s.customer_id AND r.tenant_id = #{tenantId}) "
            + "ORDER BY version_no DESC LIMIT 1 FOR UPDATE")
    CustomerHealthSnapshot selectLatestForUpdate(@Param("customerId") Long customerId,
                                                  @Param("snapshotDate") java.time.LocalDate snapshotDate,
                                                  @Param("tenantId") String tenantId);
}
