package com.ses.dto.report;

import com.ses.entity.ReportRun;
import com.ses.entity.ReportSectionSnapshot;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 生成されたrunとsection snapshot。値はDBに固定済みのものだけを返す。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportGenerationResult {
    private ReportRun run;
    private List<ReportSectionSnapshot> sections;
    private boolean reused;
}
