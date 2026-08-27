package com.ses.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.ses.entity.ManagementBudget;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

/** 管理会計予算サービス。 */
public interface ManagementBudgetService extends IService<ManagementBudget> {

    ManagementBudget upsert(ManagementBudget budget, Integer expectedVersion);

    List<ManagementBudget> listByMonth(LocalDate budgetMonth);

    /**
     * 予算CSVを全行検証し、既存行はversion付きupsertする。
     * 全行を1トランザクションで取り込み、途中失敗時はロールバックする。
     *
     * @param file CSVファイル
     * @return 取込件数
     */
    int importFromCsv(MultipartFile file);
}
