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

    /** 資産一覧の認可母集団。取得後のJava filterではなくSQLのEXISTSで絞り込む。 */
    @Select("""
            <script>
            SELECT DISTINCT a.id
            FROM m_asset a
            WHERE a.deleted_flag = 0
              AND (
                <if test="engineerIds != null and engineerIds.size() > 0">
                  EXISTS (
                    SELECT 1 FROM t_asset_assignment aa
                    WHERE aa.asset_id = a.id AND aa.deleted_flag = 0
                      AND aa.assignee_type = 'ENGINEER'
                      AND aa.assignee_id IN
                      <foreach collection="engineerIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                      AND aa.actual_return_date IS NULL
                      AND aa.status IN ('ACTIVE', 'OVERDUE')
                  )
                </if>
                <if test="engineerIds != null and engineerIds.size() > 0 and ownerCompanyIds != null">OR</if>
                <if test="ownerCompanyIds != null">
                  NOT EXISTS (
                    SELECT 1 FROM t_asset_assignment aa2
                    WHERE aa2.asset_id = a.id AND aa2.deleted_flag = 0
                      AND aa2.actual_return_date IS NULL
                      AND aa2.status IN ('ACTIVE', 'OVERDUE')
                  )
                  AND (
                    a.owner_company_id IS NULL
                    <if test="ownerCompanyIds.size() > 0">
                      OR a.owner_company_id IN
                      <foreach collection="ownerCompanyIds" item="companyId" open="(" separator="," close=")">#{companyId}</foreach>
                    </if>
                  )
                </if>
              )
            </script>
            """)
    List<Long> selectAccessibleAssetIds(@Param("engineerIds") List<Long> engineerIds,
                                        @Param("ownerCompanyIds") List<Long> ownerCompanyIds);
}
