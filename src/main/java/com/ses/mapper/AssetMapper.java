package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Asset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

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
}
