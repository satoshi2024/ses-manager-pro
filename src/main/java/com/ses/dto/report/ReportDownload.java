package com.ses.dto.report;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.InputStream;

/** 認証済みsessionまたは旧tokenのhash照合、scope・再認証確認後のdownload stream。 */
@Data
@AllArgsConstructor
public class ReportDownload {
    private InputStream stream;
    private String fileName;
    private String contentType;
}
