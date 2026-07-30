package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.ses.entity.Document;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 文書台帳Mapper。
 * 認可母集団はt_document_linkからの導出を基本とし、business layerで制御する。
 */
@Mapper
public interface DocumentMapper extends BaseMapper<Document> {

    /**
     * 保存期限切れ候補を取得する。
     * retention_until IS NULL の文書は候補から除外する（design §6.1）。
     * legal_hold_flag = 1 の文書も除外する（R4.2）。
     */
    @Select("SELECT * FROM t_document WHERE deleted_flag = 0 AND status = 'CONFIRMED' AND retention_until IS NOT NULL AND retention_until <= #{today} AND legal_hold_flag = 0 ORDER BY retention_until ASC")
    List<Document> findDisposalCandidates(@Param("today") LocalDate today);
}
