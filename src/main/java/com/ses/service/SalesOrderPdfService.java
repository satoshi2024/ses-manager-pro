package com.ses.service;

import com.ses.entity.SalesOrder;

import java.util.Locale;

/**
 * 注文請書PDF生成サービス（order-acceptance-workflow design §3）。
 * 注文請書PDFはcompany/legal entity・注文条件・明細・顧客PO参照を印字し、
 * 文書台帳（ORDER_ACKNOWLEDGEMENT）へ登録する。
 */
public interface SalesOrderPdfService {

    /** 注文請書PDFを生成する（登録済みなら文書台帳へ冪等登録してバイト列を返す）。 */
    byte[] generate(SalesOrder order);

    /** 指定ロケールで注文請書PDFを生成する。 */
    byte[] generate(SalesOrder order, Locale locale);
}
