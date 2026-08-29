package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AssetInventoryItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AssetInventoryItemMapper extends BaseMapper<AssetInventoryItem> {

    @Select("SELECT * FROM t_asset_inventory_item WHERE id = #{id} FOR UPDATE")
    AssetInventoryItem selectByIdForUpdate(@Param("id") Long id);

    @Select("SELECT * FROM t_asset_inventory_item WHERE inventory_run_id = #{runId} ORDER BY id ASC")
    List<AssetInventoryItem> selectByRunId(@Param("runId") Long runId);
}
