package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ResumeIngestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * スキルシート取込マッパー
 */
@Mapper
public interface ResumeIngestionMapper extends BaseMapper<ResumeIngestion> {

    /**
     * 孤児ファイル清理用：却下以外の論理削除されていないジョブの stored_file_name を取得する。
     */
    @Select("SELECT stored_file_name FROM t_resume_ingestion " +
            "WHERE deleted_flag = 0 AND status != '\u5374\u4e0b' AND stored_file_name IS NOT NULL")
    List<String> selectAllStoredFileNames();
}
