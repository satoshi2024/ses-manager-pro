package com.ses.common.util;

import java.math.BigDecimal;

/**
 * AI向けプロンプトで使用する金額表記の共通フォーマッタ
 */
public final class PriceFormatter {

    private PriceFormatter() {}

    /**
     * BigDecimalの金額をカンマ区切りの「〇〇円」形式にフォーマット。
     * nullの場合は「未設定」を返す。
     *
     * @param amount 金額
     * @return フォーマットされた文字列
     */
    public static String format(BigDecimal amount) {
        if (amount == null) {
            return "未設定";
        }
        return String.format("%,d円", amount.setScale(0, java.math.RoundingMode.HALF_UP).longValue());
    }

    /**
     * Integerの金額をカンマ区切りの「〇〇円」形式にフォーマット。
     * nullの場合は「未設定」を返す。
     *
     * @param amount 金額
     * @return フォーマットされた文字列
     */
    public static String format(Integer amount) {
        if (amount == null) {
            return "未設定";
        }
        return String.format("%,d円", amount);
    }

    /**
     * Longの金額をカンマ区切りの「〇〇円」形式にフォーマット。
     * nullの場合は「未設定」を返す。
     *
     * @param amount 金額
     * @return フォーマットされた文字列
     */
    public static String format(Long amount) {
        if (amount == null) {
            return "未設定";
        }
        return String.format("%,d円", amount);
    }
}
