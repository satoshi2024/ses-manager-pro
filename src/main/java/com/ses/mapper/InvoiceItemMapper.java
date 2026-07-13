package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.InvoiceItem;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface InvoiceItemMapper extends BaseMapper<InvoiceItem> {

    @Select("""
        <script>
        SELECT DISTINCT i.invoice_no
        FROM t_invoice_item it
        JOIN t_invoice i ON it.invoice_id = i.id AND i.deleted_flag = 0
        WHERE it.work_record_id IN
        <foreach item='id' collection='ids' open='(' separator=',' close=')'>
            #{id}
        </foreach>
        </script>
    """)
    List<String> selectActiveInvoiceNosByWorkRecordIds(@Param("ids") List<Long> ids);
}
