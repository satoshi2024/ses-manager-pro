package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.CustomerContact;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

@Mapper
public interface CustomerContactMapper extends BaseMapper<CustomerContact> {

    /** 顧客をJOINしてtenant ownershipをSQL境界で検証する。 */
    @Select("""
        <script>
        SELECT cc.*
          FROM t_customer_contact cc
          INNER JOIN m_customer c ON c.id = cc.customer_id
                                 AND c.deleted_flag = 0
                                 AND c.tenant_id = #{tenantId}
         WHERE cc.customer_id = #{customerId}
           AND cc.deleted_flag = 0
           <if test="status != null and status != ''">AND cc.status = #{status}</if>
           <if test="asOf != null">
             AND cc.valid_from &lt;= #{asOf}
             AND (cc.valid_to IS NULL OR cc.valid_to &gt;= #{asOf})
           </if>
           <if test="role != null and role != ''">AND cc.roles_json LIKE CONCAT('%\"', #{role}, '\"%')</if>
         ORDER BY cc.valid_from DESC, cc.id DESC
        </script>
        """)
    List<CustomerContact> selectListForTenant(@Param("customerId") Long customerId,
                                              @Param("tenantId") String tenantId,
                                              @Param("status") String status,
                                              @Param("asOf") LocalDate asOf,
                                              @Param("role") String role);

    /** 親顧客ロック取得後の重複判定を、REPEATABLE READの古いsnapshotではなく現在値で行う。 */
    @Select("""
        <script>
        SELECT cc.*
          FROM t_customer_contact cc
          INNER JOIN m_customer c ON c.id = cc.customer_id
                                 AND c.deleted_flag = 0
                                 AND c.tenant_id = #{tenantId}
         WHERE cc.customer_id = #{customerId}
           AND cc.deleted_flag = 0
           <if test="status != null and status != ''">AND cc.status = #{status}</if>
         ORDER BY cc.valid_from DESC, cc.id DESC
         FOR UPDATE
        </script>
        """)
    List<CustomerContact> selectListForUpdateForTenant(@Param("customerId") Long customerId,
                                                       @Param("tenantId") String tenantId,
                                                       @Param("status") String status);

    @Select("SELECT cc.* FROM t_customer_contact cc "
            + "INNER JOIN m_customer c ON c.id = cc.customer_id AND c.deleted_flag = 0 "
            + "AND c.tenant_id = #{tenantId} "
            + "WHERE cc.id = #{contactId} AND cc.customer_id = #{customerId} AND cc.deleted_flag = 0")
    CustomerContact selectByIdForTenant(@Param("contactId") Long contactId,
                                       @Param("customerId") Long customerId,
                                       @Param("tenantId") String tenantId);

    @Select("SELECT cc.* FROM t_customer_contact cc "
            + "INNER JOIN m_customer c ON c.id = cc.customer_id AND c.deleted_flag = 0 "
            + "AND c.tenant_id = #{tenantId} "
            + "WHERE cc.id = #{contactId} AND cc.customer_id = #{customerId} AND cc.deleted_flag = 0 FOR UPDATE")
    CustomerContact selectForUpdateForTenant(@Param("contactId") Long contactId,
                                             @Param("customerId") Long customerId,
                                             @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_customer_contact cc
           SET name = #{request.name}, name_kana = #{request.nameKana},
               department = #{request.department}, position = #{request.position},
               roles_json = #{rolesJson}, email = #{email}, phone = #{phone},
               primary_flag = #{primaryFlag}, valid_from = #{request.validFrom},
               valid_to = #{request.validTo}, status = #{request.status},
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE cc.id = #{contactId} AND cc.customer_id = #{customerId}
           AND cc.version = #{expectedVersion} AND cc.deleted_flag = 0
           AND EXISTS (SELECT 1 FROM m_customer c WHERE c.id = cc.customer_id
                       AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0)
        """)
    int updateByIdForTenant(@Param("contactId") Long contactId,
                            @Param("customerId") Long customerId,
                            @Param("tenantId") String tenantId,
                            @Param("expectedVersion") Integer expectedVersion,
                            @Param("request") com.ses.dto.customer.CustomerContactSaveRequest request,
                            @Param("rolesJson") String rolesJson,
                            @Param("email") String email,
                            @Param("phone") String phone,
                            @Param("primaryFlag") Integer primaryFlag);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_customer_contact cc
           SET status = '退職', valid_to = #{validTo}, primary_flag = 0,
               version = version + 1, updated_at = CURRENT_TIMESTAMP
         WHERE cc.id = #{contactId} AND cc.customer_id = #{customerId}
           AND cc.version = #{expectedVersion} AND cc.deleted_flag = 0
           AND EXISTS (SELECT 1 FROM m_customer c WHERE c.id = cc.customer_id
                       AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0)
        """)
    int retireForTenant(@Param("contactId") Long contactId,
                        @Param("customerId") Long customerId,
                        @Param("tenantId") String tenantId,
                        @Param("expectedVersion") Integer expectedVersion,
                        @Param("validTo") LocalDate validTo);
}
