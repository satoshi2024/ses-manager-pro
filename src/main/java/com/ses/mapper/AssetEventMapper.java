package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AssetEvent;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AssetEventMapper extends BaseMapper<AssetEvent> {

    @Select("SELECT * FROM t_asset_event WHERE asset_id = #{assetId} ORDER BY event_time DESC, id DESC")
    List<AssetEvent> selectByAssetId(@Param("assetId") Long assetId);
}
