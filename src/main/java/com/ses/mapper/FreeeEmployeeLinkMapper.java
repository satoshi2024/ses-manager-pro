package com.ses.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.FreeeEmployeeLink;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper 
public interface FreeeEmployeeLinkMapper extends BaseMapper<FreeeEmployeeLink> {
    
    @Delete("DELETE FROM t_freee_employee_link WHERE engineer_id = #{engineerId}")
    void deleteByEngineerIdHard(@Param("engineerId") Long engineerId);
    
    /** 再対応付け前に、現在companyのsoft delete済み競合行だけを物理削除する（他companyは巻き込まない）。 */
    @Delete("DELETE FROM t_freee_employee_link WHERE deleted_flag = 1 "
            + "AND (engineer_id = #{engineerId} "
            + "  OR (freee_company_id = #{companyId} AND freee_employee_id = #{employeeId}))")
    void deleteSoftDeletedConflicts(@Param("engineerId") Long engineerId,
                                    @Param("employeeId") String employeeId,
                                    @Param("companyId") Long companyId);
}
