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
    @Select("SELECT id, created_at, deleted_flag FROM t_engineer")
    List<EngineerCreatedAtDto> selectCreatedAtOnly();

    /** 孤児ファイル清掃用: 参照中の顔写真保存名を軽量取得する。 */
    @Select("SELECT photo_url FROM t_engineer WHERE deleted_flag = 0 AND photo_url IS NOT NULL")
    List<String> selectAllPhotoUrls();
}
