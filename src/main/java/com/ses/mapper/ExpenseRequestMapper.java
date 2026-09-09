package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ExpenseRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 経費申請（t_expense_request）Mapper。 */
@Mapper
public interface ExpenseRequestMapper extends BaseMapper<ExpenseRequest> {

    @Select("SELECT er.* FROM t_expense_request er "
            + "JOIN t_engineer_account_link l ON l.engineer_id = er.engineer_id AND l.deleted_flag = 0 "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "WHERE er.id = #{id} AND u.tenant_id = #{tenantId} AND er.deleted_flag = 0")
    ExpenseRequest selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT er.* FROM t_expense_request er "
            + "JOIN t_engineer_account_link l ON l.engineer_id = er.engineer_id AND l.deleted_flag = 0 "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "WHERE u.tenant_id = #{tenantId} AND er.status = '承認済' "
            + "AND er.accounting_job_id IS NULL AND er.deleted_flag = 0 "
            + "ORDER BY er.id LIMIT #{limit}")
    List<ExpenseRequest> selectApprovedUnaccountedByTenant(@Param("tenantId") String tenantId,
                                                           @Param("limit") int limit);

    /** PWAのbaseVersion確認とdomain更新を同一transactionで直列化する。 */
    @Select("SELECT * FROM t_expense_request WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    ExpenseRequest selectByIdForUpdate(@Param("id") Long id);

    /** 会計連携プレビュー用の組織スコープ付き取得 (R1-P1-06 / design §5.1, §5.2)。権限外は null。 */
    @Select("""
        <script>
        SELECT er.* FROM t_expense_request er
        WHERE er.id = #{id}
          AND (
            <if test="orgIds.size() == 0">1 = 0</if>
            <if test="orgIds.size() > 0">
              EXISTS (
                SELECT 1 FROM t_engineer_accounting_history h
                WHERE h.engineer_id = er.engineer_id AND h.deleted_flag = 0
                  AND h.valid_from &lt;= COALESCE(er.expense_date, CURRENT_DATE)
                  AND (h.valid_to IS NULL OR h.valid_to &gt;= COALESCE(er.expense_date, CURRENT_DATE))
                  AND NOT EXISTS (
                    SELECT 1 FROM t_engineer_accounting_history h2
                    WHERE h2.engineer_id = h.engineer_id AND h2.deleted_flag = 0
                      AND h2.valid_from &lt;= COALESCE(er.expense_date, CURRENT_DATE)
                      AND (h2.valid_to IS NULL OR h2.valid_to &gt;= COALESCE(er.expense_date, CURRENT_DATE))
                      AND (h2.valid_from &gt; h.valid_from OR (h2.valid_from = h.valid_from AND h2.id &gt; h.id))
                  )
                  AND h.organization_history_status &lt;&gt; 'UNKNOWN'
                  AND h.organization_id IN <foreach collection="orgIds" item="oid" open="(" separator="," close=")">#{oid}</foreach>
              )
              OR (
                NOT EXISTS (
                  SELECT 1 FROM t_engineer_accounting_history h
                  WHERE h.engineer_id = er.engineer_id AND h.deleted_flag = 0
                    AND h.valid_from &lt;= COALESCE(er.expense_date, CURRENT_DATE)
                    AND (h.valid_to IS NULL OR h.valid_to &gt;= COALESCE(er.expense_date, CURRENT_DATE))
                )
                AND EXISTS (
                  SELECT 1 FROM t_engineer e2
                  WHERE e2.id = er.engineer_id
                    AND e2.organization_id IN <foreach collection="orgIds" item="oid" open="(" separator="," close=")">#{oid}</foreach>
                )
              )
            </if>
          )
        </script>
        """)
    ExpenseRequest selectForPreviewScoped(@Param("id") Long id, @Param("orgIds") List<Long> orgIds);

    /** 月次照合 (経費母集団) の組織スコープ付き一覧 (R1-P1-06 / design §5.1)。 */
    @Select("""
        <script>
        SELECT er.* FROM t_expense_request er
        WHERE er.expense_date &gt;= #{startDate} AND er.expense_date &lt;= #{endDate}
          AND (
            <if test="orgIds.size() == 0">1 = 0</if>
            <if test="orgIds.size() > 0">
              EXISTS (
                SELECT 1 FROM t_engineer_accounting_history h
                WHERE h.engineer_id = er.engineer_id AND h.deleted_flag = 0
                  AND h.valid_from &lt;= COALESCE(er.expense_date, CURRENT_DATE)
                  AND (h.valid_to IS NULL OR h.valid_to &gt;= COALESCE(er.expense_date, CURRENT_DATE))
                  AND NOT EXISTS (
                    SELECT 1 FROM t_engineer_accounting_history h2
                    WHERE h2.engineer_id = h.engineer_id AND h2.deleted_flag = 0
                      AND h2.valid_from &lt;= COALESCE(er.expense_date, CURRENT_DATE)
                      AND (h2.valid_to IS NULL OR h2.valid_to &gt;= COALESCE(er.expense_date, CURRENT_DATE))
                      AND (h2.valid_from &gt; h.valid_from OR (h2.valid_from = h.valid_from AND h2.id &gt; h.id))
                  )
                  AND h.organization_history_status &lt;&gt; 'UNKNOWN'
                  AND h.organization_id IN <foreach collection="orgIds" item="oid" open="(" separator="," close=")">#{oid}</foreach>
              )
              OR (
                NOT EXISTS (
                  SELECT 1 FROM t_engineer_accounting_history h
                  WHERE h.engineer_id = er.engineer_id AND h.deleted_flag = 0
                    AND h.valid_from &lt;= COALESCE(er.expense_date, CURRENT_DATE)
                    AND (h.valid_to IS NULL OR h.valid_to &gt;= COALESCE(er.expense_date, CURRENT_DATE))
                )
                AND EXISTS (
                  SELECT 1 FROM t_engineer e2
                  WHERE e2.id = er.engineer_id
                    AND e2.organization_id IN <foreach collection="orgIds" item="oid" open="(" separator="," close=")">#{oid}</foreach>
                )
              )
            </if>
          )
        </script>
        """)
    List<ExpenseRequest> selectForReconciliationScoped(@Param("startDate") java.time.LocalDate startDate,
                                                       @Param("endDate") java.time.LocalDate endDate,
                                                       @Param("orgIds") List<Long> orgIds);
}
