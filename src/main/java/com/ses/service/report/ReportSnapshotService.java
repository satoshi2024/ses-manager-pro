package com.ses.service.report;

import com.ses.dto.report.ReportGenerationCommand;
import com.ses.dto.report.ReportGenerationResult;
import com.ses.dto.report.ReportScopeSnapshot;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSectionSnapshot;

import java.util.List;

/** 月次管理レポートのimmutable snapshot生成境界。 */
public interface ReportSnapshotService {

    ReportGenerationResult generate(ReportGenerationCommand command);

    ReportRun findRun(Long runId);

    List<ReportSectionSnapshot> listSections(Long runId);

    /** 現在principalがrunの保存済み組織scopeを参照できるかを検証する。 */
    void assertAccessible(ReportRun run);

    /** 再生成時に元runのscopeを引き継ぐための保存済みscope読出し。 */
    ReportScopeSnapshot scopeSnapshotOf(ReportRun run);
}
