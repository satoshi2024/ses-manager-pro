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
    
    @Delete("DELETE FROM t_freee_employee_link WHERE deleted_flag = 1 AND (engineer_id = #{engineerId} OR freee_employee_id = #{employeeId})")
    void deleteSoftDeletedConflicts(@Param("engineerId") Long engineerId, @Param("employeeId") String employeeId);
}
