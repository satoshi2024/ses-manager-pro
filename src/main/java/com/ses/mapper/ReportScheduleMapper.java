package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ReportSchedule;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 管理レポートschedule mapper。due claimはnext_run_atのCASで行う。 */
@Mapper
public interface ReportScheduleMapper extends BaseMapper<ReportSchedule> {

    @Select("SELECT * FROM m_report_schedule WHERE enabled = 1 AND deleted_flag = 0 "
            + "AND next_run_at IS NOT NULL AND next_run_at <= #{now} ORDER BY next_run_at, id LIMIT #{limit}")
    List<ReportSchedule> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("UPDATE m_report_schedule SET next_run_at = #{nextRunAt}, last_run_at = #{runAt}, "
            + "updated_at = #{runAt} WHERE id = #{id} AND enabled = 1 AND deleted_flag = 0 "
            + "AND next_run_at = #{expectedNextRunAt}")
    int claimDue(@Param("id") Long id, @Param("expectedNextRunAt") LocalDateTime expectedNextRunAt,
                 @Param("nextRunAt") LocalDateTime nextRunAt, @Param("runAt") LocalDateTime runAt);
}
