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

    @Select("""
        SELECT uo.organization_id AS organizationId, e.cost_center_id AS costCenterId,
               COALESCE(SUM(e.expected_unit_price), 0) AS waitCost
        FROM t_engineer e
        JOIN t_engineer_account_link l ON l.engineer_id = e.id
        JOIN t_user_organization uo ON uo.user_id = l.sys_user_id AND uo.primary_flag = 1
          AND uo.valid_from <= #{asOf} AND (uo.valid_to IS NULL OR uo.valid_to >= #{asOf})
        WHERE e.deleted_flag = 0 AND e.status = 'Bench'
        GROUP BY uo.organization_id, e.cost_center_id
        """)
    List<com.ses.dto.accounting.AccountingWaitCostRow> selectAccountingWaitCost(
            @org.apache.ibatis.annotations.Param("asOf") java.time.LocalDate asOf);
}
