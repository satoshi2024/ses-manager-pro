package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.DocumentLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 文書業務リンクMapper。 */
@Mapper
public interface DocumentLinkMapper extends BaseMapper<DocumentLink> {

    /**
     * 指定した業務エンティティ種別・IDにリンクされた文書IDを全て取得する。
     * 認可母集団の和集合計算に使用する（design §6.2）。
     */
    @Select("SELECT document_id FROM t_document_link WHERE target_type = #{targetType} AND target_id = #{targetId} AND deleted_flag = 0")
    List<Long> findDocumentIdsByTarget(@Param("targetType") String targetType, @Param("targetId") Long targetId);

    /**
     * 文書IDに紐づく全リンクを取得する。
     */
    @Select("SELECT * FROM t_document_link WHERE document_id = #{documentId} AND deleted_flag = 0")
    List<DocumentLink> findByDocumentId(@Param("documentId") Long documentId);
}
