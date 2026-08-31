package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface AssetMapper extends BaseMapper<Asset> {

    @Select("SELECT * FROM m_asset WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    Asset selectByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE m_asset SET status = #{toStatus}, version = version + 1 " +
            "WHERE id = #{id} AND status = #{fromStatus} AND version = #{expectedVersion} AND deleted_flag = 0")
    int updateStatusWithCas(@Param("id") Long id,
                            @Param("fromStatus") String fromStatus,
                            @Param("toStatus") String toStatus,
                            @Param("expectedVersion") Integer expectedVersion);

    /**
     * 資産一覧の認可母集団。取得後のJava filterではなくSQLのEXISTSで絞り込む。
     * 非管理ロールは、許可された要員への現在貸与がある資産だけを参照する。
     * ownerCompanyIds はマネージャーだけが指定する追加の法人条件であり、null は法人条件なし（営業/要員）を表す。
     */
    @Select("""
            <script>
            SELECT DISTINCT a.id
            FROM m_asset a
            WHERE a.deleted_flag = 0
              AND EXISTS (
                SELECT 1 FROM t_asset_assignment aa
                WHERE aa.asset_id = a.id AND aa.deleted_flag = 0
                  AND aa.assignee_type = 'ENGINEER'
                  AND aa.assignee_id IN
                  <foreach collection="engineerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                  AND aa.actual_return_date IS NULL
                  AND aa.status IN ('ACTIVE', 'OVERDUE')
              )
              <if test="ownerCompanyIds != null">
                AND (
                  a.owner_company_id IS NULL
                  <if test="ownerCompanyIds.size() > 0">
                    OR a.owner_company_id IN
                    <foreach collection="ownerCompanyIds" item="companyId" open="(" separator="," close=")">#{companyId}</foreach>
                  </if>
                )
              </if>
            </script>
            """)
    List<Long> selectAccessibleAssetIds(@Param("engineerIds") List<Long> engineerIds,
                                        @Param("ownerCompanyIds") List<Long> ownerCompanyIds);
}
