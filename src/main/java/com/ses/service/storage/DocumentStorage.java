package com.ses.service.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * ドキュメントStorage抽象化インターフェース（F2の実装はT023で提供）。
 *
 * <p>メソッドの意味:</p>
 * <ul>
 *   <li>{@link #put} — コンテンツをStorageへ保存。{@code quarantine=true} の場合は隔離領域に配置し、
 *       ダウンロード不可状態にする（scan対象）。</li>
 *   <li>{@link #promote} — 隔離領域から公開領域へ昇格する。scan合格後に呼ぶ。</li>
 *   <li>{@link #open} — streaming download用のInputStreamを返す。呼び出し元がcloseする責任を持つ。</li>
 *   <li>{@link #readAll} — 整合性検証用に全バイトを読み込んで返す。大容量には使わないこと。</li>
 *   <li>{@link #delete} — 廃棄時にStorage実体を削除する。</li>
 * </ul>
 */
public interface DocumentStorage {

    /**
     * コンテンツを保存する。
     *
     * @param key         オブジェクトキー（推測困難なUUIDベース）
     * @param content     バイト配列
     * @param quarantine  trueの場合は隔離領域に保存（scan待ち）
     */
    void put(String key, byte[] content, boolean quarantine);

    /**
     * 隔離領域からdownload可能な公開領域へ昇格する。
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
     * 大容量ファイルへの適用は避けること。
     *
     * @param key オブジェクトキー
     * @return 全バイト配列
     * @throws IOException Storage読込エラー
     */
    byte[] readAll(String key) throws IOException;

    /**
     * Storage実体を削除する。廃棄フロー専用。
     *
     * @param key オブジェクトキー
     */
    void delete(String key);
}
