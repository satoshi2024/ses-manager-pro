package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ContractPriceHistory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;

import java.util.List;

@Mapper
public interface ContractPriceHistoryMapper extends BaseMapper<ContractPriceHistory> {
    @Select("SELECT DISTINCT h.contract_id FROM t_contract_price_history h JOIN t_contract c ON c.id = h.contract_id "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0")
    List<Long> selectContractIdsForTenant(@Param("tenantId") String tenantId);
    /** 契約のownershipを親契約JOINで検証する履歴取得。 */
    @Select("SELECT h.* FROM t_contract_price_history h JOIN t_contract c ON c.id = h.contract_id "
            + "AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0 "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE h.contract_id = #{contractId} ORDER BY h.apply_from_month")
    List<ContractPriceHistory> selectByContractIdForTenant(@Param("contractId") Long contractId,
                                                           @Param("tenantId") String tenantId);

    @Update("UPDATE t_contract_price_history h SET selling_price = #{history.sellingPrice}, "
            + "cost_price = #{history.costPrice}, reason = #{history.reason}, updated_at = CURRENT_TIMESTAMP "
            + "WHERE id = #{history.id} AND contract_id = #{contractId} AND EXISTS (SELECT 1 FROM t_contract c "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE c.id = h.contract_id AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0)")
    int updateByIdForContractTenant(@Param("history") ContractPriceHistory history,
                                    @Param("contractId") Long contractId,
                                    @Param("tenantId") String tenantId);

    @Delete("DELETE FROM t_contract_price_history h WHERE contract_id = #{contractId} "
            + "AND apply_from_month = #{applyFromMonth} AND EXISTS (SELECT 1 FROM t_contract c "
            + "JOIN m_customer mc ON mc.id = c.customer_id AND mc.tenant_id = #{tenantId} AND mc.deleted_flag = 0 "
            + "WHERE c.id = h.contract_id AND c.tenant_id IS NOT NULL AND c.tenant_id = #{tenantId} AND c.deleted_flag = 0)")
    int deleteByContractAndMonthForTenant(@Param("contractId") Long contractId,
                                          @Param("applyFromMonth") String applyFromMonth,
                                          @Param("tenantId") String tenantId);
}
