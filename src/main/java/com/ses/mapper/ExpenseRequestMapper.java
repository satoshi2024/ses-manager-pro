package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ExpenseRequest;
import org.apache.ibatis.annotations.Mapper;

/** 経費申請（t_expense_request）Mapper。 */
@Mapper
public interface ExpenseRequestMapper extends BaseMapper<ExpenseRequest> {
}
