package com.ses.privacy.foundation;

import java.sql.SQLException;

/**
 * Journal の principal に書込みの権限が無く、append の transaction が DB に拒否されたことを示す確定の拒否（review rb-fe-c P3-1）。
 *
 * <p>権限の拒否（MySQL の 1142 {@code ER_TABLEACCESS_DENIED_ERROR}・1143 {@code ER_COLUMNACCESS_DENIED_ERROR}・1044
 * {@code ER_DBACCESS_DENIED_ERROR}・1227 {@code ER_SPECIFIC_ACCESS_DENIED_ERROR}・1370 {@code ER_PROCACCESS_DENIED_ERROR}）は文の実行の前に返り、transaction は rollback されるため、append は起きていない。ACK 不明
 * （{@link JournalUnavailableException}）と違い、呼出し側は pending を戻してよい（reader の principal で hold の開始・解除を試みても、
 * pending と gate の {@code UNAVAILABLE} を残さない）。</p>
 */
public final class JournalAccessDeniedException extends RuntimeException {

    /** 権限の拒否の MySQL の error code。 */
    private static final int[] ACCESS_DENIED_CODES = {1142, 1143, 1044, 1227, 1370};

    public JournalAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 例外の連鎖に権限の拒否の {@link SQLException} があるか。 */
    public static boolean isAccessDenied(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                for (SQLException next = sql; next != null; next = next.getNextException()) {
                    for (int code : ACCESS_DENIED_CODES) {
                        if (next.getErrorCode() == code) {
                            return true;
                        }
                    }
                }
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
