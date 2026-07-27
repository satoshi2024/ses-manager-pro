package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.dto.analytics.EngineerCreatedAtDto;
import com.ses.entity.Engineer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EngineerMapper extends BaseMapper<Engineer> {

    /**
     * 稼動率推移の集計用に id/created_at のみを取得する軽量プロジェクション。
     * remarks 等の大きな列を含む全カラムをメモリに載せずに済む。
     */
    @Select("SELECT id, created_at, updated_at, deleted_flag FROM t_engineer")
    List<EngineerCreatedAtDto> selectCreatedAtOnly();

    /** 孤児ファイル清掃用: 参照中の顔写真保存名を軽量取得する。 */
    @Select("SELECT photo_url FROM t_engineer WHERE deleted_flag = 0 AND photo_url IS NOT NULL")
    List<String> selectAllPhotoUrls();

    @Select("SELECT * FROM t_engineer WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Engineer selectByIdForUpdate(Long id);

    /** 組織統合で要員の所属組織を統合先へ付け替える。 */
    @org.apache.ibatis.annotations.Update(
            "UPDATE t_engineer SET organization_id = #{targetOrganizationId} "
                    + "WHERE organization_id = #{organizationId} AND deleted_flag = 0")
    int reassignOrganization(@org.apache.ibatis.annotations.Param("organizationId") Long organizationId,
                             @org.apache.ibatis.annotations.Param("targetOrganizationId") Long targetOrganizationId);

    /**
     * 対象月のBench要員の待機原価を組織・原価部門別に集計する。
     *
     * <p>Bench判定は {@link com.ses.service.UtilizationCalcService} と同じ契約ベースの口径を使う
     * （対象月に 稼動中/準備中/終了 の契約が期間として重ならない要員をBenchとする）。
     * {@code t_engineer.status} は「現在」の値しか持たないため、過去月を照会したときに
     * 数字が後から変わってしまう。月次実績は対象月時点で確定していなければならない（R2.2）。
     *
     * <p>組織scopeは呼び出し側でフィルタせず、必ずこのSQLの条件として渡す。
     * 帰属は対象月の有効なアカウント連携ユーザー主所属を優先し、未設定時のみ
     * {@code t_engineer.organization_id} を使う。
     */
    @Select("""
        <script>
        SELECT COALESCE(uo.organization_id, e.organization_id) AS organizationId,
               e.cost_center_id AS costCenterId,
               COALESCE(SUM(e.expected_unit_price), 0) AS waitCost
        FROM t_engineer e
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id AND uo.primary_flag = 1
             AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{monthStart} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{monthStart})
        LEFT JOIN m_organization_unit ou ON ou.id = COALESCE(uo.organization_id, e.organization_id)
             AND ou.deleted_flag = 0
        WHERE e.deleted_flag = 0
          AND NOT EXISTS (
            SELECT 1 FROM t_contract c
            WHERE c.engineer_id = e.id AND c.deleted_flag = 0
              AND c.status IN ('稼動中', '準備中', '終了')
              AND c.start_date IS NOT NULL AND c.start_date &lt;= #{monthEnd}
              AND (c.end_date IS NULL OR c.end_date &gt;= #{monthStart})
          )
          <if test="costCenterId != null">AND e.cost_center_id = #{costCenterId}</if>
          <if test="legalEntityId != null">AND ou.legal_entity_id = #{legalEntityId}</if>
          <if test="organizationId != null">AND COALESCE(uo.organization_id, e.organization_id) = #{organizationId}</if>
          <if test="fullAccess == false">
            <choose>
              <when test="allowedIds != null and allowedIds.size() > 0">
                AND COALESCE(uo.organization_id, e.organization_id) IN
                <foreach collection="allowedIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
        GROUP BY COALESCE(uo.organization_id, e.organization_id), e.cost_center_id
        </script>
        """)
    List<com.ses.dto.accounting.AccountingWaitCostRow> selectAccountingWaitCost(
            @org.apache.ibatis.annotations.Param("monthStart") java.time.LocalDate monthStart,
            @org.apache.ibatis.annotations.Param("monthEnd") java.time.LocalDate monthEnd,
            @org.apache.ibatis.annotations.Param("fullAccess") boolean fullAccess,
            @org.apache.ibatis.annotations.Param("allowedIds") List<Long> allowedIds,
            @org.apache.ibatis.annotations.Param("legalEntityId") Long legalEntityId,
            @org.apache.ibatis.annotations.Param("organizationId") Long organizationId,
            @org.apache.ibatis.annotations.Param("costCenterId") Long costCenterId);

    /** 月次snapshot用にBench待機原価を要員単位で取得する。 */
    @Select("""
        <script>
        SELECT e.id AS engineerId,
               COALESCE(uo.organization_id, e.organization_id) AS organizationId,
               e.cost_center_id AS costCenterId,
               COALESCE(e.expected_unit_price, 0) AS waitCost
        FROM t_engineer e
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id AND uo.primary_flag = 1
             AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{monthStart} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{monthStart})
        WHERE e.deleted_flag = 0
          AND NOT EXISTS (
            SELECT 1 FROM t_contract c
            WHERE c.engineer_id = e.id AND c.deleted_flag = 0
              AND c.status IN ('稼動中', '準備中', '終了')
              AND c.start_date IS NOT NULL AND c.start_date &lt;= #{monthEnd}
              AND (c.end_date IS NULL OR c.end_date &gt;= #{monthStart})
          )
        </script>
        """)
    List<com.ses.dto.accounting.AccountingWaitCostSnapshotRow> selectAccountingWaitCostByEngineer(
            @org.apache.ibatis.annotations.Param("monthStart") java.time.LocalDate monthStart,
            @org.apache.ibatis.annotations.Param("monthEnd") java.time.LocalDate monthEnd);
}
