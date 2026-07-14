package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.analytics.ContractDateRangeDto;
import com.ses.entity.Contract;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 契約マッパー
 */
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.dto.contract.ContractListDto;
import java.time.LocalDate;

@Mapper
public interface ContractMapper extends BaseMapper<Contract> {

    @Select("SELECT engineer_id, start_date, end_date FROM t_contract " +
            "WHERE deleted_flag = 0 AND status IN ('稼動中','終了') AND engineer_id IS NOT NULL AND start_date IS NOT NULL")
    List<ContractDateRangeDto> selectActiveDateRanges();

    @Select("SELECT MAX(contract_no) FROM t_contract WHERE contract_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxContractNoIncludingDeleted(@org.apache.ibatis.annotations.Param("prefix") String prefix);

    @Select("""
        <script>
        SELECT c.id, c.contract_no AS contractNo, c.engineer_id AS engineerId,
               c.customer_id AS customerId, c.project_id AS projectId,
               c.contract_type AS contractType, c.start_date AS startDate, c.end_date AS endDate,
               c.selling_price AS sellingPrice, c.cost_price AS costPrice, c.status,
               c.sales_user_id AS salesUserId, su.full_name AS salesUserName,
               e.full_name AS engineerName, cu.company_name AS customerName, p.project_name AS projectName
        FROM t_contract c
        LEFT JOIN t_engineer e ON c.engineer_id = e.id AND e.deleted_flag = 0
        LEFT JOIN m_customer cu ON c.customer_id = cu.id AND cu.deleted_flag = 0
        LEFT JOIN t_project p ON c.project_id = p.id AND p.deleted_flag = 0
        LEFT JOIN sys_user su ON c.sales_user_id = su.id AND su.deleted_flag = 0
        WHERE c.deleted_flag = 0
          <if test="status != null and status != ''">AND c.status = #{status}</if>
          <if test="customerId != null">AND c.customer_id = #{customerId}</if>
          <if test="engineerId != null">AND c.engineer_id = #{engineerId}</if>
          <if test="projectId != null">AND c.project_id = #{projectId}</if>
          <if test="salesUserId != null">AND c.sales_user_id = #{salesUserId}</if>
          <if test="contractNo != null and contractNo != ''">AND c.contract_no LIKE CONCAT('%', #{contractNo}, '%')</if>
          <if test="endDateFrom != null">AND c.end_date &gt;= #{endDateFrom}</if>
          <if test="endDateTo != null">AND c.end_date &lt;= #{endDateTo}</if>
        ORDER BY c.id DESC
        </script>
        """)
    Page<ContractListDto> selectPageWithNames(Page<ContractListDto> page, @org.apache.ibatis.annotations.Param("status") String status,
            @org.apache.ibatis.annotations.Param("customerId") Long customerId, @org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
            @org.apache.ibatis.annotations.Param("projectId") Long projectId, @org.apache.ibatis.annotations.Param("contractNo") String contractNo,
            @org.apache.ibatis.annotations.Param("endDateFrom") LocalDate endDateFrom, @org.apache.ibatis.annotations.Param("endDateTo") LocalDate endDateTo,
            @org.apache.ibatis.annotations.Param("salesUserId") Long salesUserId);
}
