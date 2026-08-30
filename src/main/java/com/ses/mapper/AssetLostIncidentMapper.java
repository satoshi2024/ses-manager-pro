package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AssetLostIncident;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 紛失資産インシデント台帳Mapper。 */
@Mapper
public interface AssetLostIncidentMapper extends BaseMapper<AssetLostIncident> {

    @Select("SELECT * FROM t_asset_lost_incident WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    AssetLostIncident selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_asset_lost_incident WHERE asset_id = #{assetId} AND deleted_flag = 0 ORDER BY id DESC LIMIT 1")
    AssetLostIncident selectLatestByAssetId(@Param("assetId") Long assetId);
}
