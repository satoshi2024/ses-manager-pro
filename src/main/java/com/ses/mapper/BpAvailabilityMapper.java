package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.BpAvailability;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 外部要員在庫マッパー
 */
@Mapper
public interface BpAvailabilityMapper extends BaseMapper<BpAvailability> {

    @Select("""
        <script>
        SELECT a.*
          FROM t_bp_availability a
         WHERE a.tenant_id = #{tenantId}
           AND a.deleted_flag = 0
           <if test="status != null and status != ''">AND a.status = #{status}</if>
           <if test="status == null or status == ''">AND a.status NOT IN ('未確認', '却下')</if>
         ORDER BY a.created_at DESC, a.id DESC
        </script>
        """)
    Page<BpAvailability> selectPageForTenant(Page<BpAvailability> page,
                                              @Param("tenantId") String tenantId,
                                              @Param("status") String status);

    @Select("SELECT * FROM t_bp_availability WHERE id = #{id} "
            + "AND tenant_id = #{tenantId} AND deleted_flag = 0")
    BpAvailability selectByIdForTenant(@Param("id") Long id,
                                       @Param("tenantId") String tenantId);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_bp_availability
           SET initial_name = #{availability.initialName},
               skills_json = #{availability.skillsJson},
               unit_price = #{availability.unitPrice},
               available_from = #{availability.availableFrom},
               experience_years = #{availability.experienceYears},
               status = #{availability.status},
               promoted_engineer_id = #{availability.promotedEngineerId},
               remarks = #{availability.remarks},
               updated_at = CURRENT_TIMESTAMP
         WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0
        """)
    int updateByIdForTenant(@Param("id") Long id,
                            @Param("tenantId") String tenantId,
                            @Param("availability") BpAvailability availability);

    @org.apache.ibatis.annotations.Update("""
        UPDATE t_bp_availability SET deleted_flag = 1, updated_at = CURRENT_TIMESTAMP
         WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0
        """)
    int removeByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_bp_availability WHERE tenant_id = #{tenantId} "
            + "AND status = #{status} AND deleted_flag = 0 ORDER BY id")
    List<BpAvailability> selectAvailableForTenant(@Param("tenantId") String tenantId,
                                                   @Param("status") String status);

    @org.apache.ibatis.annotations.Update("UPDATE t_bp_availability SET tenant_id = #{tenantId} "
            + "WHERE id = #{id} AND tenant_id IS NULL AND deleted_flag = 0")
    int assignTenantForRepair(@Param("id") Long id, @Param("tenantId") String tenantId);
}
