package com.ses.dto.invoice;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 入金削除の結果。削除そのものは成功しており、warnings は画面に出す注意（message key）。
 * 入金消込の取消でfreee側に消込が残る（freeeで手動の取消が必要）場合など。
 */
@Data
public class InvoicePaymentDeleteResult {
    private List<String> warnings = new ArrayList<>();
}
