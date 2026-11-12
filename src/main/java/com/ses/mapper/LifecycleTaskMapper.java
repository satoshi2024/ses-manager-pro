package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ライフサイクルタスクMapper。
 *
 * <p>行ロック順は「案件 → タスク（id昇順）」に統一する（NF01 batch 0）。
 * 以前の {@code t_lifecycle_task JOIN t_lifecycle_case ... FOR UPDATE} は実行計画次第で
 * タスク行を先にロックし、案件を先にロックする cancelCase / completeCase と逆順になり得たため廃止した。
 * タスクをロックする前に、呼出し側は必ず {@code LifecycleCaseMapper.selectByIdForUpdateAndTenant}
 * で所属案件をロックすること。</p>
 */
@Mapper
public interface LifecycleTaskMapper extends BaseMapper<LifecycleTask> {

    @Select("SELECT t.* FROM t_lifecycle_task t INNER JOIN t_lifecycle_case c ON c.id = t.case_id "
            + "WHERE t.id = #{id} AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 AND t.deleted_flag = 0")
    LifecycleTask selectByIdAndTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    /**
     * 既にロック済みの案件配下にあるタスク1行だけを行ロックする（joinしない）。
     * 案件のtenant検証とロックは呼出し側で先に済ませる前提。
     */
    @Select("SELECT * FROM t_lifecycle_task WHERE id = #{id} AND case_id = #{caseId} AND deleted_flag = 0 FOR UPDATE")
    LifecycleTask selectByIdAndCaseIdForUpdate(@Param("id") Long id, @Param("caseId") Long caseId);

    @Select("SELECT * FROM t_lifecycle_task WHERE case_id = #{caseId} AND deleted_flag = 0 ORDER BY id ASC")
    List<LifecycleTask> selectByCaseId(@Param("caseId") Long caseId);

    @Select("SELECT t.* FROM t_lifecycle_task t "
            + "INNER JOIN t_lifecycle_case c ON c.id = t.case_id AND c.tenant_id = #{tenantId} "
            + "WHERE t.case_id = #{caseId} AND t.deleted_flag = 0 ORDER BY t.id ASC")
    List<LifecycleTask> selectByCaseIdAndTenant(@Param("caseId") Long caseId,
                                                @Param("tenantId") String tenantId);

    @Select("SELECT t.* FROM t_lifecycle_task t INNER JOIN t_lifecycle_case c ON c.id = t.case_id "
            + "WHERE c.tenant_id = #{tenantId} AND c.deleted_flag = 0 AND t.deleted_flag = 0 "
            + "AND t.status IN ('PENDING','IN_PROGRESS') "
            + "AND (t.assignee_user_id = #{userId} OR t.assignee_role = #{role}) ORDER BY t.due_date, t.id")
    List<LifecycleTask> selectMyPendingByTenant(@Param("tenantId") String tenantId,
                                               @Param("userId") Long userId,
                                               @Param("role") String role);

    @Select("SELECT * FROM t_lifecycle_task WHERE case_id = #{caseId} AND is_engineer_visible = 1 AND deleted_flag = 0 ORDER BY id ASC")
    List<LifecycleTask> selectEngineerVisibleByCaseId(@Param("caseId") Long caseId);
}
