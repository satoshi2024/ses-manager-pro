package com.ses.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;
import java.util.List;

/** ライフサイクル案件番号をtenant・月単位の行ロックで採番する。 */
@Mapper
public interface LifecycleCaseSequenceMapper {

    @Select("SELECT case_no FROM t_lifecycle_case WHERE tenant_id = #{tenantId} "
            + "AND anchor_date >= #{monthStart} AND anchor_date < #{nextMonth}")
    List<String> selectExistingCaseNos(@Param("tenantId") String tenantId,
                                       @Param("monthStart") LocalDate monthStart,
                                       @Param("nextMonth") LocalDate nextMonth);

    @Insert("INSERT INTO t_lifecycle_case_sequence (tenant_id, case_month, last_number) "
            + "VALUES (#{tenantId}, #{caseMonth}, #{initialNumber}) "
            + "ON DUPLICATE KEY UPDATE last_number = GREATEST(last_number, #{initialNumber})")
    int ensureRow(@Param("tenantId") String tenantId, @Param("caseMonth") String caseMonth,
                  @Param("initialNumber") long initialNumber);

    @Update("UPDATE t_lifecycle_case_sequence SET last_number = last_number + 1, "
            + "updated_at = CURRENT_TIMESTAMP WHERE tenant_id = #{tenantId} "
            + "AND case_month = #{caseMonth} AND last_number < #{maximumNumber}")
    int incrementIfAvailable(@Param("tenantId") String tenantId, @Param("caseMonth") String caseMonth,
                             @Param("maximumNumber") long maximumNumber);

    @Select("SELECT last_number FROM t_lifecycle_case_sequence "
            + "WHERE tenant_id = #{tenantId} AND case_month = #{caseMonth}")
    Long selectLastNumber(@Param("tenantId") String tenantId, @Param("caseMonth") String caseMonth);
}
