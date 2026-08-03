package com.ses.service.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * ドキュメントStorage抽象化インターフェース（R5.1, R5.3）。
 */
public interface DocumentStorage {

    /**
     * コンテンツをStorageへ保存する（ストリーミング）。
     *
     * @param key         オブジェクトキー
     * @param content     InputStream
     * @param quarantine  trueの場合は隔離領域に保存
     */
    void put(String key, InputStream content, boolean quarantine);

    /**
     * コンテンツをStorageへ保存する（バイト配列）。
     *
     * @param key         オブジェクトキー
     * @param content     バイト配列
     * @param quarantine  trueの場合は隔離領域に保存
     */
    void put(String key, byte[] content, boolean quarantine);

    /**
     * 隔離領域から公開領域へ昇格する。
     *
     * @param key オブジェクトキー
     */
    void promote(String key);

    /**
     * streaming download用のInputStreamを返す。
     * 呼び出し元がcloseする責任を持つ。
     *
     * @param key オブジェクトキー
     * @return InputStream
     */
    InputStream open(String key);

    /**
     * 整合性検証用に全バイトを返す。
     *
     * @param key オブジェクトキー
     * @return 全バイト配列
     * @throws IOException Storage読込エラー
     */
    byte[] readAll(String key) throws IOException;

    /**
     * Storage実体を削除する。
     *
     * @param key オブジェクトキー
     */
    void delete(String key);
}
