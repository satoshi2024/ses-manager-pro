package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {
    /** サービスデスクのtenant所有権（tenant付きservice request）から顧客IDを解決する。 */
    @Select("SELECT DISTINCT customer_id FROM t_service_request "
            + "WHERE tenant_id = #{tenantId} AND customer_id IS NOT NULL")
    Set<Long> selectOwnedCustomerIds(@Param("tenantId") String tenantId);

    /** 顧客IDの存在確認もtenant所有権と同じSQL境界で行う。 */
    @Select("SELECT c.* FROM m_customer c "
            + "WHERE c.id = #{customerId} AND c.deleted_flag = 0 "
            + "AND EXISTS (SELECT 1 FROM t_service_request r "
            + "WHERE r.customer_id = c.id AND r.tenant_id = #{tenantId})")
    Customer selectByIdForTenant(@Param("customerId") Long customerId,
                                 @Param("tenantId") String tenantId);

    /** 顧客一覧をtenant所有顧客へ限定する。呼出元のDataScope条件は別途追加する。 */
    @Select("""
        <script>
        SELECT c.* FROM m_customer c
        WHERE c.deleted_flag = 0
          AND c.id IN
          <foreach collection="customerIds" item="customerId" open="(" separator="," close=")">
              #{customerId}
          </foreach>
          AND EXISTS (SELECT 1 FROM t_service_request r
                      WHERE r.customer_id = c.id AND r.tenant_id = #{tenantId})
        </script>
        """)
    List<Customer> selectListForOwnedIds(@Param("customerIds") Set<Long> customerIds,
                                         @Param("tenantId") String tenantId);

    @Select("SELECT * FROM m_customer WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Customer selectByIdForUpdate(Long id);
}
