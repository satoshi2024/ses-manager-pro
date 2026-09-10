package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.TrainingCourse;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface TrainingCourseMapper extends BaseMapper<TrainingCourse> {

    /** 更新前にtenantとversionを同時に固定する。 */
    @Select("SELECT * FROM m_training_course WHERE id = #{id} AND tenant_id = #{tenantId} "
            + "AND deleted_flag = 0 FOR UPDATE")
    TrainingCourse selectForUpdateByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    /** course本体とversionをtenant・期待version付きでCAS更新する。 */
    @Update("UPDATE m_training_course SET provider = #{provider}, name = #{name}, description = #{description}, "
            + "cost_jpy = #{costJpy}, period_days = #{periodDays}, capacity = #{capacity}, "
            + "active_flag = #{activeFlag}, updated_by = #{actorUserId}, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND version = #{expectedVersion} "
            + "AND deleted_flag = 0")
    int updateForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                        @Param("expectedVersion") Integer expectedVersion,
                        @Param("provider") String provider, @Param("name") String name,
                        @Param("description") String description, @Param("costJpy") java.math.BigDecimal costJpy,
                        @Param("periodDays") Integer periodDays, @Param("capacity") Integer capacity,
                        @Param("activeFlag") Integer activeFlag, @Param("actorUserId") Long actorUserId);

    /** course無効化も同じCAS境界で行う。 */
    @Update("UPDATE m_training_course SET active_flag = 0, updated_by = #{actorUserId}, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{id} AND tenant_id = #{tenantId} AND version = #{expectedVersion} "
            + "AND deleted_flag = 0")
    int deactivateForTenant(@Param("id") Long id, @Param("tenantId") String tenantId,
                            @Param("expectedVersion") Integer expectedVersion,
                            @Param("actorUserId") Long actorUserId);
}
