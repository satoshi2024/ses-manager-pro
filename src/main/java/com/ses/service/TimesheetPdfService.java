package com.ses.service;

/**
 * 作業報告書（月報）PDF生成サービス。
 */
public interface TimesheetPdfService {
    /** 勤怠実績IDから客先提出用の作業報告書PDFを生成する。 */
    byte[] generate(Long workRecordId);
}
