package com.ses.service;

import com.ses.dto.InvoiceDetailDto;

/**
 * 請求書PDF生成サービス。
 */
public interface InvoicePdfService {

    /**
     * 請求書詳細からA4のPDFバイト列を生成する。
     * 日本語描画用のCJKフォントが環境に見つからない場合は BusinessException を投げる。
     */
    byte[] generate(InvoiceDetailDto detail);
}
