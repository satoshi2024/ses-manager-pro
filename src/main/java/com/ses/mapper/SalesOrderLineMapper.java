package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.SalesOrderLine;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 注文明細マッパー。 */
@Mapper
public interface SalesOrderLineMapper extends BaseMapper<SalesOrderLine> {

    /**
     * 下書き差替時の物理削除。論理削除だと (order_id, line_no) のUNIQUEと
     * 新明細のline_noが衝突するため、外部参照が無い下書きの明細だけ物理削除する。
     */
    @Delete("DELETE FROM t_sales_order_line WHERE order_id = #{orderId}")
    int deletePhysicalByOrderId(@Param("orderId") Long orderId);
}
