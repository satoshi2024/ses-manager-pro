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
    @Select("SELECT * FROM t_customer_health_snapshot "
            + "WHERE customer_id = #{customerId} AND snapshot_date = #{snapshotDate} "
            + "ORDER BY version_no DESC LIMIT 1 FOR UPDATE")
    CustomerHealthSnapshot selectLatestForUpdate(@Param("customerId") Long customerId,
                                                  @Param("snapshotDate") java.time.LocalDate snapshotDate);
}
