package com.ses.service;

/**
 * アップロード済みファイルの孤児（DBから参照されなくなったファイル）清理サービス。
 */
public interface FileCleanupService {

    /**
     * 参照されていない孤児ファイルを削除する。
     * アップロード直後でDB紐付け前のファイルを誤削除しないよう、
     * 最終更新から一定時間（app.upload.cleanup-safety-hours）経過したものだけを対象にする。
     *
     * @return 削除したファイル数
     */
    int cleanupOrphanFiles();
}
