package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.CustomerCsat;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 顧客満足度調査回答マッパー
 */
@Mapper
public interface CustomerCsatMapper extends BaseMapper<CustomerCsat> {

    /** 顧客ヘルス集計用。顧客とrequestの双方を同一tenantで検証する。 */
    @Select("""
        <script>
        SELECT cs.*
          FROM t_customer_csat cs
          INNER JOIN m_customer c ON c.id = cs.customer_id
                                 AND c.tenant_id = #{tenantId}
                                 AND c.deleted_flag = 0
          INNER JOIN t_service_request r ON r.id = cs.service_request_id
                                         AND r.customer_id = cs.customer_id
                                         AND r.tenant_id = #{tenantId}
        WHERE cs.answered_at &gt;= #{from}
          AND cs.customer_id IN
          <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          AND cs.service_request_id IN
          <choose>
            <when test="requestIds != null and requestIds.size() > 0">
              <foreach collection="requestIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </when>
            <otherwise>(NULL)</otherwise>
          </choose>
        ORDER BY cs.answered_at DESC, cs.id DESC
        </script>
        """)
    List<CustomerCsat> selectByCustomersForTenant(@Param("customerIds") Collection<Long> customerIds,
                                                  @Param("requestIds") Collection<Long> requestIds,
                                                  @Param("from") LocalDateTime from,
                                                  @Param("tenantId") String tenantId);
}
