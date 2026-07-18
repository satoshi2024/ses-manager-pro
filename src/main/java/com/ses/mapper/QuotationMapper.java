package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.Quotation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface QuotationMapper extends BaseMapper<Quotation> {

    /** 論理削除済みも含む採番用の最大見積番号。 */
    @Select("SELECT MAX(quotation_no) FROM t_quotation WHERE quotation_no LIKE CONCAT(#{prefix}, '%')")
    String selectMaxQuotationNoIncludingDeleted(@Param("prefix") String prefix);
}
