package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ProjectIngestion;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 案件メール取込ジョブMapper
 */
@Mapper
public interface ProjectIngestionMapper extends BaseMapper<ProjectIngestion> {

    @Select("SELECT stored_file_name FROM t_project_ingestion WHERE stored_file_name IS NOT NULL")
    List<String> selectAllStoredFileNames();
}
