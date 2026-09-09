package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.CustomerQbr;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Collection;
import java.util.List;

/**
 * 定例会・QBR記録マッパー
 */
@Mapper
public interface CustomerQbrMapper extends BaseMapper<CustomerQbr> {

    /** 顧客ヘルス集計用。QBR自身にtenantを持たせず、顧客ownershipで限定する。 */
    @Select("""
        <script>
        SELECT q.* FROM t_customer_qbr q
        INNER JOIN m_customer c ON c.id = q.customer_id
                               AND c.tenant_id = #{tenantId}
                               AND c.deleted_flag = 0
        WHERE q.customer_id IN
        <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY q.meeting_date DESC, q.id DESC
        </script>
        """)
    List<CustomerQbr> selectByCustomerIdsForTenant(@Param("customerIds") Collection<Long> customerIds,
                                                   @Param("tenantId") String tenantId);

    @Select("""
        <script>
        SELECT q.* FROM t_customer_qbr q
        INNER JOIN m_customer c ON c.id = q.customer_id
                               AND c.tenant_id = #{tenantId}
                               AND c.deleted_flag = 0
        WHERE <choose>
          <when test="customerId != null">q.customer_id = #{customerId}</when>
          <when test="customerIds != null and customerIds.size() > 0">
            q.customer_id IN <foreach collection="customerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
          </when>
          <otherwise>1 = 0</otherwise>
        </choose>
        <if test="keyword != null and keyword != ''">
          AND (LOWER(q.title) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
            OR LOWER(q.agenda) LIKE LOWER(CONCAT('%', #{keyword}, '%'))
            OR LOWER(q.discussion) LIKE LOWER(CONCAT('%', #{keyword}, '%')))
        </if>
        ORDER BY q.meeting_date DESC, q.id DESC
        </script>
        """)
    Page<CustomerQbr> selectPageForTenant(Page<CustomerQbr> page,
                                          @Param("tenantId") String tenantId,
                                          @Param("customerIds") Collection<Long> customerIds,
                                          @Param("customerId") Long customerId,
                                          @Param("keyword") String keyword);

    @Select("SELECT q.* FROM t_customer_qbr q INNER JOIN m_customer c ON c.id = q.customer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE q.id = #{id} AND q.customer_id = #{customerId}")
    CustomerQbr selectByIdForTenant(@Param("id") Long id,
                                   @Param("customerId") Long customerId,
                                   @Param("tenantId") String tenantId);

    @Select("SELECT q.* FROM t_customer_qbr q INNER JOIN m_customer c ON c.id = q.customer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 WHERE q.id = #{id}")
    CustomerQbr selectByIdForTenantId(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Update("UPDATE t_customer_qbr SET meeting_date = #{qbr.meetingDate}, title = #{qbr.title}, "
            + "agenda = #{qbr.agenda}, discussion = #{qbr.discussion}, decisions = #{qbr.decisions}, "
            + "attendees = #{qbr.attendees}, updated_by = #{qbr.updatedBy}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{qbr.id} AND customer_id = #{qbr.customerId} "
            + "AND EXISTS (SELECT 1 FROM m_customer c WHERE c.id = t_customer_qbr.customer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0)")
    int updateByIdForTenant(@Param("qbr") CustomerQbr qbr, @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM t_customer_qbr WHERE id = #{id} "
            + "AND customer_id = #{customerId} AND EXISTS (SELECT 1 FROM m_customer c "
            + "WHERE c.id = t_customer_qbr.customer_id AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0)")
    int deleteByIdForTenant(@Param("id") Long id, @Param("customerId") Long customerId,
                            @Param("tenantId") String tenantId);
}
