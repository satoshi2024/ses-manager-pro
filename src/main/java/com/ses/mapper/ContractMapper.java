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
import com.ses.dto.contract.ContractDraftStatusDto;
import com.ses.dto.contract.RenewalCalendarItemDto;
import java.time.LocalDate;

@Mapper
public interface ContractMapper extends BaseMapper<Contract> {

    @Select("SELECT engineer_id, start_date, end_date FROM t_contract " +
            "WHERE deleted_flag = 0 AND status IN ('稼動中','終了') AND engineer_id IS NOT NULL AND start_date IS NOT NULL")
    List<ContractDateRangeDto> selectActiveDateRanges();

    @Select("SELECT MAX(contract_no) FROM t_contract WHERE contract_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxContractNoIncludingDeleted(@org.apache.ibatis.annotations.Param("prefix") String prefix);

    @Select("SELECT COUNT(*) FROM t_contract WHERE renewed_from_contract_id = #{originalId}")
    int countRenewedDraftsIncludingDeleted(@org.apache.ibatis.annotations.Param("originalId") Long originalId);

    /** 契約行を FOR UPDATE でロックして取得する（通常更新と単価同期/改定を直列化する / R3R-29）。 */
    @Select("SELECT * FROM t_contract WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Contract selectByIdForUpdate(@org.apache.ibatis.annotations.Param("id") Long id);

    /** 単価列のみを部分更新する（同期/改定が他項目を旧値で上書きしないようにする / R3R-29）。 */
    @org.apache.ibatis.annotations.Update(
            "UPDATE t_contract SET selling_price = #{sellingPrice}, cost_price = #{costPrice} "
            + "WHERE id = #{id} AND deleted_flag = 0")
    int updatePriceOnly(@org.apache.ibatis.annotations.Param("id") Long id,
                        @org.apache.ibatis.annotations.Param("sellingPrice") java.math.BigDecimal sellingPrice,
                        @org.apache.ibatis.annotations.Param("costPrice") java.math.BigDecimal costPrice);

    @Select("""
        <script>
        SELECT c.id, c.contract_no AS contractNo, c.engineer_id AS engineerId,
               c.customer_id AS customerId, c.project_id AS projectId,
               c.contract_type AS contractType, c.start_date AS startDate, c.end_date AS endDate,
               c.selling_price AS sellingPrice, c.cost_price AS costPrice, c.status,
               c.sales_user_id AS salesUserId, su.real_name AS salesUserName,
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
          <if test="salesUnassigned != null and salesUnassigned">AND c.sales_user_id IS NULL</if>
          <if test="contractNo != null and contractNo != ''">AND c.contract_no LIKE CONCAT('%', #{contractNo}, '%')</if>
          <if test="endDateFrom != null">AND c.end_date &gt;= #{endDateFrom}</if>
          <if test="endDateTo != null">AND c.end_date &lt;= #{endDateTo}</if>
          <!-- ガント期間フィルタ (R7-08): 期間と重なる契約を取得 -->
          <if test="periodFrom != null">AND (c.end_date IS NULL OR c.end_date &gt;= #{periodFrom})</if>
          <if test="periodTo != null">AND c.start_date &lt;= #{periodTo}</if>
          <!-- データスコープ: allowedIds!=null なら担当契約のみに絞る(件数・ページングもスコープ後の値) -->
          <if test="allowedIds != null">AND c.id IN <foreach collection="allowedIds" item="cid" open="(" separator="," close=")">#{cid}</foreach></if>
        ORDER BY c.id DESC
        </script>
        """)
    Page<ContractListDto> selectPageWithNames(Page<ContractListDto> page, @org.apache.ibatis.annotations.Param("status") String status,
            @org.apache.ibatis.annotations.Param("customerId") Long customerId, @org.apache.ibatis.annotations.Param("engineerId") Long engineerId,
            @org.apache.ibatis.annotations.Param("projectId") Long projectId, @org.apache.ibatis.annotations.Param("contractNo") String contractNo,
            @org.apache.ibatis.annotations.Param("endDateFrom") LocalDate endDateFrom, @org.apache.ibatis.annotations.Param("endDateTo") LocalDate endDateTo,
            @org.apache.ibatis.annotations.Param("salesUserId") Long salesUserId,
            @org.apache.ibatis.annotations.Param("salesUnassigned") Boolean salesUnassigned,
            @org.apache.ibatis.annotations.Param("periodFrom") LocalDate periodFrom,
            @org.apache.ibatis.annotations.Param("periodTo") LocalDate periodTo,
            @org.apache.ibatis.annotations.Param("allowedIds") java.util.List<Long> allowedIds);

    /**
     * 契約更新カレンダー(FR-06)候補の取得。指定ステータス・終了日範囲の契約を要員/顧客/営業名付きで返す。
     * 1000件上限は呼び出し側で担保する(A7-22: 黙って欠けないよう limit+1 で取得し切り詰めを検知)。
     */
    @Select("""
        <script>
        SELECT c.id AS contractId, c.contract_no AS contractNo, c.engineer_id AS engineerId,
               c.customer_id AS customerId, c.end_date AS endDate, c.status,
               c.renewal_decision AS renewalDecision,
               c.sales_user_id AS salesUserId, su.real_name AS salesUserName,
               e.full_name AS engineerName, cu.company_name AS customerName
        FROM t_contract c
        LEFT JOIN t_engineer e ON c.engineer_id = e.id AND e.deleted_flag = 0
        LEFT JOIN m_customer cu ON c.customer_id = cu.id AND cu.deleted_flag = 0
        LEFT JOIN sys_user su ON c.sales_user_id = su.id AND su.deleted_flag = 0
        WHERE c.deleted_flag = 0
          AND c.status = #{status}
          AND c.end_date IS NOT NULL
          AND c.end_date &gt;= #{endDateFrom}
          AND c.end_date &lt;= #{endDateTo}
          <if test="allowedIds != null">AND c.id IN <foreach collection="allowedIds" item="cid" open="(" separator="," close=")">#{cid}</foreach></if>
        ORDER BY c.end_date ASC
        <if test="limit != null">LIMIT #{limit}</if>
        </script>
        """)
    java.util.List<RenewalCalendarItemDto> selectRenewalCalendarCandidates(
            @org.apache.ibatis.annotations.Param("status") String status,
            @org.apache.ibatis.annotations.Param("endDateFrom") LocalDate endDateFrom,
            @org.apache.ibatis.annotations.Param("endDateTo") LocalDate endDateTo,
            @org.apache.ibatis.annotations.Param("allowedIds") java.util.List<Long> allowedIds,
            @org.apache.ibatis.annotations.Param("limit") Integer limit);

    /**
     * 指定した元契約群を親とする更新ドラフト(renewed_from_contract_id)の状態のみを返す。
     * 状態導出(DRAFT有/確定判定)専用の軽量クエリ。
     */
    @Select("""
        <script>
        SELECT renewed_from_contract_id AS renewedFromContractId, status
        FROM t_contract
        WHERE deleted_flag = 0 AND renewed_from_contract_id IN
        <foreach collection="ids" item="i" open="(" separator="," close=")">#{i}</foreach>
        </script>
        """)
    java.util.List<ContractDraftStatusDto> selectDraftStatusesByOriginalIds(@org.apache.ibatis.annotations.Param("ids") java.util.List<Long> ids);
}
