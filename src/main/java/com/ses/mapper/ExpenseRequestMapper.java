package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.ses.entity.ExpenseRequest;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 経費申請（t_expense_request）Mapper。 */
@Mapper
public interface ExpenseRequestMapper extends BaseMapper<ExpenseRequest> {

    /** 本人の経費一覧も、要員account linkのtenant所有権をSQL内で解決する。 */
    @Select("""
        <script>
        SELECT er.*
        FROM t_expense_request er
        INNER JOIN t_engineer_account_link l
                ON l.engineer_id = er.engineer_id
               AND l.tenant_id = #{tenantId}
        INNER JOIN sys_user u
                ON u.id = l.sys_user_id
               AND u.deleted_flag = 0
               AND u.tenant_id = #{tenantId}
        WHERE er.engineer_id = #{engineerId}
          AND er.deleted_flag = 0
          <if test="status != null and status != ''">
            AND er.status = #{status}
          </if>
        ORDER BY er.id DESC
        </script>
        """)
    Page<ExpenseRequest> selectPageForEngineerTenant(Page<ExpenseRequest> page,
                                                      @Param("engineerId") Long engineerId,
                                                      @Param("status") String status,
                                                      @Param("tenantId") String tenantId);

    /**
     * 管理画面の経費一覧。経費本体にtenant列を追加せず、要員account linkとsys_userを
     * ownershipの正本としてSQL内で解決する。検索・状態・scope・並び順は全てDBへ渡す。
     */
    @Select("""
        <script>
        SELECT er.*
        FROM t_expense_request er
        INNER JOIN t_engineer e ON e.id = er.engineer_id AND e.deleted_flag = 0
        INNER JOIN t_engineer_account_link l
                ON l.engineer_id = er.engineer_id
               AND l.tenant_id = #{tenantId}
        INNER JOIN sys_user u
                ON u.id = l.sys_user_id
               AND u.deleted_flag = 0
               AND u.tenant_id = #{tenantId}
        WHERE er.deleted_flag = 0
          <if test="engineerIds != null">
            <choose>
              <when test="engineerIds.size() > 0">
                AND er.engineer_id IN
                <foreach collection="engineerIds" item="engineerId" open="(" separator="," close=")">
                    #{engineerId}
                </foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
          <if test="engineerName != null and engineerName != ''">
            AND LOWER(COALESCE(e.full_name, '')) LIKE CONCAT('%', LOWER(#{engineerName}), '%')
          </if>
          <if test="status != null and status != ''">
            AND er.status = #{status}
          </if>
        ORDER BY er.id DESC
        </script>
        """)
    Page<ExpenseRequest> selectManagementPage(Page<ExpenseRequest> page,
                                               @Param("tenantId") String tenantId,
                                               @Param("engineerIds") List<Long> engineerIds,
                                               @Param("engineerName") String engineerName,
                                               @Param("status") String status);

    @Select("SELECT er.* FROM t_expense_request er "
            + "JOIN t_engineer_account_link l ON l.engineer_id = er.engineer_id "
            + "AND l.tenant_id = #{tenantId} "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "WHERE er.id = #{id} AND u.tenant_id = #{tenantId} AND er.deleted_flag = 0")
    ExpenseRequest selectByIdForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    @Select("SELECT er.* FROM t_expense_request er "
            + "JOIN t_engineer_account_link l ON l.engineer_id = er.engineer_id "
            + "AND l.tenant_id = #{tenantId} "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "WHERE u.tenant_id = #{tenantId} AND er.status = '承認済' "
            + "AND er.accounting_job_id IS NULL AND er.deleted_flag = 0 "
            + "ORDER BY er.id LIMIT #{limit}")
    List<ExpenseRequest> selectApprovedUnaccountedByTenant(@Param("tenantId") String tenantId,
                                                           @Param("limit") int limit);

    /** PWAのbaseVersion確認とdomain更新を同一transactionで直列化する。 */
    @Select("SELECT * FROM t_expense_request WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    ExpenseRequest selectByIdForUpdate(@Param("id") Long id);

    /** PWAのFOR UPDATEもtenant所有権をSQLで検証する。 */
    @Select("SELECT er.* FROM t_expense_request er "
            + "JOIN t_engineer_account_link l ON l.engineer_id = er.engineer_id "
            + "AND l.tenant_id = #{tenantId} "
            + "JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0 "
            + "WHERE er.id = #{id} AND u.tenant_id = #{tenantId} AND er.deleted_flag = 0 FOR UPDATE")
    ExpenseRequest selectByIdForUpdateForTenant(@Param("id") Long id, @Param("tenantId") String tenantId);

    /** 会計連携プレビュー用の組織スコープ付き取得 (R1-P1-06 / design §5.1, §5.2)。権限外は null。 */
    @Select("""
        <script>
        SELECT er.* FROM t_expense_request er
        WHERE er.id = #{id}
          AND er.deleted_flag = 0
          AND EXISTS (
            SELECT 1 FROM t_engineer_account_link owner_link
            JOIN sys_user owner_user ON owner_user.id = owner_link.sys_user_id
                 AND owner_user.deleted_flag = 0
            WHERE owner_link.engineer_id = er.engineer_id
              AND owner_link.tenant_id = #{tenantId}
              AND owner_user.tenant_id = #{tenantId}
          )
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
    ExpenseRequest selectForPreviewScoped(@Param("id") Long id, @Param("orgIds") List<Long> orgIds,
                                          @Param("tenantId") String tenantId);

    /** 月次照合 (経費母集団) の組織スコープ付き一覧 (R1-P1-06 / design §5.1)。 */
    @Select("""
        <script>
        SELECT er.* FROM t_expense_request er
        WHERE er.expense_date &gt;= #{startDate} AND er.expense_date &lt;= #{endDate}
          AND er.deleted_flag = 0
          AND EXISTS (
            SELECT 1 FROM t_engineer_account_link owner_link
            JOIN sys_user owner_user ON owner_user.id = owner_link.sys_user_id
                 AND owner_user.deleted_flag = 0
            WHERE owner_link.engineer_id = er.engineer_id
              AND owner_link.tenant_id = #{tenantId}
              AND owner_user.tenant_id = #{tenantId}
          )
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
                                                       @Param("orgIds") List<Long> orgIds,
                                                       @Param("tenantId") String tenantId);

    /** 管理者の全組織アクセスでもtenant所有権は省略しない。 */
    @Select("""
        SELECT er.* FROM t_expense_request er
        WHERE er.expense_date >= #{startDate} AND er.expense_date <= #{endDate}
          AND er.deleted_flag = 0
          AND EXISTS (
            SELECT 1 FROM t_engineer_account_link l
            JOIN sys_user u ON u.id = l.sys_user_id AND u.deleted_flag = 0
            WHERE l.engineer_id = er.engineer_id
              AND l.tenant_id = #{tenantId}
              AND u.tenant_id = #{tenantId}
          )
        ORDER BY er.id
        """)
    List<ExpenseRequest> selectForReconciliationByTenant(
            @Param("startDate") java.time.LocalDate startDate,
            @Param("endDate") java.time.LocalDate endDate,
            @Param("tenantId") String tenantId);
}
