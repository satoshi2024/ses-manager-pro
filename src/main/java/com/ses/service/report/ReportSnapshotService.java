package com.ses.service.report;

import com.ses.dto.report.ReportGenerationCommand;
import com.ses.dto.report.ReportGenerationResult;
import com.ses.entity.ReportRun;
import com.ses.entity.ReportSectionSnapshot;

import java.util.List;

/** 月次管理レポートのimmutable snapshot生成境界。 */
public interface ReportSnapshotService {

    ReportGenerationResult generate(ReportGenerationCommand command);

    ReportRun findRun(Long runId);

    List<ReportSectionSnapshot> listSections(Long runId);
}
