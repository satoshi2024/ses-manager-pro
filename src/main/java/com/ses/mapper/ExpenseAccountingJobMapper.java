package com.ses.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ses.entity.ExpenseAccountingJob;
import org.apache.ibatis.annotations.Mapper;

/** 経費の会計連携outbox job（t_expense_accounting_job）Mapper。 */
@Mapper
public interface ExpenseAccountingJobMapper extends BaseMapper<ExpenseAccountingJob> {
}
