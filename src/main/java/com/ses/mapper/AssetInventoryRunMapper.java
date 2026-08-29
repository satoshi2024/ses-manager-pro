package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.AssetInventoryRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface AssetInventoryRunMapper extends BaseMapper<AssetInventoryRun> {

    @Select("SELECT * FROM t_asset_inventory_run WHERE id = #{id} AND deleted_flag = 0 FOR UPDATE")
    AssetInventoryRun selectByIdForUpdate(@Param("id") Long id);
}
