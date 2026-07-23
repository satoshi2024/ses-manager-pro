package com.ses.common.util;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * ページネーションユーティリティ（A7-11）。
 *
 * <p>全一覧 API は {@code new Page<>(current, size)} の代わりに
 * {@link #safePage(long, long)} を使用すること。
 * MyBatis-Plus の {@code maxLimit(1000)} と整合し、
 * 負の size による全件取得（N-2）をすべての API で構造的に防ぐ。
 * ガードをコントローラーに個別 if 文でコピペしない（今回の漏れの原因）。</p>
 *
 * <h3>正規化ルール</h3>
 * <ul>
 *   <li>current &lt;= 0 → 1 へ補正</li>
 *   <li>size &lt;= 0 → {@code defaultSize} へ補正（0 以下の全件取得を防ぐ）</li>
 *   <li>size &gt; {@link #MAX_PAGE_SIZE} → {@code MAX_PAGE_SIZE} へ丸める（上限と同値に統一）</li>
 * </ul>
 */
public final class PageUtils {

    /**
     * MyBatisPlusConfig の {@code PaginationInnerInterceptor.setMaxLimit(1000L)} と合わせる上限。
     * 変更する場合は両者を同時に変更すること。
     */
    public static final long MAX_PAGE_SIZE = 1000L;

    /** UI のデフォルトページサイズ。 */
    public static final long DEFAULT_PAGE_SIZE = 10L;

    private PageUtils() {}

    /**
     * current・size を正規化し {@link Page} を返す。既定サイズは {@link #DEFAULT_PAGE_SIZE}。
     *
     * @param current リクエストのページ番号（1 始まり）
     * @param size    リクエストのページサイズ
     * @param <T>     エンティティ型
     * @return 正規化済みの {@link Page}
     */
    public static <T> Page<T> safePage(long current, long size) {
        return safePage(current, size, DEFAULT_PAGE_SIZE);
    }

    /**
     * current・size を正規化し {@link Page} を返す（既定サイズ指定版）。
     *
     * @param current     リクエストのページ番号（1 始まり）
     * @param size        リクエストのページサイズ
     * @param defaultSize size が 0 以下だったときの既定値
     * @param <T>         エンティティ型
     * @return 正規化済みの {@link Page}
     */
    public static <T> Page<T> safePage(long current, long size, long defaultSize) {
        if (current <= 0) current = 1;
        if (size <= 0) size = (defaultSize > 0 ? defaultSize : DEFAULT_PAGE_SIZE);
        if (size > MAX_PAGE_SIZE) size = MAX_PAGE_SIZE;
        return new Page<>(current, size);
    }
}
