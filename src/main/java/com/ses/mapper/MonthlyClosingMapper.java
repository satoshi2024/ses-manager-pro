package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.MonthlyClosing;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 月次締め（tenant×月）の行ロック・CAS更新。 */
@Mapper
public interface MonthlyClosingMapper extends BaseMapper<MonthlyClosing> {

    /** ロック対象行を確保する（未作成なら未締め行を挿入）。 */
    @Insert("INSERT INTO t_monthly_closing (tenant_id, work_month, version) "
            + "VALUES (#{tenantId}, #{workMonth}, 0) "
            + "ON DUPLICATE KEY UPDATE tenant_id = tenant_id")
    int ensureRow(@Param("tenantId") String tenantId, @Param("workMonth") String workMonth);

    @Select("SELECT * FROM t_monthly_closing "
            + "WHERE tenant_id = #{tenantId} AND work_month = #{workMonth} FOR UPDATE")
    MonthlyClosing selectForUpdate(@Param("tenantId") String tenantId,
                                   @Param("workMonth") String workMonth);

    @Select("SELECT * FROM t_monthly_closing "
            + "WHERE tenant_id = #{tenantId} AND work_month = #{workMonth}")
    MonthlyClosing selectByTenantAndMonth(@Param("tenantId") String tenantId,
                                          @Param("workMonth") String workMonth);

    @Select("SELECT work_month FROM t_monthly_closing "
            + "WHERE tenant_id = #{tenantId} AND confirmed_at IS NOT NULL "
            + "ORDER BY work_month")
    List<String> selectClosedMonths(@Param("tenantId") String tenantId);

    @Update("UPDATE t_monthly_closing "
            + "SET confirmed_by = #{confirmedBy}, confirmed_at = #{confirmedAt}, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND work_month = #{workMonth} "
            + "AND version = #{expectedVersion} AND confirmed_at IS NULL")
    int confirmCas(@Param("tenantId") String tenantId,
                   @Param("workMonth") String workMonth,
                   @Param("confirmedBy") Long confirmedBy,
                   @Param("confirmedAt") LocalDateTime confirmedAt,
                   @Param("expectedVersion") Integer expectedVersion);

    @Update("UPDATE t_monthly_closing "
            + "SET confirmed_by = NULL, confirmed_at = NULL, "
            + "version = version + 1, updated_at = CURRENT_TIMESTAMP "
            + "WHERE tenant_id = #{tenantId} AND work_month = #{workMonth} "
            + "AND version = #{expectedVersion} AND confirmed_at IS NOT NULL")
    int reopenCas(@Param("tenantId") String tenantId,
                  @Param("workMonth") String workMonth,
                  @Param("expectedVersion") Integer expectedVersion);
}
