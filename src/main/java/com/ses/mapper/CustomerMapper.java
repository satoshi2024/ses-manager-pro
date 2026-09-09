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
    /** 顧客自身に保存された明示的tenant ownershipから顧客IDを解決する。 */
    @Select("SELECT id FROM m_customer "
            + "WHERE tenant_id = #{tenantId} AND deleted_flag = 0")
    Set<Long> selectOwnedCustomerIds(@Param("tenantId") String tenantId);

    /** 顧客IDの存在確認もtenant ownershipと同じSQL境界で行う。 */
    @Select("SELECT c.* FROM m_customer c "
            + "WHERE c.id = #{customerId} AND c.deleted_flag = 0 "
            + "AND c.tenant_id = #{tenantId}")
    Customer selectByIdForTenant(@Param("customerId") Long customerId,
                                 @Param("tenantId") String tenantId);

    /** 顧客一覧をtenant所有顧客へ限定する。呼出元のDataScope条件は別途追加する。 */
    @Select("""
        <script>
        SELECT c.* FROM m_customer c
        WHERE c.deleted_flag = 0
          AND c.tenant_id = #{tenantId}
          <choose>
            <when test="customerIds != null and customerIds.size() > 0">
              AND c.id IN
              <foreach collection="customerIds" item="customerId" open="(" separator="," close=")">
                  #{customerId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
        </script>
        """)
    List<Customer> selectListForOwnedIds(@Param("customerIds") Set<Long> customerIds,
                                         @Param("tenantId") String tenantId);

    /** 会社名検索もpermission母集団と同じSQL内で実行する。 */
    @Select("""
        <script>
        SELECT c.* FROM m_customer c
        WHERE c.deleted_flag = 0
          AND c.tenant_id = #{tenantId}
          <choose>
            <when test="customerIds != null and customerIds.size() > 0">
              AND c.id IN
              <foreach collection="customerIds" item="customerId" open="(" separator="," close=")">
                  #{customerId}
              </foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
          <if test="keyword != null and keyword != ''">
            AND LOWER(c.company_name) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
          </if>
        ORDER BY c.id DESC
        </script>
        """)
    List<Customer> selectListForOwnedIdsByKeyword(@Param("customerIds") Set<Long> customerIds,
                                                  @Param("tenantId") String tenantId,
                                                  @Param("keyword") String keyword);

    @Select("SELECT * FROM m_customer WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Customer selectByIdForUpdate(Long id);
}
