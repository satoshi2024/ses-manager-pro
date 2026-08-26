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

    /** 組織統合で要員の所属組織を統合先へ付け替える。
     * version を +1 し、並行する単行更新との衝突を検出できるようにする。
     * 単行の期待 version 照合が必要な場合は {@code updateById} を使うこと。 */
    @org.apache.ibatis.annotations.Update(
            "UPDATE t_engineer SET organization_id = #{targetOrganizationId}, version = version + 1 "
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
     *
     * <p><b>帰属解決順（design.md「帰属の解決順」と一致させる）</b>:
     * <ol>
     *   <li>要員自身の所属組織（対象月時点の版。{@code t_engineer_accounting_history.organization_id}、
     *       履歴行が無ければ {@code t_engineer.organization_id} の現在値。過去所属を復元できない
     *       {@code UNKNOWN} 行は未配賦として扱う）</li>
     *   <li>1が既知のNULLの場合のみ、対象月時点のアカウント連携ユーザー主所属</li>
     * </ol>
     * 旧実装はこの優先順位が逆（アカウント連携を先に見る）になっていた（第十四次Review P1-4）。
     *
     * <p>原価部門・想定単価も同じ履歴行から対象月時点の版を解決する。<b>{@code COALESCE} ではなく
     * {@code CASE WHEN eh.id IS NULL}</b> で判定するのは、履歴行が見つかった（版が存在する）のに
     * その版の値が明示的なNULLであるケースを、以後の版（未来の値、あるいは要員マスタの現在値）へ
     * 誤ってフォールバックさせないため（第十四次Review P1-5）。履歴行そのものが無い場合
     * （V61/V62適用前からの要員でbackfillが走っていない異常系）だけ、要員マスタの現在値を使う。
     */
    @Select("""
        <script>
        SELECT CASE
                 WHEN eh.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                 WHEN eh.organization_history_status = 'UNKNOWN' THEN NULL
                 ELSE COALESCE(eh.organization_id, uo.organization_id)
               END AS organizationId,
               CASE WHEN eh.id IS NULL THEN e.cost_center_id ELSE eh.cost_center_id END AS costCenterId,
               COALESCE(SUM(CASE WHEN eh.id IS NULL THEN e.expected_unit_price ELSE eh.expected_unit_price END), 0) AS waitCost
        FROM t_engineer e
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
        LEFT JOIN t_engineer_accounting_history eh ON eh.engineer_id = e.id
             AND eh.deleted_flag = 0
             AND eh.valid_from &lt;= #{monthStart} AND (eh.valid_to IS NULL OR eh.valid_to &gt;= #{monthStart})
        LEFT JOIN t_user_organization uo ON uo.user_id = l.sys_user_id AND uo.primary_flag = 1
             AND uo.deleted_flag = 0
             AND uo.valid_from &lt;= #{monthStart} AND (uo.valid_to IS NULL OR uo.valid_to &gt;= #{monthStart})
        LEFT JOIN m_organization_unit ou ON ou.id = CASE
                 WHEN eh.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                 WHEN eh.organization_history_status = 'UNKNOWN' THEN NULL
                 ELSE COALESCE(eh.organization_id, uo.organization_id)
               END
             AND ou.deleted_flag = 0
        WHERE e.deleted_flag = 0
          AND NOT EXISTS (
            SELECT 1 FROM t_contract c
            WHERE c.engineer_id = e.id AND c.deleted_flag = 0
              AND c.status IN ('稼動中', '準備中', '終了')
              AND c.start_date IS NOT NULL AND c.start_date &lt;= #{monthEnd}
              AND (c.end_date IS NULL OR c.end_date &gt;= #{monthStart})
          )
          <if test="costCenterId != null">AND CASE WHEN eh.id IS NULL THEN e.cost_center_id ELSE eh.cost_center_id END = #{costCenterId}</if>
          <if test="legalEntityId != null">AND ou.legal_entity_id = #{legalEntityId}</if>
          <if test="organizationId != null">AND CASE
                 WHEN eh.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                 WHEN eh.organization_history_status = 'UNKNOWN' THEN NULL
                 ELSE COALESCE(eh.organization_id, uo.organization_id)
               END = #{organizationId}</if>
          <if test="fullAccess == false">
            <choose>
              <when test="allowedIds != null and allowedIds.size() > 0">
                AND CASE
                 WHEN eh.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                 WHEN eh.organization_history_status = 'UNKNOWN' THEN NULL
                 ELSE COALESCE(eh.organization_id, uo.organization_id)
               END IN
                <foreach collection="allowedIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              </when>
              <otherwise>AND 1 = 0</otherwise>
            </choose>
          </if>
        GROUP BY CASE
                 WHEN eh.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                 WHEN eh.organization_history_status = 'UNKNOWN' THEN NULL
                 ELSE COALESCE(eh.organization_id, uo.organization_id)
               END,
                 CASE WHEN eh.id IS NULL THEN e.cost_center_id ELSE eh.cost_center_id END
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

    /**
     * 月次snapshot用にBench待機原価を要員単位で取得する。
     *
     * <p>所属組織・原価部門・想定単価は<b>対象月時点の履歴</b>から解決する。要員マスタの現在値を
     * 読むと、締めが遅れている間に異動・単価改定を行った場合、確定済み扱いの過去月へ新しい値が
     * 焼き付いてしまう（snapshotは訂正しない運用のため誤りが永続化する）。
     * 解決順・NULL semanticsは {@link #selectAccountingWaitCost} と同一にする。
     */
    @Select("""
        <script>
        SELECT e.id AS engineerId,
               CASE
                 WHEN eh.id IS NULL THEN COALESCE(e.organization_id, uo.organization_id)
                 WHEN eh.organization_history_status = 'UNKNOWN' THEN NULL
                 ELSE COALESCE(eh.organization_id, uo.organization_id)
               END AS organizationId,
               CASE WHEN eh.id IS NULL THEN e.cost_center_id ELSE eh.cost_center_id END AS costCenterId,
               COALESCE(CASE WHEN eh.id IS NULL THEN e.expected_unit_price ELSE eh.expected_unit_price END, 0) AS waitCost
        FROM t_engineer e
        LEFT JOIN t_engineer_account_link l ON l.engineer_id = e.id
        LEFT JOIN t_engineer_accounting_history eh ON eh.engineer_id = e.id
             AND eh.deleted_flag = 0
             AND eh.valid_from &lt;= #{monthStart} AND (eh.valid_to IS NULL OR eh.valid_to &gt;= #{monthStart})
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
