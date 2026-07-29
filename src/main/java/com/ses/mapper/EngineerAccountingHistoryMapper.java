package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.EngineerAccountingHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDate;

/** 要員の会計属性履歴Mapper。 */
@Mapper
public interface EngineerAccountingHistoryMapper extends BaseMapper<EngineerAccountingHistory> {

    /** 現行版（valid_to IS NULL）を指定日の前日で締める。 */
    @Update("UPDATE t_engineer_accounting_history SET valid_to = #{validTo} "
            + "WHERE engineer_id = #{engineerId} AND valid_to IS NULL AND deleted_flag = 0")
    int closeCurrent(@Param("engineerId") Long engineerId, @Param("validTo") LocalDate validTo);

    /** 現行版を1件返す。無ければnull。 */
    @Select("SELECT * FROM t_engineer_accounting_history WHERE engineer_id = #{engineerId} "
            + "AND valid_to IS NULL AND deleted_flag = 0 ORDER BY id DESC LIMIT 1")
    EngineerAccountingHistory selectCurrent(@Param("engineerId") Long engineerId);

    /** 対象月時点の要員会計履歴を1件返す。履歴が無ければnull。 */
    @Select("SELECT * FROM t_engineer_accounting_history WHERE engineer_id = #{engineerId} "
            + "AND valid_from <= #{asOf} AND (valid_to IS NULL OR valid_to >= #{asOf}) "
            + "AND deleted_flag = 0 ORDER BY valid_from DESC, id DESC LIMIT 1")
    EngineerAccountingHistory selectAt(@Param("engineerId") Long engineerId,
                                       @Param("asOf") LocalDate asOf);

    /**
     * 指定組織が現行版として記録されている要員の会計履歴を返す。
     *
     * <p>組織統合（{@link com.ses.service.impl.OrganizationServiceImpl#merge}）で
     * {@code t_engineer.organization_id} を一括付け替えたあと、履歴側も同じ統合日から
     * 新しい所属組織を記録するために使う。現在値だけを書き換えると、統合前の日付で
     * Bench待機原価を照会したときに統合後の組織が焼き付いてしまう。
     */
    @Select("SELECT * FROM t_engineer_accounting_history WHERE organization_id = #{organizationId} "
            + "AND valid_to IS NULL AND deleted_flag = 0")
    java.util.List<EngineerAccountingHistory> selectCurrentByOrganizationId(
            @Param("organizationId") Long organizationId);

}
