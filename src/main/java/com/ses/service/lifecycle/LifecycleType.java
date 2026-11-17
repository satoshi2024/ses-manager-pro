package com.ses.service.lifecycle;

import java.util.Optional;

/**
 * ライフサイクル案件・テンプレートの種別（V109 の列注釈・画面の選択肢と同じ6値）。
 *
 * <p>W3-02 review P3-3（FINAL review §7 (b) b-3）: 判定は正規の表記（大文字の英字と {@code _}）との完全一致だけで行い、
 * 大文字小文字・前後の空白・全角文字などの表記揺れは正規化せずに拒否する。DB の照合順序（ci・PAD SPACE）では
 * {@code resignation} や {@code "RESIGNATION "} が退社案件として未終結退社の一意制約（V187）に数えられる一方、
 * Java 側の完全一致（{@code "RESIGNATION".equals}）では退社ではないと判定され、完了時の退社gate・自動処理・外部アカウントの
 * 失効要求を通らずに完了できてしまうため。</p>
 */
public enum LifecycleType {
    JOIN,
    ASSIGNMENT,
    TRANSFER,
    LEAVE,
    REINSTATEMENT,
    RESIGNATION;

    /** 正規の表記と完全に一致する種別。null・表記揺れ・一覧外の値は空。 */
    public static Optional<LifecycleType> parseStrict(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (LifecycleType type : values()) {
            if (type.name().equals(value)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
