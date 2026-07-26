package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.ManagementBudget;

import java.time.LocalDate;
import java.util.List;

/** 管理会計予算サービス。 */
public interface ManagementBudgetService extends IService<ManagementBudget> {

    ManagementBudget upsert(ManagementBudget budget, Integer expectedVersion);

    List<ManagementBudget> listByMonth(LocalDate budgetMonth);
}
