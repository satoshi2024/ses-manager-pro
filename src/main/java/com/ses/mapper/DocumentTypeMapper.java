package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DocumentType;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DocumentTypeMapper extends BaseMapper<DocumentType> {

    @Select("SELECT * FROM m_document_type WHERE code = #{code} AND deleted_flag = 0 LIMIT 1")
    DocumentType selectActiveByCode(@Param("code") String code);
}
