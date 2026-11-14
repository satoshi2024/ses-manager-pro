package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface LifecycleCaseMapper extends BaseMapper<LifecycleCase> {

    /** 要員行を先にロックした呼出元から利用する。後続の再入社案件は未完了でも自動失効を止める。 */
    @Select("SELECT c.* FROM t_lifecycle_case c WHERE c.tenant_id = #{tenantId} "
            + "AND c.engineer_id = #{engineerId} AND c.deleted_flag = 0 AND c.status = 'COMPLETED' "
            + "AND c.lifecycle_type = 'RESIGNATION' "
            + "AND NOT EXISTS (SELECT 1 FROM t_lifecycle_case newer WHERE newer.tenant_id = c.tenant_id "
            + "AND newer.engineer_id = c.engineer_id AND newer.deleted_flag = 0 AND newer.id > c.id "
            + "AND (newer.status IN ('COMPLETED','CANCELLED') OR newer.lifecycle_type IN ('JOIN','REINSTATEMENT'))) "
            + "ORDER BY c.id DESC LIMIT 1")
    LifecycleCase selectLatestCompletedResignationWithoutReentry(@Param("tenantId") String tenantId,
                                                                 @Param("engineerId") Long engineerId);

    @Select("SELECT * FROM t_lifecycle_case WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    LifecycleCase selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_lifecycle_case WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0")
    LifecycleCase selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT * FROM t_lifecycle_case WHERE id = #{id} AND tenant_id = #{tenantId} AND deleted_flag = 0 FOR UPDATE")
    LifecycleCase selectByIdForUpdateAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT l.* FROM t_lifecycle_case l "
            + "INNER JOIN t_engineer_certification c ON c.engineer_id = l.engineer_id "
            + "AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "WHERE l.engineer_id = #{engineerId} AND l.tenant_id = #{tenantId} "
            + "AND l.deleted_flag = 0 ORDER BY l.anchor_date, l.id")
    java.util.List<LifecycleCase> selectByEngineerIdAndTenant(@Param("engineerId") Long engineerId,
                                                               @Param("tenantId") String tenantId);

}
