package com.ses.importcenter.metadata;

/** 公開済み検証runのPII-free要約（行数・件数だけ。原文・hash全体は含めない）。 */
public record ImportValidationRunSummary(long jobId, String state, long runId, int runNo, long sourceRows,
                                         long acceptedRows, long rejectedRows, long emptyRows) { }
