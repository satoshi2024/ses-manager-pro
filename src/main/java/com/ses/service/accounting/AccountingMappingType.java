package com.ses.service.accounting;

import java.util.Optional;

/**
 * 会計連携マッピングのオブジェクト種別。
 *
 * <p>{@code m_external_mapping.object_type} の正規値。保存時に未知の種別（例: seedの {@code CUSTOMER}）を
 * 拒否し、送信ガードのエラー文言へ種別を安全に埋め込むために使う（SafeErrorPolicyは文字列引数を伏せるが、
 * enum引数はそのまま表示する）。</p>
 */
public enum AccountingMappingType {
    /** 顧客 → freee取引先。内部キーは {@code CUST-<顧客ID>}。 */
    CUSTOMER_PARTNER,
    /** BP会社 → freee取引先。内部キーは {@code BP-<BP会社ID>}。 */
    BP_PARTNER,
    /** 売上高の勘定科目。内部キーは {@code SALES_DEFAULT}。 */
    ACCOUNT_SALES,
    /** BP仕入（外注費）の勘定科目。内部キーは {@code PURCHASE_DEFAULT}。 */
    ACCOUNT_PURCHASE,
    /** 要員経費の勘定科目。内部キーは {@code EXPENSE_DEFAULT}。 */
    ACCOUNT_EXPENSE,
    /** 旧種別。送信経路では未使用だが、既存データの照合のため受け付ける。 */
    ACCOUNT_OUTSOURCING,
    /** 課税売上10%の税区分コード。内部キーは {@code TAX_10}。 */
    TAX_SALES_10,
    /** 課税売上8%（軽減税率）の税区分コード。内部キーは {@code TAX_8}。税率8%の請求書だけが使う。 */
    TAX_SALES_8,
    /** 税率0%の売上（非課税・免税・対象外のいずれかを事業所の運用で選ぶ）。内部キーは {@code TAX_0}。 */
    TAX_SALES_0,
    /** 課税仕入10%の税区分コード（BP仕入・要員経費で共用）。内部キーは {@code TAX_PURCHASE_10}。 */
    TAX_PURCHASE_10,
    /** 旧種別。送信経路では未使用だが、既存データの照合のため受け付ける。 */
    TAX_EXPENSE_10,
    /** 部門。 */
    SECTION,
    /** 原価センタ（部門と同じfreee sectionsで照合する）。 */
    COST_CENTER;

    /** 未知・空の値は empty を返す（大文字小文字は区別する）。 */
    public static Optional<AccountingMappingType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (AccountingMappingType type : values()) {
            if (type.name().equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
