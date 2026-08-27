package com.ses.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.LifecycleEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * ライフサイクルイベントマッパー
 *
 * <p>{@code t_lifecycle_event} は追記専用（イミュータブル）の監査台帳です。
 * UPDATE/DELETE は業務ルール上禁止されています。更新が必要な場合は新規イベントを INSERT して
 * 訂正として追記してください（完全訂正は「TASK_CORRECTION」イベントで表現）。
 */
@Mapper
public interface LifecycleEventMapper extends BaseMapper<LifecycleEvent> {

    @Select("SELECT * FROM t_lifecycle_event WHERE case_id = #{caseId} ORDER BY occurred_at ASC, id ASC")
    List<LifecycleEvent> selectByCaseId(@Param("caseId") Long caseId);

    @Select("SELECT * FROM t_lifecycle_event WHERE task_id = #{taskId} ORDER BY occurred_at ASC, id ASC")
    List<LifecycleEvent> selectByTaskId(@Param("taskId") Long taskId);

    /** イミュータブル保護: DELETE は禁止 */
    @Override
    default int deleteById(java.io.Serializable id) {
        throw new UnsupportedOperationException("t_lifecycle_event はイミュータブルです。DELETE は禁止されています。");
    }

    /** イミュータブル保護: DELETE は禁止 */
    @Override
    default int deleteById(LifecycleEvent entity) {
        throw new UnsupportedOperationException("t_lifecycle_event はイミュータブルです。DELETE は禁止されています。");
    }

    /** イミュータブル保護: WHERE 条件付き DELETE は禁止 */
    @Override
    default int delete(Wrapper<LifecycleEvent> queryWrapper) {
        throw new UnsupportedOperationException("t_lifecycle_event はイミュータブルです。DELETE は禁止されています。");
    }

    /** イミュータブル保護: UPDATE は禁止。訂正はイベント追記で行うこと。 */
    @Override
    default int updateById(LifecycleEvent entity) {
        throw new UnsupportedOperationException("t_lifecycle_event はイミュータブルです。UPDATE は禁止されています。訂正はイベント追記（TASK_CORRECTION）で行ってください。");
    }

    /** イミュータブル保護: WHERE 条件付き UPDATE は禁止 */
    @Override
    default int update(LifecycleEvent entity, Wrapper<LifecycleEvent> updateWrapper) {
        throw new UnsupportedOperationException("t_lifecycle_event はイミュータブルです。UPDATE は禁止されています。訂正はイベント追記（TASK_CORRECTION）で行ってください。");
    }
}
