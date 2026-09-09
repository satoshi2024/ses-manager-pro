package com.ses.service;

import com.ses.common.enums.FileKind;
import com.ses.dto.file.StoredFile;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/**
 * ファイル保存サービス。
 */
public interface FileStorageService {

    /**
     * ファイルを検証して保存する。拡張子・サイズ・Content-Typeが不正な場合は BusinessException。
     */
    StoredFile store(MultipartFile file, FileKind kind);

    /**
     * バイト配列からファイルを検証して保存する。
     */
    StoredFile store(byte[] data, String originalName, FileKind kind);

    /**
     * 保存済みファイルを読み込む。パストラバーサルは拒否する。
     */
    Resource load(String storedName);

    /** 保持期限到達等の認可済み処理で、現在tenantの保存実体を消去する。 */
    void delete(String storedName);

    /** quarantine中のファイルを再scanし、CLEANなら公開領域へ移す。 */
    boolean rescan(String storedName);
}
