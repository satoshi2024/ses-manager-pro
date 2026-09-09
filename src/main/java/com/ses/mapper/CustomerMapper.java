package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.Customer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Set;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer> {

    @org.apache.ibatis.annotations.Update("UPDATE m_customer SET tenant_id = #{tenantId} "
            + "WHERE id = #{id} AND tenant_id IS NULL AND deleted_flag = 0")
    int assignTenantForRepair(@org.apache.ibatis.annotations.Param("id") Long id,
                              @org.apache.ibatis.annotations.Param("tenantId") String tenantId);

    /** 顧客一覧はtenant ownershipと検索条件を同一SQLで適用する。 */
    @Select("""
        <script>
        SELECT c.* FROM m_customer c
        WHERE c.deleted_flag = 0
          AND c.tenant_id = #{tenantId}
          <choose>
            <when test="customerIds != null and customerIds.size() > 0">
              AND c.id IN
              <foreach collection="customerIds" item="customerId" open="(" separator="," close=")">#{customerId}</foreach>
            </when>
            <otherwise>AND 1 = 0</otherwise>
          </choose>
          <if test="companyName != null and companyName != ''">
            AND LOWER(c.company_name) LIKE LOWER(CONCAT('%', #{companyName}, '%'))
          </if>
          <if test="commercialFlow != null and commercialFlow != ''">AND c.commercial_flow = #{commercialFlow}</if>
          <if test="trustLevel != null and trustLevel != ''">AND c.trust_level = #{trustLevel}</if>
        ORDER BY c.id DESC
        </script>
        """)
    Page<Customer> selectPageForTenant(Page<Customer> page,
                                       @Param("tenantId") String tenantId,
                                       @Param("customerIds") Set<Long> customerIds,
                                       @Param("companyName") String companyName,
                                       @Param("commercialFlow") String commercialFlow,
                                       @Param("trustLevel") String trustLevel);
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

    /** tenant・ID・versionを含む顧客CAS更新。NULL ownershipの行は更新できない。 */
    @Update("""
        <script>
        UPDATE m_customer
           SET company_name = #{customer.companyName},
               company_name_kana = #{customer.companyNameKana},
               contact_person = #{customer.contactPerson},
               contact_email = #{customer.contactEmail},
               contact_phone = #{customer.contactPhone},
               address = #{customer.address},
               commercial_flow = #{customer.commercialFlow},
               trust_level = #{customer.trustLevel},
               delivery_preference = #{customer.deliveryPreference},
               remarks = #{customer.remarks},
               updated_at = CURRENT_TIMESTAMP,
               version = version + 1
         WHERE id = #{customer.id}
           AND tenant_id = #{tenantId}
           AND version = #{expectedVersion}
           AND deleted_flag = 0
        </script>
        """)
    int updateByIdForTenant(@Param("customer") Customer customer,
                            @Param("tenantId") String tenantId,
                            @Param("expectedVersion") Integer expectedVersion);

    /** tenant・ID・versionを含む論理削除CAS。 */
    @Update("UPDATE m_customer SET deleted_flag = 1, version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND version = #{expectedVersion} AND deleted_flag = 0")
    int deleteByIdForTenant(@Param("id") Long id,
                            @Param("tenantId") String tenantId,
                            @Param("expectedVersion") Integer expectedVersion);

    @Select("SELECT * FROM m_customer WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Customer selectByIdForUpdate(Long id);

    @Select("SELECT * FROM m_customer WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 FOR UPDATE")
    Customer selectByIdForUpdateForTenant(@Param("id") Long id,
                                          @Param("tenantId") String tenantId);
}
